package com.example.hello;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Low-memory, click-free non-linear editing operations on 48 kHz 32-bit float
 * WAV session files. Supports Trim/Crop, Delete Selection, Variable-Length
 * Select & Replace (where replacement audio can be shorter or longer than the
 * selected segment), Chunked DSP Range Commit, and 5-step Full-Take Undo/Redo
 * with streaming LZ77 compression and range-delta snapshots.
 */
public final class EditEngine {

    private static final int CHUNK_BYTES = 65536;
    /**
     * 5-step Undo/Redo journal supporting full takes of any duration via
     * streaming compression and range-delta snapshots.
     */
    public static final int MAX_UNDO_STEPS = 5;

    private static final byte SNAP_MAGIC_0 = (byte) 'T';
    private static final byte SNAP_MAGIC_1 = (byte) 'U';
    private static final byte SNAP_MAGIC_2 = (byte) 'Z';
    private static final byte SNAP_MAGIC_3 = (byte) 1;
    private static final byte SNAP_MODE_FULL = 0;
    private static final byte SNAP_MODE_DELTA = 1;

    private EditEngine() {}

    /** Callback for bounded-chunk float streaming over a WAV file range. */
    public interface FloatChunkConsumer {
        void onChunk(float[] interleaved, int frames, int channels,
                     long frameOffset) throws Exception;
    }

    /** Copies a session file for a non-destructive FX preview render. */
    public static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[CHUNK_BYTES];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /**
     * Drops any undo/redo snapshots recorded for {@code raw}, e.g. after the
     * file was renamed into its persistent 32-bit float master.
     */
    public static void clearSessionUndo(File raw) {
        if (raw == null || raw.getParentFile() == null) return;
        File[] snaps = new File(raw.getParentFile(), ".undo").listFiles();
        if (snaps == null) return;
        String prefix = raw.getName() + ".";
        for (File snap : snaps) {
            if (snap != null && snap.getName().startsWith(prefix)) {
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
            }
        }
    }

    /**
     * Bounded 5-step undo/redo journal storing compressed full-take and
     * range-delta snapshots in {@code takes/.undo/}.
     *
     * <p>Unlike the legacy implementation (which had a 30 MB per-file cap that
     * refused takes longer than ~20 seconds), this journal streams snapshots
     * in 64 KB chunks through {@link Deflater#BEST_SPEED} and uses byte-range
     * delta snapshots for in-place DSP edits, supporting entire takes of any
     * length across all 5 undo/redo steps.
     */
    public static final class UndoJournal {
        private static final int IO_BUF_BYTES = 16384;
        private static final int MAX_HEADER_BYTES = 64;

        private final File undoDir;
        private final Deque<File> stack = new ArrayDeque<>();
        private final Deque<File> redoStack = new ArrayDeque<>();
        private final byte[] ioBuf = new byte[IO_BUF_BYTES];
        private final byte[] zBuf = new byte[IO_BUF_BYTES];
        private final byte[] shiftBuf = new byte[IO_BUF_BYTES];
        private final byte[] hdrBuf = new byte[MAX_HEADER_BYTES];
        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        private final Inflater inflater = new Inflater();
        private int seq = 0;

        public UndoJournal(File takesDir) {
            this.undoDir = new File(takesDir, ".undo");
            // noinspection ResultOfMethodCallIgnored
            this.undoDir.mkdirs();
        }

        public synchronized boolean canUndo() {
            while (!stack.isEmpty() && !stack.peekFirst().exists()) {
                stack.pollFirst();
            }
            return !stack.isEmpty();
        }

        public synchronized boolean canRedo() {
            while (!redoStack.isEmpty() && !redoStack.peekFirst().exists()) {
                redoStack.pollFirst();
            }
            return !redoStack.isEmpty();
        }

        public synchronized int undoDepth() {
            while (!stack.isEmpty() && !stack.peekFirst().exists()) {
                stack.pollFirst();
            }
            return stack.size();
        }

        public synchronized int redoDepth() {
            while (!redoStack.isEmpty() && !redoStack.peekFirst().exists()) {
                redoStack.pollFirst();
            }
            return redoStack.size();
        }

        /**
         * True when {@link #recordSnapshot(File)} will accept {@code raw}.
         * Works on entire takes of any duration without limiting footage length.
         */
        public synchronized boolean wouldRecord(File raw) {
            return raw != null && raw.exists() && raw.length() > 0L;
        }

        public synchronized void clearRedo() {
            while (!redoStack.isEmpty()) {
                File old = redoStack.pollFirst();
                if (old != null) {
                    // noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
            }
        }

        private void evictOldestForSpaceLocked() {
            if (!redoStack.isEmpty()) {
                File oldRedo = redoStack.removeLast();
                if (oldRedo != null) {
                    // noinspection ResultOfMethodCallIgnored
                    oldRedo.delete();
                }
            } else if (!stack.isEmpty()) {
                File oldUndo = stack.removeLast();
                if (oldUndo != null) {
                    // noinspection ResultOfMethodCallIgnored
                    oldUndo.delete();
                }
            }
        }

        /**
         * Saves a zero-allocation streaming compressed snapshot of {@code raw}
         * before a mutating edit.
         */
        public synchronized boolean recordSnapshot(File raw) {
            if (!wouldRecord(raw)) {
                return false;
            }
            try {
                // noinspection ResultOfMethodCallIgnored
                undoDir.mkdirs();
                clearRedo();
                while (stack.size() >= MAX_UNDO_STEPS) {
                    File old = stack.removeLast();
                    // noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                File snap = new File(undoDir,
                        raw.getName() + "." + (++seq) + ".undo");
                if (writeFullCompressedSnapshot(raw, snap)) {
                    stack.addFirst(snap);
                    return true;
                }
                // Retry once after evicting the oldest snapshot if disk space was low:
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
                evictOldestForSpaceLocked();
                if (writeFullCompressedSnapshot(raw, snap)) {
                    stack.addFirst(snap);
                    return true;
                }
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
                return false;
            } catch (OutOfMemoryError oom) {
                evictOldestForSpaceLocked();
                return false;
            }
        }

        /**
         * Saves a splice-aware range-delta compressed snapshot of
         * {@code [byteOffset, byteOffset + byteLength)} (plus the WAV header)
         * before an in-place or duration-changing sub-range edit.
         *
         * <p>Works for both equal-length DSP edits and variable-length splices
         * (Delete Selection, Select &amp; Replace, Time Stretch, Repeat, Silence
         * Truncation), shifting the untouched post-selection tail in-place on
         * Undo/Redo with zero heap allocations. Falls back to
         * {@link #recordSnapshot(File)} if the range covers the entire file.
         */
        public synchronized boolean recordRangeSnapshot(File raw,
                                                        long byteOffset,
                                                        long byteLength) {
            if (!wouldRecord(raw)) {
                return false;
            }
            try {
                long fileLen = raw.length();
                long clampedOff = Math.max(0L, Math.min(fileLen, byteOffset));
                long clampedLen = Math.max(0L,
                        Math.min(fileLen - clampedOff, byteLength));
                if (clampedOff <= MAX_HEADER_BYTES
                        && clampedLen >= fileLen - MAX_HEADER_BYTES) {
                    return recordSnapshot(raw);
                }
                // noinspection ResultOfMethodCallIgnored
                undoDir.mkdirs();
                clearRedo();
                while (stack.size() >= MAX_UNDO_STEPS) {
                    File old = stack.removeLast();
                    // noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                File snap = new File(undoDir,
                        raw.getName() + "." + (++seq) + ".undo");
                if (writeRangeDeltaSnapshot(raw, clampedOff, clampedLen, snap)) {
                    stack.addFirst(snap);
                    return true;
                }
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
                evictOldestForSpaceLocked();
                if (writeRangeDeltaSnapshot(raw, clampedOff, clampedLen, snap)) {
                    stack.addFirst(snap);
                    return true;
                }
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
                return false;
            } catch (OutOfMemoryError oom) {
                evictOldestForSpaceLocked();
                return false;
            }
        }

        /** Restores the most recent snapshot over `raw` and pushes current state to redo. */
        public synchronized boolean undo(File raw) {
            if (!canUndo() || raw == null) return false;
            File snap = stack.pollFirst();
            if (snap == null || !snap.exists()) return false;

            File redoSnap = null;
            try {
                if (wouldRecord(raw)) {
                    // noinspection ResultOfMethodCallIgnored
                    undoDir.mkdirs();
                    while (redoStack.size() >= MAX_UNDO_STEPS) {
                        File oldRedo = redoStack.removeLast();
                        // noinspection ResultOfMethodCallIgnored
                        oldRedo.delete();
                    }
                    redoSnap = new File(undoDir,
                            raw.getName() + "." + (++seq) + ".redo");
                    if (!createOppositeSnapshot(raw, snap, redoSnap)) {
                        // noinspection ResultOfMethodCallIgnored
                        redoSnap.delete();
                        redoSnap = null;
                    }
                }

                if (!restoreSnapshotToFile(snap, raw)) {
                    if (redoSnap != null) {
                        // noinspection ResultOfMethodCallIgnored
                        redoSnap.delete();
                    }
                    return false;
                }
                if (redoSnap != null) {
                    redoStack.addFirst(redoSnap);
                }
                return true;
            } catch (Throwable t) {
                if (redoSnap != null) {
                    // noinspection ResultOfMethodCallIgnored
                    redoSnap.delete();
                }
                return false;
            } finally {
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
            }
        }

        /** Re-applies the most recently undone edit over `raw` and pushes current state to undo. */
        public synchronized boolean redo(File raw) {
            if (!canRedo() || raw == null) return false;
            File redoSnap = redoStack.pollFirst();
            if (redoSnap == null || !redoSnap.exists()) return false;

            File undoSnap = null;
            try {
                if (wouldRecord(raw)) {
                    // noinspection ResultOfMethodCallIgnored
                    undoDir.mkdirs();
                    while (stack.size() >= MAX_UNDO_STEPS) {
                        File oldUndo = stack.removeLast();
                        // noinspection ResultOfMethodCallIgnored
                        oldUndo.delete();
                    }
                    undoSnap = new File(undoDir,
                            raw.getName() + "." + (++seq) + ".undo");
                    if (!createOppositeSnapshot(raw, redoSnap, undoSnap)) {
                        // noinspection ResultOfMethodCallIgnored
                        undoSnap.delete();
                        undoSnap = null;
                    }
                }

                if (!restoreSnapshotToFile(redoSnap, raw)) {
                    if (undoSnap != null) {
                        // noinspection ResultOfMethodCallIgnored
                        undoSnap.delete();
                    }
                    return false;
                }
                if (undoSnap != null) {
                    stack.addFirst(undoSnap);
                }
                return true;
            } catch (Throwable t) {
                if (undoSnap != null) {
                    // noinspection ResultOfMethodCallIgnored
                    undoSnap.delete();
                }
                return false;
            } finally {
                // noinspection ResultOfMethodCallIgnored
                redoSnap.delete();
            }
        }

        public synchronized void clear() {
            while (!stack.isEmpty()) {
                // noinspection ResultOfMethodCallIgnored
                stack.pollFirst().delete();
            }
            clearRedo();
        }

        private void deflateStream(RandomAccessFile src, long startPos,
                                   long byteCount, RandomAccessFile dst)
                throws Exception {
            deflater.reset();
            src.seek(startPos);
            long remaining = byteCount;
            while (remaining > 0L) {
                int want = (int) Math.min((long) ioBuf.length, remaining);
                int n = src.read(ioBuf, 0, want);
                if (n <= 0) break;
                remaining -= n;
                deflater.setInput(ioBuf, 0, n);
                while (!deflater.needsInput()) {
                    int c = deflater.deflate(zBuf, 0, zBuf.length);
                    if (c > 0) {
                        dst.write(zBuf, 0, c);
                    }
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                int c = deflater.deflate(zBuf, 0, zBuf.length);
                if (c > 0) {
                    dst.write(zBuf, 0, c);
                } else {
                    break;
                }
            }
        }

        private void inflateStream(RandomAccessFile srcSnap,
                                   RandomAccessFile dstRaw,
                                   long dstStartPos,
                                   long expectedBytes) throws Exception {
            inflater.reset();
            dstRaw.seek(dstStartPos);
            long written = 0L;
            while (written < expectedBytes && !inflater.finished()) {
                if (inflater.needsInput()) {
                    int n = srcSnap.read(zBuf, 0, zBuf.length);
                    if (n <= 0) break;
                    inflater.setInput(zBuf, 0, n);
                }
                int maxOut = (int) Math.min(
                        (long) ioBuf.length, expectedBytes - written);
                int dc = inflater.inflate(ioBuf, 0, maxOut);
                if (dc > 0) {
                    dstRaw.write(ioBuf, 0, dc);
                    written += dc;
                } else if (inflater.needsDictionary()
                        || (dc == 0 && !inflater.needsInput())) {
                    break;
                }
            }
        }

        private boolean createOppositeSnapshot(File currentRaw,
                                               File incomingSnap,
                                               File outOppositeSnap) {
            try (RandomAccessFile in = new RandomAccessFile(incomingSnap, "r")) {
                if (in.length() >= 13L) {
                    byte b0 = in.readByte();
                    byte b1 = in.readByte();
                    byte b2 = in.readByte();
                    byte b3 = in.readByte();
                    if (b0 == SNAP_MAGIC_0 && b1 == SNAP_MAGIC_1
                            && b2 == SNAP_MAGIC_2 && b3 == SNAP_MAGIC_3) {
                        byte mode = in.readByte();
                        long origLen = in.readLong();
                        if (mode == SNAP_MODE_DELTA) {
                            int hdrLen = in.readInt();
                            if (hdrLen > 0 && hdrLen <= MAX_HEADER_BYTES) {
                                in.seek(in.getFilePointer() + hdrLen);
                                long deltaOff = in.readLong();
                                long origDeltaLen = in.readLong();
                                long tailLen = Math.max(0L,
                                        origLen - (deltaOff + origDeltaLen));
                                long curLen = currentRaw.length();
                                long curDeltaLen = curLen - deltaOff - tailLen;
                                if (deltaOff >= 0L && curDeltaLen >= 0L
                                        && deltaOff + curDeltaLen <= curLen) {
                                    return writeRangeDeltaSnapshot(
                                            currentRaw, deltaOff, curDeltaLen,
                                            outOppositeSnap);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return writeFullCompressedSnapshot(currentRaw, outOppositeSnap);
        }

        private boolean writeFullCompressedSnapshot(File raw, File snap) {
            long origLen = raw.length();
            try (RandomAccessFile in = new RandomAccessFile(raw, "r");
                 RandomAccessFile out = new RandomAccessFile(snap, "rw")) {
                out.setLength(0L);
                out.writeByte(SNAP_MAGIC_0);
                out.writeByte(SNAP_MAGIC_1);
                out.writeByte(SNAP_MAGIC_2);
                out.writeByte(SNAP_MAGIC_3);
                out.writeByte(SNAP_MODE_FULL);
                out.writeLong(origLen);
                deflateStream(in, 0L, origLen, out);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean writeRangeDeltaSnapshot(File raw, long deltaOff,
                                                long deltaLen, File snap) {
            long origLen = raw.length();
            int hdrLen = (int) Math.min((long) MAX_HEADER_BYTES, origLen);
            try (RandomAccessFile raf = new RandomAccessFile(raw, "r");
                 RandomAccessFile out = new RandomAccessFile(snap, "rw")) {
                out.setLength(0L);
                raf.seek(0L);
                raf.readFully(hdrBuf, 0, hdrLen);

                out.writeByte(SNAP_MAGIC_0);
                out.writeByte(SNAP_MAGIC_1);
                out.writeByte(SNAP_MAGIC_2);
                out.writeByte(SNAP_MAGIC_3);
                out.writeByte(SNAP_MODE_DELTA);
                out.writeLong(origLen);
                out.writeInt(hdrLen);
                out.write(hdrBuf, 0, hdrLen);
                out.writeLong(deltaOff);
                out.writeLong(deltaLen);

                if (deltaLen > 0L) {
                    deflateStream(raf, deltaOff, deltaLen, out);
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean restoreSnapshotToFile(File snap, File raw)
                throws Exception {
            try (RandomAccessFile in = new RandomAccessFile(snap, "r");
                 RandomAccessFile out = new RandomAccessFile(raw, "rw")) {
                long snapLen = in.length();
                if (snapLen >= 13L) {
                    byte b0 = in.readByte();
                    byte b1 = in.readByte();
                    byte b2 = in.readByte();
                    byte b3 = in.readByte();
                    if (b0 == SNAP_MAGIC_0 && b1 == SNAP_MAGIC_1
                            && b2 == SNAP_MAGIC_2 && b3 == SNAP_MAGIC_3) {
                        byte mode = in.readByte();
                        long origLen = in.readLong();
                        if (mode == SNAP_MODE_FULL) {
                            out.setLength(0L);
                            inflateStream(in, out, 0L, origLen);
                            out.setLength(origLen);
                            return true;
                        } else if (mode == SNAP_MODE_DELTA) {
                            int hdrLen = in.readInt();
                            if (hdrLen < 0 || hdrLen > MAX_HEADER_BYTES) {
                                return false;
                            }
                            in.readFully(hdrBuf, 0, hdrLen);
                            long deltaOff = in.readLong();
                            long origDeltaLen = in.readLong();
                            long snapDataPos = in.getFilePointer();

                            long curLen = out.length();
                            long tailLen = Math.max(0L,
                                    origLen - (deltaOff + origDeltaLen));
                            long curDeltaLen = curLen - deltaOff - tailLen;

                            if (tailLen > 0L && origDeltaLen != curDeltaLen
                                    && curDeltaLen >= 0L) {
                                long srcTailOff = deltaOff + curDeltaLen;
                                long dstTailOff = deltaOff + origDeltaLen;
                                if (dstTailOff < srcTailOff) {
                                    // Shrinking splice: shift untouched tail forward in 16 KB chunks
                                    long moved = 0L;
                                    while (moved < tailLen) {
                                        int step = (int) Math.min(
                                                (long) shiftBuf.length,
                                                tailLen - moved);
                                        out.seek(srcTailOff + moved);
                                        out.readFully(shiftBuf, 0, step);
                                        out.seek(dstTailOff + moved);
                                        out.write(shiftBuf, 0, step);
                                        moved += step;
                                    }
                                } else {
                                    // Growing splice: extend file and shift untouched tail backward from EOF
                                    out.setLength(origLen);
                                    long rem = tailLen;
                                    while (rem > 0L) {
                                        int step = (int) Math.min(
                                                (long) shiftBuf.length, rem);
                                        long off = rem - step;
                                        out.seek(srcTailOff + off);
                                        out.readFully(shiftBuf, 0, step);
                                        out.seek(dstTailOff + off);
                                        out.write(shiftBuf, 0, step);
                                        rem -= step;
                                    }
                                }
                            }

                            out.seek(0L);
                            out.write(hdrBuf, 0, hdrLen);
                            if (origDeltaLen > 0L) {
                                in.seek(snapDataPos);
                                inflateStream(in, out, deltaOff, origDeltaLen);
                            }
                            out.setLength(origLen);
                            return true;
                        }
                    }
                }
                // Fallback for legacy uncompressed snapshots:
                in.seek(0L);
                out.setLength(0L);
                int n;
                while ((n = in.read(ioBuf)) > 0) {
                    out.write(ioBuf, 0, n);
                }
                return true;
            }
        }
    }

    /**
     * Saves the post-selection tail `[tailStartFrame, totalFrames)` of `raw`
     * into `tailOut` before a variable-length Select & Replace recording starts.
     */
    public static long saveTailFrom(File raw, long tailStartFrame, File tailOut)
            throws Exception {
        return saveTailFrom(raw, tailStartFrame, tailOut, true);
    }

    /**
     * Saves the tail starting at exactly {@code tailStartFrame} without
     * snapping to a zero crossing.
     */
    static long saveTailFrom(File raw, long tailStartFrame, File tailOut,
                             boolean snap) throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            if (snap) {
                tailStartFrame = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                        tailStartFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            }
            long tailFrames = Math.max(0, totalFrames - tailStartFrame);
            long frameBytes = ch * 4L;
            long tailBytes = tailFrames * frameBytes;
            try (RandomAccessFile w = new RandomAccessFile(tailOut, "rw")) {
                w.setLength(0);
                w.write(Dsp.wavFloatHeader(ch, info.sampleRate, tailBytes));
                if (tailBytes > 0) {
                    raf.seek(info.dataStart + tailStartFrame * frameBytes);
                    byte[] buf = new byte[CHUNK_BYTES];
                    long left = tailBytes;
                    while (left > 0) {
                        int want = (int) Math.min(buf.length, left);
                        int n = raf.read(buf, 0, want);
                        if (n <= 0) break;
                        w.write(buf, 0, n);
                        left -= n;
                    }
                }
            }
            return tailFrames;
        }
    }

    /**
     * Completes a variable-length Select & Replace by crossfading the end of
     * the newly recorded replacement (`replaceEndFrame` in `raw`) with the
     * beginning of `savedTail` (240 frames = 5 ms raised-cosine bridge) and
     * appending the remainder of `savedTail` in 64 KB chunks.
     *
     * @return the new total payload bytes of `raw`.
     */
    public static long spliceTailAfterReplace(File raw, long replaceEndFrame,
                                              File savedTail) throws Exception {
        if (savedTail == null || !savedTail.exists()) {
            return RecordingEngine.payloadBytesOf(raw);
        }
        normalizeFloatDataStart(raw);
        FormatConverter.WavInfo rawInfo = FormatConverter.readFloatWav(raw);
        FormatConverter.WavInfo tailInfo = FormatConverter.readFloatWav(savedTail);
        int ch = rawInfo.channels;
        long frameBytes = ch * 4L;
        long rawTotalFrames = rawInfo.dataBytes / frameBytes;
        long tailTotalFrames = tailInfo.dataBytes / frameBytes;
        replaceEndFrame = Math.max(0, Math.min(rawTotalFrames, replaceEndFrame));

        if (tailTotalFrames <= 0) {
            try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
                long newBytes = replaceEndFrame * frameBytes;
                raf.setLength(rawInfo.dataStart + newBytes);
                raf.seek(0);
                raf.write(Dsp.wavFloatHeader(ch, rawInfo.sampleRate, newBytes));
                return newBytes;
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw");
             RandomAccessFile traf = new RandomAccessFile(savedTail, "r")) {
            int fade = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                    Math.min(replaceEndFrame, tailTotalFrames));
            long writePos = rawInfo.dataStart + (replaceEndFrame - fade) * frameBytes;
            if (fade > 0) {
                float[] pre = readFloatFrames(raf, writePos, fade, ch);
                float[] post = readFloatFrames(traf, tailInfo.dataStart, fade, ch);
                float[] blended = new float[fade * ch];
                Dsp.crossfadeFrames(pre, 0, post, 0, blended, 0, fade, ch);
                raf.seek(writePos);
                Dsp.writeFloatsLE(raf, blended, 0, blended.length);
                writePos += fade * frameBytes;
            }
            long remTailFrames = Math.max(0, tailTotalFrames - fade);
            long remTailBytes = remTailFrames * frameBytes;
            traf.seek(tailInfo.dataStart + fade * frameBytes);
            raf.seek(writePos);
            byte[] buf = new byte[CHUNK_BYTES];
            long moved = 0;
            while (moved < remTailBytes) {
                int want = (int) Math.min(buf.length, remTailBytes - moved);
                int n = traf.read(buf, 0, want);
                if (n <= 0) break;
                raf.write(buf, 0, n);
                moved += n;
            }
            long newPayloadBytes = (writePos - rawInfo.dataStart) + moved;
            raf.setLength(rawInfo.dataStart + newPayloadBytes);
            raf.seek(0);
            raf.write(Dsp.wavFloatHeader(ch, rawInfo.sampleRate, newPayloadBytes));
            return newPayloadBytes;
        } finally {
            // noinspection ResultOfMethodCallIgnored
            savedTail.delete();
        }
    }

    /**
     * Splices a newly recorded replacement session file (`repWav`) into
     * `[selStartFrame, selEndFrame)` of `parentRaw` with 240-frame (5 ms)
     * raised-cosine crossfades at both splice boundaries.
     */
    public static long spliceReplacementFile(File parentRaw, long selStartFrame,
                                             long selEndFrame, File repWav,
                                             UndoJournal undo) throws Exception {
        normalizeFloatDataStart(parentRaw);
        FormatConverter.WavInfo pInfo = FormatConverter.readFloatWav(parentRaw);
        FormatConverter.WavInfo rInfo = FormatConverter.readFloatWav(repWav);
        int ch = pInfo.channels;
        long frameBytes = ch * 4L;
        long pTotalFrames = pInfo.dataBytes / frameBytes;
        long rTotalFrames = rInfo.dataBytes / (rInfo.channels * 4L);
        if (rTotalFrames <= 0) return pInfo.dataBytes;

        selStartFrame = Math.max(0, Math.min(pTotalFrames, selStartFrame));
        selEndFrame = Math.max(selStartFrame, Math.min(pTotalFrames, selEndFrame));

        if (undo != null) {
            long touchStart = Math.max(0L, selStartFrame - Dsp.CROSSFADE_FRAMES);
            long touchEnd = Math.min(pTotalFrames,
                    selEndFrame + 2L * Dsp.CROSSFADE_FRAMES);
            undo.recordRangeSnapshot(parentRaw,
                    pInfo.dataStart + touchStart * frameBytes,
                    Math.max(0L, (touchEnd - touchStart) * frameBytes));
        }

        File tailTmp = new File(parentRaw.getParentFile(),
                parentRaw.getName() + ".subtail");
        try {
            saveTailFrom(parentRaw, selEndFrame, tailTmp);
            try (RandomAccessFile praf = new RandomAccessFile(parentRaw, "rw");
                 RandomAccessFile rraf = new RandomAccessFile(repWav, "r")) {
                int fadeIn = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                        Math.min(selStartFrame, rTotalFrames));
                long writePos = pInfo.dataStart + selStartFrame * frameBytes;
                if (fadeIn > 0) {
                    float[] pre = readFloatFrames(praf,
                            pInfo.dataStart + (selStartFrame - fadeIn) * frameBytes,
                            fadeIn, ch);
                    float[] inc = readFloatFrames(rraf, rInfo.dataStart, fadeIn, ch);
                    float[] blended = new float[fadeIn * ch];
                    Dsp.crossfadeFrames(pre, 0, inc, 0, blended, 0, fadeIn, ch);
                    praf.seek(pInfo.dataStart + (selStartFrame - fadeIn) * frameBytes);
                    Dsp.writeFloatsLE(praf, blended, 0, blended.length);
                }
                long remRepFrames = Math.max(0, rTotalFrames - fadeIn);
                long remRepBytes = remRepFrames * frameBytes;
                rraf.seek(rInfo.dataStart + fadeIn * frameBytes);
                praf.seek(writePos);
                byte[] buf = new byte[CHUNK_BYTES];
                long copied = 0;
                while (copied < remRepBytes) {
                    int want = (int) Math.min(buf.length, remRepBytes - copied);
                    int n = rraf.read(buf, 0, want);
                    if (n <= 0) break;
                    praf.write(buf, 0, n);
                    copied += n;
                }
                long repEndFrame = selStartFrame + remRepFrames;
                long curPayload = repEndFrame * frameBytes;
                praf.setLength(pInfo.dataStart + curPayload);
                praf.seek(0);
                praf.write(Dsp.wavFloatHeader(ch, pInfo.sampleRate, curPayload));
            }
            long replaceEndFrame = selStartFrame
                    + Math.max(0, rTotalFrames - Math.min(Dsp.CROSSFADE_FRAMES,
                            Math.min(selStartFrame, rTotalFrames)));
            return spliceTailAfterReplace(parentRaw, replaceEndFrame, tailTmp);
        } finally {
            if (tailTmp.exists()) {
                // noinspection ResultOfMethodCallIgnored
                tailTmp.delete();
            }
        }
    }

    /**
     * Moves a float WAV payload down to offset 44 so the fixed-offset header
     * rewrites performed by splice() cannot orphan or misread it.
     */
    static void normalizeFloatDataStart(File raw) throws Exception {
        if (raw == null || !raw.exists()) return;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        if (info.dataStart <= 44L) return;
        long payload = info.dataBytes;
        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
            byte[] buf = new byte[CHUNK_BYTES];
            long moved = 0;
            while (moved < payload) {
                int want = (int) Math.min(buf.length, payload - moved);
                raf.seek(info.dataStart + moved);
                raf.readFully(buf, 0, want);
                raf.seek(44L + moved);
                raf.write(buf, 0, want);
                moved += want;
            }
            raf.setLength(44L + payload);
            raf.seek(0);
            raf.write(Dsp.wavFloatHeader(info.channels, info.sampleRate,
                    payload));
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
            long snappedStart = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    startFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            long snappedEnd = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    endFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            if (snappedStart < snappedEnd
                    && snappedEnd - snappedStart >= 480) {
                startFrame = snappedStart;
                endFrame = snappedEnd;
            }
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
        normalizeFloatDataStart(raw);
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

        long frameBytes = ch * 4L;
        int fade;
        try (RandomAccessFile r = new RandomAccessFile(raw, "r")) {
            long snappedStart = Dsp.snapToZeroCrossing(r, info.dataStart, ch,
                    startFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            long snappedEnd = Dsp.snapToZeroCrossing(r, info.dataStart, ch,
                    endFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            if (snappedStart < snappedEnd) {
                startFrame = snappedStart;
                endFrame = snappedEnd;
            }
            delFrames = endFrame - startFrame;
            if (delFrames >= totalFrames - 480) {
                throw new IllegalArgumentException("cannot delete entire take");
            }
            fade = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                    Math.min(startFrame, totalFrames - endFrame));
        }

        if (undo != null) {
            long deltaOff = info.dataStart + (startFrame - fade) * frameBytes;
            long deltaLen = (delFrames + 2L * fade) * frameBytes;
            undo.recordRangeSnapshot(raw, deltaOff, deltaLen);
        }

        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {

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
     * Applies a duration-changing or offline effect (Change Tempo, Change Speed,
     * Change Pitch, Paulstretch, Sliding Stretch, Fade, Timeline Utility,
     * Truncate Silence, Audio Generator) to {@code [startFrame, endFrame)} of
     * {@code raw} using bounded-chunk file streaming.
     *
     * <p>The region is streamed through {@link AudioProcessor#processOfflineRangeToFile}
     * to a temporary file in bounded chunks, then spliced back into {@code raw}
     * in 64 KB blocks so multi-minute or multi-hour takes never OOM.
     *
     * @return the new total frame count of the file, or -1 when the effect
     *         produced no output
     */
    public static long applyLengthChangingEffectToRange(
            File raw, long startFrame, long endFrame,
            AudioProcessor processor, UndoJournal undo) throws Exception {
        if (raw == null || processor == null) return -1;
        normalizeFloatDataStart(raw);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return -1;

        float mix = processor.getWetDryMix();
        if (mix <= 0.001f) return totalFrames;

        File renderTmp = new File(raw.getParentFile(),
                raw.getName() + ".lcrender");
        File tailTmp = new File(raw.getParentFile(),
                raw.getName() + ".lctail");
        try {
            long newFrames = processor.processOfflineRangeToFile(
                    raw, startFrame, endFrame, renderTmp, info.sampleRate, ch);
            if (newFrames <= 0 || !renderTmp.exists()) return -1;

            if (undo != null) {
                long frameBytes = ch * 4L;
                long touchStart = Math.max(0L, startFrame - Dsp.CROSSFADE_FRAMES);
                long touchEnd = Math.min(totalFrames, endFrame + Dsp.CROSSFADE_FRAMES);
                undo.recordRangeSnapshot(raw,
                        info.dataStart + touchStart * frameBytes,
                        Math.max(0L, (touchEnd - touchStart) * frameBytes));
            }

            long tailFrames = saveTailFrom(raw, endFrame, tailTmp, false);
            long frameBytes = ch * 4L;
            FormatConverter.WavInfo rInfo = FormatConverter.readFloatWav(renderTmp);
            try (RandomAccessFile raf = new RandomAccessFile(raw, "rw");
                 RandomAccessFile rRaf = new RandomAccessFile(renderTmp, "r")) {
                long writePos = info.dataStart + startFrame * frameBytes;
                raf.seek(writePos);
                rRaf.seek(rInfo.dataStart);
                byte[] copyBuf = new byte[CHUNK_BYTES];
                long leftBytes = newFrames * frameBytes;
                while (leftBytes > 0) {
                    int want = (int) Math.min((long) copyBuf.length, leftBytes);
                    int n = rRaf.read(copyBuf, 0, want);
                    if (n <= 0) break;
                    raf.write(copyBuf, 0, n);
                    leftBytes -= n;
                }
                long newEndFrame = startFrame + newFrames;
                raf.setLength(info.dataStart + newEndFrame * frameBytes);
                raf.seek(0);
                raf.write(Dsp.wavFloatHeader(ch, info.sampleRate,
                        newEndFrame * frameBytes));
            }
            long newEnd = startFrame + newFrames;
            int fade = (int) Math.min(Dsp.CROSSFADE_FRAMES,
                    Math.min(newEnd, tailFrames));
            long newTotal = newEnd + tailFrames - fade;
            spliceTailAfterReplace(raw, newEnd, tailTmp);
            return newTotal;
        } finally {
            if (renderTmp.exists()) {
                // noinspection ResultOfMethodCallIgnored
                renderTmp.delete();
            }
            if (tailTmp.exists()) {
                // noinspection ResultOfMethodCallIgnored
                tailTmp.delete();
            }
        }
    }

    /** Progress callback for long full-file DSP bakes (0..100). */
    public interface Progress {
        void onProgress(int pct);
    }

    /**
     * Applies `chain` in-place to `[startFrame, endFrame)` of `raw`.
     */
    public static boolean applyDspToRange(File raw, long startFrame, long endFrame,
                                          DspChain chain, UndoJournal undo)
            throws Exception {
        return applyDspToRange(raw, startFrame, endFrame, chain, undo, null);
    }

    public static boolean applyDspToRangeWithProgress(
            File raw, long startFrame, long endFrame, DspChain chain,
            Progress progress) throws Exception {
        return applyDspToRange(raw, startFrame, endFrame, chain,
                (UndoJournal) null, progress);
    }

    private static boolean applyDspToRange(File raw, long startFrame, long endFrame,
                                           DspChain chain, UndoJournal undo,
                                           Progress progress)
            throws Exception {
        if (chain == null || !chain.isActive()) return false;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return false;

        long frameBytes = ch * 4L;
        int tailFrames = Math.max(0, chain.getTailFrames());
        long maxTouchedEndFrame = Math.min(totalFrames, endFrame + tailFrames);
        long touchByteStart = info.dataStart + startFrame * frameBytes;
        long touchByteLen = Math.max(0L, (maxTouchedEndFrame - startFrame) * frameBytes);

        boolean snapshotRecorded = true;
        if (undo != null) {
            snapshotRecorded = undo.recordRangeSnapshot(
                    raw, touchByteStart, touchByteLen);
        }

        // Configure the chain for the file's real sample rate, reset states,
        // then run two-pass measurement so scanned state is preserved.
        chain.setSampleRate(info.sampleRate);
        chain.reset();
        chain.prepareOffline(raw, startFrame, endFrame, info.sampleRate, ch);
        final int latencyFrames = Math.max(0, chain.getLatencyFrames());
        int maxFade = (int) Math.min(Dsp.CROSSFADE_FRAMES, selFrames / 2);
        int headFade = (startFrame > 0) ? maxFade : 0;
        int tailFade = (endFrame < totalFrames) ? maxFade : 0;

        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
            // Snapshot dry boundary frames BEFORE writing anything in-place.
            float[] origHead = headFade > 0
                    ? readFloatFrames(raf,
                            info.dataStart + startFrame * frameBytes,
                            headFade, ch)
                    : null;
            float[] origTail = tailFade > 0
                    ? readFloatFrames(raf,
                            info.dataStart + (endFrame - tailFade) * frameBytes,
                            tailFade, ch)
                    : null;

            // Pre-roll warm-up over up to 240 frames before startFrame so
            // minimum-phase biquad states and envelope followers settle before
            // the splice.
            int preRoll = (int) Math.min(Dsp.CROSSFADE_FRAMES, startFrame);
            if (preRoll > 0) {
                float[] warm = readFloatFrames(raf,
                        info.dataStart + (startFrame - preRoll) * frameBytes,
                        preRoll, ch);
                chain.processInPlace(warm, preRoll);
            }

            float[] postContext = null;
            int postContextFrames = 0;
            if (latencyFrames > 0) {
                postContextFrames = (int) Math.min(latencyFrames,
                        Math.max(0L, totalFrames - endFrame));
                if (postContextFrames > 0) {
                    postContext = readFloatFrames(raf,
                            info.dataStart + endFrame * frameBytes,
                            postContextFrames, ch);
                }
                int primeRemaining = latencyFrames;
                long primePos = startFrame;
                int primeChunk = 1024;
                float[] primeBuf = new float[primeChunk * ch];
                while (primeRemaining > 0) {
                    int step = Math.min(primeChunk, primeRemaining);
                    int fromSel = (int) Math.max(0L,
                            Math.min((long) step, endFrame - primePos));
                    if (fromSel > 0) {
                        float[] tmp = readFloatFrames(raf,
                                info.dataStart + primePos * frameBytes,
                                fromSel, ch);
                        System.arraycopy(tmp, 0, primeBuf, 0, fromSel * ch);
                    }
                    if (fromSel < step) {
                        for (int f = fromSel; f < step; f++) {
                            long postIdx = (primePos + f) - endFrame;
                            for (int c = 0; c < ch; c++) {
                                primeBuf[f * ch + c] = (postContext != null
                                        && postIdx >= 0 && postIdx < postContextFrames)
                                        ? postContext[(int) postIdx * ch + c]
                                        : 0f;
                            }
                        }
                    }
                    chain.processInPlace(primeBuf, step);
                    primePos += step;
                    primeRemaining -= step;
                }
            }

            int chunkFrames = AudioProcessor.DEFAULT_DSP_CHUNK_FRAMES;
            byte[] rawBuf = new byte[chunkFrames * ch * 4];
            float[] fBuf = new float[chunkFrames * ch];
            ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);

            long cur = startFrame;
            long tailFadeStartFrame = endFrame - tailFade;
            while (cur < endFrame) {
                int want = (int) Math.min(chunkFrames, endFrame - cur);
                long inStart = cur + latencyFrames;
                int fromFile = (int) Math.max(0L,
                        Math.min((long) want, endFrame - inStart));
                if (fromFile > 0) {
                    raf.seek(info.dataStart + inStart * frameBytes);
                    raf.readFully(rawBuf, 0, fromFile * ch * 4);
                    bb.clear();
                    bb.limit(fromFile * ch * 4);
                    bb.asFloatBuffer().get(fBuf, 0, fromFile * ch);
                }
                if (fromFile < want) {
                    for (int f = fromFile; f < want; f++) {
                        long absFrame = inStart + f;
                        long postIdx = absFrame - endFrame;
                        for (int c = 0; c < ch; c++) {
                            fBuf[f * ch + c] = (postContext != null
                                    && postIdx >= 0 && postIdx < postContextFrames)
                                    ? postContext[(int) postIdx * ch + c]
                                    : 0f;
                        }
                    }
                }

                chain.processInPlace(fBuf, want);
                if (progress != null) {
                    int pct = (int) ((cur + want - startFrame) * 100L
                            / Math.max(1L, selFrames));
                    progress.onProgress(Math.max(0, Math.min(100, pct)));
                }

                // Head seam: dry origHead -> wet, over [startFrame, startFrame+headFade).
                if (headFade > 0 && cur < startFrame + headFade) {
                    int fStart = (int) (cur - startFrame);
                    int fEnd = (int) Math.min(headFade, fStart + want);
                    for (int f = fStart; f < fEnd; f++) {
                        int localFrame = f - fStart;
                        float u = (headFade == 1) ? 1f : (f / (float) (headFade - 1));
                        float gWet = 0.5f * (1f - (float) Math.cos(Math.PI * u));
                        float gDry = 1f - gWet;
                        int baseBuf = localFrame * ch;
                        int baseOrig = f * ch;
                        for (int c = 0; c < ch; c++) {
                            fBuf[baseBuf + c] = origHead[baseOrig + c] * gDry
                                    + fBuf[baseBuf + c] * gWet;
                        }
                    }
                }

                // Tail seam: wet -> dry origTail, over [endFrame-tailFade, endFrame).
                if (tailFade > 0 && cur + want > tailFadeStartFrame) {
                    long overlapStart = Math.max(cur, tailFadeStartFrame);
                    long overlapEnd = Math.min(cur + want, endFrame);
                    for (long frame = overlapStart; frame < overlapEnd; frame++) {
                        int f = (int) (frame - tailFadeStartFrame);
                        int localFrame = (int) (frame - cur);
                        float u = (tailFade == 1) ? 1f : (f / (float) (tailFade - 1));
                        float gDry = 0.5f * (1f - (float) Math.cos(Math.PI * u));
                        float gWet = 1f - gDry;
                        int baseBuf = localFrame * ch;
                        int baseOrig = f * ch;
                        for (int c = 0; c < ch; c++) {
                            fBuf[baseBuf + c] = fBuf[baseBuf + c] * gWet
                                    + origTail[baseOrig + c] * gDry;
                        }
                    }
                }

                long outPos = info.dataStart + cur * frameBytes;
                bb.clear();
                bb.asFloatBuffer().put(fBuf, 0, want * ch);
                raf.seek(outPos);
                raf.write(rawBuf, 0, want * ch * 4);
                cur += want;
            }

            // ---- Tail flushing ------------------------------------------------
            if (tailFrames > 0) {
                long tailStart = endFrame;
                long tailEnd = Math.min(totalFrames, endFrame + tailFrames);
                if (tailEnd > tailStart) {
                    flushTailIntoRange(raf, info, chain, tailStart, tailEnd, ch);
                }
            }
        }
        return snapshotRecorded;
    }

    /**
     * Renders the chain's decaying tail into `[tailStart, tailEnd)` and adds
     * it to the audio that is already there.
     */
    private static void flushTailIntoRange(RandomAccessFile raf,
                                           FormatConverter.WavInfo info,
                                           DspChain chain, long tailStart,
                                           long tailEnd, int ch)
            throws Exception {
        long frameBytes = ch * 4L;
        int chunk = AudioProcessor.DEFAULT_DSP_CHUNK_FRAMES;
        float[] dry = new float[chunk * ch];
        float[] wet = new float[chunk * ch];
        byte[] io = new byte[chunk * ch * 4];
        java.nio.ByteBuffer ioBuf = java.nio.ByteBuffer.wrap(io)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        long pos = tailStart;
        while (pos < tailEnd) {
            int want = (int) Math.min(chunk, tailEnd - pos);
            int samples = want * ch;
            raf.seek(info.dataStart + pos * frameBytes);
            raf.readFully(io, 0, samples * 4);
            ioBuf.clear();
            ioBuf.limit(samples * 4);
            ioBuf.asFloatBuffer().get(dry, 0, samples);
            System.arraycopy(dry, 0, wet, 0, samples);
            chain.processInPlace(wet, want);
            for (int f = 0; f < want; f++) {
                long absFrame = pos + f;
                // 5 ms ramp-in so the tail never steps at the boundary.
                float ramp = 1f;
                if (absFrame < tailStart + Dsp.CROSSFADE_FRAMES) {
                    float u = (absFrame - tailStart) / (float) Dsp.CROSSFADE_FRAMES;
                    ramp = 0.5f * (1f - (float) Math.cos(Math.PI * u));
                }
                for (int c = 0; c < ch; c++) {
                    int idx = f * ch + c;
                    wet[idx] = dry[idx] + (wet[idx] - dry[idx]) * ramp;
                }
            }
            raf.seek(info.dataStart + pos * frameBytes);
            Dsp.writeFloatsLE(raf, wet, 0, want * ch);
            pos += want;
        }
    }

    /**
     * Streams {@code [startFrame, endFrame)} from {@code raw} in bounded
     * chunks (sized by {@link MemoryBudget#chunkFrames}) and invokes
     * {@code consumer} for each chunk without allocating a full-take array.
     *
     * @return total number of frames streamed.
     */
    public static long forEachFloatChunk(File raw, long startFrame,
                                         long endFrame, int desiredChunkFrames,
                                         FloatChunkConsumer consumer)
            throws Exception {
        if (raw == null || !raw.exists() || consumer == null) return 0L;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = Math.max(1, info.channels);
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long count = endFrame - startFrame;
        if (count <= 0) return 0L;

        int chunkFrames = MemoryBudget.chunkFrames(
                Math.max(1024, desiredChunkFrames), ch);
        byte[] rawBuf = new byte[chunkFrames * ch * 4];
        float[] fBuf = new float[chunkFrames * ch];
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);
        long frameBytes = ch * 4L;
        long cur = startFrame;

        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            while (cur < endFrame) {
                int want = (int) Math.min((long) chunkFrames, endFrame - cur);
                raf.seek(info.dataStart + cur * frameBytes);
                raf.readFully(rawBuf, 0, want * ch * 4);
                bb.clear();
                bb.limit(want * ch * 4);
                bb.asFloatBuffer().get(fBuf, 0, want * ch);
                consumer.onChunk(fBuf, want, ch, cur);
                cur += want;
            }
        }
        return count;
    }

    /** Reads an interleaved frame range from a 32-bit float WAV for analysis. */
    static float[] readFloatSelection(File raw, long startFrame, long endFrame)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = Math.max(1, info.channels);
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long count = endFrame - startFrame;
        if (count <= 0) return new float[0];
        int maxSafeFrames = (Integer.MAX_VALUE / 4) / ch;
        int frames = (int) Math.min(count, (long) maxSafeFrames);
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            return readFloatFrames(raf,
                    info.dataStart + startFrame * ch * 4L, frames, ch);
        }
    }

    private static float[] readFloatFrames(RandomAccessFile raf, long bytePos,
                                           int frames, int ch) throws Exception {
        int cCount = Math.max(1, ch);
        int totalSamples = frames * cCount;
        if (totalSamples <= 0) return new float[0];
        float[] out = new float[totalSamples];
        int chunkSamples = Math.min(totalSamples, CHUNK_BYTES / 4);
        byte[] raw = new byte[chunkSamples * 4];
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        raf.seek(bytePos);
        int readSamples = 0;
        while (readSamples < totalSamples) {
            int wantSamples = Math.min(chunkSamples, totalSamples - readSamples);
            raf.readFully(raw, 0, wantSamples * 4);
            bb.clear();
            bb.limit(wantSamples * 4);
            bb.asFloatBuffer().get(out, readSamples, wantSamples);
            readSamples += wantSamples;
        }
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
