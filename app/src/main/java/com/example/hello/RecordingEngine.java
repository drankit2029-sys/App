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
 * float, 48 kHz) under the user-chosen take name. A sibling
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
    private int cachedSrc = -1;
    private int cachedChCfg = -1;
    private int cachedEnc = -1;
    private int cachedWantedCh = -1;
    private long lastHeaderPatch;
    private long cursorFrame = 0;
    private long punchOutFrame = -1;
    private boolean cursorExplicit = false;
    private boolean truncateTailOnWrite = false;
    private boolean inPlaceEdited = false;
    private float[] punchInFadeBuf;
    private float[] punchOutFadeBuf;

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

    /** Current recording/playhead cursor in frames. */
    public long cursorFrames() {
        synchronized (lock) {
            return cursorFrame;
        }
    }

    /** Current cursor position in milliseconds. */
    public long cursorMs() {
        synchronized (lock) {
            return cursorFrame * 1000L / SAMPLE_RATE;
        }
    }

    /** Total frames currently stored in the session WAV. */
    public long totalFrames() {
        synchronized (lock) {
            int ch = Math.max(1, channels);
            return dataBytes / (ch * 4L);
        }
    }

    /**
     * Positions the recording/playhead cursor at `frame` while IDLE or PAUSED.
     *
     * <p>IMPORTANT: Does NOT set {@code truncateTailOnWrite = true}! When the
     * user presses Replace and records from {@code cursorFrame}, the entire take
     * forward of {@code cursorFrame} is kept intact and only the frames up to
     * where Pause is pressed are modified in-place.
     */
    public void seekCursorFrame(long frame) {
        synchronized (lock) {
            if (state == State.RECORDING) return;
            long total = totalFrames();
            cursorFrame = Math.max(0L, Math.min(total, frame));
            punchOutFrame = -1L;
            cursorExplicit = true;
            truncateTailOnWrite = false;
        }
    }

    /**
     * Arms a bounded punch-in overwrite across `[startFrame, endFrame)`.
     * Capture automatically pauses with a 5 ms crossfade when `endFrame` is reached.
     */
    public void armPunchRange(long startFrame, long endFrame) {
        synchronized (lock) {
            if (state == State.RECORDING) return;
            long total = totalFrames();
            cursorFrame = Math.max(0L, Math.min(total, startFrame));
            punchOutFrame = (endFrame > cursorFrame)
                    ? Math.min(total, endFrame) : -1L;
            cursorExplicit = true;
            truncateTailOnWrite = false;
        }
    }

    // Parent session stash when inside "Select & Replace" sub-editor mode
    private File parentRawFile;
    private String parentTakeName;
    private int parentChannels = 2;
    private long parentDataBytes = 0;
    private long parentSelStartFrame = 0;
    private long parentSelEndFrame = 0;

    /**
     * No-op hook retained for callers: never probe or hold AudioRecord open on
     * a background thread while the user is scrubbing, as concurrent HAL queries
     * can race openRecorderLocked() when the user taps Resume.
     */
    public void warmUpMic(final Context ctx, final int requestedSource,
                          final int wantedOutCh) {
        // Intentionally empty: openRecorderLocked() opens AudioRecord cleanly on demand.
    }

    public void releaseWarmMic() {
        // No idle AudioRecord held open; nothing to release.
    }

    /**
     * Enters a dedicated sub-session for "Select & Replace" so the replacement
     * take is recorded and edited in its own float WAV session before splicing
     * into the parent take.
     */
    public void enterSelectAndReplaceSubSession(Context ctx, long selStartFrame,
                                                long selEndFrame) throws Exception {
        synchronized (lock) {
            if (state == State.RECORDING) pauseCapture();
            endStreamingAbortLocked();
            parentRawFile = rawFile;
            parentTakeName = takeName;
            parentChannels = channels;
            parentDataBytes = dataBytes;
            parentSelStartFrame = selStartFrame;
            parentSelEndFrame = selEndFrame;

            File subFile = new File(takesDir(ctx),
                    sanitize(takeName != null ? takeName : "Take")
                            + "_subrep_" + System.nanoTime() + ".wav");
            writeHeaderNew(subFile, channels, 0);
            rawFile = subFile;
            dataBytes = 0;
            cursorFrame = 0;
            punchOutFrame = -1;
            cursorExplicit = false;
            truncateTailOnWrite = false;
            inPlaceEdited = false;
            state = State.IDLE;
            level = 0f;
        }
    }

    public boolean isInSubSession() {
        synchronized (lock) {
            return parentRawFile != null;
        }
    }

    /**
     * Exits the "Select & Replace" sub-session, splicing the recorded replacement
     * into `[parentSelStartFrame, parentSelEndFrame)` of the parent take when
     * `commit == true` (or discarding the sub-session when `commit == false`).
     */
    public void exitSelectAndReplaceSubSession(boolean commit,
                                               EditEngine.UndoJournal undo) throws Exception {
        File sub;
        File parent;
        long sFrame;
        long eFrame;
        synchronized (lock) {
            if (parentRawFile == null) return;
            if (state == State.RECORDING) pauseCapture();
            endStreamingAbortLocked();
            sub = rawFile;
            parent = parentRawFile;
            sFrame = parentSelStartFrame;
            eFrame = parentSelEndFrame;

            rawFile = parentRawFile;
            takeName = parentTakeName;
            channels = parentChannels;
            dataBytes = parentDataBytes;
            parentRawFile = null;
            parentTakeName = null;
        }
        try {
            if (commit && sub != null && sub.exists() && payloadBytesOf(sub) > 0) {
                long newBytes = EditEngine.spliceReplacementFile(
                        parent, sFrame, eFrame, sub, undo);
                synchronized (lock) {
                    dataBytes = newBytes;
                    long repFrames = payloadBytesOf(sub) / (Math.max(1, channels) * 4L);
                    cursorFrame = Math.min(totalFrames(), sFrame + repFrames);
                    cursorExplicit = true;
                    state = State.PAUSED;
                }
            } else {
                synchronized (lock) {
                    dataBytes = payloadBytesOf(parent);
                    cursorFrame = Math.min(totalFrames(), sFrame);
                    cursorExplicit = true;
                    state = State.PAUSED;
                }
            }
        } finally {
            if (sub != null && sub.exists()) {
                // noinspection ResultOfMethodCallIgnored
                sub.delete();
            }
        }
    }

    /**
     * When pausing an in-place mid-take Replace (`cursorFrame < totalFrames`),
     * fades in the first 240 frames (5 ms) of the surviving future audio so the
     * punch-out transition has zero click.
     */
    private void crossfadeOutOnPauseLocked() {
        if (rawFile == null || !rawFile.exists()) return;
        try {
            int ch = Math.max(1, channels);
            long frameBytes = ch * 4L;
            long totalFrames = dataBytes / frameBytes;
            if (cursorFrame > Dsp.CROSSFADE_FRAMES && cursorFrame < totalFrames) {
                int fade = (int) Math.min(Dsp.CROSSFADE_FRAMES, totalFrames - cursorFrame);
                if (fade > 0 && raf != null) {
                    byte[] tailBytes = new byte[fade * ch * 4];
                    long pos = 44L + cursorFrame * frameBytes;
                    raf.seek(pos);
                    raf.readFully(tailBytes);
                    float[] tail = new float[fade * ch];
                    ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer().get(tail);
                    Dsp.applyFadeInPlace(tail, 0, fade, ch, true);
                    raf.seek(pos);
                    Dsp.writeFloatsLE(raf, tail, 0, tail.length);
                }
            }
        } catch (Exception ignored) {}
    }

    /** Aborts any running incremental encoder after an in-place edit/undo/DSP commit. */
    public void abortStreaming() {
        synchronized (lock) {
            inPlaceEdited = true;
            endStreamingAbortLocked();
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
            File candidate = uniqueRaw(dir, takeName);
            try {
                writeHeaderNew(candidate, channels, 0);
                writePending(candidate, takeName, channels);
            } catch (Exception | Error e) {
                try {
                    // noinspection ResultOfMethodCallIgnored
                    candidate.delete();
                } catch (Exception ignored) {}
                throw e;
            }
            rawFile = candidate;
            dataBytes = 0;
            cursorFrame = 0;
            punchOutFrame = -1;
            cursorExplicit = false;
            truncateTailOnWrite = false;
            inPlaceEdited = false;
            punchInFadeBuf = null;
            punchOutFadeBuf = null;
            fellBackToMono = false;
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
            cursorFrame = 0;
            punchOutFrame = -1;
            cursorExplicit = false;
            truncateTailOnWrite = false;
            inPlaceEdited = false;
            punchInFadeBuf = null;
            punchOutFadeBuf = null;
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
                endStreamingAbortLocked();
            }
            takeName = clean;
        }
        ConversionService.onRecordingStateChanged();
        RecordingService.onRecordingStateChanged();
    }

    public void clearSession() {
        synchronized (lock) {
            endStreamingAbortLocked();
            state = State.IDLE;
            takeName = null;
            rawFile = null;
            channels = 2;
            dataBytes = 0;
            cursorFrame = 0;
            punchOutFrame = -1;
            cursorExplicit = false;
            truncateTailOnWrite = false;
            inPlaceEdited = false;
            punchInFadeBuf = null;
            punchOutFadeBuf = null;
            level = 0f;
        }
        ConversionService.onRecordingStateChanged();
        RecordingService.onRecordingStateChanged();
    }

    // ---- incremental background encoding ----

    public void beginStreaming(Context ctx, RecordPreset preset,
                               SaveFormat format) {
        synchronized (lock) {
            if (streaming != null || rawFile == null) return;
            // Never start the append-only incremental encoder when replacing
            // mid-file or after an in-place edit: it would race ahead and encode
// the entire un-replaced file on disk in milliseconds!
            // cursorFrame == 0 means a freshly attached/imported session parked
            // at the start of the take: nothing has been replaced yet, so the
            // append-only encoder is still safe (and required, see
            // ConversionConcurrencyTest.abandonedAdoptLeavesNoEncoderOnThePartFile).
            if (inPlaceEdited || (dataBytes > 0 && cursorFrame > 0
                    && cursorFrame < totalFrames())) {
                return;
            }
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

    public StreamingEncoder streaming() {
        synchronized (lock) {
            return streaming;
        }
    }

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
        // Release any pre-warmed low-latency speaker AudioTrack before opening
        // AudioRecord so the output HAL never conflicts with mic capture.
        RawFloatPlayer.releaseWarmSpeaker();
        synchronized (lock) {
            if (rawFile == null) throw new IllegalStateException("no session");
            if (state == State.RECORDING) return;
            raf = new RandomAccessFile(rawFile, "rw");
            try {
                dataBytes = payloadBytesOf(rawFile);
                int ch = Math.max(1, channels);
                long totalFrames = dataBytes / (ch * 4L);
                if (!cursorExplicit || cursorFrame < 0 || cursorFrame > totalFrames) {
                    cursorFrame = totalFrames;
                    truncateTailOnWrite = false;
                }
                punchInFadeBuf = null;
                punchOutFadeBuf = null;
                if (cursorFrame < totalFrames) {
                    inPlaceEdited = true;
                    endStreamingAbortLocked();
                    int fadeInN = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                            totalFrames - cursorFrame);
                    if (fadeInN > 0) {
                        punchInFadeBuf = new float[fadeInN * ch];
                        byte[] tmp = new byte[fadeInN * ch * 4];
                        raf.seek(44L + cursorFrame * ch * 4L);
                        raf.readFully(tmp);
                        ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer().get(punchInFadeBuf);
                    }
                    if (punchOutFrame > cursorFrame && punchOutFrame <= totalFrames) {
                        int fadeOutN = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                                punchOutFrame - cursorFrame);
                        if (fadeOutN > 0) {
                            punchOutFadeBuf = new float[fadeOutN * ch];
                            byte[] tmp = new byte[fadeOutN * ch * 4];
                            raf.seek(44L + (punchOutFrame - fadeOutN) * ch * 4L);
                            raf.readFully(tmp);
                            ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
                                    .asFloatBuffer().get(punchOutFadeBuf);
                        }
                    } else if (truncateTailOnWrite) {
                        dataBytes = cursorFrame * ch * 4L;
                        raf.setLength(44L + dataBytes);
                    }
                    if (streaming != null && cursorFrame < streaming.framesEncoded()) {
                        endStreamingAbortLocked();
                    }
                }
                raf.seek(44L + cursorFrame * ch * 4L);
            } catch (Exception | Error e) {
                try {
                    raf.close();
                } catch (Exception ignored) {}
                raf = null;
                throw e;
            }
            try {
                openRecorderLocked(requestedSource, channels);
            } catch (Exception | Error e) {
                try {
                    raf.close();
                } catch (Exception ignored) {}
                raf = null;
                throw e;
            }
            cursorExplicit = false;
            dataBytes = payloadBytesOf(rawFile);
            capRun = true;
            capThread = new Thread(this::capLoop, "takeone-cap");
            capThread.setPriority(Thread.MAX_PRIORITY);
            capThread.start();
            state = State.RECORDING;
            lastHeaderPatch = System.currentTimeMillis();
        }
        ConversionService.onRecordingStateChanged();
        RecordingService.onRecordingStateChanged();
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
                crossfadeOutOnPauseLocked();
                closeRafLocked();
                patchHeaderLocked();
                state = State.PAUSED;
                level = 0f;
            }
        }
        ConversionService.onRecordingStateChanged();
        RecordingService.onRecordingStateChanged();
    }

    public void resumeCapture(Context ctx, int requestedSource) throws Exception {
        // Release any pre-warmed low-latency speaker AudioTrack before opening
        // AudioRecord so the output HAL never conflicts with mic capture.
        RawFloatPlayer.releaseWarmSpeaker();
        synchronized (lock) {
            if (state != State.PAUSED) return;
            raf = new RandomAccessFile(rawFile, "rw");
            try {
                dataBytes = payloadBytesOf(rawFile);
                int ch = Math.max(1, channels);
                long totalFrames = dataBytes / (ch * 4L);
                // When resuming from PAUSED, cursorFrame is ALWAYS valid in
                // [0, totalFrames]; never reset it to totalFrames if the user
                // scrubbed back to a mid-take playhead!
                if (cursorFrame < 0 || cursorFrame > totalFrames) {
                    cursorFrame = totalFrames;
                    truncateTailOnWrite = false;
                }
                punchInFadeBuf = null;
                punchOutFadeBuf = null;
                if (cursorFrame < totalFrames) {
                    inPlaceEdited = true;
                    endStreamingAbortLocked();
                    int fadeInN = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                            totalFrames - cursorFrame);
                    if (fadeInN > 0) {
                        punchInFadeBuf = new float[fadeInN * ch];
                        byte[] tmp = new byte[fadeInN * ch * 4];
                        raf.seek(44L + cursorFrame * ch * 4L);
                        raf.readFully(tmp);
                        ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer().get(punchInFadeBuf);
                    }
                    if (punchOutFrame > cursorFrame && punchOutFrame <= totalFrames) {
                        int fadeOutN = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                                punchOutFrame - cursorFrame);
                        if (fadeOutN > 0) {
                            punchOutFadeBuf = new float[fadeOutN * ch];
                            byte[] tmp = new byte[fadeOutN * ch * 4];
                            raf.seek(44L + (punchOutFrame - fadeOutN) * ch * 4L);
                            raf.readFully(tmp);
                            ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
                                    .asFloatBuffer().get(punchOutFadeBuf);
                        }
                    } else if (truncateTailOnWrite) {
                        dataBytes = cursorFrame * ch * 4L;
                        raf.setLength(44L + dataBytes);
                    }
                    if (streaming != null && cursorFrame < streaming.framesEncoded()) {
                        endStreamingAbortLocked();
                    }
                }
                raf.seek(44L + cursorFrame * ch * 4L);
            } catch (Exception | Error e) {
                try {
                    raf.close();
                } catch (Exception ignored) {}
                raf = null;
                throw e;
            }
            try {
                openRecorderLocked(requestedSource, channels);
            } catch (Exception | Error e) {
                try {
                    raf.close();
                } catch (Exception ignored) {}
                raf = null;
                throw e;
            }
            cursorExplicit = false;
            dataBytes = payloadBytesOf(rawFile);
            capRun = true;
            capThread = new Thread(this::capLoop, "takeone-cap");
            capThread.setPriority(Thread.MAX_PRIORITY);
            capThread.start();
            state = State.RECORDING;
            lastHeaderPatch = System.currentTimeMillis();
        }
        ConversionService.onRecordingStateChanged();
        RecordingService.onRecordingStateChanged();
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
            ConversionService.onRecordingStateChanged();
            RecordingService.onRecordingStateChanged();
            return out;
        }
    }

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
            cursorFrame = 0;
            punchOutFrame = -1;
            cursorExplicit = false;
            truncateTailOnWrite = false;
            inPlaceEdited = false;
            punchInFadeBuf = null;
            punchOutFadeBuf = null;
            level = 0f;
        }
        boolean ok = true;
        if (raw != null) {
            // noinspection ResultOfMethodCallIgnored
            pendingFor(raw).delete();
            if (raw.exists() && !raw.delete()) ok = false;
        }
        ConversionService.onRecordingStateChanged();
        RecordingService.onRecordingStateChanged();
        return ok;
    }

    // ---- internals ----

    @android.annotation.SuppressLint("MissingPermission")
    private void openRecorderLocked(int requestedSource, int wantedOutCh) throws Exception {
        Exception last = null;
        fellBackToMono = false;
        // Fast path: if we already verified a working hardware config for this
        // source & channel count, try it immediately on the first shot to avoid
        // probing unsupported combinations.
        if (cachedSrc == requestedSource && cachedWantedCh == wantedOutCh
                && cachedChCfg != -1 && cachedEnc != -1) {
            if (tryOpenSingleConfigLocked(cachedSrc, cachedChCfg, cachedEnc, wantedOutCh)) {
                return;
            }
        }
        int[] channelPref = wantedOutCh == 1
                ? new int[]{AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO}
                : new int[]{AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO};
        int[] encPref = {AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT};
        int[] sources = requestedSource == MediaRecorder.AudioSource.UNPROCESSED
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                        MediaRecorder.AudioSource.MIC}
                : new int[]{requestedSource};
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
                        boolean hasAudio = dataBytes > 0 || streaming != null;
                        if (wantedOutCh == 1 && !hasAudio) {
                            channels = 1;
                        } else if (!hasAudio && actualChannels > 1) {
                            channels = actualChannels;
                        }
                        fellBackToMono = wantedOutCh == 2 && actualChannels == 1;
                        cachedSrc = requestedSource;
                        cachedWantedCh = wantedOutCh;
                        cachedChCfg = chCfg;
                        cachedEnc = enc;
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

    @android.annotation.SuppressLint("MissingPermission")
    private boolean tryOpenSingleConfigLocked(int src, int chCfg, int enc, int wantedOutCh) {
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, chCfg, enc);
            if (min <= 0) return false;
            int bufSize = Math.max(min * 2, 16384);
            ar = new AudioRecord(src, SAMPLE_RATE, chCfg, enc, bufSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                try { ar.release(); } catch (Exception ignored) {}
                return false;
            }
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                try { ar.stop(); } catch (Exception ignored) {}
                try { ar.release(); } catch (Exception ignored) {}
                return false;
            }
            recorder = ar;
            audioSource = src;
            actualChannels = (chCfg == AudioFormat.CHANNEL_IN_STEREO) ? 2 : 1;
            floatNative = enc == AudioFormat.ENCODING_PCM_FLOAT;
            boolean hasAudio = dataBytes > 0 || streaming != null;
            if (wantedOutCh == 1 && !hasAudio) {
                channels = 1;
            } else if (!hasAudio && actualChannels > 1) {
                channels = actualChannels;
            }
            fellBackToMono = wantedOutCh == 2 && actualChannels == 1;
            patchHeaderLocked();
            return true;
        } catch (Exception e) {
            if (ar != null) {
                try { ar.release(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    private void capLoop() {
        // 512 frames @ 48 kHz = 10.6 ms per read (4x faster response than 2048),
        // eliminating initial read buffering lag and allowing fine-grained
        // trimming of hardware ADC/HAL startup dead air.
        final int framesPerRead = 512;
        final int maxWarmupSkipFrames = SAMPLE_RATE / 3; // bound warmup skip to ~333 ms
        final float warmupSilenceFloor = 0.0008f;        // ~ -62 dBFS hardware mute floor
        final int outCh;
        synchronized (lock) {
            outCh = Math.max(1, channels);
        }
        byte[] out = new byte[framesPerRead * outCh * 4];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        float[] fReadBuf = floatNative ? new float[framesPerRead * Math.max(1, actualChannels)] : null;
        short[] sReadBuf = !floatNative ? new short[framesPerRead * Math.max(1, actualChannels)] : null;
        int sincePatch = 0;
        int transientErrors = 0;
        boolean micWarmupActive = true;
        int warmupSkippedFrames = 0;
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
                    int n = ar.read(fReadBuf, 0, fReadBuf.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        // Tolerate brief HAL route-switch hiccups on initial reads
                        // instead of immediately aborting capLoop back to PAUSED!
                        if (n < 0 && ++transientErrors > 8) break;
                        try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    transientErrors = 0;
                    int rawFrames = n / actualChannels;
                    int firstValid = 0;
                    if (micWarmupActive) {
                        while (firstValid < rawFrames) {
                            float s0 = fReadBuf[firstValid * actualChannels];
                            float s1 = actualChannels > 1
                                    ? fReadBuf[firstValid * actualChannels + 1] : s0;
                            if (Math.max(Math.abs(s0), Math.abs(s1)) > warmupSilenceFloor) {
                                micWarmupActive = false;
                                break;
                            }
                            firstValid++;
                        }
                        if (micWarmupActive) {
                            warmupSkippedFrames += rawFrames;
                            if (warmupSkippedFrames < maxWarmupSkipFrames) {
                                continue;
                            }
                            micWarmupActive = false;
                            firstValid = 0;
                        }
                    }
                    frames = rawFrames - firstValid;
                    for (int f = firstValid; f < rawFrames; f++) {
                        float s0 = fReadBuf[f * actualChannels];
                        float s1 = actualChannels > 1
                                ? fReadBuf[f * actualChannels + 1] : s0;
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
                    int n = ar.read(sReadBuf, 0, sReadBuf.length, AudioRecord.READ_BLOCKING);
                    if (n <= 0) {
                        if (n < 0 && ++transientErrors > 8) break;
                        try { Thread.sleep(10); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    transientErrors = 0;
                    int rawFrames = n / actualChannels;
                    int firstValid = 0;
                    if (micWarmupActive) {
                        while (firstValid < rawFrames) {
                            float s0 = sReadBuf[firstValid * actualChannels] / 32768f;
                            float s1 = actualChannels > 1
                                    ? sReadBuf[firstValid * actualChannels + 1] / 32768f : s0;
                            if (Math.max(Math.abs(s0), Math.abs(s1)) > warmupSilenceFloor) {
                                micWarmupActive = false;
                                break;
                            }
                            firstValid++;
                        }
                        if (micWarmupActive) {
                            warmupSkippedFrames += rawFrames;
                            if (warmupSkippedFrames < maxWarmupSkipFrames) {
                                continue;
                            }
                            micWarmupActive = false;
                            firstValid = 0;
                        }
                    }
                    frames = rawFrames - firstValid;
                    for (int f = firstValid; f < rawFrames; f++) {
                        float s0 = sReadBuf[f * actualChannels] / 32768f;
                        float s1 = actualChannels > 1
                                ? sReadBuf[f * actualChannels + 1] / 32768f : s0;
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
                boolean reachedPunchOut = false;
                synchronized (lock) {
                    if (punchOutFrame > 0 && cursorFrame + frames >= punchOutFrame) {
                        frames = (int) Math.max(0, punchOutFrame - cursorFrame);
                        reachedPunchOut = true;
                    }
                }
                if (frames <= 0) break;

                // Blend first 240 frames on mid-file punch-in
                if (punchInFadeBuf != null) {
                    int fadeN = Math.min(punchInFadeBuf.length / outCh, frames);
                    bb.position(0);
                    java.nio.FloatBuffer fb = bb.asFloatBuffer();
                    for (int f = 0; f < fadeN; f++) {
                        float wIn = (f + 0.5f) / fadeN;
                        float wOut = 1.0f - wIn;
                        for (int c = 0; c < outCh; c++) {
                            int idx = f * outCh + c;
                            fb.put(idx, punchInFadeBuf[idx] * wOut + fb.get(idx) * wIn);
                        }
                    }
                    punchInFadeBuf = null;
                }
                // Blend last 240 frames on range punch-out
                if (reachedPunchOut && punchOutFadeBuf != null) {
                    int fadeN = Math.min(punchOutFadeBuf.length / outCh, frames);
                    int startF = frames - fadeN;
                    bb.position(0);
                    java.nio.FloatBuffer fb = bb.asFloatBuffer();
                    for (int f = 0; f < fadeN; f++) {
                        float wOut = (f + 0.5f) / fadeN;
                        float wIn = 1.0f - wOut;
                        for (int c = 0; c < outCh; c++) {
                            int bIdx = (startF + f) * outCh + c;
                            int fIdx = f * outCh + c;
                            fb.put(bIdx, fb.get(bIdx) * wIn + punchOutFadeBuf[fIdx] * wOut);
                        }
                    }
                    punchOutFadeBuf = null;
                }

                int bytes = frames * outCh * 4;
                synchronized (lock) {
                    if (raf != null && capRun) {
                        try {
                            raf.write(out, 0, bytes);
                            cursorFrame += frames;
                            long endBytes = cursorFrame * outCh * 4L;
                            if (endBytes > dataBytes) dataBytes = endBytes;
                        } catch (Exception e) {
                            break;
                        }
                    } else {
                        break;
                    }
                }
                if (reachedPunchOut) {
                    break;
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
            boolean unexpectedExit = false;
            AudioRecord deadRec = null;
            synchronized (lock) {
                patchHeaderLocked();
                if (capRun) {
                    capRun = false;
                    unexpectedExit = true;
                    deadRec = recorder;
                    recorder = null;
                    capThread = null;
                    closeRafLocked();
                    state = State.PAUSED;
                    level = 0f;
                }
            }
            if (deadRec != null) {
                try {
                    deadRec.stop();
                } catch (Exception ignored) {}
                try {
                    deadRec.release();
                } catch (Exception ignored) {}
            }
            if (unexpectedExit) {
                ConversionService.onRecordingStateChanged();
                RecordingService.onRecordingStateChanged();
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
