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
    public static void applyDspToRange(File raw, long startFrame, long endFrame,
                                       DspChain chain, UndoJournal undo)
            throws Exception {
        if (chain == null || !chain.isActive()) return;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        int ch = info.channels;
        long totalFrames = info.dataBytes / 4 / ch;
        startFrame = Math.max(0, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return;
        if (undo != null) undo.recordSnapshot(raw);

        chain.reset();
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
                    int avail = (int) Math.max(0L,
                            Math.min((long) step, endFrame - primePos));
                    if (avail > 0) {
                        float[] tmp = readFloatFrames(raf,
                                info.dataStart + primePos * frameBytes,
                                avail, ch);
                        System.arraycopy(tmp, 0, primeBuf, 0, avail * ch);
                    }
                    if (avail < step) {
                        java.util.Arrays.fill(primeBuf, avail * ch, step * ch, 0f);
                    }
                    chain.processInPlace(primeBuf, step);
                    primePos += avail;
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
