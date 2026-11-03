package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renders the live FX rack over a take and reports the resulting peak and
 * spectral-energy envelope, so the studio can draw the effect-applied waveform
 * while the user is still dialling the effects in.
 *
 * <p>The render is chunked and bounded across both in-place and offline
 * duration-changing effects so even multi-hour takes never allocate full-take
 * arrays on the Java heap or block the UI thread.
 */
final class FxPreviewPeaks {

    private FxPreviewPeaks() {}

    /**
     * Computes a perceptual composite envelope per bucket combining true peak,
     * RMS energy, and high-frequency spectral flux so that gain, dynamics,
     * EQ/filter, phase, and pitch parameters all produce smooth, proportional
     * waveform updates.
     */
    static float[] computePerceptualBuckets(float[] samples, int frames,
                                            int ch, int bucketCount) {
        if (samples == null || frames <= 0 || bucketCount <= 0) {
            return new float[Math.max(1, bucketCount)];
        }
        int cCount = Math.max(1, ch);
        float[] env = new float[bucketCount];
        for (int b = 0; b < bucketCount; b++) {
            int fStart = (int) ((long) b * frames / bucketCount);
            int fEnd = (int) ((long) (b + 1) * frames / bucketCount);
            if (fEnd <= fStart) fEnd = Math.min(frames, fStart + 1);
            float peak = 0f;
            double sumSq = 0.0;
            double sumDiff = 0.0;
            int count = 0;
            float prev = 0f;
            for (int f = fStart; f < fEnd; f++) {
                float frameSample = 0f;
                for (int c = 0; c < cCount; c++) {
                    int idx = f * cCount + c;
                    if (idx >= samples.length) break;
                    float v = samples[idx];
                    float a = Math.abs(v);
                    if (a > peak) peak = a;
                    frameSample += v;
                }
                frameSample /= cCount;
                sumSq += (double) frameSample * frameSample;
                if (count > 0) {
                    sumDiff += Math.abs(frameSample - prev);
                }
                prev = frameSample;
                count++;
            }
            if (count > 0) {
                float rms = (float) Math.sqrt(sumSq / count);
                float flux = (float) (sumDiff / count);
                env[b] = 0.45f * peak + 0.35f * (rms * 1.414f) + 0.20f * (flux * 1.85f);
            } else {
                env[b] = peak;
            }
        }
        return env;
    }

    /**
     * Streams {@code [startFrame, startFrame + frames)} from {@code raf} in
     * 4096-frame chunks to compute both true bucket peaks (into {@code outPeaks}
     * at {@code peakOffset}) and the perceptual composite envelope without
     * loading the region into RAM.
     */
    private static float[] computePerceptualBucketsStreaming(
            RandomAccessFile raf, long dataStart, long startFrame, long frames,
            int ch, int bucketCount, float[] outPeaks, int peakOffset)
            throws Exception {
        int bCount = Math.max(1, bucketCount);
        float[] env = new float[bCount];
        if (raf == null || frames <= 0) return env;
        int cCount = Math.max(1, ch);
        float[] bPeak = new float[bCount];
        double[] bSumSq = new double[bCount];
        double[] bSumDiff = new double[bCount];
        int[] bCountArr = new int[bCount];

        int chunkFrames = AudioProcessor.DEFAULT_DSP_CHUNK_FRAMES;
        byte[] rawBuf = new byte[chunkFrames * cCount * 4];
        float[] fBuf = new float[chunkFrames * cCount];
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);
        long frameBytes = cCount * 4L;

        float prev = 0f;
        int prevBucket = -1;
        long cur = 0L;
        while (cur < frames) {
            int want = (int) Math.min((long) chunkFrames, frames - cur);
            raf.seek(dataStart + (startFrame + cur) * frameBytes);
            raf.readFully(rawBuf, 0, want * cCount * 4);
            bb.clear();
            bb.limit(want * cCount * 4);
            bb.asFloatBuffer().get(fBuf, 0, want * cCount);

            for (int f = 0; f < want; f++) {
                long globalF = cur + f;
                int b = (int) (globalF * bCount / frames);
                if (b < 0) b = 0;
                if (b >= bCount) b = bCount - 1;
                float frameMono = 0f;
                float framePeak = 0f;
                int base = f * cCount;
                for (int c = 0; c < cCount; c++) {
                    float v = fBuf[base + c];
                    float a = Math.abs(v);
                    if (a > framePeak) framePeak = a;
                    frameMono += v;
                }
                frameMono /= cCount;
                if (framePeak > bPeak[b]) bPeak[b] = framePeak;
                if (outPeaks != null) {
                    int pkIdx = peakOffset + b;
                    if (pkIdx >= 0 && pkIdx < outPeaks.length
                            && framePeak > outPeaks[pkIdx]) {
                        outPeaks[pkIdx] = framePeak;
                    }
                }
                bSumSq[b] += (double) frameMono * frameMono;
                if (b == prevBucket && bCountArr[b] > 0) {
                    bSumDiff[b] += Math.abs(frameMono - prev);
                }
                prev = frameMono;
                prevBucket = b;
                bCountArr[b]++;
            }
            cur += want;
        }
        for (int b = 0; b < bCount; b++) {
            int cnt = bCountArr[b];
            if (cnt > 0) {
                float rms = (float) Math.sqrt(bSumSq[b] / cnt);
                float flux = (float) (bSumDiff[b] / cnt);
                env[b] = 0.45f * bPeak[b] + 0.35f * (rms * 1.414f)
                        + 0.20f * (flux * 1.85f);
            } else {
                env[b] = bPeak[b];
            }
        }
        return env;
    }

    /**
     * Renders {@code chain} over {@code raw} and returns the peak envelope
     * in {@code buckets} buckets (the same count the waveform view holds).
     *
     * @return the peaks, or null when the render could not be completed
     */
    static float[] render(File raw, DspChain chain, int buckets,
                           int sampleRate) {
        if (raw == null || chain == null || buckets <= 0) return null;
        int bCount = Math.min(buckets, 8192);
        return renderRegion(raw, chain, bCount, sampleRate, 0f, 1f);
    }

    /**
     * Renders only {@code [startFrac, endFrac)} of {@code raw} through
     * {@code chain} and returns peaks on the waveform's absolute bucket grid.
     */
    static float[] renderRegion(File raw, DspChain chain, int buckets,
                                int sampleRate, float startFrac,
                                float endFrac) {
        LiveRegionResult res = renderRegionInternal(raw, chain, buckets,
                sampleRate, startFrac, endFrac, null, false);
        return res != null ? res.peaks : null;
    }

    /**
     * Result of a real-time FX region render.
     */
    static final class LiveRegionResult {
        final float[] peaks;
        final float[] gainRatios;
        final boolean lengthChanged;
        final long newTotalFrames;
        final long startFrame;
        final long newRegionEndFrame;
        final File renderedWav;

        LiveRegionResult(float[] peaks, boolean lengthChanged,
                         long newTotalFrames, long startFrame,
                         long newRegionEndFrame, File renderedWav) {
            this(peaks, null, lengthChanged, newTotalFrames, startFrame,
                    newRegionEndFrame, renderedWav);
        }

        LiveRegionResult(float[] peaks, float[] gainRatios,
                         boolean lengthChanged, long newTotalFrames,
                         long startFrame, long newRegionEndFrame,
                         File renderedWav) {
            this.peaks = peaks;
            this.gainRatios = gainRatios;
            this.lengthChanged = lengthChanged;
            this.newTotalFrames = newTotalFrames;
            this.startFrame = startFrame;
            this.newRegionEndFrame = newRegionEndFrame;
            this.renderedWav = renderedWav;
        }
    }

    /**
     * Renders {@code [startFrac, endFrac)} of {@code raw} in real time for the
     * Studio FX timeline.
     */
    static LiveRegionResult renderRegionLive(File raw, DspChain chain,
                                             int baseBuckets, int sampleRate,
                                             float startFrac, float endFrac,
                                             File previewOutFile) {
        return renderRegionInternal(raw, chain, baseBuckets, sampleRate,
                startFrac, endFrac, previewOutFile, true);
    }

    private static LiveRegionResult renderRegionInternal(
            File raw, DspChain chain, int buckets, int sampleRate,
            float startFrac, float endFrac, File previewOutFile,
            boolean isLiveTimeline) {
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

            int bStart = Math.max(0, Math.min(buckets - 1,
                    Math.round(startFrac * buckets)));
            int bEnd = Math.max(bStart + 1, Math.min(buckets,
                    Math.round(endFrac * buckets)));
            int origRegionBuckets = Math.max(1, bEnd - bStart);

            int effSampleRate = h.sampleRate > 0 ? h.sampleRate : sampleRate;
            chain.setSampleRate(effSampleRate);
            if (!chain.offlineProcessors().isEmpty()) {
                long dryFrames = end - start;
                boolean hasGenerator = false;
                boolean hasReverse = false;
                for (AudioProcessor p : chain.offlineProcessors()) {
                    if (p instanceof AudioGeneratorTool) hasGenerator = true;
                    if (p instanceof TimelineUtilityProcessor) {
                        FxParameter act = p.getParam("tl_action");
                        if (act != null && act.getEnumIndex() == 1) hasReverse = true;
                    }
                }

                // For large regions (> 32,768 frames), render offline effects
                // file-to-file in bounded chunks so we never OOM on long takes.
                if (dryFrames > AudioProcessor.MAX_IN_MEMORY_OFFLINE_FRAMES) {
                    float[] dryEnv = computePerceptualBucketsStreaming(
                            raf, h.dataStart, start, dryFrames, ch,
                            origRegionBuckets, null, 0);
                    File regionTmp = new File(raw.getParentFile(),
                            raw.getName() + ".fxprev_reg_tmp");
                    try {
                        long rFrames = chain.renderRegionToFile(
                                raw, start, end, effSampleRate, regionTmp);
                        if (rFrames <= 0 || !regionTmp.exists()) return null;
                        long tailFrames = Math.max(0L, totalFrames - end);
                        long newTotalFrames = start + rFrames + tailFrames;

                        try (RandomAccessFile rRaf = new RandomAccessFile(regionTmp, "r")) {
                            Dsp.WavHeader rh = Dsp.parseWavHeader(rRaf);
                            if (!isLiveTimeline) {
                                float[] peaks = new float[buckets];
                                float[] wetEnv = computePerceptualBucketsStreaming(
                                        rRaf, rh.dataStart, 0L, rFrames, ch,
                                        origRegionBuckets, peaks, bStart);
                                if (!hasGenerator && !hasReverse) {
                                    for (int i = 0; i < origRegionBuckets; i++) {
                                        int b = bStart + i;
                                        if (b < buckets && dryEnv[i] > 1e-4f) {
                                            float ratio = wetEnv[i] / dryEnv[i];
                                            peaks[b] = 0.5f * peaks[b] + 0.5f * (peaks[b] * ratio);
                                        }
                                    }
                                }
                                return new LiveRegionResult(peaks, null, false,
                                        newTotalFrames, start, start + rFrames, null);
                            }

                            int newRegionBuckets = (rFrames == dryFrames)
                                    ? origRegionBuckets
                                    : Math.max(1, Math.min(8192,
                                            Math.round((float) rFrames * buckets
                                                    / Math.max(1L, totalFrames))));
                            float[] regionPeaks = new float[newRegionBuckets];
                            float[] wetEnv = computePerceptualBucketsStreaming(
                                    rRaf, rh.dataStart, 0L, rFrames, ch,
                                    newRegionBuckets, regionPeaks, 0);
                            float[] gainRatios = computeOfflineGainRatios(
                                    dryEnv, wetEnv, regionPeaks, origRegionBuckets,
                                    newRegionBuckets, rFrames == dryFrames,
                                    hasGenerator, hasReverse);
                            File outWav = null;
                            if (previewOutFile != null) {
                                writeSplicedPreviewWavFromFile(
                                        raf, h, start, end, totalFrames,
                                        rRaf, rh.dataStart, rFrames, previewOutFile);
                                outWav = previewOutFile;
                            }
                            return new LiveRegionResult(regionPeaks, gainRatios,
                                    rFrames != dryFrames, newTotalFrames,
                                    start, start + rFrames, outWav);
                        }
                    } finally {
                        if (regionTmp.exists()) {
                            // noinspection ResultOfMethodCallIgnored
                            regionTmp.delete();
                        }
                    }
                }

                float[] drySel = EditEngine.readFloatSelection(raw, start, end);
                int dryF = drySel != null ? drySel.length / ch : 0;
                float[] dryEnv = computePerceptualBuckets(
                        drySel, dryF, ch, origRegionBuckets);

                float[] rendered = chain.renderRegionOfflineAndInPlace(
                        raw, start, end, effSampleRate);
                if (rendered == null || rendered.length == 0) return null;
                int rFrames = rendered.length / ch;
                if (rFrames <= 0) return null;
                long tailFrames = Math.max(0L, totalFrames - end);
                long newTotalFrames = start + rFrames + tailFrames;

                if (!isLiveTimeline) {
                    float[] peaks = new float[buckets];
                    float[] wetEnv = computePerceptualBuckets(
                            rendered, rFrames, ch, origRegionBuckets);
                    for (int f = 0; f < rFrames; f++) {
                        float peak = 0f;
                        for (int c = 0; c < ch; c++) {
                            float a = Math.abs(rendered[f * ch + c]);
                            if (a > peak) peak = a;
                        }
                        int b = bStart + (int) ((long) f * origRegionBuckets
                                / Math.max(1, rFrames));
                        if (b < 0) b = 0;
                        if (b >= buckets) b = buckets - 1;
                        if (peak > peaks[b]) peaks[b] = peak;
                    }
                    if (!hasGenerator && !hasReverse) {
                        for (int i = 0; i < origRegionBuckets; i++) {
                            int b = bStart + i;
                            if (b < buckets && dryEnv[i] > 1e-4f) {
                                float ratio = wetEnv[i] / dryEnv[i];
                                peaks[b] = 0.5f * peaks[b] + 0.5f * (peaks[b] * ratio);
                            }
                        }
                    }
                    return new LiveRegionResult(peaks, null, false,
                            newTotalFrames, start, start + rFrames, null);
                }

                int newRegionBuckets = (rFrames == dryF)
                        ? origRegionBuckets
                        : Math.max(1, Math.min(8192,
                                Math.round((float) rFrames * buckets
                                        / Math.max(1L, totalFrames))));
                float[] regionPeaks = new float[newRegionBuckets];
                for (int f = 0; f < rFrames; f++) {
                    float peak = 0f;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(rendered[f * ch + c]);
                        if (a > peak) peak = a;
                    }
                    int b = (int) ((long) f * newRegionBuckets
                            / Math.max(1, rFrames));
                    if (b < 0) b = 0;
                    if (b >= newRegionBuckets) b = newRegionBuckets - 1;
                    if (peak > regionPeaks[b]) regionPeaks[b] = peak;
                }

                float[] wetEnv = computePerceptualBuckets(
                        rendered, rFrames, ch, newRegionBuckets);
                float[] gainRatios = computeOfflineGainRatios(
                        dryEnv, wetEnv, regionPeaks, origRegionBuckets,
                        newRegionBuckets, rFrames == dryF,
                        hasGenerator, hasReverse);

                File outWav = null;
                if (previewOutFile != null) {
                    writeSplicedPreviewWav(raf, h, start, end, totalFrames,
                            rendered, rFrames, previewOutFile);
                    outWav = previewOutFile;
                }
                return new LiveRegionResult(regionPeaks, gainRatios,
                        rFrames != dryF,
                        newTotalFrames, start, start + rFrames, outWav);
            }

            chain.reset();
            chain.prepareOffline(raw, start, end, effSampleRate, ch);

            float[] dryPeaks = new float[buckets];
            float[] wetPeaks = new float[buckets];
            double[] drySumSq = new double[buckets];
            double[] wetSumSq = new double[buckets];
            double[] drySumFlux = new double[buckets];
            double[] wetSumFlux = new double[buckets];
            int[] bucketFrames = new int[buckets];

            int chunkFrames = AudioProcessor.DEFAULT_DSP_CHUNK_FRAMES;
            byte[] rawBuf = new byte[chunkFrames * ch * 4];
            float[] fBuf = new float[chunkFrames * ch];
            float[] dryBuf = new float[chunkFrames * ch];
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

            float prevDry = 0f;
            float prevWet = 0f;
            long cur = start;
            while (cur < end) {
                int want = (int) Math.min(chunkFrames, end - cur);
                // Read aligned dry frames for [cur, cur + want)
                raf.seek(h.dataStart + cur * ch * 4L);
                raf.readFully(rawBuf, 0, want * ch * 4);
                bb.clear();
                bb.limit(want * ch * 4);
                bb.asFloatBuffer().get(dryBuf, 0, want * ch);

                // Read latency-offset frames for wet processing
                if (latencyFrames == 0) {
                    System.arraycopy(dryBuf, 0, fBuf, 0, want * ch);
                } else {
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
                }
                chain.processInPlace(fBuf, want);
                for (int f = 0; f < want; f++) {
                    float dPeak = 0f;
                    float wPeak = 0f;
                    float dMono = 0f;
                    float wMono = 0f;
                    for (int c = 0; c < ch; c++) {
                        float dv = dryBuf[f * ch + c];
                        float wv = fBuf[f * ch + c];
                        float da = Math.abs(dv);
                        float wa = Math.abs(wv);
                        if (da > dPeak) dPeak = da;
                        if (wa > wPeak) wPeak = wa;
                        dMono += dv;
                        wMono += wv;
                    }
                    dMono /= ch;
                    wMono /= ch;
                    int b = (int) ((cur + f) * buckets / totalFrames);
                    if (b < 0) b = 0;
                    if (b >= buckets) b = buckets - 1;
                    if (dPeak > dryPeaks[b]) dryPeaks[b] = dPeak;
                    if (wPeak > wetPeaks[b]) wetPeaks[b] = wPeak;
                    drySumSq[b] += (double) dMono * dMono;
                    wetSumSq[b] += (double) wMono * wMono;
                    drySumFlux[b] += Math.abs(dMono - prevDry);
                    wetSumFlux[b] += Math.abs(wMono - prevWet);
                    prevDry = dMono;
                    prevWet = wMono;
                    bucketFrames[b]++;
                }
                cur += want;
            }

            float[] outPeaks = new float[buckets];
            float[] gainRatios = new float[buckets];
            java.util.Arrays.fill(gainRatios, -1f);
            for (int b = 0; b < buckets; b++) {
                int cnt = bucketFrames[b];
                if (cnt <= 0) continue;
                float dRms = (float) Math.sqrt(drySumSq[b] / cnt);
                float wRms = (float) Math.sqrt(wetSumSq[b] / cnt);
                float dFlux = (float) (drySumFlux[b] / cnt);
                float wFlux = (float) (wetSumFlux[b] / cnt);
                float dEnv = 0.45f * dryPeaks[b] + 0.35f * (dRms * 1.414f)
                        + 0.20f * (dFlux * 1.85f);
                float wEnv = 0.45f * wetPeaks[b] + 0.35f * (wRms * 1.414f)
                        + 0.20f * (wFlux * 1.85f);
                if (dEnv > 1e-4f && dryPeaks[b] > 1e-4f) {
                    float envRatio = wEnv / dEnv;
                    float peakRatio = wetPeaks[b] / dryPeaks[b];
                    float combinedRatio = (Math.abs(peakRatio - 1f) > 0.04f)
                            ? (0.65f * peakRatio + 0.35f * envRatio)
                            : envRatio;
                    gainRatios[b] = Math.max(0f, combinedRatio);
                    outPeaks[b] = dryPeaks[b] * gainRatios[b];
                } else {
                    outPeaks[b] = wetPeaks[b];
                    gainRatios[b] = -1f;
                }
            }

            return new LiveRegionResult(outPeaks, gainRatios, false,
                    totalFrames, start, end, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static float[] computeOfflineGainRatios(
            float[] dryEnv, float[] wetEnv, float[] regionPeaks,
            int origRegionBuckets, int newRegionBuckets,
            boolean sameLength, boolean hasGenerator, boolean hasReverse) {
        if (hasGenerator) return null;
        float[] gainRatios = new float[newRegionBuckets];
        float meanDry = 0f;
        for (float d : dryEnv) meanDry += d;
        meanDry /= Math.max(1, dryEnv.length);
        for (int i = 0; i < newRegionBuckets; i++) {
            int dIdx = Math.min(origRegionBuckets - 1,
                    (int) ((long) i * origRegionBuckets
                            / Math.max(1, newRegionBuckets)));
            if (hasReverse) {
                int revIdx = origRegionBuckets - 1 - dIdx;
                float ref = dryEnv[revIdx] > 1e-4f ? dryEnv[revIdx] : meanDry;
                gainRatios[i] = ref > 1e-4f ? (wetEnv[i] / ref) : -1f;
            } else if (sameLength && dryEnv[dIdx] > 1e-4f) {
                gainRatios[i] = wetEnv[i] / dryEnv[dIdx];
                regionPeaks[i] = 0.5f * regionPeaks[i]
                        + 0.5f * (regionPeaks[i] * gainRatios[i]);
            } else if (meanDry > 1e-4f) {
                gainRatios[i] = wetEnv[i] / meanDry;
            } else {
                gainRatios[i] = -1f;
            }
        }
        return gainRatios;
    }

    private static void writeSplicedPreviewWav(RandomAccessFile rawRaf,
                                               Dsp.WavHeader h,
                                               long startFrame,
                                               long endFrame,
                                               long totalFrames,
                                               float[] rendered,
                                               int rFrames,
                                               File outFile) throws Exception {
        int ch = Math.max(1, h.channels);
        long frameBytes = ch * 4L;
        long prefixFrames = Math.max(0L, startFrame);
        long suffixFrames = Math.max(0L, totalFrames - endFrame);
        long newTotalFrames = prefixFrames + rFrames + suffixFrames;
        long payloadBytes = newTotalFrames * frameBytes;

        try (RandomAccessFile out = new RandomAccessFile(outFile, "rw")) {
            out.setLength(0);
            out.write(Dsp.wavFloatHeader(ch, h.sampleRate, payloadBytes));
            byte[] buf = new byte[65536];

            long prefixBytes = prefixFrames * frameBytes;
            if (prefixBytes > 0) {
                rawRaf.seek(h.dataStart);
                long left = prefixBytes;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = rawRaf.read(buf, 0, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }

            Dsp.writeFloatsLE(out, rendered, 0, rFrames * ch);

            long suffixBytes = suffixFrames * frameBytes;
            if (suffixBytes > 0) {
                rawRaf.seek(h.dataStart + endFrame * frameBytes);
                long left = suffixBytes;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = rawRaf.read(buf, 0, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        }
    }

    private static void writeSplicedPreviewWavFromFile(
            RandomAccessFile rawRaf, Dsp.WavHeader h, long startFrame,
            long endFrame, long totalFrames, RandomAccessFile regRaf,
            long regDataStart, long rFrames, File outFile) throws Exception {
        int ch = Math.max(1, h.channels);
        long frameBytes = ch * 4L;
        long prefixFrames = Math.max(0L, startFrame);
        long suffixFrames = Math.max(0L, totalFrames - endFrame);
        long newTotalFrames = prefixFrames + rFrames + suffixFrames;
        long payloadBytes = newTotalFrames * frameBytes;

        try (RandomAccessFile out = new RandomAccessFile(outFile, "rw")) {
            out.setLength(0);
            out.write(Dsp.wavFloatHeader(ch, h.sampleRate, payloadBytes));
            byte[] buf = new byte[65536];

            long prefixBytes = prefixFrames * frameBytes;
            if (prefixBytes > 0) {
                rawRaf.seek(h.dataStart);
                long left = prefixBytes;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = rawRaf.read(buf, 0, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }

            long regBytes = rFrames * frameBytes;
            if (regBytes > 0) {
                regRaf.seek(regDataStart);
                long left = regBytes;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = regRaf.read(buf, 0, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }

            long suffixBytes = suffixFrames * frameBytes;
            if (suffixBytes > 0) {
                rawRaf.seek(h.dataStart + endFrame * frameBytes);
                long left = suffixBytes;
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    int n = rawRaf.read(buf, 0, want);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
            }
        }
    }
}
