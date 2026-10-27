package com.example.hello;

/**
 * Reusable Short-Time Fourier Transform overlap-add framework (Stage 5).
 *
 * <p>The processor keeps a per-channel analysis ring buffer, applies a
 * Hann window, runs the FFT, hands the magnitude/phase spectrum to the
 * subclass and resynthesises with a second window plus overlap-add. With a
 * 75 % overlap (hop = fftSize / 4) the Hann-squared sum is unity, so the
 * round trip is transparent to within numerical precision.
 *
 * <p>Subclasses only implement {@link #processSpectrum(float[], float[],
 * int, int)} to modify the complex spectrum in place.
 */
abstract class StftProcessor {

    /** FFT size (power of two). */
    protected int fftSize = 1024;
    /** Hop size between analysis frames. */
    protected int hopSize = 256;
    /** Overlap factor: 4 means 75 % overlap (hop = fftSize / 4). */
    protected int overlap = 4;

    private final float[] window;
    private float[] re;
    private float[] im;

    protected StftProcessor(int fftSize, int overlap) {
        setGeometry(fftSize, overlap);
        window = Dsp.hannWindow(this.fftSize);
        allocate();
    }

    private void setGeometry(int size, int ov) {
        this.fftSize = Math.max(64, Integer.highestOneBit(Math.max(64, size)));
        this.overlap = Math.max(1, Math.min(8, ov));
        this.hopSize = this.fftSize / this.overlap;
    }

    private void allocate() {
        re = new float[fftSize];
        im = new float[fftSize];
    }

    /** Rebuilds the FFT geometry (call when the size or overlap changes). */
    protected void reconfigure(int size, int ov) {
        setGeometry(size, ov);
        allocate();
    }

    /** Number of usable spectrum bins (fftSize / 2 + 1). */
    protected int bins() {
        return fftSize / 2 + 1;
    }

    /**
     * Modifies one frame's spectrum.
     *
     * @param re     real part, length fftSize
     * @param im     imaginary part, length fftSize
     * @param binCount number of positive-frequency bins
     * @param channel channel index (0 or 1)
     */
    protected abstract void processSpectrum(float[] re, float[] im,
                                            int binCount, int channel);

    /**
     * Processes a whole interleaved buffer through the STFT and writes the
     * resynthesised signal back into {@code buf}.
     */
    public void process(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int out = frames * ch;
        float[] result = new float[out];
        // Analysis / synthesis buffers with a full frame of padding so the
        // overlap-add can emit every input frame.
        int pad = fftSize;
        float[] inBuf = new float[(frames + 2 * pad) * ch];
        float[] acc = new float[(frames + 2 * pad) * ch];
        float[] norm = new float[(frames + 2 * pad) * ch];
        System.arraycopy(buf, 0, inBuf, pad * ch, out);

        for (int channel = 0; channel < ch; channel++) {
            int frameStart = 0;
            while (frameStart + fftSize <= frames + 2 * pad) {
                for (int i = 0; i < fftSize; i++) {
                    float s = inBuf[(frameStart + i) * ch + channel];
                    re[i] = s * window[i];
                    im[i] = 0f;
                }
                Dsp.fft(re, im, false);
                processSpectrum(re, im, bins(), channel);
                Dsp.fft(re, im, true);
                for (int i = 0; i < fftSize; i++) {
                    float s = re[i] * window[i];
                    int pos = frameStart + i;
                    acc[pos * ch + channel] += s;
                    // The window is applied on both the analysis and the
                    // synthesis side, so the overlap-add sum is window^2.
                    // Track it per channel and divide it out.
                    norm[pos * ch + channel] += window[i] * window[i];
                }
                frameStart += hopSize;
            }
        }
        for (int i = 0; i < out; i++) {
            int chan = i % ch;
            int pos = pad + i / ch;
            float n = norm[pos * ch + chan];
            float v = acc[pos * ch + chan];
            result[i] = n > 1e-6f ? v / n : v;
        }
        System.arraycopy(result, 0, buf, 0, out);
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

    /**
     * Converts a bin index to its centre frequency in Hz.
     */
    protected float binToHz(int bin, int sampleRate) {
        return (float) bin * sampleRate / fftSize;
    }
}
