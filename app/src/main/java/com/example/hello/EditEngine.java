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
 * WAV session files. Supports Trim/Crop, Delete Selection, Variable-Length
 * Select & Replace (where replacement audio can be shorter or longer than the
 * selected segment), DSP Range Commit, and 3-step Undo.
 */
public final class EditEngine {

    private static final int CHUNK_BYTES = 65536;
    private static final long MAX_UNDO_BYTES = 30L * 1024L * 1024L;
    public static final int MAX_UNDO_STEPS = 10;

    private EditEngine() {}

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

    /** Bounded 10-step undo/redo journal storing snapshots in takes/.undo/. */
    public static final class UndoJournal {
        private final File undoDir;
        private final Deque<File> stack = new ArrayDeque<>();
        private final Deque<File> redoStack = new ArrayDeque<>();
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

        /** True when {@link #recordSnapshot(File)} would accept `raw`. */
        public synchronized boolean wouldRecord(File raw) {
            return raw != null && raw.exists() && raw.length() <= MAX_UNDO_BYTES;
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

        /**
         * Saves a snapshot of `raw` before a mutating edit if within budget.
         *
         * @return true when a snapshot was actually written; false when the
         *         take is missing or exceeds the 30 MB undo budget. Callers
         *         that mirror undo state (waveform peak snapshots) must only
         *         record it when this returns true, or the two undo stacks
         *         desynchronise on long takes.
         */
        public synchronized boolean recordSnapshot(File raw) {
            if (!wouldRecord(raw)) {
                return false;
            }
            // noinspection ResultOfMethodCallIgnored
            undoDir.mkdirs();
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
                clearRedo();
                return true;
            } catch (Exception e) {
                // noinspection ResultOfMethodCallIgnored
                snap.delete();
                return false;
            }
        }

        /** Restores the most recent snapshot over `raw` and pushes current state to redo. */
        public synchronized boolean undo(File raw) {
            if (!canUndo() || raw == null) return false;
            File snap = stack.pollFirst();
            if (snap == null || !snap.exists()) return false;

            File redoSnap = null;
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
                try (FileInputStream curIn = new FileInputStream(raw);
                     FileOutputStream redoOut = new FileOutputStream(redoSnap)) {
                    byte[] buf = new byte[CHUNK_BYTES];
                    int n;
                    while ((n = curIn.read(buf)) > 0) redoOut.write(buf, 0, n);
                } catch (Exception ignored) {
                    // noinspection ResultOfMethodCallIgnored
                    redoSnap.delete();
                    redoSnap = null;
                }
            }

            try (FileInputStream in = new FileInputStream(snap);
                 RandomAccessFile out = new RandomAccessFile(raw, "rw")) {
                out.setLength(0);
                byte[] buf = new byte[CHUNK_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                if (redoSnap != null) {
                    redoStack.addFirst(redoSnap);
                }
                return true;
            } catch (Exception e) {
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
                try (FileInputStream curIn = new FileInputStream(raw);
                     FileOutputStream undoOut = new FileOutputStream(undoSnap)) {
                    byte[] buf = new byte[CHUNK_BYTES];
                    int n;
                    while ((n = curIn.read(buf)) > 0) undoOut.write(buf, 0, n);
                } catch (Exception ignored) {
                    // noinspection ResultOfMethodCallIgnored
                    undoSnap.delete();
                    undoSnap = null;
                }
            }

            try (FileInputStream in = new FileInputStream(redoSnap);
                 RandomAccessFile out = new RandomAccessFile(raw, "rw")) {
                out.setLength(0);
                byte[] buf = new byte[CHUNK_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                if (undoSnap != null) {
                    stack.addFirst(undoSnap);
                }
                return true;
            } catch (Exception e) {
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
    }

    /**
     * Saves the post-selection tail `[tailStartFrame, totalFrames)` of `raw`
     * into `tailOut` before a variable-length Select & Replace recording starts.
     * That way the user can record a replacement of ANY length (shorter or
     * longer than `[selStart, selEnd)`), and when they pause/stop,
     * {@link #spliceTailAfterReplace(File, long, File)} crossfades and appends
     * the saved tail right after the new recording!
     */
    public static long saveTailFrom(File raw, long tailStartFrame, File tailOut)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            tailStartFrame = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    tailStartFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
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

        if (undo != null) undo.recordSnapshot(parentRaw);

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
            long snappedStart = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    startFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            long snappedEnd = Dsp.snapToZeroCrossing(raf, info.dataStart, ch,
                    endFrame, totalFrames, Dsp.CROSSFADE_FRAMES);
            if (snappedStart < snappedEnd) {
                startFrame = snappedStart;
                endFrame = snappedEnd;
            }
            delFrames = endFrame - startFrame;
            if (delFrames >= totalFrames - 480) {
                throw new IllegalArgumentException("cannot delete entire take");
            }
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
     * Applies a duration-changing effect (Change Tempo, Change Speed,
     * Paulstretch, Repeat, Truncate Silence, ...) to `[startFrame, endFrame)`
     * of `raw`.
     *
     * <p>The region is rendered to a temporary buffer, then spliced back into
     * the file. Because the rendered region can be longer or shorter than
     * the original, the post-selection tail is stashed first and re-attached
     * afterwards with a 5 ms raised-cosine crossfade.
     *
     * @return the new total frame count of the file, or -1 when the effect
     *         produced no output
     */
    public static long applyLengthChangingEffectToRange(
            File raw, long startFrame, long endFrame,
            AudioProcessor processor, UndoJournal undo) throws Exception {
        if (raw == null || processor == null) return -1;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return -1;

        float[] region = readFloatSelection(raw, startFrame, endFrame);
        if (region == null || region.length == 0) return -1;
        int regionFrames = region.length / ch;
        float mix = processor.getWetDryMix();
        if (mix <= 0.001f) return totalFrames;

        processor.prepare(info.sampleRate, ch);
        float[] rendered = processor.processOfflineRegion(region, regionFrames, ch);
        if (rendered == null || rendered.length == 0) return -1;
        int newFrames = rendered.length / ch;
        if (newFrames <= 0) return -1;
        if (mix < 0.999f) {
            float dryWeight = 1f - mix;
            int common = Math.min(region.length, rendered.length);
            for (int i = 0; i < common; i++) {
                rendered[i] = region[i] * dryWeight + rendered[i] * mix;
            }
            for (int i = common; i < rendered.length; i++) {
                rendered[i] *= mix;
            }
        }

        if (undo != null) undo.recordSnapshot(raw);

        // Stash the tail so the file can be rewritten with a different
        // region length.
        File tailTmp = new File(raw.getParentFile(), raw.getName() + ".lctail");
        try {
            long tailFrames = saveTailFrom(raw, endFrame, tailTmp);
            try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
                long writePos = info.dataStart + startFrame * ch * 4L;
                raf.seek(writePos);
                Dsp.writeFloatsLE(raf, rendered, 0, rendered.length);
                long newEndFrame = startFrame + newFrames;
                raf.setLength(info.dataStart + newEndFrame * ch * 4L);
                raf.seek(0);
                raf.write(Dsp.wavFloatHeader(ch, info.sampleRate,
                        newEndFrame * ch * 4L));
            }
            long newTotal = startFrame + newFrames + tailFrames;
            spliceTailAfterReplace(raw, startFrame + newFrames, tailTmp);
            return newTotal;
        } finally {
            if (tailTmp.exists()) {
                // noinspection ResultOfMethodCallIgnored
                tailTmp.delete();
            }
        }
    }

    /**
     * Applies `chain` in-place to `[startFrame, endFrame)` of `raw`.
     *
     * <p>Two details make an offline bake inaudible at the seams:
     * <ul>
     *   <li>240 frames (5 ms) of raised-cosine crossfades blend the processed
     *       audio back into the untouched samples at both boundaries, so gain,
     *       EQ, phase or dynamics changes cannot click.</li>
     *   <li>240 frames of pre-roll are pushed through the chain BEFORE
     *       {@code startFrame} and discarded, so biquad and envelope state is
     *       already settled when the first processed sample is written (no
     *       filter start-up transient at the seam, and time-based effects such
     *       as delay/reverb have their tail already running).</li>
     * </ul>
     * Samples outside the range (including the pre-roll) are never modified.
     */
    public static boolean applyDspToRange(File raw, long startFrame, long endFrame,
                                          DspChain chain, UndoJournal undo)
            throws Exception {
        if (chain == null || !chain.isActive()) return false;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return false;
        boolean snapshotRecorded = true;
        if (undo != null) snapshotRecorded = undo.recordSnapshot(raw);

        // Reset filter/envelope states first, then run two-pass measurement
        // (Amplify, Normalize, Loudness) so scanned state is preserved.
        chain.reset();
        chain.prepareOffline(raw, startFrame, endFrame, info.sampleRate, ch);
        final int latencyFrames = Math.max(0, chain.getLatencyFrames());
        long frameBytes = ch * 4L;
        // Never let the fades eat more than half of a very short range, and
        // only crossfade at INTERIOR splices: at the file edges (startFrame 0
        // or endFrame == totalFrames) there is no unprocessed audio to blend
        // toward, so a fade would only leave the first/last 5 ms half dry.
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

            // If the chain has linear-phase FIR latency (e.g. FilterCurveEqProcessor
            // with group delay = taps / 2), prime the pipeline with the first
            // `latencyFrames` of the selection so output frame 0 aligns with
            // input `startFrame`, and snapshot up to `latencyFrames` of post-
            // selection lookahead (or zero-pad at EOF) before in-place writes.
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

            // Stream [startFrame, endFrame) in 4096-frame chunks. When
            // latencyFrames > 0, each chunk reads input from `cur + latencyFrames`
            // (falling back to `postContext` or zero-padding past `endFrame`)
            // and writes the latency-compensated output at `cur`.
            int chunkFrames = 4096;
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
            // Time-based effects (reverb, delay, echo) keep ringing after the
            // last input sample. Their decay is rendered into the audio that
            // already follows the selection, so the effect never gets
            // chopped off abruptly. The file is never grown: a take that
            // ends with the selection simply ends with the tail's crossfade.
            int tailFrames = chain.getTailFrames();
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
     *
     * <p>Only the wet contribution is added (processed minus dry), so the
     * original audio underneath is preserved and the reverb simply bleeds
     * into the following material. The contribution is ramped in over 5 ms
     * so the seam cannot click.
     */
    private static void flushTailIntoRange(RandomAccessFile raf,
                                           FormatConverter.WavInfo info,
                                           DspChain chain, long tailStart,
                                           long tailEnd, int ch)
            throws Exception {
        long frameBytes = ch * 4L;
        long frames = tailEnd - tailStart;
        int chunk = 4096;
        float[] dry = new float[chunk * ch];
        float[] wet = new float[chunk * ch];
        long pos = tailStart;
        while (pos < tailEnd) {
            int want = (int) Math.min(chunk, tailEnd - pos);
            raf.seek(info.dataStart + pos * frameBytes);
            raf.readFully(Dsp.toBytes(dry, 0, want * ch));
            System.arraycopy(dry, 0, wet, 0, want * ch);
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

    /** Reads an interleaved frame range from a 32-bit float WAV for analysis. */
    static float[] readFloatSelection(File raw, long startFrame, long endFrame)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / Math.max(1, ch);
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long count = endFrame - startFrame;
        if (count <= 0) return new float[0];
        int frames = (int) Math.min(count, Integer.MAX_VALUE / Math.max(1, ch));
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            return readFloatFrames(raf,
                    info.dataStart + startFrame * ch * 4L, frames, ch);
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
