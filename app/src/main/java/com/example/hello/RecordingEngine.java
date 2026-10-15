package com.example.hello;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Crash-safe 32-bit float capture engine.
 *
 * Audio is streamed straight to a permanent 32-bit float WAV file (IEEE
 * float, 48&nbsp;kHz) under the user-chosen take name. A sibling
 * {@code .pending} marker means "unfinalised": it is created when the
 * session starts and deleted only on a clean finalize/discard, so a crash
 * (or process kill) always leaves a recoverable trace that is picked up on
 * the next app start.
 */
public final class RecordingEngine {

    public enum State { IDLE, RECORDING, PAUSED }

    public static final int SAMPLE_RATE = 48000;

    private static final RecordingEngine INSTANCE = new RecordingEngine();

    public static RecordingEngine get() {
        return INSTANCE;
    }

    /** One unfinished (unfinalised) raw take found on disk. */
    public static final class Unfinished {
        public final File rawFile;
        public final String name;
        public final int channels;
        public final long durationMs;

        Unfinished(File rawFile, String name, int channels, long durationMs) {
            this.rawFile = rawFile;
            this.name = name;
            this.channels = channels;
            this.durationMs = durationMs;
        }
    }

    private final Object lock = new Object();

    public State state = State.IDLE;
    public String takeName;
    public File rawFile;
    /** Output channels of the streamed float WAV (1 mono / 2 stereo). */
    public int channels = 2;
    public long dataBytes = 0;
    public volatile float level = 0f;
    /** Audio source used for the current capture (MIC or UNPROCESSED). */
    public int audioSource = MediaRecorder.AudioSource.MIC;
    /** True when stereo was requested but the hardware only gave mono. */
    public volatile boolean fellBackToMono;

    private AudioRecord recorder;
    private Thread capThread;
    private volatile boolean capRun;
    private RandomAccessFile raf;
    private boolean floatNative;
    private int actualChannels;
    private long lastHeaderPatch;

    /**
     * Incremental encoder following the raw file while capture runs. Null
     * when streaming is unavailable or was never started; the raw always
     * remains the source of truth and a full post-stop conversion covers
     * every case this misses.
     */
    private StreamingEncoder streaming;

    private RecordingEngine() {}

    // ---- storage helpers ----

    public static File takesDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "takes");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Filename-safe take name. Pure logic lives in {@link Dsp} (unit-tested). */
    public static String sanitize(String name) {
        return Dsp.sanitize(name);
    }

    private static File pendingFor(File raw) {
        return new File(raw.getAbsolutePath() + ".pending");
    }

    public boolean hasSession() {
        synchronized (lock) {
            return rawFile != null && rawFile.exists();
        }
    }

    public boolean isCapturing() {
        return state == State.RECORDING;
    }

    public long durationMs() {
        synchronized (lock) {
            int ch = Math.max(1, channels);
            return dataBytes * 1000L / ((long) SAMPLE_RATE * ch * 4L);
        }
    }

    // ---- session lifecycle ----

    /** Begin a new named session (creates the raw file + pending marker). */
    public void startNewSession(Context ctx, String name, int outChannels) throws Exception {
        synchronized (lock) {
            if (rawFile != null) throw new IllegalStateException("session active");
            endStreamingAbortLocked();
            takeName = sanitize(name);
            channels = outChannels == 1 ? 1 : 2;
            File dir = takesDir(ctx);
            rawFile = uniqueRaw(dir, takeName);
            dataBytes = 0;
            fellBackToMono = false;
            writeHeaderNew(rawFile, channels, 0);
            writePending(rawFile, takeName, channels);
            state = State.IDLE;
            level = 0f;
        }
    }

    /** Back-compat: stereo session. */
    public void startNewSession(Context ctx, String name) throws Exception {
        startNewSession(ctx, name, 2);
    }

    /** Marker that a raw file is streamed but not yet finalised. */
    public static void writePendingMarker(File raw, String name, int ch) {
        writePending(raw, name, ch);
    }

    /** Attach to an existing unfinalised file (recovery continue). */
    public void attachExisting(Context ctx, File raw, String name,
                               int ch, long bytes) throws Exception {
        synchronized (lock) {
            if (rawFile != null) throw new IllegalStateException("session active");
            rawFile = raw;
            takeName = sanitize(name);
            channels = Math.max(1, ch);
            dataBytes = Math.max(0, bytes);
            if (!pendingFor(raw).exists()) writePending(raw, takeName, channels);
            state = State.IDLE;
            level = 0f;
        }
    }

    /** Rename the session (file on disk moves too when idle/paused). */
    public void renameSession(Context ctx, String newName) throws Exception {
        synchronized (lock) {
            if (state == State.RECORDING) throw new IllegalStateException("recording");
            String clean = sanitize(newName);
            if (rawFile != null && rawFile.exists()) {
                File dir = rawFile.getParentFile();
                File dest = uniqueRaw(dir, clean);
                if (!rawFile.renameTo(dest)) throw new IllegalStateException("rename");
                File oldPending = pendingFor(rawFile);
                rawFile = dest;
                if (oldPending.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    oldPending.renameTo(pendingFor(dest));
                }
                writePending(rawFile, clean, channels);
            }
            takeName = clean;
        }
    }

    public void clearSession() {
        synchronized (lock) {
            endStreamingAbortLocked();
            state = State.IDLE;
            takeName = null;
            rawFile = null;
            channels = 2;
            dataBytes = 0;
            level = 0f;
        }
        // Let a parked conversion worker yield/resume immediately.
        ConversionService.onRecordingStateChanged();
    }

    // ---- incremental background encoding ----

    /**
     * Starts the incremental encoder for the current session, if none is
     * running. Any failure (missing encoder, I/O) is swallowed: capture is
     * unaffected and the save falls back to a full conversion. Best-effort
     * by design — the raw file stays the source of truth.
     */
    public void beginStreaming(Context ctx, RecordPreset preset,
                               SaveFormat format) {
        synchronized (lock) {
            if (streaming != null || rawFile == null) return;
            try {
                streaming = StreamingEncoder.start(preset, format,
                        Math.max(1, channels), rawFile);
            } catch (Exception e) {
                streaming = null;
                try {
                    CrashLog.breadcrumb(ctx, "streaming unavailable: " + e);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * A4-07: switch the incremental encoder to a new format mid-take. The
     * running pump keeps writing the old container otherwise, and at save
     * time the mismatch throws the whole .part away and re-converts from
     * scratch. Returns true when a new pump took over.
     */
    public boolean restartStreaming(Context ctx, RecordPreset preset,
                                    SaveFormat format) {
        synchronized (lock) {
            if (rawFile == null) return false;
            if (streaming != null) {
                streaming.abort();
                streaming = null;
            }
            try {
                streaming = StreamingEncoder.start(preset, format,
                        Math.max(1, channels), rawFile);
                return streaming != null;
            } catch (Exception e) {
                streaming = null;
                try {
                    CrashLog.breadcrumb(ctx, "streaming restart failed: " + e);
                } catch (Exception ignored) {}
                return false;
            }
        }
    }

    /** Live incremental session, or null when not streaming. */
    public StreamingEncoder streaming() {
        synchronized (lock) {
            return streaming;
        }
    }

    /**
     * Hands the streaming session to the save flow and forgets it. The
     * caller owns finishing or aborting it.
     */
    public StreamingEncoder endStreamingDetach() {
        synchronized (lock) {
            StreamingEncoder sess = streaming;
            streaming = null;
            return sess;
        }
    }

    private void endStreamingAbortLocked() {
        if (streaming != null) {
            try {
                streaming.abort();
            } catch (Exception ignored) {}
            streaming = null;
        }
    }

    // ---- capture ----

    public void startCapture(Context ctx, int requestedSource) throws Exception {
        synchronized (lock) {
            if (rawFile == null) throw new IllegalStateException("no session");
            if (state == State.RECORDING) return;
            openRecorderLocked(requestedSource, channels);
            raf = new RandomAccessFile(rawFile, "rw");
            raf.seek(raf.length());
            // Reconcile byte count with what is actually on disk.
            dataBytes = payloadBytesOf(rawFile);
            capRun = true;
            capThread = new Thread(this::capLoop, "takeone-cap");
            capThread.setPriority(Thread.MAX_PRIORITY);
            capThread.start();
            state = State.RECORDING;
            lastHeaderPatch = System.currentTimeMillis();
        }
        // Let a parked conversion worker yield/resume immediately.
        ConversionService.onRecordingStateChanged();
    }

    /** Back-compat: capture with the session's stored source. */
    public void startCapture(Context ctx) throws Exception {
        startCapture(ctx, audioSource);
    }

    public void pauseCapture() {
        AudioRecord rec;
        Thread th;
        synchronized (lock) {
            if (state != State.RECORDING) return;
            capRun = false;
            rec = recorder;
            th = capThread;
            recorder = null;
            capThread = null;
        }
        try {
            stopThenJoinThenRelease(rec, th);
        } finally {
            synchronized (lock) {
                closeRafLocked();
                patchHeaderLocked();
                state = State.PAUSED;
                level = 0f;
            }
        }
        // Let a parked conversion worker yield/resume immediately.
        ConversionService.onRecordingStateChanged();
    }

    public void resumeCapture(Context ctx, int requestedSource) throws Exception {
        synchronized (lock) {
            if (state != State.PAUSED) return;
            openRecorderLocked(requestedSource, channels);
            raf = new RandomAccessFile(rawFile, "rw");
            raf.seek(raf.length());
            dataBytes = payloadBytesOf(rawFile);
            capRun = true;
            capThread = new Thread(this::capLoop, "takeone-cap");
            capThread.setPriority(Thread.MAX_PRIORITY);
            capThread.start();
            state = State.RECORDING;
            lastHeaderPatch = System.currentTimeMillis();
        }
        // Let a parked conversion worker yield/resume immediately.
        ConversionService.onRecordingStateChanged();
    }

    /** Back-compat: resume with the session's stored source. */
    public void resumeCapture(Context ctx) throws Exception {
        resumeCapture(ctx, audioSource);
    }

    /** Clean finalize: stop capture, write final header, remove pending mark. */
    public File finalizeRaw() {
        synchronized (lock) {
            capRun = false;
        }
        AudioRecord rec;
        Thread th;
        synchronized (lock) {
            rec = recorder;
            th = capThread;
            recorder = null;
            capThread = null;
        }
        stopThenJoinThenRelease(rec, th);
        synchronized (lock) {
            closeRafLocked();
            patchHeaderLocked();
            state = State.IDLE;
            level = 0f;
            File out = rawFile;
            if (out != null) {
                // noinspection ResultOfMethodCallIgnored
                pendingFor(out).delete();
            }
            // Capture is over: let a parked conversion worker resume.
            ConversionService.onRecordingStateChanged();
            return out;
        }
    }

    /**
     * Shuts the capture path down in the only safe order: stop (so the
     * blocked read() returns), join the writer, THEN release. Releasing the
     * AudioRecord while the capture thread is still inside read() is a
     * use-after-free in native code and takes the whole process down.
     */
    private static void stopThenJoinThenRelease(AudioRecord rec, Thread th) {
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {}
        }
        if (th != null) {
            try {
                th.join(1500);
            } catch (InterruptedException ignored) {}
        }
        if (rec != null) {
            try {
                rec.release();
            } catch (Exception ignored) {}
        }
    }

    /** Stop capture and delete everything (returns true if gone). */
    public boolean abortAndDelete() {
        File raw;
        StreamingEncoder sess;
        synchronized (lock) {
            capRun = false;
        }
        AudioRecord rec;
        Thread th;
        synchronized (lock) {
            rec = recorder;
            th = capThread;
            recorder = null;
            capThread = null;
            raw = rawFile;
            sess = streaming;
            streaming = null;
        }
        stopThenJoinThenRelease(rec, th);
        if (sess != null) {
            try {
                sess.abort();
            } catch (Exception ignored) {}
        }
        synchronized (lock) {
            closeRafLocked();
            state = State.IDLE;
            takeName = null;
            rawFile = null;
            dataBytes = 0;
            level = 0f;
        }
        boolean ok = true;
        if (raw != null) {
            // noinspection ResultOfMethodCallIgnored
            pendingFor(raw).delete();
            if (raw.exists() && !raw.delete()) ok = false;
        }
        ConversionService.onRecordingStateChanged();
        return ok;
    }

    // ---- internals ----

    /**
     * Opens the mic for {@code wantedOutCh} output channels (1/2) using
     * {@code requestedSource} first (UNPROCESSED for Music/Raw) and falling
     * back to plain MIC when the device refuses it. Channel layouts are
     * tried wanted-first, so a stereo request on single-mic hardware lands
     * on mono and reports {@link #fellBackToMono} instead of failing.
     *
     * <p>RECORD_AUDIO is verified at every entry point (StudioFragment's
     * permission gate, including the recovery autostart path), and a revoked
     * permission surfaces as SecurityException, which is caught per-config
     * below and degrades to a startup toast instead of a crash.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private void openRecorderLocked(int requestedSource, int wantedOutCh) throws Exception {
        int[] channelPref = wantedOutCh == 1
                ? new int[]{AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO}
                : new int[]{AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};
        int[] encPref = {AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT};
        int[] sources = requestedSource == MediaRecorder.AudioSource.UNPROCESSED
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                        MediaRecorder.AudioSource.MIC}
                : new int[]{requestedSource};
        Exception last = null;
        fellBackToMono = false;
        for (int src : sources) {
            for (int chCfg : channelPref) {
                for (int enc : encPref) {
                    AudioRecord ar = null;
                    try {
                        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, chCfg, enc);
                        if (min <= 0) continue;
                        int bufSize = Math.max(min * 4, 32768);
                        ar = new AudioRecord(src, SAMPLE_RATE, chCfg, enc, bufSize);
                        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                            try {
                                ar.release();
                            } catch (Exception ignored) {}
                            continue;
                        }
                        ar.startRecording();
                        if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                            try {
                                ar.stop();
                            } catch (Exception ignored) {}
                            try {
                                ar.release();
                            } catch (Exception ignored) {}
                            continue;
                        }
                        recorder = ar;
                        audioSource = src;
                        actualChannels = (chCfg == AudioFormat.CHANNEL_IN_STEREO) ? 2 : 1;
                        floatNative = enc == AudioFormat.ENCODING_PCM_FLOAT;
                        // Mono input for a stereo file is duplicated; stereo
                        // input for a mono file is downmixed. A stereo request
                        // that only yields mono keeps recording in mono.
                        //
                        // A4-03: never renegotiate the layout of a session
                        // that already holds audio (punch-in edit, resumed
                        // take) or is being streamed. Rewriting the header to
                        // mono while the file holds stereo frames makes it
                        // play back at half speed and desynchronises the
                        // incremental encoder, which has already committed to
                        // the old geometry. capLoop duplicates the mono mic
                        // input into both channels instead.
                        boolean hasAudio = dataBytes > 0 || streaming != null;
                        if (wantedOutCh == 1 && !hasAudio) {
                            channels = 1;
                        } else if (!hasAudio && actualChannels > 1) {
                            channels = actualChannels;
                        }
                        fellBackToMono = wantedOutCh == 2 && actualChannels == 1;
                        // Keep the on-disk header in sync when the channel
                        // layout was renegotiated (e.g. mono fallback).
                        patchHeaderLocked();
                        return;
                    } catch (Exception e) {
                        last = e;
                        if (ar != null) {
                            try {
                                ar.release();
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("no mic config");
    }

    private void capLoop() {
        final int framesPerRead = 2048;
        final int outCh;
        synchronized (lock) {
            outCh = Math.max(1, channels);
        }
        byte[] out = new byte[framesPerRead * outCh * 4];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        int sincePatch = 0;
        try {
            while (capRun) {
                AudioRecord ar;
                synchronized (lock) {
                    ar = recorder;
                }
                if (ar == null) break;
                int frames;
                float peak = 0f;
                bb.clear();
                if (floatNative) {
                    float[] buf = new float[framesPerRead * actualChannels];
                    int n = ar.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION
                                || n == AudioRecord.ERROR_BAD_VALUE) break;
                        continue;
                    }
                    frames = n / actualChannels;
                    for (int f = 0; f < frames; f++) {
                        float s0 = buf[f * actualChannels];
                        float s1 = actualChannels > 1
                                ? buf[f * actualChannels + 1] : s0;
                        if (outCh == 2) {
                            bb.putFloat(s0);
                            bb.putFloat(s1);
                        } else {
                            bb.putFloat((s0 + s1) * 0.5f);
                        }
                        float a = Math.max(Math.abs(s0), Math.abs(s1));
                        if (a > peak) peak = a;
                    }
                } else {
                    short[] buf = new short[framesPerRead * actualChannels];
                    int n = ar.read(buf, 0, buf.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        if (n == AudioRecord.ERROR_INVALID_OPERATION
                                || n == AudioRecord.ERROR_BAD_VALUE) break;
                        continue;
                    }
                    frames = n / actualChannels;
                    for (int f = 0; f < frames; f++) {
                        float s0 = buf[f * actualChannels] / 32768f;
                        float s1 = actualChannels > 1
                                ? buf[f * actualChannels + 1] / 32768f : s0;
                        if (outCh == 2) {
                            bb.putFloat(s0);
                            bb.putFloat(s1);
                        } else {
                            bb.putFloat((s0 + s1) * 0.5f);
                        }
                        float a = Math.max(Math.abs(s0), Math.abs(s1));
                        if (a > peak) peak = a;
                    }
                }
                int bytes = frames * outCh * 4;
                synchronized (lock) {
                    if (raf != null && capRun) {
                        try {
                            raf.write(out, 0, bytes);
                            dataBytes += bytes;
                        } catch (Exception e) {
                            break;
                        }
                    } else {
                        break;
                    }
                }
                level = Math.max(0f, Math.min(1f, peak));
                if (++sincePatch >= 60) {
                    sincePatch = 0;
                    synchronized (lock) {
                        patchHeaderLocked();
                    }
                    lastHeaderPatch = System.currentTimeMillis();
                }
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (lock) {
                patchHeaderLocked();
            }
        }
    }

    private void closeRafLocked() {
        if (raf != null) {
            try {
                raf.close();
            } catch (Exception ignored) {}
            raf = null;
        }
    }

    private void patchHeaderLocked() {
        if (rawFile == null) return;
        try {
            byte[] hdr = wavHeader(channels, dataBytes);
            if (raf != null) {
                long pos = raf.getFilePointer();
                raf.seek(0);
                raf.write(hdr);
                raf.seek(pos);
            } else {
                RandomAccessFile w = new RandomAccessFile(rawFile, "rw");
                try {
                    w.seek(0);
                    w.write(hdr);
                } finally {
                    try {
                        w.close();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private static void writeHeaderNew(File f, int ch, long bytes) throws Exception {
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(wavHeader(ch, bytes));
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }

    static byte[] wavHeader(int ch, long bytes) {
        return Dsp.wavFloatHeader(ch, SAMPLE_RATE, bytes);
    }

    private static void writePending(File raw, String name, int ch) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("sampleRate", SAMPLE_RATE);
            o.put("channels", ch);
            o.put("startedAt", System.currentTimeMillis());
            java.io.FileWriter w = new java.io.FileWriter(pendingFor(raw));
            try {
                w.write(o.toString());
            } finally {
                w.close();
            }
        } catch (Exception ignored) {}
    }

    private static File uniqueRaw(File dir, String base) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File c = new File(dir, base + "_" + stamp + ".wav");
        if (!c.exists()) return c;
        int i = 2;
        while (true) {
            File n = new File(dir, base + "_" + stamp + " (" + i + ").wav");
            if (!n.exists()) return n;
            i++;
        }
    }

    // ---- recovery scan ----

    public static List<Unfinished> scanUnfinished(Context ctx) {
        List<Unfinished> out = new ArrayList<>();
        try {
            File dir = takesDir(ctx);
            File[] files = dir.listFiles();
            if (files == null) return out;
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".pending")) continue;
                String rawName = f.getName().substring(
                        0, f.getName().length() - ".pending".length());
                File raw = new File(dir, rawName);
                long payload = payloadBytesOf(raw);
                if (!raw.exists() || payload <= 0) {
                    // Orphan marker with no usable audio: clean it up silently.
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                String name = rawName.replaceFirst("\\.[^.]+$", "").replace('_', ' ');
                int ch = 2;
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    try {
                        byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
                        int n = in.read(buf);
                        String txt = n > 0 ? new String(buf, 0, n, "UTF-8") : "{}";
                        JSONObject o = new JSONObject(txt);
                        name = o.optString("name", name);
                        ch = o.optInt("channels", 2);
                    } finally {
                        try {
                            in.close();
                        } catch (Exception ignored) {}
                    }
                    if (ch < 1) ch = 2;
                } catch (Exception ignored) {}
                long ms = payload * 1000L / ((long) SAMPLE_RATE * ch * 4L);
                out.add(new Unfinished(raw, name, ch, ms));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static boolean deleteUnfinished(File raw) {
        boolean ok = true;
        try {
            // noinspection ResultOfMethodCallIgnored
            pendingFor(raw).delete();
            if (raw.exists() && !raw.delete()) ok = false;
        } catch (Exception e) {
            ok = false;
        }
        return ok;
    }

    /**
     * NEW-07 fix: locates the real {@code data} chunk via
     * {@link Dsp#parseWavHeader(RandomAccessFile)} instead of assuming a
     * fixed 44-byte offset, falling back to {@code raw.length() - 44} only
     * when the header is unparseable.
     */
    public static long payloadBytesOf(File raw) {
        if (raw == null || !raw.exists()) return 0;
        try (RandomAccessFile r = new RandomAccessFile(raw, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            return Math.max(0, raw.length() - h.dataStart);
        } catch (Exception e) {
            try {
                return Math.max(0, raw.length() - 44);
            } catch (Exception ex) {
                return 0;
            }
        }
    }
}
