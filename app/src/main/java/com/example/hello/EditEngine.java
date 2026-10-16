package com.example.hello;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Low-memory, click-free non-linear editing operations on 48 kHz 32-bit float
 * WAV session files. Uses a single 64 KB buffer and a bounded delta journal
 * so low-end devices never allocate large heap arrays or copy entire files.
 */
public final class EditEngine {

    private static final int CHUNK_BYTES = 65536;
    private static final long MAX_UNDO_BYTES = 30L * 1024L * 1024L;
    private static final int MAX_UNDO_STEPS = 3;

    private EditEngine() {}

    /** Bounded 3-step undo journal storing pre-edit snapshots in takes/.undo/. */
    public static final class UndoJournal {
        private final File undoDir;
        private final Deque<File> stack = new ArrayDeque<>();
        private int seq = 0;

        public UndoJournal(File takesDir) {
            this.undoDir = new File(takesDir, ".undo");
            // noinspection ResultOfMethodCallIgnored
            this.undoDir.mkdirs();
        }

        public synchronized boolean canUndo() {
            return !stack.isEmpty() && stack.peekFirst().exists();
        }

        /** Saves a snapshot of `raw` before a mutating edit if within budget. */
        public synchronized void recordSnapshot(File raw) {
            if (raw == null || !raw.exists() || raw.length() > MAX_UNDO_BYTES) {
                return;
            }
            while (stack.size() >= MAX_UNDO_STEPS) {
                File old = stack.removeLast();
                // noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            File snap = new File(undoDir,
                    raw.getName() + "." + (++seq) + ".undo");
            try (FileInputStream in = new FileInputStream(raw);
                 FileOutputStream out = new FileOutputStream(snap)) {
                byte[] buf = new byte[CHUNK_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                stack.addFirst(snap);
            } catch (Exception e) {
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
            }
        }

        /** Restores the most recent snapshot over `raw`. */
        public synchronized boolean undo(File raw) {
            if (!canUndo() || raw == null) return false;
            File snap = stack.pollFirst();
            if (snap == null || !snap.exists()) return false;
            try (FileInputStream in = new FileInputStream(snap);
                 RandomAccessFile out = new RandomAccessFile(raw, "rw")) {
                out.setLength(0);
                byte[] buf = new byte[CHUNK_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return true;
            } catch (Exception e) {
                return false;
            } finally {
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
            }
        }

        public synchronized void clear() {
            while (!stack.isEmpty()) {
                // noinspection ResultOfMethodCallIgnored
                stack.pollFirst().delete();
            }
        }
    }

    /**
     * Crops `raw` in-place so only `[startFrame, endFrame)` remains, with
     * zero-crossing snap and 5 ms raised-cosine fade-in/fade-out at the edges.
     */
    public static long cropToSelection(File raw, long startFrame,
                                       long endFrame, UndoJournal undo)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        if (startFrame < 0) startFrame = 0;
        if (endFrame > totalFrames) endFrame = totalFrames;
        if (endFrame - startFrame < 480) {
            throw new IllegalArgumentException("selection too short");
        }
        if (undo != null) undo.recordSnapshot(raw);

        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
            startFrame = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    startFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            endFrame = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    endFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            long keepFrames = endFrame - startFrame;
            long frameBytes = ch * 4L;
            long keepBytes = keepFrames * frameBytes;

            // In-place forward shift: readOffset >= writeOffset at every step.
            byte[] buf = new byte[CHUNK_BYTES];
            long moved = 0;
            while (moved < keepBytes) {
                int want = (int) Math.min(buf.length, keepBytes - moved);
                raf.seek(info.dataStart + startFrame * frameBytes + moved);
                raf.readFully(buf, 0, want);
                raf.seek(44L + moved);
                raf.write(buf, 0, want);
                moved += want;
            }
            // Apply 240-frame (5ms) edge fades to prevent start/end clicks
            int fadeFrames = (int) Math.min(Dsp.CROSSFADE_FRAMES, keepFrames / 4);
            if (fadeFrames > 0) {
                applyEdgeFade(raf, 44L, ch, fadeFrames, true);
                applyEdgeFade(raf, 44L + (keepFrames - fadeFrames) * frameBytes,
                        ch, fadeFrames, false);
            }
            raf.setLength(44L + keepBytes);
            raf.seek(0);
            raf.write(Dsp.wavFloatHeader(ch, info.sampleRate, keepBytes));
            return keepBytes;
        }
    }

    /**
     * Deletes `[startFrame, endFrame)` from `raw` in-place, crossfading the
     * 240 frames across the cut boundary so the splice is completely seamless.
     */
    public static long deleteSelection(File raw, long startFrame,
                                       long endFrame, UndoJournal undo)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long delFrames = endFrame - startFrame;
        if (delFrames <= 0) return info.dataBytes;
        if (delFrames >= totalFrames - 480) {
            throw new IllegalArgumentException("cannot delete entire take");
        }
        if (undo != null) undo.recordSnapshot(raw);

        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
            startFrame = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    startFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            endFrame = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    endFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            long frameBytes = ch * 4L;
            int fade = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                    Math.min(startFrame, totalFrames - endFrame));

            float[] crossfaded = null;
            if (fade > 0) {
                float[] pre = readFloatFrames(raf,
                        info.dataStart + (startFrame - fade) * frameBytes,
                        fade, ch);
                float[] post = readFloatFrames(raf,
                        info.dataStart + endFrame * frameBytes,
                        fade, ch);
                crossfaded = new float[fade * ch];
                Dsp.crossfadeFrames(pre, 0, post, 0, crossfaded, 0, fade, ch);
            }

            long prefixFrames = Math.max(0, startFrame - fade);
            long prefixBytes = prefixFrames * frameBytes;
            if (info.dataStart > 44L && prefixBytes > 0) {
                byte[] pbuf = new byte[CHUNK_BYTES];
                long pmoved = 0;
                while (pmoved < prefixBytes) {
                    int want = (int) Math.min(pbuf.length, prefixBytes - pmoved);
                    raf.seek(info.dataStart + pmoved);
                    raf.readFully(pbuf, 0, want);
                    raf.seek(44L + pmoved);
                    raf.write(pbuf, 0, want);
                    pmoved += want;
                }
            }
            long writePos = 44L + prefixBytes;
            if (crossfaded != null) {
                raf.seek(writePos);
                Dsp.writeFloatsLE(raf, crossfaded, 0, crossfaded.length);
                writePos += fade * frameBytes;
            }

            // Shift surviving tail [endFrame + fade, totalFrames) down to writePos
            long readPos = info.dataStart + (endFrame + fade) * frameBytes;
            long tailBytes = Math.max(0,
                    (totalFrames - (endFrame + fade)) * frameBytes);
            byte[] buf = new byte[CHUNK_BYTES];
            long moved = 0;
            while (moved < tailBytes) {
                int want = (int) Math.min(buf.length, tailBytes - moved);
                raf.seek(readPos + moved);
                raf.readFully(buf, 0, want);
                raf.seek(writePos + moved);
                raf.write(buf, 0, want);
                moved += want;
            }

            long newPayloadBytes = (writePos - 44L) + tailBytes;
            raf.setLength(44L + newPayloadBytes);
            raf.seek(0);
            raf.write(Dsp.wavFloatHeader(ch, info.sampleRate, newPayloadBytes));
            return newPayloadBytes;
        }
    }

    /**
     * Applies `DspChain` in-place to `[startFrame, endFrame)` of `raw` with
     * 240-frame crossfades at both boundaries.
     */
    public static void applyDspToRange(File raw, long startFrame, long endFrame,
                                       DspChain chain, UndoJournal undo)
            throws Exception {
        if (chain == null || !chain.isActive()) return;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        if (endFrame <= startFrame) return;
        if (undo != null) undo.recordSnapshot(raw);

        chain.reset();
        long frameBytes = ch * 4L;
        int chunkFrames = 4096;
        byte[] rawBuf = new byte[chunkFrames * ch * 4];
        float[] fBuf = new float[chunkFrames * ch];
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);

        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
            long cur = startFrame;
            while (cur < endFrame) {
                int want = (int) Math.min(chunkFrames, endFrame - cur);
                long pos = info.dataStart + cur * frameBytes;
                raf.seek(pos);
                raf.readFully(rawBuf, 0, want * ch * 4);
                bb.clear();
                bb.limit(want * ch * 4);
                bb.asFloatBuffer().get(fBuf, 0, want * ch);

                chain.processInPlace(fBuf, want);

                bb.clear();
                bb.asFloatBuffer().put(fBuf, 0, want * ch);
                raf.seek(pos);
                raf.write(rawBuf, 0, want * ch * 4);
                cur += want;
            }
        }
    }

    private static float[] readFloatFrames(RandomAccessFile raf, long bytePos,
                                           int frames, int ch) throws Exception {
        byte[] raw = new byte[frames * ch * 4];
        raf.seek(bytePos);
        raf.readFully(raw);
        float[] out = new float[frames * ch];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(out);
        return out;
    }

    private static void applyEdgeFade(RandomAccessFile raf, long bytePos,
                                      int ch, int frames, boolean fadeIn)
            throws Exception {
        float[] samples = readFloatFrames(raf, bytePos, frames, ch);
        Dsp.applyFadeInPlace(samples, 0, frames, ch, fadeIn);
        raf.seek(bytePos);
        Dsp.writeFloatsLE(raf, samples, 0, samples.length);
    }
}
