package com.example.hello;

/**
 * Stage 5 short-glitch interpolator: fits forward and backward linear
 * predictive models to the material on either side of a damaged region and
 * cross-fades the two predictions across the gap.
 *
 * <p>The LPC coefficients are estimated with the autocorrelation method
 * (Levinson-Durbin recursion), which is stable and cheap enough to run over
 * a 512-sample context.
 */
final class RepairProcessor extends AudioProcessor {

    private final FxParameter order = addParameter(FxParameter.intParam(
            "repair_order", "Prediction Order", "", 8, 32, 1, 16));
    private final FxParameter maxLength = addParameter(FxParameter.intParam(
            "repair_len", "Max Glitch Length", " ms", 1, 50, 1, 12));

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
        return "LPC short-glitch interpolator for dropouts and damage";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    /**
     * Repairs a damaged region of a mono buffer.
     *
     * @param buf    samples
     * @param start  first damaged sample
     * @param end    first sample after the damaged region
     */
    public void repairRegion(float[] buf, int start, int end) {
        if (buf == null || start < 1 || end <= start || end >= buf.length) return;
        int p = Math.min(order.getCurrentRaw(), 32);
        int context = Math.min(512, Math.min(start, buf.length - end));
        if (context < p + 2) return;

        float[] pre = new float[context];
        System.arraycopy(buf, start - context, pre, 0, context);
        float[] post = new float[context];
        System.arraycopy(buf, end, post, 0, context);

        float[] a = lpcCoefficients(pre, p);
        float[] b = lpcCoefficients(post, p);

        int len = end - start;
        for (int i = 0; i < len; i++) {
            float t = (i + 1f) / (len + 1f);
            // Equal-power cross-fade between the two predictions.
            float wf = (float) Math.sin(t * Math.PI * 0.5);
            float wb = (float) Math.cos(t * Math.PI * 0.5);
            float fwd = predictForward(pre, context, i, a);
            float bwd = predictBackward(post, context, len - 1 - i, b);
            buf[start + i] = fwd * wf + bwd * wb;
        }
    }

    /**
     * Forward LPC prediction: the model was fitted to the samples that
     * precede the gap, so the prediction runs backwards through that history.
     */
    private static float predictForward(float[] history, int context, int i,
                                        float[] coeffs) {
        float sum = 0;
        for (int k = 0; k < coeffs.length; k++) {
            int idx = context - 1 - i - k;
            if (idx < 0) break;
            sum += coeffs[k] * history[idx];
        }
        return sum;
    }

    /**
     * Backward LPC prediction: the model was fitted to the samples that
     * follow the gap, so the prediction runs forwards through them.
     */
    private static float predictBackward(float[] history, int context, int i,
                                         float[] coeffs) {
        float sum = 0;
        for (int k = 0; k < coeffs.length; k++) {
            int idx = i + k;
            if (idx >= context) break;
            sum += coeffs[k] * history[idx];
        }
        return sum;
    }

    /**
     * Autocorrelation LPC with the Levinson-Durbin recursion.
     */
    static float[] lpcCoefficients(float[] x, int order) {
        int n = x.length;
        float[] r = new float[order + 1];
        for (int k = 0; k <= order; k++) {
            double sum = 0;
            for (int i = k; i < n; i++) sum += (double) x[i] * x[i - k];
            r[k] = (float) (sum / Math.max(1, n - k));
        }
        if (r[0] <= 1e-12f) return new float[order];

        float[] a = new float[order + 1];
        float[] prev = new float[order + 1];
        a[0] = 1f;
        float err = r[0];
        for (int i = 1; i <= order; i++) {
            System.arraycopy(a, 0, prev, 0, a.length);
            double acc = r[i];
            for (int j = 1; j < i; j++) acc += prev[j] * r[i - j];
            float k = (float) (acc / err);
            a[i] = k;
            for (int j = 1; j < i; j++) {
                a[j] = prev[j] - k * prev[i - j];
            }
            err *= (1f - k * k);
            if (err <= 1e-12f) {
                for (int j = i + 1; j <= order; j++) a[j] = 0f;
                break;
            }
        }
        float[] out = new float[order];
        for (int i = 0; i < order; i++) out[i] = -a[i + 1];
        return out;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        // Find short silent dropouts and repair them.
        for (int c = 0; c < ch; c++) {
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) mono[i] = buf[i * ch + c];
            int f = 0;
            while (f < frames) {
                if (mono[f] != 0f) {
                    f++;
                    continue;
                }
                int start = f;
                while (f < frames && mono[f] == 0f) f++;
                int len = f - start;
                if (len < 2 || len > 64) continue;
                repairRegion(mono, start, start + len);
            }
            for (int i = 0; i < frames; i++) buf[i * ch + c] = mono[i];
        }
    }
}
