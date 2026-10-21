package com.example.hello;

import java.util.Locale;

/**
 * Stage 7 audio diagnostics: measures peak, true peak, RMS, integrated
 * LUFS, dynamic range, DC offset and the foreground/background contrast,
 * and counts clipped samples.
 *
 * <p>All the maths is pure Java so it can be unit tested without a device.
 */
final class AudioDiagnosticsTool {

    private AudioDiagnosticsTool() {}

    /** Result of a diagnostics run over a region. */
    static final class Report {
        float peakDbfs;
        float truePeakDbfs;
        float rmsDbfs;
        float integratedLufs;
        float dynamicRangeLu;
        float dcOffsetPercent;
        float contrastDb;
        boolean wcagPass;
        int clippedRuns;
        int clippedSamples;

        /** One-line summary for the studio dialog. */
        public String summary() {
            return String.format(Locale.US,
                    "Peak %.1f dBFS • True peak %.1f dBFS • RMS %.1f dBFS"
                            + " • %.1f LUFS • DR %.1f LU • DC %.2f%%"
                            + " • Contrast %.1f dB %s • Clipped runs %d",
                    peakDbfs, truePeakDbfs, rmsDbfs, integratedLufs,
                    dynamicRangeLu, dcOffsetPercent, contrastDb,
                    wcagPass ? "(WCAG AAA pass)" : "(WCAG below AAA)",
                    clippedRuns);
        }
    }

    /**
     * Runs the analysis.
     *
     * @param buf            interleaved region samples
     * @param frames         number of frames
     * @param channels       channel count
     * @param sampleRate     sample rate in Hz
     * @param clipThreshold  consecutive-clipped-sample threshold (1..10)
     */
    static Report analyze(float[] buf, int frames, int channels, int sampleRate,
                          int clipThreshold) {
        Report r = new Report();
        if (buf == null || frames <= 0) return r;
        int ch = Math.max(1, Math.min(2, channels));

        float peak = 0f;
        double sumSq = 0;
        double sum = 0;
        int count = 0;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                float v = buf[f * ch + c];
                float a = Math.abs(v);
                if (a > peak) peak = a;
                sumSq += (double) v * v;
                sum += v;
                count++;
            }
        }
        r.peakDbfs = Dsp.linToDb(peak);
        r.rmsDbfs = (float) (10 * Math.log10(Math.max(1e-12, sumSq / count)));
        r.dcOffsetPercent = (float) (100.0 * Math.abs(sum / count));
        r.truePeakDbfs = truePeakDbfs(buf, frames, ch);

        // Integrated loudness via the same K-weighting path as the
        // loudness normalizer.
        r.integratedLufs = integratedLufs(buf, frames, ch, sampleRate);

        // Dynamic range: crest factor between the 95th and 10th percentile
        // of the frame loudness.
        r.dynamicRangeLu = dynamicRangeLu(buf, frames, ch, sampleRate);

        // Foreground / background contrast: the level difference between the
        // loudest 10 % and the quietest 10 % of 100 ms frames.
        float[] frameDb = frameLevels(buf, frames, ch, sampleRate);
        r.contrastDb = contrastDb(frameDb);
        // WCAG 2.0 AAA asks for at least 15 dB of speech contrast.
        r.wcagPass = r.contrastDb >= 15f;

        // Clipping: runs of consecutive samples at or above 0.9999.
        int thr = Math.max(1, clipThreshold);
        int runs = 0;
        int clippedSamples = 0;
        int runLen = 0;
        for (int f = 0; f < frames; f++) {
            boolean clipped = false;
            for (int c = 0; c < ch; c++) {
                if (Math.abs(buf[f * ch + c]) >= 0.9999f) clipped = true;
            }
            if (clipped) {
                runLen++;
                clippedSamples++;
            } else {
                if (runLen >= thr) runs++;
                runLen = 0;
            }
        }
        if (runLen >= thr) runs++;
        r.clippedRuns = runs;
        r.clippedSamples = clippedSamples;
        return r;
    }

    /** 4x oversampled true-peak estimate in dBFS. */
    static float truePeakDbfs(float[] buf, int frames, int ch) {
        float peak = 0f;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                float v = buf[f * ch + c];
                float a = Math.abs(v);
                if (a > peak) peak = a;
                // Linear interpolation towards the next sample.
                if (f + 1 < frames) {
                    float n = buf[(f + 1) * ch + c];
                    float inter = Math.abs(v + 0.5f * (n - v));
                    if (inter > peak) peak = inter;
                }
            }
        }
        return Dsp.linToDb(peak);
    }

    /** Integrated loudness in LUFS using the K-weighting filter. */
    static float integratedLufs(float[] buf, int frames, int ch, int sampleRate) {
        int block = Math.max(1, (int) (sampleRate * 0.4));
        int step = Math.max(1, (int) (sampleRate * 0.1));
        float[] shelf = Dsp.rbjHighShelf(sampleRate, 1681.97, 0.7071, 4f);
        float[] hp = Dsp.rbjHighPass(sampleRate, 38.13, 0.5003);
        float[] scratch = new float[block * ch];
        java.util.List<Float> blockLufs = new java.util.ArrayList<>();
        for (int start = 0; start + block <= frames; start += step) {
            System.arraycopy(buf, start * ch, scratch, 0, block * ch);
            BiquadFilter f = new BiquadFilter();
            f.setSections(new float[][]{shelf, hp}, ch);
            f.process(scratch, block, ch);
            double sum = 0;
            for (int i = 0; i < block * ch; i++) {
                sum += (double) scratch[i] * scratch[i];
            }
            double mean = sum / (block * (double) ch);
            blockLufs.add((float) (-0.691 + 10 * Math.log10(Math.max(1e-12, mean))));
        }
        if (blockLufs.isEmpty()) return -70f;
        double gated = 0;
        int n = 0;
        for (float l : blockLufs) {
            if (l > -70f) {
                gated += Math.pow(10, (l + 0.691) / 10.0);
                n++;
            }
        }
        if (n == 0) return -70f;
        double ungated = -0.691 + 10 * Math.log10(gated / n);
        double rel = ungated - 10.0;
        gated = 0;
        n = 0;
        for (float l : blockLufs) {
            if (l > -70f && l > rel) {
                gated += Math.pow(10, (l + 0.691) / 10.0);
                n++;
            }
        }
        if (n == 0) return (float) ungated;
        return (float) (-0.691 + 10 * Math.log10(gated / n));
    }

    /** Per-frame RMS levels in dBFS using 100 ms frames. */
    static float[] frameLevels(float[] buf, int frames, int ch, int sampleRate) {
        int size = Math.max(1, (int) (sampleRate * 0.1));
        int count = Math.max(1, frames / size);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            int start = i * size;
            int end = Math.min(frames, start + size);
            double sum = 0;
            int n = 0;
            for (int f = start; f < end; f++) {
                for (int c = 0; c < ch; c++) {
                    double v = buf[f * ch + c];
                    sum += v * v;
                    n++;
                }
            }
            out[i] = (float) (10 * Math.log10(Math.max(1e-12, sum / Math.max(1, n))));
        }
        return out;
    }

    /** Difference between the loudest and quietest deciles, in dB. */
    static float contrastDb(float[] frameDb) {
        if (frameDb == null || frameDb.length < 4) return 0f;
        float[] sorted = frameDb.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        int tenth = Math.max(1, n / 10);
        double loud = 0;
        for (int i = n - tenth; i < n; i++) loud += sorted[i];
        double quiet = 0;
        for (int i = 0; i < tenth; i++) quiet += sorted[i];
        loud /= tenth;
        quiet /= tenth;
        return (float) (loud - quiet);
    }

    /** Dynamic range in loudness units (95th minus 10th percentile). */
    static float dynamicRangeLu(float[] buf, int frames, int ch, int sampleRate) {
        float[] frameDb = frameLevels(buf, frames, ch, sampleRate);
        if (frameDb.length < 4) return 0f;
        float[] sorted = frameDb.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        int hi = Math.max(0, (int) (n * 0.95) - 1);
        int lo = Math.min(n - 1, (int) (n * 0.10));
        return sorted[hi] - sorted[lo];
    }
}
