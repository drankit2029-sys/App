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
import android.os.PowerManager;
import android.os.SystemClock;

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
    private static volatile boolean running;
    /**
     * Process-wide lock ensuring that if ConversionService is destroyed and
     * recreated while a previous worker thread is still unwinding a codec
     * chunk, the new worker thread waits until the retiring thread exits
     * before touching any sidecar or `.part` file.
     */
    private static final Object WORKER_SERIAL_LOCK = new Object();
    /**
     * Set when the platform enforces the foreground-service time budget
     * (onTimeout). The worker parks instead of cancelling so every job
     * survives for the next launch. Reset whenever a worker starts.
     */
    private volatile boolean serviceTimedOut;

    /** Thrown from the progress callback to park the job on timeout. */
    private static final class PausedForTimeoutException extends RuntimeException {
        PausedForTimeoutException() {
            super("service time budget expired");
        }
    }
    private volatile ConversionJob current;
    /** Set when a startForegroundService() start still owes a foreground. */
    private volatile boolean pendingFg;
    /** Keeps the CPU awake while an encoder is pumping with the screen off. */
    private PowerManager.WakeLock wakeLock;

    /**
     * Starts (or re-kicks) the conversion service and arms the recovery
     * watchdog. Returns false when the platform rejected both start paths;
     * the job sidecar still exists, so the next app launch or watchdog pass
     * can recover it.
     */
    public static boolean startJob(Context ctx, String jobId) {
        boolean started = kick(ctx, jobId);
        ConversionWatchdog.arm(ctx);
        return started;
    }

    /**
     * Start path used by the watchdog: no re-arming, so the watchdog cannot
     * cancel itself while it is running. Package-private for tests.
     */
    static boolean kick(Context ctx, String jobId) {
        Intent i = new Intent(ctx, ConversionService.class);
        i.setAction(ACTION_CONVERT);
        i.putExtra(EXTRA_JOB_ID, jobId);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Throwable first) {
            // Android 12+ blocks background FGS starts. The app is usually in
            // the foreground at save time, but a notification action or a
            // retry can land in the background; keep the sidecar and let the
            // watchdog retry with a temporary foreground allowance.
            try {
                ctx.startService(i);
                return true;
            } catch (Throwable second) {
                CrashLog.breadcrumb(ctx, "conv start failed: " + first
                        + " / " + second);
                return false;
            }
        }
    }

    /** True while a live service instance owns the conversion queue. */
    static boolean isRunning() {
        return running;
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
        running = true;
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
                    Thread w = worker;
                    if (w != null) w.interrupt();
                } else {
                    // Queued but never started: drop it now.
                    ConversionJobs.cancel(id, false);
                }
            }
            if (queue.isEmpty() && current == null) {
                stopSelf();
            } else {
                refreshNotif();
            }
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
        running = false;
        workerAlive = false;
        Thread w;
        synchronized (this) {
            w = worker;
            worker = null;
        }
        if (w != null) {
            w.interrupt();
        }
        // An unexpected destroy (OEM task-kill, FGS budget, process trim) must
        // leave a resumable QUEUED job rather than a RUNNING sidecar that no
        // worker will ever touch again.
        ConversionJob job = current;
        if (job != null && !job.cancelled
                && ConversionJob.RUNNING.equals(job.state)) {
            job.state = ConversionJob.QUEUED;
            job.updatedAt = System.currentTimeMillis();
            job.write();
        }
        if (!serviceTimedOut && (job != null || !queue.isEmpty())) {
            // Service died with work still pending: make sure a watchdog pass
            // brings it back even if the user never reopens the app. A dataSync
            // budget timeout is deliberate and must not be retried in a loop.
            ConversionWatchdog.arm(this);
        }
        releaseWakeLock();
        super.onDestroy();
    }

    /**
     * The user swiped the task away: persist the in-flight job as QUEUED
     * before the process can die, keeping its raw and partial output intact,
     * and make sure a watchdog pass brings it back.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        ConversionJob job = current;
        if (job != null && !job.cancelled
                && ConversionJob.RUNNING.equals(job.state)) {
            job.paused = false;
            job.state = ConversionJob.QUEUED;
            job.updatedAt = System.currentTimeMillis();
            job.write();
        }
        if (!serviceTimedOut) {
            ConversionWatchdog.arm(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    // ---- wake lock ----

    /**
     * A foreground service keeps the process alive but does not keep the CPU
     * awake. Without a partial wake lock, a screen-off device can suspend the
     * conversion thread indefinitely, which is exactly the "background
     * converter stopped" symptom. Acquired for the life of the worker and
     * released in every exit path (process death releases it automatically).
     */
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "takeone:conversion");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable t) {
            CrashLog.breadcrumb(this, "conv wake lock failed: " + t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
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
            if (!startFg(resolveCurrent())) return;
        }
        if (workerAlive) return;
        if (queue.isEmpty()) {
            ConversionWatchdog.cancel(this);
            stopSelf();
            return;
        }
        workerAlive = true;
        serviceTimedOut = false;
        acquireWakeLock();
        if (!startFg(resolveCurrent())) {
            workerAlive = false;
            releaseWakeLock();
            return;
        }
        worker = new Thread(() -> {
            // Encoding is never latency-critical. Run below UI, capture and
            // preview so a conversion cannot make the studio stutter.
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            workLoop();
        }, "takeone-conv");
        worker.start();
    }

    /** Android 15 dataSync budget expiry: stop cleanly instead of being killed. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        // Pause, don't destroy: cancelling the job here would delete its
        // sidecar and .part, and clearing the queue would strand the other
        // QUEUED jobs with no worker. Flag the timeout and wake any parked
        // worker; the progress callback parks the current job as QUEUED so
        // the next launch resumes everything intact.
        serviceTimedOut = true;
        // onTimeout does not stop WorkManager's own chain; re-arm it so the
        // queued sidecars are resumed after the dataSync budget resets.
        ConversionWatchdog.retry(this);
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        stopSelf();
        super.onTimeout(startId, fgsType);
    }

    /** Monitor that parks conversion while the microphone is capturing. */
    private static final Object CAPTURE_PAUSE_LOCK = new Object();

    /** Wakes a parked conversion immediately when capture pauses or stops. */
    public static void onRecordingStateChanged() {
        synchronized (CAPTURE_PAUSE_LOCK) {
            CAPTURE_PAUSE_LOCK.notifyAll();
        }
    }

    /**
     * True while the mic is actually capturing. A conversion yields briefly
     * per progress tick so a live take always wins CPU and codec contention,
     * but it keeps moving: an edit session or an open raw reader is safe
     * alongside the append-only raw, and final raw deletion is deferred until
     * the last reader closes.
     */
    private static boolean recordingInProgress() {
        try {
            return RecordingEngine.get().state == RecordingEngine.State.RECORDING;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Parks conversion completely while the mic is capturing. The worker
     * keeps its exact in-memory position, so resuming after Pause/Stop is
     * immediate and never restarts the take.
     */
    private void paceForCapture(ConversionJob job) {
        if (!recordingInProgress()) return;
        synchronized (CAPTURE_PAUSE_LOCK) {
            try {
                while (workerAlive && !serviceTimedOut && recordingInProgress()
                        && (job == null || !job.cancelled)) {
                    if (job != null && !job.paused) {
                        job.paused = true;
                        handler.post(this::refreshNotif);
                    }
                    CAPTURE_PAUSE_LOCK.wait(250L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (job != null && job.paused) {
                    job.paused = false;
                    handler.post(this::refreshNotif);
                }
            }
        }
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
        final Thread self = Thread.currentThread();
        try {
            synchronized (WORKER_SERIAL_LOCK) {
                while (workerAlive && worker == self && !self.isInterrupted()) {
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
                    if (serviceTimedOut || !workerAlive || worker != self) break;
                }
            }
        } finally {
            synchronized (this) {
                if (worker == self) {
                    worker = null;
                    workerAlive = false;
                    releaseWakeLock();
                    if (queue.isEmpty()) {
                        ConversionWatchdog.cancel(ConversionService.this);
                        handler.removeCallbacksAndMessages(null);
                        try {
                            stopForeground(true);
                        } catch (Exception ignored) {}
                        try {
                            NotificationManager nm = (NotificationManager)
                                    getSystemService(NOTIFICATION_SERVICE);
                            if (nm != null) nm.cancel(NOTIF_ID);
                        } catch (Exception ignored) {}
                        stopSelf();
                    } else {
                        pump();
                    }
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
            final int[] lastReportedPct = {-1};
            TakeSaver.Progress cb = pct -> {
                if (serviceTimedOut) throw new PausedForTimeoutException();
                if (job.cancelled) throw new CancellationException();
                // Brief yield between chunks while the mic captures; editing
                // and reading never stop the job.
                paceForCapture(job);
                if (job.cancelled) throw new CancellationException();
                if (pct != lastReportedPct[0]) {
                    lastReportedPct[0] = pct;
                    ConversionJobs.updateProgress(job, pct,
                            pct * Math.max(1, job.framesTotal) / 100);
                    handler.post(this::refreshNotif);
                }
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
            // finished take is visible. It wins over every title captured
            // earlier; without one, the initial edit-replace title (or the
            // live job title, or the conversion result) stands as before.
            String renamed = ConversionJobs.peekRenamedTitle(job.rawPath);
            String liveTitle;
            if (renamed != null) {
                liveTitle = renamed;
            } else if (job.replaceTakeId != -1 && job.replaceTitle != null
                    && !job.replaceTitle.isEmpty()) {
                liveTitle = job.replaceTitle;
            } else if (job.title != null && !job.title.trim().isEmpty()) {
                liveTitle = job.title.trim();
            } else {
                liveTitle = r.title;
            }
            job.title = liveTitle;
            Take take;
            if (job.replaceTakeId != -1) {
                // Edit punch-in saved to the background worker: honour the
                // replace-vs-new choice made up front instead of duplicating.
                take = TakesRepository.replaceRecording(job.replaceTakeId,
                        r.file, r.durationMs, liveTitle, r.label);
                if (take == null) {
                    // The target take was deleted while this replacement was
                    // converting. Clean up output + raw + sidecar silently.
                    if (r.file != null) {
                        // noinspection ResultOfMethodCallIgnored
                        r.file.delete();
                        TakesRepository.deleteFloatMaster(r.file);
                    }
                    ConversionJobs.deleteOrDeferRaw(job.rawPath);
                    // noinspection ResultOfMethodCallIgnored
                    new File(job.rawPath + ".pending").delete();
                    ConversionJobs.markDone(job);
                    return;
                }
            } else {
                take = TakesRepository.addRecording(r.file, r.durationMs,
                        liveTitle, r.label);
            }
            ConversionJobs.noteCompletion(job.rawPath, take.id);
            String lateRename = ConversionJobs.consumeRenamedTitle(job.rawPath);
            if (lateRename != null && !lateRename.equals(take.title)) {
                TakesRepository.rename(take.id, lateRename);
            }
            // Maintain the 32-bit float file in storage for all recordings
            // (moving job.rawPath into .float32/<r.file.getName()>.f32.wav,
            // or deleting it if r.file is already WAV32), deferring until
            // the last reader finishes if an Edit or preview holds it open.
            if (r.file == null || !r.file.getAbsolutePath()
                    .equals(job.rawPath)) {
                ConversionJobs.completeOrDeferRawMaster(job.rawPath, r.file);
            }
            // noinspection ResultOfMethodCallIgnored
            new File(job.rawPath + ".pending").delete();
            ConversionJobs.markDone(job);
            doneNotif(true, job.title, r.label, null);
            broadcast(true, job, r.label, null);
        } catch (PausedForTimeoutException paused) {
            // The platform reclaimed our foreground time mid-job. Park the
            // job as QUEUED with its sidecar and valid .part intact — never
            // delete — so the next launch resumes it. Tell the user why
            // progress stopped.
            job.state = ConversionJob.QUEUED;
            job.updatedAt = System.currentTimeMillis();
            job.write();
            timeoutPausedNotif(job);
        } catch (CancellationException cancelled) {
            if (job.superseded) {
                // Replaced by an edit while converting: the replacement job
                // owns the take now. Abort silently — no failure notification,
                // no toast, no .pending marker — and drop the raw the
                // replacement no longer needs.
                ConversionJobs.cancel(job.jobId, true);
                return;
            }
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
                // A superseded job whose raw vanished mid-encode lands here
                // instead of the CancellationException branch: still silent.
                if (job.superseded) {
                    ConversionJobs.cancel(job.jobId, true);
                    return;
                }
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
        return eff == SaveFormat.WAV16 || eff == SaveFormat.WAV24
                || eff == SaveFormat.WAV32;
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
        boolean w32 = eff == SaveFormat.WAV32;
        boolean w24 = eff == SaveFormat.WAV24;
        int pcmFormat = w32 ? 3 : 1;
        int bits = w32 ? 32 : (w24 ? 24 : 16);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = Math.max(1, info.channels);
        int frameBytes = ch * (bits / 8);
        long totalFrames = info.dataBytes / 4 / ch;
        long expectLen = 44 + job.framesDone * frameBytes;
        if (!isWavHeaderValid(part, ch, info.sampleRate, pcmFormat, bits)) {
            // noinspection ResultOfMethodCallIgnored
            part.delete();
            return TakeSaver.convertRaw(app, raw, job.title, preset, format, cb);
        }
        if (part.length() != expectLen) {
            // AUDIT-3-02 fix: `job.framesDone` is only a 5%-rounded estimate
            // while converting, so it almost never lines up with the chunked
            // bytes actually on disk. Adopt the real frame-aligned payload
            // instead of throwing away a perfectly good partial.
            long diskPayload = part.length() - 44;
            if (diskPayload > 0 && (diskPayload % frameBytes) == 0
                    && isWavHeaderValid(part, ch, info.sampleRate,
                    pcmFormat, bits)
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
        try {
            if (job.framesDone >= totalFrames) {
                patchWavHeader(part, info.channels, info.sampleRate,
                        w24, w32, totalFrames);
                FormatConverter.validateFinal(part, eff);
                return finishResumed(app, raw, part, job, eff, preset);
            }
        java.io.RandomAccessFile r = new java.io.RandomAccessFile(raw, "r");
        java.io.RandomAccessFile w = null;
        try {
            w = new java.io.RandomAccessFile(part, "rw");
            long totalSamples = info.dataBytes / 4;
            long doneSamples = job.framesDone * info.channels;
            w.seek(w.length());
            int chunk = MemoryBudget.chunkFrames(65536, ch);
            byte[] in = new byte[chunk * 4];
            // Worst case (32-bit float / 24-bit packed: 4 bytes per sample).
            byte[] out = new byte[chunk * 4];
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(in)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int[] ints = w24 ? new int[chunk] : null;
            java.nio.ShortBuffer sb = w24 ? null : java.nio.ByteBuffer.wrap(out)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int lastPct = -1;
            while (doneSamples < totalSamples) {
                if (job.cancelled) throw new CancellationException();
                int want = (int) Math.min(chunk, totalSamples - doneSamples);
                r.seek(info.dataStart + doneSamples * 4L);
                r.readFully(in, 0, want * 4);
                if (w32) {
                    w.write(in, 0, want * 4);
                } else if (w24) {
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
                if (cb != null) cb.onProgress(pct);
                if (pct != lastPct) {
                    lastPct = pct;
                    job.framesDone = doneSamples / Math.max(1, info.channels);
                }
            }
            patchWavHeader(part, info.channels, info.sampleRate,
                    w24, w32, totalFrames);
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {}
            }
        }
        FormatConverter.validateFinal(part, eff);
        return finishResumed(app, raw, part, job, eff, preset);
        } catch (Throwable t) {
            // A failed resume must not leave its part behind: the job's
            // sidecar is deleted on failure, so nothing could ever resume
            // this part again. Exception: an Android 15 FGS timeout leaves
            // a byte-valid resumable WAV part behind on purpose.
            boolean keepWavPart = t.getClass().getSimpleName().contains("Timeout")
                    && (eff == SaveFormat.WAV16 || eff == SaveFormat.WAV24
                    || eff == SaveFormat.WAV32);
            if (!keepWavPart) {
                try {
                    // noinspection ResultOfMethodCallIgnored
                    part.delete();
                } catch (Exception ignored) {}
            }
            if (t instanceof Error) throw (Error) t;
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException(t);
        }
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
        File fin = TakeSaver.uniqueFinal(raw, "wav");
        if (!part.renameTo(fin)) throw new IllegalStateException("adopt");
        // AUDIT-3-01 fix: `fin` may be integer PCM16/PCM24 (audioFormat 1)
        // or raw float WAV32 (audioFormat 3), so `TakeSaver.durationOf(fin)`
        // cannot read it uniformly. Read duration via `Dsp.wavDurationMs`.
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

    /**
     * True when the partial is a real RIFF/WAVE file with exactly the geometry
     * a resume would append: expected PCM format, channels, rate and depth. A
     * stale part from a different bit depth or channel count whose byte size
     * happens to divide evenly must never be adopted.
     */
    private static boolean isWavHeaderValid(File part, int expectedCh,
                                            int expectedSr, int expectedFormat,
                                            int expectedBits) {
        try (java.io.RandomAccessFile r =
                     new java.io.RandomAccessFile(part, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            return h.audioFormat == expectedFormat
                    && h.channels == expectedCh
                    && h.sampleRate == expectedSr && h.bits == expectedBits;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- notifications ----

    private void ensureChannel() {
        ensureChannel(this);
    }

    /** Package-private so the recovery watchdog can foreground itself. */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, ctx.getString(R.string.conv_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(ctx.getString(R.string.conv_channel_desc));
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    /**
     * Small ongoing notice used by the watchdog while it obtains a temporary
     * foreground allowance to restart a stranded conversion. It disappears
     * as soon as the worker finishes; the conversion service then owns the
     * real progress notification.
     */
    static Notification buildWatchdogNotif(Context ctx) {
        ensureChannel(ctx);
        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL);
        b.setSmallIcon(R.drawable.ic_wave);
        b.setColor(0xFFFF9F0A);
        b.setContentTitle(ctx.getString(R.string.conv_watchdog_title));
        b.setContentText(ctx.getString(R.string.conv_watchdog_text));
        b.setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS);
        b.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setSilent(true);
        b.setShowWhen(false);
        try {
            Intent open = new Intent(ctx, MainActivity.class);
            PendingIntent content = PendingIntent.getActivity(ctx, 33, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
            b.setContentIntent(content);
        } catch (Exception ignored) {}
        try {
            return b.build();
        } catch (Exception e) {
            return new Notification();
        }
    }

    private boolean startFg(ConversionJob job) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, buildNotif(job),
                        android.content.pm.ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, buildNotif(job));
            }
            return true;
        } catch (Exception e) {
            CrashLog.breadcrumb(this, "conv startForeground failed: " + e);
            try {
                stopSelf();
            } catch (Exception ignored) {}
            return false;
        }
    }

    private void refreshNotif() {
        try {
            if (!workerAlive) return;
            ConversionJob job = resolveCurrent();
            if (job == null && queue.isEmpty()) return;
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif(job));
        } catch (Exception ignored) {}
    }

    private Notification buildNotif(ConversionJob job) {
        String title;
        String text;
        String badge = null;
        int pct = -1;
        if (job != null) {
            pct = Math.max(0, Math.min(100, job.progress));
            badge = TakeSaver.labelFor(RecordPreset.of(job.presetId),
                    SaveFormat.of(job.formatId));
            title = getString(R.string.conv_notif_title, pct);
            text = getString(R.string.conv_notif_text, job.title, badge);
            if (job.paused) {
                text = getString(R.string.notif_paused_short, text);
            }
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
        b.setColor(0xFFFF3B30);
        b.setColorized(false);
        if (badge != null) b.setSubText(badge);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS);
        b.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setSilent(true);
        b.setShowWhen(false);
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

    /**
     * Tells the user the platform paused background conversion (time budget),
     * and that it resumes on next launch. Informational only: no take state
     * changes and no worker action.
     */
    private void timeoutPausedNotif(ConversionJob job) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            androidx.core.app.NotificationCompat.Builder b =
                    new androidx.core.app.NotificationCompat.Builder(this,
                            CHANNEL);
            b.setSmallIcon(R.drawable.ic_wave);
            b.setColor(0xFFFF9F0A);
            b.setContentTitle(getString(R.string.conv_timeout_title));
            b.setContentText(getString(R.string.conv_timeout_msg,
                    job.title != null ? job.title : ""));
            b.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC);
            b.setAutoCancel(true);
            nm.notify(DONE_NOTIF_ID + (int) (System.currentTimeMillis() % 1000),
                    b.build());
        } catch (Exception ignored) {}
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
            boolean isCancel = "cancelled".equals(msg)
                    || (msg != null && msg.contains("cancelled"));
            androidx.core.app.NotificationCompat.Builder b =
                    new androidx.core.app.NotificationCompat.Builder(this,
                            CHANNEL);
            b.setSmallIcon(ok ? R.drawable.ic_check : R.drawable.ic_wave);
            b.setColor(ok ? 0xFF34C759 : 0xFFFF3B30);
            if (label != null && !label.isEmpty()) b.setSubText(label);
            b.setContentTitle(ok ? getString(R.string.convert_done_title)
                    : (isCancel ? getString(R.string.conv_cancelled)
                            : getString(R.string.convert_failed_title)));
            b.setContentText(ok
                    ? getString(R.string.convert_done_msg, title, label)
                    : (msg != null ? msg : ""));
            b.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC);
            b.setAutoCancel(true);
            if (content != null) b.setContentIntent(content);
            nm.notify(DONE_NOTIF_ID + (int) (System.currentTimeMillis() % 1000),
                    b.build());
        } catch (Exception ignored) {}
        // NOTE: Do NOT call handler.post(this::refreshNotif) here!
        // Posting refreshNotif after the final job finishes races with
        // stopForeground(true) and leaves a permanent "Conversion ready"
        // notification stuck in the notification shade.
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
        // No bottom toast here: completion is surfaced by the stylized top
        // banner in the Library/Studio, never by a transient snackbar.
    }

    private static void patchWavHeader(File part, int ch, int sr, boolean w24,
                                       boolean w32, long frames)
            throws Exception {
        java.io.RandomAccessFile w = new java.io.RandomAccessFile(part, "rw");
        try {
            w.seek(0);
            w.write(w32 ? Dsp.wavFloatHeader(ch, sr, frames * ch * 4L)
                    : (w24 ? Dsp.wav24Header(ch, sr, frames * ch * 3L)
                    : Dsp.wav16Header(ch, sr, frames * ch * 2L)));
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }
}
