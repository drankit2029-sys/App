package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renders the live FX rack over a take and reports the resulting peak
 * envelope, so the studio can draw the effect-applied waveform while the
 * user is still dialling the effects in.
 *
 * <p>The render is chunked and bounded so a long take does not block the
 * UI thread, and the result is bucketed with the same grid the waveform view
 * uses so the overlay lines up with the dry bars.
 */
final class FxPreviewPeaks {

    private FxPreviewPeaks() {}

    /**
     * Renders {@code chain} over {@code raw} and returns the peak envelope
     * in {@code buckets} buckets (the same count the waveform view holds).
     *
     * @return the peaks, or null when the render could not be completed
     */
    static float[] render(File raw, DspChain chain, int buckets,
                           int sampleRate) {
        if (raw == null || chain == null || buckets <= 0) return null;
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(raf);
            long onDisk = Math.max(0, raf.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            int ch = Math.max(1, h.channels);
            long totalFrames = dataBytes / 4 / ch;
            if (totalFrames <= 0) return null;

            int bCount = Math.min(buckets, (int) Math.max(1L,
                    Math.min(totalFrames, 8192L)));
            float[] peaks = new float[bCount];
            int framesPerRead = Math.max(1, 65536 / 4 / ch);
            byte[] in = new byte[framesPerRead * ch * 4];
            ByteBuffer fb = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            raf.seek(h.dataStart);
            chain.reset();
            long frame = 0;
            while (frame < totalFrames) {
                int want = (int) Math.min(framesPerRead, totalFrames - frame);
                raf.readFully(in, 0, want * ch * 4);
                fb.clear();
                fb.limit(want * ch);
                for (int f = 0; f < want; f++) {
                    float s = 0f;
                    for (int c = 0; c < ch; c++) {
                        s += fb.get(f * ch + c);
                    }
                    s /= ch;
                    int b = (int) ((frame + f) * bCount / totalFrames);
                    if (b >= bCount) b = bCount - 1;
                    if (b < 0) b = 0;
                    float a = Math.abs(s);
                    if (a > peaks[b]) peaks[b] = a;
                }
                float[] block = new float[want * ch];
                fb.clear();
                fb.limit(want * ch);
                for (int i = 0; i < want * ch; i++) {
                    block[i] = fb.get();
                }
                chain.processInPlace(block, want);
                for (int f = 0; f < want; f++) {
                    float peak = 0f;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(block[f * ch + c]);
                        if (a > peak) peak = a;
                    }
                    int b = (int) ((frame + f) * bCount / totalFrames);
                    if (b >= bCount) b = bCount - 1;
                    if (b < 0) b = 0;
                    if (peak > peaks[b]) peaks[b] = peak;
                }
                frame += want;
            }
            return peaks;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Renders only {@code [startFrac, endFrac)} of {@code raw} through
     * {@code chain} and returns peaks on the waveform's absolute bucket grid.
     *
     * <p>Rendering just the target region keeps the live preview responsive on
     * long takes. The chain is warmed with a short pre-roll and primed for its
     * algorithmic latency, exactly like {@code EditEngine.applyDspToRange}, so
     * the bars line up with the audio that Stop will commit.
     *
     * @return one peak per history bucket (zero outside the region), or null
     */
    static float[] renderRegion(File raw, DspChain chain, int buckets,
                                int sampleRate, float startFrac,
                                float endFrac) {
        if (raw == null || chain == null || buckets <= 0) return null;
        if (!(startFrac < endFrac)) return null;
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(raf);
            long onDisk = Math.max(0, raf.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            int ch = Math.max(1, h.channels);
            long totalFrames = dataBytes / 4 / ch;
            if (totalFrames <= 0) return null;
            long start = Math.max(0L, Math.min(totalFrames,
                    Math.round(startFrac * totalFrames)));
            long end = Math.max(start, Math.min(totalFrames,
                    Math.round(endFrac * totalFrames)));
            if (end <= start) return null;

            if (!chain.offlineProcessors().isEmpty()) {
                float[] rendered = chain.renderRegionOfflineAndInPlace(
                        raw, start, end, sampleRate);
                if (rendered == null || rendered.length == 0) return null;
                int rFrames = rendered.length / ch;
                float[] peaks = new float[buckets];
                int bStart = Math.max(0, Math.min(buckets - 1,
                        (int) (start * buckets / totalFrames)));
                int bEnd = Math.max(bStart + 1, Math.min(buckets,
                        (int) (end * buckets / totalFrames)));
                int bSpan = Math.max(1, bEnd - bStart);
                for (int f = 0; f < rFrames; f++) {
                    float peak = 0f;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(rendered[f * ch + c]);
                        if (a > peak) peak = a;
                    }
                    int b = bStart + (int) ((long) f * bSpan / Math.max(1, rFrames));
                    if (b < 0) b = 0;
                    if (b >= buckets) b = buckets - 1;
                    if (peak > peaks[b]) peaks[b] = peak;
                }
                return peaks;
            }

            chain.prepareOffline(raw, start, end, sampleRate, ch);
            chain.reset();

            float[] peaks = new float[buckets];
            int chunkFrames = 4096;
            byte[] rawBuf = new byte[chunkFrames * ch * 4];
            float[] fBuf = new float[chunkFrames * ch];
            ByteBuffer bb = ByteBuffer.wrap(rawBuf)
                    .order(ByteOrder.LITTLE_ENDIAN);

            // Warm the chain's filter/envelope state just before the region.
            int preRoll = (int) Math.min(Dsp.CROSSFADE_FRAMES, start);
            if (preRoll > 0) {
                raf.seek(h.dataStart + (start - preRoll) * ch * 4L);
                raf.readFully(rawBuf, 0, preRoll * ch * 4);
                bb.clear();
                bb.limit(preRoll * ch * 4);
                bb.asFloatBuffer().get(fBuf, 0, preRoll * ch);
                chain.processInPlace(fBuf, preRoll);
            }

            // Prime the latency line so output frame `cur` aligns with input
            // frame `cur`, matching the offline apply path.
            final int latencyFrames = Math.max(0, chain.getLatencyFrames());
            float[] postContext = null;
            int postContextFrames = 0;
            if (latencyFrames > 0) {
                postContextFrames = (int) Math.min(latencyFrames,
                        Math.max(0L, totalFrames - end));
                if (postContextFrames > 0) {
                    postContext = new float[postContextFrames * ch];
                    byte[] pcBuf = new byte[postContextFrames * ch * 4];
                    raf.seek(h.dataStart + end * ch * 4L);
                    raf.readFully(pcBuf);
                    ByteBuffer.wrap(pcBuf).order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer().get(postContext);
                }
                int remaining = latencyFrames;
                long pos = start;
                while (remaining > 0) {
                    int step = Math.min(chunkFrames, remaining);
                    int fromSel = (int) Math.max(0L,
                            Math.min((long) step, end - pos));
                    if (fromSel > 0) {
                        raf.seek(h.dataStart + pos * ch * 4L);
                        raf.readFully(rawBuf, 0, fromSel * ch * 4);
                        bb.clear();
                        bb.limit(fromSel * ch * 4);
                        bb.asFloatBuffer().get(fBuf, 0, fromSel * ch);
                    }
                    for (int f = fromSel; f < step; f++) {
                        long postIdx = (pos + f) - end;
                        for (int c = 0; c < ch; c++) {
                            fBuf[f * ch + c] = (postContext != null
                                    && postIdx >= 0
                                    && postIdx < postContextFrames)
                                    ? postContext[(int) postIdx * ch + c] : 0f;
                        }
                    }
                    chain.processInPlace(fBuf, step);
                    pos += step;
                    remaining -= step;
                }
            }

            long cur = start;
            while (cur < end) {
                int want = (int) Math.min(chunkFrames, end - cur);
                long inStart = cur + latencyFrames;
                int fromFile = (int) Math.max(0L,
                        Math.min((long) want, end - inStart));
                if (fromFile > 0) {
                    raf.seek(h.dataStart + inStart * ch * 4L);
                    raf.readFully(rawBuf, 0, fromFile * ch * 4);
                    bb.clear();
                    bb.limit(fromFile * ch * 4);
                    bb.asFloatBuffer().get(fBuf, 0, fromFile * ch);
                }
                if (fromFile < want) {
                    for (int f = fromFile; f < want; f++) {
                        long absFrame = inStart + f;
                        long postIdx = absFrame - end;
                        for (int c = 0; c < ch; c++) {
                            fBuf[f * ch + c] = (postContext != null
                                    && postIdx >= 0
                                    && postIdx < postContextFrames)
                                    ? postContext[(int) postIdx * ch + c] : 0f;
                        }
                    }
                }
                chain.processInPlace(fBuf, want);
                for (int f = 0; f < want; f++) {
                    float peak = 0f;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(fBuf[f * ch + c]);
                        if (a > peak) peak = a;
                    }
                    int b = (int) ((cur + f) * buckets / totalFrames);
                    if (b < 0) b = 0;
                    if (b >= buckets) b = buckets - 1;
                    if (peak > peaks[b]) peaks[b] = peak;
                }
                cur += want;
            }
            return peaks;
        } catch (Exception e) {
            return null;
        }
    }

}
