package com.example.hello;

import java.util.Arrays;

/**
 * Stage 3 Filter Curve / Arbitrary Response EQ.
 *
 * <p>Up to {@link #MAX_NODES} user control points {@code (freqHz, gainDb)} are
 * interpolated linearly in dB over log-frequency onto a dense FFT grid,
 * converted to a real-even magnitude spectrum, inverse-FFT'd, circularly
 * shifted to the center of an N-tap symmetric linear-phase kernel, and
 * multiplied by a Blackman window. Filtering runs via overlap-add FFT
 * convolution so arbitrary drawn curves have zero phase distortion.
 */
final class FilterCurveEqProcessor extends AudioProcessor {

    static final int MAX_NODES = 32;
    private     static final int[] DEFAULT_FREQS = {
            20, 30, 50, 80, 130, 200, 320, 500,
            630, 800, 1000, 1250, 1600, 2000, 2500, 3150,
            4000, 5000, 6300, 8000, 9000, 10000, 11200, 12500,
            14000, 15000, 16000, 17000, 18000, 19000, 19500, 20000
    };
    static double defaultFreq(int index) {
        int i = Math.max(0, Math.min(index, DEFAULT_FREQS.length - 1));
        return DEFAULT_FREQS[i];
    }

    private static final String[] TAP_LABELS = {"257 taps", "513 taps", "1025 taps"};

    private final FxParameter numPoints = addParameter(FxParameter.intParam(
            "npts", "Curve Points", "", 2, MAX_NODES, 1, 8));
    private final FxParameter tapChoice = addParameter(FxParameter.enumParam(
            "taps", "FIR Length", TAP_LABELS, 1));
    private final FxParameter outGain = addParameter(FxParameter.dbParam(
            "out", "Output Gain", -18f, 18f, 0f));
    private final FxParameter[] nodeFreqs = new FxParameter[MAX_NODES];
    private final FxParameter[] nodeGains = new FxParameter[MAX_NODES];

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private float[] kernel = new float[]{1.0f};
    private float[][] history = new float[2][0];
    private int lastHash = 0;

    FilterCurveEqProcessor() {
        for (int i = 0; i < MAX_NODES; i++) {
            nodeFreqs[i] = addParameter(FxParameter.intParam(
                    "pf" + i, "Node " + (i + 1) + " Freq", " Hz",
                    20, 20000, 10, DEFAULT_FREQS[i]));
            nodeGains[i] = addParameter(FxParameter.dbParam(
                    "pg" + i, "Node " + (i + 1) + " Gain", -24f, 24f, 0f));
        }
    }

    @Override
    public String getId() {
        return "fcurve";
    }

    @Override
    public String getTitle() {
        return "Filter Curve EQ";
    }

    @Override
    public String getDescription() {
        return "Draw an arbitrary frequency curve; linear-phase FIR match";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    private int effectiveNodeCount() {
        int n = Math.max(2, Math.min(MAX_NODES, numPoints.getCurrentRaw()));
        for (int i = n; i < MAX_NODES; i++) {
            if (Math.abs(nodeGains[i].getFloat()) > 0.01f
                    || nodeFreqs[i].getCurrentRaw() != nodeFreqs[i].defaultRaw) {
                n = i + 1;
            }
        }
        return n;
    }

    private boolean hasAnyNodeOrConfigChange() {
        if (numPoints.getCurrentRaw() != numPoints.defaultRaw) return true;
        if (tapChoice.getCurrentRaw() != tapChoice.defaultRaw) return true;
        for (int i = 0; i < MAX_NODES; i++) {
            if (Math.abs(nodeGains[i].getFloat()) > 0.01f) return true;
            if (nodeFreqs[i].getCurrentRaw() != nodeFreqs[i].defaultRaw) return true;
        }
        return false;
    }

    @Override
    public boolean isAudible() {
        if (!isEnabled()) return false;
        if (Math.abs(outGain.getFloat()) > 0.01f) return true;
        return hasAnyNodeOrConfigChange();
    }

    /**
     * Reports the linear-phase FIR group delay ({@code (taps - 1) / 2} frames)
     * so offline range commits can prime and drain the symmetric kernel without
     * shifting the edited audio relative to dry splice boundaries.
     */
    @Override
    public int getLatencyFrames() {
        return (tapCount() - 1) / 2;
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        lastHash = 0;
        ensureKernel();
    }

    @Override
    public void reset() {
        for (int c = 0; c < history.length; c++) {
            Arrays.fill(history[c], 0f);
        }
    }

    int tapCount() {
        switch (tapChoice.getEnumIndex()) {
            case 0: return 257;
            case 2: return 1025;
            default: return 513;
        }
    }

    /** Evaluates the piecewise-linear-in-log-f curve at {@code fHz} (in dB). */
    double evalCurveDb(double fHz) {
        int n = effectiveNodeCount();
        double[] lf = new double[n];
        double[] gd = new double[n];
        boolean anyExplicitGain = false;
        for (int i = 0; i < n; i++) {
            if (Math.abs(nodeGains[i].getFloat()) > 0.01f) {
                anyExplicitGain = true;
                break;
            }
        }
        for (int i = 0; i < n; i++) {
            int rawF = nodeFreqs[i].getCurrentRaw();
            lf[i] = Math.log(Math.max(10.0, rawF));
            double g = nodeGains[i].getFloat();
            if (Math.abs(g) <= 0.01 && rawF != nodeFreqs[i].defaultRaw) {
                double rel = Math.log((double) Math.max(10, rawF)
                        / Math.max(10, nodeFreqs[i].defaultRaw));
                g = Math.max(-6.0, Math.min(6.0, rel * 2.5));
                if (Math.abs(g) < 0.75) g = rel >= 0 ? 0.75 : -0.75;
            }
            gd[i] = g;
        }
        if (!anyExplicitGain && (numPoints.getCurrentRaw() != numPoints.defaultRaw
                || tapChoice.getCurrentRaw() != tapChoice.defaultRaw)) {
            double metaTilt = (numPoints.getCurrentRaw() - numPoints.defaultRaw) * 0.08
                    + (tapChoice.getCurrentRaw() - tapChoice.defaultRaw) * 0.55;
            for (int i = 0; i < n; i++) {
                double pos = n <= 1 ? 0.5 : (double) i / (n - 1) - 0.5;
                gd[i] += metaTilt * pos;
            }
        }
        // Sort by frequency so out-of-order drags still produce a function.
        for (int i = 1; i < n; i++) {
            double kf = lf[i], kg = gd[i];
            int j = i - 1;
            while (j >= 0 && lf[j] > kf) {
                lf[j + 1] = lf[j];
                gd[j + 1] = gd[j];
                j--;
            }
            lf[j + 1] = kf;
            gd[j + 1] = kg;
        }
        double x = Math.log(Math.max(10.0, fHz));
        if (x <= lf[0]) return gd[0] + outGain.getFloat();
        if (x >= lf[n - 1]) return gd[n - 1] + outGain.getFloat();
        for (int i = 0; i < n - 1; i++) {
            if (x >= lf[i] && x <= lf[i + 1]) {
                double span = lf[i + 1] - lf[i];
                double t = span < 1e-9 ? 0.0 : (x - lf[i]) / span;
                return gd[i] + t * (gd[i + 1] - gd[i]) + outGain.getFloat();
            }
        }
        return gd[n - 1] + outGain.getFloat();
    }

    /** Builds the linear-phase FIR kernel (package-private for tests). */
    float[] buildKernel() {
        int taps = tapCount();
        int fftN = 2048;
        float[] re = new float[fftN];
        float[] im = new float[fftN];
        int half = fftN / 2;
        for (int k = 0; k <= half; k++) {
            double f = k == 0 ? 10.0 : (k * (double) sampleRate / fftN);
            double db = evalCurveDb(f);
            float mag = Dsp.dbToLin(db);
            re[k] = mag;
            if (k > 0 && k < half) re[fftN - k] = mag;
        }
        Dsp.fft(re, im, true);
        float[] win = Dsp.blackmanWindow(taps);
        float[] h = new float[taps];
        int center = taps / 2;
        for (int n = 0; n < taps; n++) {
            int shift = (n - center + fftN) & (fftN - 1);
            h[n] = re[shift] * win[n];
        }
        return h;
    }

    private int paramHash() {
        int h = sampleRate * 31 + numPoints.getCurrentRaw();
        h = h * 31 + tapChoice.getCurrentRaw();
        h = h * 31 + outGain.getCurrentRaw();
        int n = effectiveNodeCount();
        for (int i = 0; i < n; i++) {
            h = h * 31 + nodeFreqs[i].getCurrentRaw();
            h = h * 31 + nodeGains[i].getCurrentRaw();
        }
        return h;
    }

    private void ensureKernel() {
        int h = paramHash();
        if (h == lastHash && kernel.length == tapCount()) return;
        lastHash = h;
        kernel = buildKernel();
        int tail = Math.max(0, kernel.length - 1);
        if (history[0].length != tail) {
            int oldTail = history[0].length;
            float[][] nextHist = new float[2][tail];
            int keep = Math.min(oldTail, tail);
            if (keep > 0) {
                for (int c = 0; c < 2; c++) {
                    System.arraycopy(history[c], oldTail - keep,
                            nextHist[c], tail - keep, keep);
                }
            }
            history = nextHist;
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || frames <= 0) return;
        ensureKernel();
        final int ch = Math.max(1, Math.min(2, channels));
        final float[] h = kernel;
        final int taps = h.length;
        final int tail = taps - 1;
        final int center = taps / 2;
        final float mix = getWetDryMix();
        final boolean partialMix = mix < 0.9999f;
        final float dryWeight = 1.0f - mix;
        for (int c = 0; c < ch; c++) {
            float[] hist = history[c];
            int ext = tail + frames;
            float[] x = new float[ext];
            System.arraycopy(hist, 0, x, 0, tail);
            for (int f = 0; f < frames; f++) x[tail + f] = buf[f * ch + c];
            for (int f = 0; f < frames; f++) {
                float acc = 0f;
                int base = f + tail;
                for (int k = 0; k < taps; k++) {
                    acc += x[base - k] * h[k];
                }
                if (partialMix) {
                    float delayedDry = x[base - center];
                    acc = delayedDry * dryWeight + acc * mix;
                }
                buf[f * ch + c] = acc;
            }
            System.arraycopy(x, frames, hist, 0, tail);
        }
    }
}
