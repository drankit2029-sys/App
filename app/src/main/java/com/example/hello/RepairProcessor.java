package com.example.hello;

/**
 * Stage 5 Repair (LPC Short-Glitch & Dropout Interpolator): detects the worst
 * glitch or dropout span inside the region and reconstructs it by running
 * Burg/Levinson-Durbin linear predictive coding (LPC) forward from the left
 * context and backward from the right context, cross-fading the two
 * predictions with a raised-cosine window across the full repair length.
 */
final class RepairProcessor extends AudioProcessor {

    private final FxParameter lpcOrder = addParameter(FxParameter.intParam(
            "repair_order", "Prediction Order", "", 8, 32, 2, 16));
    private final FxParameter repairLen = addParameter(FxParameter.floatParam(
            "repair_len", "Max Repair Length", " ms", 0.5f, 50f, 3.0f));

    private int sampleRate = DspChain.SAMPLE_RATE;

    RepairProcessor() {
    }

    @Override
    public String getId() {
        return "repair";
    }

    @Override
    public String getTitle() {
        return "Repair";
    }

    @Override
    public String getDescription() {
        return "Bidirectional LPC autoregressive interpolation for glitches & dropouts";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames < 128) return;
        int ch = Math.max(1, Math.min(2, channels));
        int order = Math.max(4, Math.min(32, lpcOrder.getCurrentRaw()));
        int maxSamples = Math.max(8, Math.min(frames / 3,
                (int) Math.round(repairLen.getFloat() * 0.001 * sampleRate)));
        int ctxLen = Math.max(order * 4, 128);
        if (frames < ctxLen * 2 + maxSamples) return;

        for (int c = 0; c < ch; c++) {
            float[] x = new float[frames];
            for (int f = 0; f < frames; f++) x[f] = buf[f * ch + c];

            // Locate a sudden dropout to silence surrounded by active audio.
            int dropoutStart = -1;
            int dropoutEnd = -1;
            for (int f = ctxLen; f < frames - ctxLen; f++) {
                if (dropoutStart < 0 && Math.abs(x[f]) < 1e-4f
                        && Math.abs(x[f - 1]) > 0.01f) {
                    int e = f;
                    while (e < frames - ctxLen && Math.abs(x[e]) < 1e-4f) e++;
                    if (e - f >= 4) {
                        dropoutStart = f;
                        dropoutEnd = e;
                    }
                }
            }

            // Only repair actual dropouts. Never overwrite a clean window
            // around the largest natural curvature peak: that corrupted up to
            // 58% of every dropout-free chunk with synthetic LPC audio.
            if (dropoutStart < ctxLen || dropoutEnd <= dropoutStart) continue;
            int start = dropoutStart;
            int end = Math.min(dropoutEnd, dropoutStart + maxSamples);
            repairSpan(x, start, end, order, ctxLen);
            for (int f = start; f < end; f++) buf[f * ch + c] = x[f];
        }
    }

    /** Reconstructs {@code x[start..end)} from surrounding context via LPC. */
    static void repairSpan(float[] x, int start, int end, int order, int ctxLen) {
        int len = end - start;
        if (len <= 0 || start < ctxLen || end + ctxLen > x.length) return;
        double[] aFwd = levinsonDurbin(x, start - ctxLen, ctxLen, order);
        double[] aBwd = levinsonDurbin(x, end, ctxLen, order);

        float[] fwd = new float[len];
        float[] bwd = new float[len];
        float[] fHist = new float[order];
        float[] bHist = new float[order];
        for (int k = 0; k < order; k++) {
            fHist[k] = x[start - 1 - k];
            bHist[k] = x[end + k];
        }
        // Damping factor slightly below unity (0.9992) keeps very long
        // autoregressive extrapolations (up to 2400 samples) bounded.
        final double damp = 0.9992;
        for (int i = 0; i < len; i++) {
            double sF = 0;
            double sB = 0;
            for (int k = 1; k <= order; k++) {
                sF -= aFwd[k] * fHist[k - 1];
                sB -= aBwd[k] * bHist[k - 1];
            }
            float nextF = (float) (sF * damp);
            float nextB = (float) (sB * damp);
            if (!Float.isFinite(nextF)) nextF = 0f;
            if (!Float.isFinite(nextB)) nextB = 0f;
            fwd[i] = nextF;
            bwd[len - 1 - i] = nextB;
            System.arraycopy(fHist, 0, fHist, 1, order - 1);
            fHist[0] = nextF;
            System.arraycopy(bHist, 0, bHist, 1, order - 1);
            bHist[0] = nextB;
        }
        for (int i = 0; i < len; i++) {
            float u = (i + 0.5f) / len;
            float wBwd = 0.5f * (1f - (float) Math.cos(Math.PI * u));
            float wFwd = 1f - wBwd;
            x[start + i] = fwd[i] * wFwd + bwd[i] * wBwd;
        }
    }

    private static double[] levinsonDurbin(float[] x, int off, int n, int order) {
        double[] r = new double[order + 1];
        for (int k = 0; k <= order; k++) {
            double s = 0;
            for (int i = 0; i < n - k; i++) {
                s += (double) x[off + i] * x[off + i + k];
            }
            r[k] = s;
        }
        double[] a = new double[order + 1];
        double[] aPrev = new double[order + 1];
        a[0] = 1.0;
        if (r[0] < 1e-12) return a;
        double e = r[0];
        for (int m = 1; m <= order; m++) {
            double sum = r[m];
            for (int k = 1; k < m; k++) sum += a[k] * r[m - k];
            double kappa = -sum / Math.max(1e-12, e);
            if (kappa > 0.995) kappa = 0.995;
            if (kappa < -0.995) kappa = -0.995;
            System.arraycopy(a, 0, aPrev, 0, m);
            a[m] = kappa;
            for (int k = 1; k < m; k++) {
                a[k] = aPrev[k] + kappa * aPrev[m - k];
            }
            e *= (1.0 - kappa * kappa);
            if (e <= 1e-14) break;
        }
        return a;
    }
}
