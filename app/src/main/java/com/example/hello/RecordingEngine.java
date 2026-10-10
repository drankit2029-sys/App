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
    public int channels = 2;
    public long dataBytes = 0;
    public volatile float level = 0f;

    private AudioRecord recorder;
    private Thread capThread;
    private volatile boolean capRun;
    private RandomAccessFile raf;
    private boolean floatNative;
    private int actualChannels;
    private long lastHeaderPatch;

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

    public static String sanitize(String name) {
        if (name == null) return "Take";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "Take";
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
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
    public void startNewSession(Context ctx, String name) throws Exception {
        synchronized (lock) {
            if (rawFile != null) throw new IllegalStateException("session active");
            takeName = sanitize(name);
            channels = 2;
            File dir = takesDir(ctx);
            rawFile = uniqueRaw(dir, takeName);
            dataBytes = 0;
            writeHeaderNew(rawFile, channels, 0);
            writePending(rawFile, takeName, channels);
            state = State.IDLE;
            level = 0f;
        }
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
            state = State.IDLE;
            takeName = null;
            rawFile = null;
            channels = 2;
            dataBytes = 0;
            level = 0f;
        }
    }

    // ---- capture ----

    public void startCapture(Context ctx) throws Exception {
        synchronized (lock) {
            if (rawFile == null) throw new IllegalStateException("no session");
            if (state == State.RECORDING) return;
            openRecorderLocked();
            raf = new RandomAccessFile(rawFile, "rw");
            raf.seek(raf.length());
            // Reconcile byte count with what is actually on disk.
            dataBytes = Math.max(0, raf.length() - 44);
            capRun = true;
            capThread = new Thread(this::capLoop, "takeone-cap");
            capThread.setPriority(Thread.MAX_PRIORITY);
            capThread.start();
            state = State.RECORDING;
            lastHeaderPatch = System.currentTimeMillis();
        }
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
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {}
                try {
                    rec.release();
                } catch (Exception ignored) {}
            }
        } finally {
            if (th != null) {
                try {
                    th.join(1500);
                } catch (InterruptedException ignored) {}
            }
            synchronized (lock) {
                closeRafLocked();
                patchHeaderLocked();
                state = State.PAUSED;
                level = 0f;
            }
        }
    }

    public void resumeCapture(Context ctx) throws Exception {
        synchronized (lock) {
            if (state != State.PAUSED) return;
            openRecorderLocked();
            raf = new RandomAccessFile(rawFile, "rw");
            raf.seek(raf.length());
            dataBytes = Math.max(0, raf.length() - 44);
            capRun = true;
            capThread = new Thread(this::capLoop, "takeone-cap");
            capThread.setPriority(Thread.MAX_PRIORITY);
            capThread.start();
            state = State.RECORDING;
            lastHeaderPatch = System.currentTimeMillis();
        }
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
        try {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {}
                try {
                    rec.release();
                } catch (Exception ignored) {}
            }
        } finally {
            if (th != null) {
                try {
                    th.join(1500);
                } catch (InterruptedException ignored) {}
            }
        }
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
            return out;
        }
    }

    /** Stop capture and delete everything (returns true if gone). */
    public boolean abortAndDelete() {
        File raw;
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
        }
        try {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {}
                try {
                    rec.release();
                } catch (Exception ignored) {}
            }
        } finally {
            if (th != null) {
                try {
                    th.join(1500);
                } catch (InterruptedException ignored) {}
            }
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
        return ok;
    }

    // ---- internals ----

    private void openRecorderLocked() throws Exception {
        int[][] configs = {
                {AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT},
                {AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT},
                {AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT},
                {AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT},
        };
        Exception last = null;
        for (int[] cfg : configs) {
            try {
                int min = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, cfg[0], cfg[1]);
                if (min <= 0) continue;
                int bufSize = Math.max(min * 4, 32768);
                AudioRecord ar = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, cfg[0], cfg[1], bufSize);
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
                actualChannels = (cfg[0] == AudioFormat.CHANNEL_IN_STEREO) ? 2 : 1;
                floatNative = cfg[1] == AudioFormat.ENCODING_PCM_FLOAT;
                // Session files are always stereo float on disk; mono input is
                // duplicated to both channels on write.
                channels = 2;
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("no mic config");
    }

    private void capLoop() {
        final int framesPerRead = 2048;
        final int ch = 2;
        byte[] out = new byte[framesPerRead * ch * 4];
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
                        bb.putFloat(s0);
                        bb.putFloat(s1);
                        float a = Math.abs(s0);
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
                        bb.putFloat(s0);
                        bb.putFloat(s1);
                        float a = Math.abs(s0);
                        if (a > peak) peak = a;
                    }
                }
                int bytes = frames * ch * 4;
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
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, 36 + bytes));
        b.put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 3); // IEEE float
        b.putShort((short) ch);
        b.putInt(SAMPLE_RATE);
        b.putInt(SAMPLE_RATE * ch * 4);
        b.putShort((short) (ch * 4));
        b.putShort((short) 32);
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, bytes));
        return b.array();
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
                if (!raw.exists() || raw.length() <= 44) {
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
                long payload = Math.max(0, raw.length() - 44);
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
        try {
            return Math.max(0, raw.length() - 44);
        } catch (Exception e) {
            return 0;
        }
    }
}
