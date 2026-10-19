package com.example.hello;

import java.util.Locale;

/**
 * Stage 3 · A3: Filter Curve EQ. Up to 32 draggable frequency/gain nodes
 * design a linear-phase FIR whose magnitude matches the drawn curve; audio
 * runs through a uniform overlap-add FFT convolution (block 256). Node
 * geometry persists per slot through {@code extraParams} (npts, pf#, pg#),
 * so it survives rack save/load without bespoke serialization.
 */
final class FilterCurveEqProcessor extends AudioProcessor {

    static final int MAX_NODES = 32;
    private static final int BLOCK = 256;
    private static final String[] TAP_LABELS = {"1024", "2048", "4096"};

    private final FxParameter out = addParameter(
            FxParameter.dbParam("out", "Output", -18f, 18f, 0f));
    private final FxParameter npts = addParameter(
            FxParameter.intParam("npts", "Points", "", 2, MAX_NODES, 1, 8));
    private final FxParameter tapsSel = addParameter(
            FxParameter.enumParam("taps", "Precision", TAP_LABELS, 1));
    private final FxParameter[] nodeFreq = new FxParameter[MAX_NODES];
    private final FxParameter[] nodeGain = new FxParameter[MAX_NODES];

    private final BiquadFilter outputGain = new BiquadFilter();

    private float[] ir = new float[0];
    private float[] hRe = new float[0];
    private float[] hIm = new float[0];
    private float[][] carry = new float[2][0];
    private float[] inRe = new float[0];
    private float[] inIm = new float[0];
    private int fftSize = 0;
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;
    private long designKey = Long.MIN_VALUE;

    FilterCurveEqProcessor() {
        for (int i = 0; i < MAX_NODES; i++) {
            double f = defaultFreq(i);
            nodeFreq[i] = addParameter(FxParameter.intParam(
                    "pf" + i, "F" + (i + 1), " Hz", 20, 20000, 1, (int) f));
        }
        for (int i = 0; i < MAX_NODES; i++) {
            nodeGain[i] = addParameter(FxParameter.dbParam(
                    "pg" + i, "G" + (i + 1), -24f, 24f, 0f));
        }
    }

    /**
     * Default node layout: the first eight nodes (the default point count)
     * span 40 Hz to 16 kHz; additional nodes start at 16 kHz and are placed
     * by dragging in the editor.
     */
    static double defaultFreq(int index) {
        return Math.round(40.0 * Math.pow(400.0, Math.min(index, 7) / 7.0));
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
        return "Draw an arbitrary response curve; linear-phase FIR (1024-4096 taps)";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sr, int ch) {
        sampleRate = Math.max(8000, sr);
        channels = Math.max(1, Math.min(2, ch));
        designKey = Long.MIN_VALUE;
        rebuild();
    }

    @Override
    public void reset() {
        for (float[] c : carry) {
            if (c != null) java.util.Arrays.fill(c, 0f);
        }
        outputGain.reset();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int ch) {
        if (buf == null || frames <= 0) return;
        rebuild();
        int c = Math.max(1, Math.min(2, ch));
        offset256(buf, frames, c);
        float g = Dsp.dbToLin(out.getFloat());
        if (g != 1.0f) {
            float[] gain = {g, 0f, 0f, 0f, 0f};
            outputGain.setSections(new float[][]{gain}, c);
            outputGain.process(buf, frames, c);
        }
    }

    private void rebuild() {
        long key = designKeyNow();
        if (key == designKey) return;
        designKey = key;
        int taps = tapsForIndex(tapsSel.getEnumIndex());
        int n = Math.max(2, Math.min(MAX_NODES, npts.getCurrentRaw()));
        double[][] nodes = new double[n][2];
        for (int i = 0; i < n; i++) {
            nodes[i][0] = nodeFreq[i].getFloat();
            nodes[i][1] = nodeGain[i].getFloat();
        }
        sortNodes(nodes);
        designFir(nodes, taps);
        installCarry();
    }

    private long designKeyNow() {
        long h = 1469598103934665603L;
        h = h * 31 + sampleRate;
        h = h * 31 + channels;
        h = h * 31 + npts.getCurrentRaw();
        h = h * 31 + tapsSel.getCurrentRaw();
        for (int i = 0; i < MAX_NODES; i++) {
            h = h * 31 + nodeFreq[i].getCurrentRaw();
            h = h * 31 + nodeGain[i].getCurrentRaw();
        }
        return h;
    }

    private static void sortNodes(double[][] nodes) {
        for (int i = 1; i < nodes.length; i++) {
            double[] v = nodes[i];
            int j = i - 1;
            while (j >= 0 && nodes[j][0] > v[0]) {
                nodes[j + 1] = nodes[j];
                j--;
            }
            nodes[j + 1] = v;
        }
    }

    /** Builds the windowed impulse response via inverse FFT of the curve. */
    private void designFir(double[][] nodes, int taps) {
        int size = 2;
        while (size < BLOCK + taps - 1) size <<= 1;
        fftSize = size;
        float[] re = new float[size];
        float[] im = new float[size];
        int half = size / 2;
        for (int k = 0; k <= half; k++) {
            double f = k * (double) sampleRate / size;
            double db = curveDbAt(nodes, f);
            float mag = Dsp.dbToLin(db);
            re[k] = mag;
            if (k > 0 && k < half) re[size - k] = mag;
        }
        Dsp.fft(re, im, true);
        int center = size / 2;
        float[] w = Dsp.blackmanWindow(taps);
        ir = new float[taps];
        double sum = 0;
        for (int t = 0; t < taps; t++) {
            int src = ((center - taps / 2 + t) % size + size) % size;
            ir[t] = re[src] * w[t];
            sum += ir[t];
        }
        if (Math.abs(sum) > 1e-9) {
            float norm = (float) (1.0 / sum);
            for (int t = 0; t < taps; t++) ir[t] *= norm;
        }
        if (hRe.length != size) {
            hRe = new float[size];
            hIm = new float[size];
            inRe = new float[size];
            inIm = new float[size];
        }
        java.util.Arrays.fill(hRe, 0f);
        java.util.Arrays.fill(hIm, 0f);
        System.arraycopy(ir, 0, hRe, 0, taps);
        Dsp.fft(hRe, hIm, false);
    }

    /** Piecewise-linear dB interpolation over the sorted nodes (log-f axis). */
    private static double curveDbAt(double[][] nodes, double f) {
        if (f <= nodes[0][0]) return nodes[0][1];
        int last = nodes.length - 1;
        if (f >= nodes[last][0]) return nodes[last][1];
        for (int i = 0; i < last; i++) {
            double f0 = nodes[i][0], f1 = nodes[i + 1][0];
            if (f >= f0 && f <= f1) {
                double t = (Math.log(f) - Math.log(f0))
                        / Math.max(1e-9, Math.log(f1) - Math.log(f0));
                return nodes[i][1] + t * (nodes[i + 1][1] - nodes[i][1]);
            }
        }
        return nodes[last][1];
    }

    private void installCarry() {
        if (carry[0].length != fftSize - BLOCK) {
            carry = new float[][]{new float[fftSize - BLOCK],
                    new float[fftSize - BLOCK]};
        }
    }

    /** Uniform overlap-add blocks of 256 frames through the FIR. */
    private void offset256(float[] buf, int frames, int c) {
        if (ir.length == 0 || fftSize == 0) return;
        int off = 0;
        while (off < frames) {
            int blk = Math.min(BLOCK, frames - off);
            for (int chn = 0; chn < c; chn++) {
                java.util.Arrays.fill(inRe, 0f);
                java.util.Arrays.fill(inIm, 0f);
                for (int f = 0; f < blk; f++) {
                    inRe[f] = buf[(off + f) * c + chn];
                }
                Dsp.fft(inRe, inIm, false);
                for (int k = 0; k < fftSize; k++) {
                    float rr = inRe[k] * hRe[k] - inIm[k] * hIm[k];
                    float ii = inRe[k] * hIm[k] + inIm[k] * hRe[k];
                    inRe[k] = rr;
                    inIm[k] = ii;
                }
                Dsp.fft(inRe, inIm, true);
                float[] cr = carry[chn];
                for (int f = 0; f < blk; f++) {
                    buf[(off + f) * c + chn] = inRe[f] + cr[f];
                }
                int keep = fftSize - BLOCK;
                for (int f = 0; f < keep; f++) {
                    cr[f] = inRe[BLOCK + f];
                }
            }
            off += blk;
        }
    }

    static int tapsForIndex(int idx) {
        switch (idx) {
            case 0: return 1024;
            case 2: return 4096;
            default: return 2048;
        }
    }

    String curveSummary() {
        int n = Math.max(2, Math.min(MAX_NODES, npts.getCurrentRaw()));
        return String.format(Locale.US, "%d pts · %s", n, TAP_LABELS[
                Math.max(0, Math.min(2, tapsSel.getEnumIndex()))]);
    }
}
