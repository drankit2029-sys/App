package com.example.hello;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Foreground host for background conversions. Keeps converting after the
 * user leaves the studio — or the app entirely — with one persistent
 * progress notification per service (latest job shown), a completion notice,
 * and a broadcast the library listens for.
 *
 * <p>Jobs run serially on a single worker thread: encoders contend badly,
 * and serial order makes progress reporting and cancellation exact. A kill
 * mid-job loses only the in-memory encoder state — the raw, the sidecar and
 * any WAV partial survive, and {@link ConversionJobs#sweep} requeues on the
 * next launch (or via {@code START_REDELIVER_INTENT}).
 */
public class ConversionService extends Service {

    public static final String ACTION_CONVERT =
            "com.example.hello.CONV_RUN";
    public static final String ACTION_CANCEL_JOB =
            "com.example.hello.CONV_CANCEL";

    public static final String EXTRA_JOB_ID = "job_id";

    /** Broadcast when a background conversion finishes (ok or not). */
    public static final String BROADCAST_CONVERSION_DONE =
            "com.example.hello.CONV_DONE";
    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL = "takeone_converting";
    private static final int NOTIF_ID = 42;
    private static final int DONE_NOTIF_ID = 43;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Deque<String> queue = new ConcurrentLinkedDeque<>();
    private Thread worker;
    private volatile boolean workerAlive;
    private volatile ConversionJob current;
    /** Set when a startForegroundService() start still owes a foreground. */
    private volatile boolean pendingFg;

    public static void startJob(Context ctx, String jobId) {
        Intent i = new Intent(ctx, ConversionService.class);
        i.setAction(ACTION_CONVERT);
        i.putExtra(EXTRA_JOB_ID, jobId);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            try {
                ctx.startService(i);
            } catch (Exception ignored) {}
        }
    }

    public static void cancelJob(Context ctx, String jobId) {
        ConversionJob job = ConversionJobs.get(jobId);
        if (job != null) job.cancelled = true;
        Intent i = new Intent(ctx, ConversionService.class);
        i.setAction(ACTION_CANCEL_JOB);
        i.putExtra(EXTRA_JOB_ID, jobId);
        try {
            ctx.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        CrashLog.install(this);
        super.onCreate();
        ensureChannel();
        // Death-resume: rebuild the queue from sidecars before doing any
        // work, so a redelivered intent can never strand a job.
        for (ConversionJob job : ConversionJobs.sweep(this)) {
            enqueueIfNeeded(job.jobId);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Adds a job to the queue at most once, and never re-adds the job that is
     * currently executing. {@code queue.contains()} alone was not enough: the
     * running job had already been polled off the queue, so a sweep could
     * insert it again and run it a second time.
     */
    private void enqueueIfNeeded(String id) {
        if (id == null) return;
        synchronized (this) {
            if (current != null && id.equals(current.jobId)) return;
            if (queue.contains(id)) return;
            if (ConversionJobs.get(id) == null) return;
            queue.addLast(id);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            pump();
            return START_REDELIVER_INTENT;
        }
        String a = intent.getAction();
        if (ACTION_CANCEL_JOB.equals(a)) {
            String id = intent.getStringExtra(EXTRA_JOB_ID);
            if (id != null) {
                ConversionJob job = ConversionJobs.get(id);
                if (job != null) job.cancelled = true;
                queue.remove(id);
                if (current != null && id.equals(current.jobId)) {
                    current.cancelled = true;
                } else {
                    // Queued but never started: drop it now.
                    ConversionJobs.cancel(id, false);
                }
            }
            if (queue.isEmpty() && current == null) stopSelf();
            return START_REDELIVER_INTENT;
        }
        // Started via startForegroundService(): the platform is now watching
        // for startForeground() before pump() decides what to do.
        pendingFg = true;
        // ACTION_CONVERT (or anything else): make sure the id is queued,
        // then pump. Sweep only when nothing is in flight: sweeping mid-run
        // used to re-queue the job that was already executing (its id had
        // been polled off the queue) and reset its state.
        if (queue.isEmpty() && current == null) {
            for (ConversionJob job : ConversionJobs.sweep(this)) {
                enqueueIfNeeded(job.jobId);
            }
        }
        if (intent.hasExtra(EXTRA_JOB_ID)) {
            enqueueIfNeeded(intent.getStringExtra(EXTRA_JOB_ID));
        }
        pump();
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        workerAlive = false;
        super.onDestroy();
    }

    // ---- worker ----

    private synchronized void pump() {
        // A4-04: when this service was launched with startForegroundService()
        // the framework requires startForeground() within ~5s or it throws
        // ForegroundServiceDidNotStartInTimeException. Answer every start,
        // including one that finds a worker already running or an empty
        // queue (both used to return/stop without ever foregrounding).
        if (pendingFg) {
            pendingFg = false;
            startFg(resolveCurrent());
        }
        if (workerAlive) return;
        if (queue.isEmpty()) {
            stopSelf();
            return;
        }
        workerAlive = true;
        startFg(resolveCurrent());
        worker = new Thread(this::workLoop, "takeone-conv");
        worker.start();
    }

    /** Android 15 dataSync budget expiry: stop cleanly instead of being killed. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        try {
            handler.post(() -> {
                ConversionJob job = current;
                if (job != null) job.cancelled = true;
                queue.clear();
            });
        } catch (Exception ignored) {}
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        stopSelf();
        super.onTimeout(startId, fgsType);
    }

    private static final Object REC_PAUSE_LOCK = new Object();

    /**
     * Wakes a worker parked by {@link #awaitRecordingIdle}. Called by
     * {@link RecordingEngine} whenever capture state changes.
     */
    public static void onRecordingStateChanged() {
        synchronized (REC_PAUSE_LOCK) {
            REC_PAUSE_LOCK.notifyAll();
        }
    }

    /**
     * True while the mic is actually capturing. Background encoding then
     * yields so a live take never shares CPU, disk and MediaCodec with a
     * conversion already in flight.
     */
    private static boolean recordingInProgress() {
        try {
            return RecordingEngine.get().state == RecordingEngine.State.RECORDING;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Parks the worker (polling, so a missed notify cannot wedge it) while
     * the user is recording.
     */
    private void awaitRecordingIdle(ConversionJob job) {
        if (!recordingInProgress()) return;
        handler.post(this::refreshNotif);
        synchronized (REC_PAUSE_LOCK) {
            while (workerAlive && recordingInProgress()
                    && (job == null || !job.cancelled)) {
                try {
                    REC_PAUSE_LOCK.wait(250L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        handler.post(this::refreshNotif);
    }

    private ConversionJob resolveCurrent() {
        ConversionJob job = current;
        if (job == null) {
            String id = queue.peekFirst();
            if (id != null) job = ConversionJobs.get(id);
        }
        return job;
    }

    private void workLoop() {
        try {
            while (true) {
                awaitRecordingIdle(null);
                String id;
                synchronized (this) {
                    id = queue.pollFirst();
                }
                if (id == null) break;
                ConversionJob job = ConversionJobs.get(id);
                if (job == null || job.cancelled) {
                    if (job != null) ConversionJobs.cancel(id, false);
                    continue;
                }
                current = job;
                runJob(job);
                current = null;
            }
        } finally {
            synchronized (this) {
                workerAlive = false;
                if (queue.isEmpty()) {
                    try {
                        stopForeground(true);
                    } catch (Exception ignored) {}
                    stopSelf();
                } else {
                    pump();
                }
            }
        }
    }

    private void runJob(ConversionJob job) {
        Context app = getApplicationContext();
        ConversionJobs.markRunning(job);
        handler.post(this::refreshNotif);
        File raw = new File(job.rawPath);
        RecordPreset preset = RecordPreset.of(job.presetId);
        SaveFormat format = SaveFormat.of(job.formatId);
        try {
            if (!raw.exists()) throw new IllegalStateException("raw gone");
            if (job.cancelled) throw new CancellationException();
            TakeSaver.Progress cb = pct -> {
                if (job.cancelled) throw new CancellationException();
                // Yields between chunks too: the encoder must not hold the
                // codec while the user is capturing a new take.
                awaitRecordingIdle(job);
                if (job.cancelled) throw new CancellationException();
                ConversionJobs.updateProgress(job, pct,
                        pct * Math.max(1, job.framesTotal) / 100);
                handler.post(this::refreshNotif);
            };
            TakeSaver.Result r;
            File part = new File(job.partPath);
            // A4-08: judge the partial by its bytes, not by job.framesDone,
            // which is only flushed every 5% of progress. A kill at 2% left a
            // perfectly resumable part that this gate threw away.
            if (part.exists() && part.length() > 44
                    && isWavFormat(format.effective(preset))) {
                r = resumeWav(app, job, raw, part, preset, format, cb);
            } else {
                if (part.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                r = TakeSaver.convertRaw(app, raw, job.title, preset,
                        format, cb);
            }
            if (job.cancelled) throw new CancellationException();
            // A rename can arrive after encoding finished but before the
            // finished take is visible. Resolve the latest title first so both
            // plain and edit-replace completions use it, including the
            // notification and broadcast below.
            String liveTitle = ConversionJobs.latestTitle(job, r.title);
            job.title = liveTitle;
            Take take;
            if (job.replaceTakeId != -1) {
                // Edit punch-in saved to the background worker: honour the
                // replace-vs-new choice made up front instead of duplicating.
                // A rename during conversion overrides the initial
                // replaceTitle.
                take = TakesRepository.replaceRecording(job.replaceTakeId,
                        r.file, r.durationMs, liveTitle, r.label);
            } else {
                take = TakesRepository.addRecording(r.file, r.durationMs,
                        liveTitle, r.label);
            }
            ConversionJobs.noteCompletion(job.rawPath, take.id);
            String renamed = ConversionJobs.consumeRenamedTitle(job.rawPath);
            if (renamed != null && !renamed.equals(take.title)) {
                TakesRepository.rename(take.id, renamed);
            }
            // STU-03: an Edit on the converting card decodes this same raw.
            // Keep the file while a reader holds it instead of deleting it
            // out from under AudioImporter.
            if (ConversionJobs.rawIsBeingRead(job.rawPath)) {
                java.util.logging.Logger.getLogger("TakeOne").fine(
                        "raw still in use by an edit; keeping");
            } else {
                // noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
            // noinspection ResultOfMethodCallIgnored
            new File(job.rawPath + ".pending").delete();
            ConversionJobs.markDone(job);
            doneNotif(true, job.title, r.label, null);
            broadcast(true, job, r.label, null);
        } catch (CancellationException cancelled) {
            ConversionJobs.cancel(job.jobId, false);
            doneNotif(false, job.title, null,
                    getString(R.string.conv_cancelled_msg, job.title));
            broadcast(false, job, null, "cancelled");
        } catch (Throwable t) {
            // Catch Throwable, not Exception: a native codec aborting throws
            // an Error, which slipped past the old handler and left the job
            // stuck in RUNNING forever - the library card never resolved and
            // the service never stopped.
            if (job.cancelled) {
                // Lost a race with an explicit cancel (e.g. the raw was
                // deleted under us): the canceller owns the UX, stay quiet.
                ConversionJobs.cancel(job.jobId, false);
                return;
            }
            CrashLog.breadcrumb(getApplicationContext(),
                    "convert worker THREW: " + t);
            android.util.Log.w("ConversionService",
                    "convert worker threw", t);
            ConversionJobs.markFailed(job, String.valueOf(t));
            // Keep the raw and re-arm its pending marker so the next launch
            // offers the usual continue/discard recovery for the take.
            RecordingEngine.writePendingMarker(raw, job.title, job.channels);
            doneNotif(false, job.title, null,
                    getString(R.string.convert_failed_msg, job.title));
            broadcast(false, job, null, String.valueOf(t));
        }
    }

    private static boolean isWavFormat(SaveFormat eff) {
        return eff == SaveFormat.WAV16 || eff == SaveFormat.WAV24;
    }

    /**
     * Appends the remaining raw frames to a partial WAV from an interrupted
     * attempt, then patches the header. Byte-exact resume: the cheapest
     * possible "minimum reconversion".
     */
    private TakeSaver.Result resumeWav(Context app, ConversionJob job,
                                       File raw, File part,
                                       RecordPreset preset, SaveFormat format,
                                       TakeSaver.Progress cb) throws Exception {
        return resumeWavConvert(app, raw, part, preset, format, job, cb);
    }

    /**
     * Byte-exact WAV resume: appends frames [job.framesDone, end) to a
     * partial output, then finalizes. A resumed file is bit-identical to a
     * from-scratch conversion, which is what makes "minimum reconversion"
     * more than a slogan. Static + headless so unit tests can prove it.
     */
    static TakeSaver.Result resumeWavConvert(Context app, File raw, File part,
                                             RecordPreset preset,
                                             SaveFormat format,
                                             ConversionJob job,
                                             TakeSaver.Progress cb)
            throws Exception {
        SaveFormat eff = format.effective(preset);
        boolean w24 = eff == SaveFormat.WAV24;
        int frameBytes = Math.max(1, job.channels) * (w24 ? 3 : 2);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        long totalFrames = info.dataBytes / 4 / Math.max(1, info.channels);
        long expectLen = 44 + job.framesDone * frameBytes;
        if (part.length() != expectLen) {
            // AUDIT-3-02 fix: `job.framesDone` is only a 5%-rounded estimate
            // while converting, so it almost never lines up with the chunked
            // bytes actually on disk. Adopt the real frame-aligned payload
            // instead of throwing away a perfectly good partial.
            long diskPayload = part.length() - 44;
            if (diskPayload > 0 && (diskPayload % frameBytes) == 0
                    && isWavHeaderValid(part)
                    && (diskPayload / frameBytes) <= totalFrames) {
                job.framesDone = diskPayload / frameBytes;
            } else {
                // Partial does not line up: restart cleanly.
                // noinspection ResultOfMethodCallIgnored
                part.delete();
                return TakeSaver.convertRaw(app, raw, job.title, preset,
                        format, cb);
            }
        }
        if (job.framesDone >= totalFrames) {
            patchWavHeader(part, info.channels, info.sampleRate, w24,
                    totalFrames);
            FormatConverter.validateFinal(part, eff);
            return finishResumed(app, raw, part, job, eff, preset);
        }
        java.io.RandomAccessFile r = new java.io.RandomAccessFile(raw, "r");
        java.io.RandomAccessFile w = new java.io.RandomAccessFile(part, "rw");
        try {
            long totalSamples = info.dataBytes / 4;
            long doneSamples = job.framesDone * info.channels;
            w.seek(w.length());
            byte[] in = new byte[65536 * 4];
            // Worst case (24-bit packs 3 bytes per sample).
            byte[] out = new byte[65536 * 3];
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(in)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int[] ints = w24 ? new int[65536] : null;
            java.nio.ShortBuffer sb = w24 ? null : java.nio.ByteBuffer.wrap(out)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int lastPct = -1;
            while (doneSamples < totalSamples) {
                if (job.cancelled) throw new CancellationException();
                int want = (int) Math.min(65536, totalSamples - doneSamples);
                r.seek(info.dataStart + doneSamples * 4L);
                r.readFully(in, 0, want * 4);
                if (w24) {
                    FormatConverter.toInt24(fb, want, ints);
                    int p = 0;
                    for (int i = 0; i < want; i++) {
                        int v = ints[i];
                        out[p++] = (byte) (v & 0xFF);
                        out[p++] = (byte) ((v >> 8) & 0xFF);
                        out[p++] = (byte) ((v >> 16) & 0xFF);
                    }
                    w.write(out, 0, want * 3);
                } else {
                    FormatConverter.toInt16(fb, want, sb);
                    w.write(out, 0, want * 2);
                }
                doneSamples += want;
                int pct = (int) (doneSamples * 100 / Math.max(1, totalSamples));
                if (pct != lastPct) {
                    lastPct = pct;
                    if (cb != null) cb.onProgress(pct);
                    job.framesDone = doneSamples / Math.max(1, info.channels);
                }
            }
            patchWavHeader(part, info.channels, info.sampleRate, w24,
                    totalFrames);
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
            try {
                w.close();
            } catch (Exception ignored) {}
        }
        FormatConverter.validateFinal(part, eff);
        return finishResumed(app, raw, part, job, eff, preset);
    }

    private static TakeSaver.Result finishResumed(Context app, File raw,
                                                   File part,
                                                   ConversionJob job,
                                                   SaveFormat eff,
                                                   RecordPreset preset)
            throws Exception {
        // Same bar as a fresh conversion (NEW-03): never expose an
        // unvalidated container under a final extension.
        FormatConverter.validateFinal(part, eff);
        File fin = TakeSaver.uniqueFinal(raw,
                eff == SaveFormat.WAV24 ? "wav" : "wav");
        if (!part.renameTo(fin)) throw new IllegalStateException("adopt");
        // AUDIT-3-01 fix: `fin` is integer PCM16/PCM24 (audioFormat 1), so
        // `TakeSaver.durationOf(fin)` (which calls `readFloatWav`) always
        // threw and returned 1000 ms. Read duration via `Dsp.wavDurationMs`.
        long dur = Dsp.wavDurationMs(fin);
        if (dur <= 0) {
            dur = Math.max(TakeSaver.durationOf(raw),
                    Math.max(job.durationMs, 1000));
        }
        return new TakeSaver.Result(fin, eff.badge, Math.max(dur, 1000),
                job.title);
    }

    /** True when the partial carries a real RIFF/WAVE header. */
    private static boolean isWavHeaderValid(File part) {
        try (java.io.RandomAccessFile r =
                     new java.io.RandomAccessFile(part, "r")) {
            if (r.length() < 44) return false;
            byte[] h = new byte[12];
            r.readFully(h);
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V'
                    && h[11] == 'E';
        } catch (Exception e) {
            return false;
        }
    }

    // ---- notifications ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.conv_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.conv_channel_desc));
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    private void startFg(ConversionJob job) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotif(job),
                        android.content.pm.ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, buildNotif(job));
            }
        } catch (Exception e) {
            CrashLog.breadcrumb(this, "conv startForeground failed: " + e);
            try {
                stopSelf();
            } catch (Exception ignored) {}
        }
    }

    private void refreshNotif() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif(resolveCurrent()));
        } catch (Exception ignored) {}
    }

    private Notification buildNotif(ConversionJob job) {
        String title;
        String text;
        int pct = -1;
        if (job != null) {
            pct = Math.max(0, Math.min(100, job.progress));
            title = getString(R.string.conv_notif_title, pct);
            text = getString(R.string.conv_notif_text, job.title,
                    TakeSaver.labelFor(RecordPreset.of(job.presetId),
                            SaveFormat.of(job.formatId)));
        } else {
            title = getString(R.string.conv_notif_idle_title);
            text = getString(R.string.conv_notif_idle_text);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = null;
        try {
            content = PendingIntent.getActivity(this, 30, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ignored) {}
        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_wave);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        if (content != null) b.setContentIntent(content);
        if (pct >= 0) b.setProgress(100, pct, false);
        if (job != null) {
            Intent cancel = new Intent(this, ConversionService.class);
            cancel.setAction(ACTION_CANCEL_JOB);
            cancel.putExtra(EXTRA_JOB_ID, job.jobId);
            PendingIntent pi = null;
            try {
                pi = PendingIntent.getService(this, 31 + job.jobId.hashCode(),
                        cancel, PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
            } catch (Exception ignored) {}
            if (pi != null) {
                b.addAction(0, getString(R.string.conv_cancel), pi);
            }
        }
        try {
            return b.build();
        } catch (Exception ex) {
            return new Notification();
        }
    }

    private void doneNotif(boolean ok, String title, String label, String msg) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent content = null;
            try {
                content = PendingIntent.getActivity(this, 32, open,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
            } catch (Exception ignored) {}
            androidx.core.app.NotificationCompat.Builder b =
                    new androidx.core.app.NotificationCompat.Builder(this,
                            CHANNEL);
            b.setSmallIcon(R.drawable.ic_wave);
            b.setContentTitle(ok ? getString(R.string.convert_done_title)
                    : getString(R.string.convert_failed_title));
            b.setContentText(ok
                    ? getString(R.string.convert_done_msg, title, label)
                    : (msg != null ? msg : ""));
            b.setAutoCancel(true);
            if (content != null) b.setContentIntent(content);
            nm.notify(DONE_NOTIF_ID + (int) (System.currentTimeMillis() % 1000),
                    b.build());
        } catch (Exception ignored) {}
        handler.post(this::refreshNotif);
    }

    private void broadcast(boolean ok, ConversionJob job, String label,
                           String message) {
        try {
            Intent b = new Intent(BROADCAST_CONVERSION_DONE);
            b.putExtra(EXTRA_OK, ok);
            b.putExtra(EXTRA_TITLE, job.title);
            b.putExtra("job_id", job.jobId);
            if (label != null) b.putExtra(EXTRA_LABEL, label);
            if (message != null) b.putExtra("message", message);
            b.setPackage(getPackageName());
            sendBroadcast(b);
        } catch (Exception ignored) {}
        try {
            handler.post(() -> {
                try {
                    Toast.makeText(getApplicationContext(),
                            ok ? getString(R.string.convert_done_msg,
                                    job.title,
                                    label != null ? label : "")
                                    : getString(R.string.convert_failed_msg,
                                    job.title),
                            Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static void patchWavHeader(File part, int ch, int sr, boolean w24,
                                       long frames) throws Exception {
        java.io.RandomAccessFile w = new java.io.RandomAccessFile(part, "rw");
        try {
            w.seek(0);
            w.write(w24 ? Dsp.wav24Header(ch, sr, frames * ch * 3L)
                    : Dsp.wav16Header(ch, sr, frames * ch * 2L));
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }
}
