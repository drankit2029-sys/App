package com.takeone.studio;

import java.util.Arrays;

/**
 * Reusable Short-Time Fourier Transform overlap-add framework.
 *
 * <p>The zero-latency {@link #process} path uses bounded neighbor-context
 * windows so large in-memory buffers never show slice-boundary glitches. The
 * causal {@link #processStreaming} path keeps a bounded ring buffer for
 * real-time and chunked offline rendering, reporting one FFT frame of latency
 * exactly like NoiseSuppressionProcessor.
 */
abstract class StftProcessor {

    /** FFT size (power of two). */
    protected int fftSize = 1024;
    /** Hop size between analysis frames. */
    protected int hopSize = 256;
    /** Overlap factor: 4 means 75 % overlap (hop = fftSize / 4). */
    protected int overlap = 4;

    private float[] window;
    private float olaScale;
    private float[] re;
    private float[] im;

    // Streaming overlap-add state (bounded O(fftSize) memory).
    private float[][] inHist;
    private float[][] ola;
    private float[][] outQ = {new float[0], new float[0]};
    private int outCount = 0;
    private int histFill = 0;
    private long inputConsumed = 0L;
    private long genuinePopped = 0L;

    protected StftProcessor(int fftSize, int overlap) {
        setGeometry(fftSize, overlap);
        allocate();
    }

    private void setGeometry(int size, int ov) {
        this.fftSize = Math.max(64, Integer.highestOneBit(Math.max(64, size)));
        this.overlap = Math.max(1, Math.min(8, ov));
        this.hopSize = this.fftSize / this.overlap;
        // Hann^2 COLA normalization; 2/3 for the normal 75 % overlap.
        this.olaScale = 8.0f / (3.0f * this.overlap);
    }

    private void allocate() {
        window = Dsp.hannWindow(this.fftSize);
        re = new float[fftSize];
        im = new float[fftSize];
        inHist = new float[][]{new float[fftSize], new float[fftSize]};
        ola = new float[][]{new float[fftSize], new float[fftSize]};
        reset();
    }

    /** Rebuilds the FFT geometry (call when the size or overlap changes). */
    protected void reconfigure(int size, int ov) {
        setGeometry(size, ov);
        allocate();
    }

    /** Clears the streaming history and pending output. */
    public void reset() {
        for (int c = 0; c < 2; c++) {
            Arrays.fill(inHist[c], 0f);
            Arrays.fill(ola[c], 0f);
        }
        outCount = 0;
        histFill = 0;
        inputConsumed = 0L;
        genuinePopped = 0L;
    }

    /** One FFT frame of streaming latency. */
    public int getLatencyFrames() {
        return fftSize;
    }

    /** Number of usable spectrum bins (fftSize / 2 + 1). */
    protected int bins() {
        return fftSize / 2 + 1;
    }

    /**
     * Modifies one frame's spectrum.
     *
     * @param re       real part, length fftSize
     * @param im       imaginary part, length fftSize
     * @param binCount number of positive-frequency bins
     * @param channel  channel index (0 or 1)
     */
    protected abstract void processSpectrum(float[] re, float[] im,
                                            int binCount, int channel);

    /**
     * Zero-latency overlap-add over an in-memory buffer. Large buffers are
     * processed in bounded windows, but each window copies real left/right
     * neighbor context instead of zero-padding at the slice boundary.
     */
    public void process(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int pad = fftSize;
        int maxChunk = Math.max(fftSize * 4, AudioProcessor.DEFAULT_DSP_CHUNK_FRAMES);
        maxChunk = (maxChunk / hopSize) * hopSize;
        if (frames <= maxChunk) {
            processWindowedSlice(buf, 0, frames, frames, ch,
                    new float[(frames + 2 * pad) * ch],
                    new float[(frames + 2 * pad) * ch],
                    new float[(frames + 2 * pad) * ch],
                    buf);
            return;
        }
        float[] result = new float[frames * ch];
        int maxWin = maxChunk + 2 * pad;
        float[] inBuf = new float[maxWin * ch];
        float[] acc = new float[maxWin * ch];
        float[] norm = new float[maxWin * ch];
        for (int off = 0; off < frames; off += maxChunk) {
            int want = Math.min(maxChunk, frames - off);
            processWindowedSlice(buf, off, want, frames, ch,
                    inBuf, acc, norm, result);
        }
        System.arraycopy(result, 0, buf, 0, frames * ch);
    }

    private void processWindowedSlice(float[] src, int off, int want, int totalFrames,
                                      int ch, float[] inBuf, float[] acc, float[] norm,
                                      float[] dst) {
        int pad = fftSize;
        int totalWin = want + 2 * pad;
        Arrays.fill(inBuf, 0, totalWin * ch, 0f);
        Arrays.fill(acc, 0, totalWin * ch, 0f);
        Arrays.fill(norm, 0, totalWin * ch, 0f);

        int srcStart = Math.max(0, off - pad);
        int srcEnd = Math.min(totalFrames, off + want + pad);
        int dstStart = srcStart - (off - pad);
        int copyFrames = srcEnd - srcStart;
        System.arraycopy(src, srcStart * ch, inBuf, dstStart * ch, copyFrames * ch);

        for (int channel = 0; channel < ch; channel++) {
            int frameStart = 0;
            while (frameStart + fftSize <= totalWin) {
                for (int i = 0; i < fftSize; i++) {
                    re[i] = inBuf[(frameStart + i) * ch + channel] * window[i];
                    im[i] = 0f;
                }
                Dsp.fft(re, im, false);
                processSpectrum(re, im, bins(), channel);
                Dsp.fft(re, im, true);
                for (int i = 0; i < fftSize; i++) {
                    float s = re[i] * window[i];
                    int pos = frameStart + i;
                    acc[pos * ch + channel] += s;
                    norm[pos * ch + channel] += window[i] * window[i];
                }
                frameStart += hopSize;
            }
        }
        for (int f = 0; f < want; f++) {
            int pos = pad + f;
            for (int c = 0; c < ch; c++) {
                float n = norm[pos * ch + c];
                float v = acc[pos * ch + c];
                dst[(off + f) * ch + c] = n > 1e-6f ? v / n : v;
            }
        }
    }

    /**
     * Causal streaming STFT with {@code fftSize} latency. Callers prime with
     * the first latency frames and read {@code fftSize} ahead, matching
     * EditEngine/RawFloatPlayer/FxPreviewPeaks chunked streaming.
     */
    public void processStreaming(float[] buf, int frames, int channels, float mix) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float w = Math.max(0f, Math.min(1f, mix));
        ensureOutCapacity(outCount + frames + 2 * hopSize);
        int off = 0;
        while (off < frames) {
            int take = Math.min(fftSize - histFill, frames - off);
            if (take > 0) {
                for (int f = 0; f < take; f++) {
                    for (int c = 0; c < ch; c++) {
                        inHist[c][histFill + f] = buf[(off + f) * ch + c];
                    }
                }
                histFill += take;
                off += take;
                inputConsumed += take;
            }
            if (histFill < fftSize) continue;

            for (int c = 0; c < ch; c++) {
                for (int i = 0; i < fftSize; i++) {
                    re[i] = inHist[c][i] * window[i];
                    im[i] = 0f;
                }
                Dsp.fft(re, im, false);
                processSpectrum(re, im, bins(), c);
                Dsp.fft(re, im, true);
                for (int i = 0; i < fftSize; i++) {
                    ola[c][i] += re[i] * window[i] * olaScale;
                }
                for (int i = 0; i < hopSize; i++) {
                    float wet = ola[c][i];
                    float dry = inHist[c][i];
                    outQ[c][outCount + i] = w * wet + (1f - w) * dry;
                }
                System.arraycopy(ola[c], hopSize, ola[c], 0, fftSize - hopSize);
                Arrays.fill(ola[c], fftSize - hopSize, fftSize, 0f);
                System.arraycopy(inHist[c], hopSize, inHist[c], 0, fftSize - hopSize);
            }
            outCount += hopSize;
            histFill = fftSize - hopSize;
        }

        long ready = Math.max(0L, inputConsumed - fftSize);
        int readyInQueue = (int) Math.min(outCount,
                Math.max(0L, ready - genuinePopped));
        int give = Math.min(frames, readyInQueue);
        for (int f = 0; f < give; f++) {
            for (int c = 0; c < ch; c++) {
                buf[f * ch + c] = outQ[c][f];
            }
        }
        for (int f = give; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                buf[f * ch + c] = 0f;
            }
        }
        genuinePopped += give;
        if (give > 0) {
            int left = outCount - give;
            for (int c = 0; c < ch; c++) {
                System.arraycopy(outQ[c], give, outQ[c], 0, left);
            }
            outCount = left;
        }
    }

    private void ensureOutCapacity(int capacity) {
        if (outQ[0].length >= capacity) return;
        float[][] grown = {new float[capacity], new float[capacity]};
        if (outCount > 0) {
            System.arraycopy(outQ[0], 0, grown[0], 0, outCount);
            System.arraycopy(outQ[1], 0, grown[1], 0, outCount);
        }
        outQ = grown;
    }

    /**
     * Welch-style averaged power spectrum of an interleaved buffer, used by
     * the spectral analyser and the noise-profile learner.
     *
     * @return normalised power per bin (length fftSize / 2 + 1)
     */
    public static float[] welchSpectrum(float[] buf, int frames, int channels,
                                        int size, float windowGain) {
        int n = Math.max(64, Integer.highestOneBit(Math.max(64, size)));
        int hop = n / 4;
        float[] win = Dsp.hannWindow(n);
        int ch = Math.max(1, Math.min(2, channels));
        int binCount = n / 2 + 1;
        float[] power = new float[binCount];
        float[] re = new float[n];
        float[] im = new float[n];
        int framesUsed = 0;
        for (int start = 0; start + n <= frames; start += hop) {
            for (int i = 0; i < n; i++) {
                float s = 0f;
                for (int c = 0; c < ch; c++) s += buf[(start + i) * ch + c];
                re[i] = (s / ch) * win[i];
                im[i] = 0f;
            }
            Dsp.fft(re, im, false);
            for (int b = 0; b < binCount; b++) {
                power[b] += re[b] * re[b] + im[b] * im[b];
            }
            framesUsed++;
        }
        if (framesUsed > 0) {
            float scale = 1f / (framesUsed * (float) n * n * 0.25f);
            for (int b = 0; b < binCount; b++) power[b] *= scale;
        }
        return power;
    }

    /** Converts a bin index to its centre frequency in Hz. */
    protected float binToHz(int bin, int sampleRate) {
        return (float) bin * sampleRate / fftSize;
    }
}
