package com.example.hello;

/**
 * Dynamic 2nd/4th-order Butterworth high-pass filter ("Low-Cut Filter Pro").
 * Coefficients are smoothed per sample across cutoff edits and snapped cleanly
 * when the filter order (`hpf_slope`) changes so feedback poles never cross
 * through unstable intermediate values.
 */
public final class ProHpfProcessor extends AudioProcessor {

    private final FxParameter cutoff = addParameter(
            FxParameter.intParam("cutoff", "Cutoff", " Hz", 40, 160, 10, 80));
    private final FxParameter slope = addParameter(
            FxParameter.enumParam("slope", "Slope",
                    new String[]{"12 dB/oct", "24 dB/oct"}, 0));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    private float tb0 = 1.0f, tb1 = 0.0f, tb2 = 0.0f, ta1 = 0.0f, ta2 = 0.0f;
    private float x1L, x2L, y1L, y2L;
    private float x1R, x2R, y1R, y2R;
    private float s2x1L, s2x2L, s2y1L, s2y2L;
    private float s2x1R, s2x2R, s2y1R, s2y2R;
    private int lastSlopeIdx = -1;

    public ProHpfProcessor() {
        setEnabled(false);
        recomputeTarget(false);
        snapCoeffsToTarget();
    }

    @Override
    public String getId() {
        return "hpf";
    }

    @Override
    public String getTitle() {
        return "Low-Cut Filter Pro";
    }

    @Override
    public String getDescription() {
        return "Butterworth rumble & plosive filter";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        this.sampleRate = sampleRate > 0 ? sampleRate : DspChain.SAMPLE_RATE;
        recomputeTarget(isEnabled());
        snapCoeffsToTarget();
    }

    @Override
    public void reset() {
        recomputeTarget(isEnabled());
        snapCoeffsToTarget();
        x1L = x2L = y1L = y2L = 0f;
        x1R = x2R = y1R = y2R = 0f;
        s2x1L = s2x2L = s2y1L = s2y2L = 0f;
        s2x1R = s2x2R = s2y1R = s2y2R = 0f;
    }

    private void snapCoeffsToTarget() {
        b0 = tb0;
        b1 = tb1;
        b2 = tb2;
        a1 = ta1;
        a2 = ta2;
    }

    void setLegacyState(boolean enabled, int cutoffHz) {
        boolean wasEnabled = isEnabled();
        setEnabled(enabled);
        cutoff.setCurrentRaw(cutoffHz);
        recomputeTarget(enabled);
        if (!wasEnabled && enabled) {
            snapCoeffsToTarget();
        }
    }

    private void recomputeTarget(boolean active) {
        if (!active) {
            tb0 = 1.0f;
            tb1 = 0.0f;
            tb2 = 0.0f;
            ta1 = 0.0f;
            ta2 = 0.0f;
            return;
        }
        double fc = Math.max(30.0, Math.min(240.0, cutoff.getCurrentRaw()));
        double w0 = 2.0 * Math.PI * fc / Math.max(8000, sampleRate);
        double cosW = Math.cos(w0);
        double alpha = Math.sin(w0) / Math.sqrt(2.0);
        double a0 = 1.0 + alpha;
        tb0 = (float) (((1.0 + cosW) * 0.5) / a0);
        tb1 = (float) (-(1.0 + cosW) / a0);
        tb2 = tb0;
        ta1 = (float) ((-2.0 * cosW) / a0);
        ta2 = (float) ((1.0 - alpha) / a0);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        recomputeTarget(true);
        int slopeIdx = slope.getEnumIndex();
        if (slopeIdx != lastSlopeIdx) {
            if (lastSlopeIdx != -1) {
                s2x1L = s2x2L = s2y1L = s2y2L = 0f;
                s2x1R = s2x2R = s2y1R = s2y2R = 0f;
            }
            snapCoeffsToTarget();
            lastSlopeIdx = slopeIdx;
        }
        boolean steep = slopeIdx == 1;
        final float cSmooth = 0.004f;
        for (int f = 0; f < frames; f++) {
            b0 += cSmooth * (tb0 - b0);
            b1 += cSmooth * (tb1 - b1);
            b2 += cSmooth * (tb2 - b2);
            a1 += cSmooth * (ta1 - a1);
            a2 += cSmooth * (ta2 - a2);

            int idx = f * channels;
            float sL = buf[idx];
            float yL = b0 * sL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L;
            x2L = x1L;
            x1L = sL;
            y2L = y1L;
            y1L = yL;
            if (steep) {
                float y2 = b0 * yL + b1 * s2x1L + b2 * s2x2L - a1 * s2y1L - a2 * s2y2L;
                s2x2L = s2x1L;
                s2x1L = yL;
                s2y2L = s2y1L;
                s2y1L = y2;
                yL = y2;
            }
            buf[idx] = yL;

            if (channels == 2) {
                float sR = buf[idx + 1];
                float yR = b0 * sR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R;
                x2R = x1R;
                x1R = sR;
                y2R = y1R;
                y1R = yR;
                if (steep) {
                    float y2 = b0 * yR + b1 * s2x1R + b2 * s2x2R - a1 * s2y1R - a2 * s2y2R;
                    s2x2R = s2x1R;
                    s2x1R = yR;
                    s2y2R = s2y1R;
                    s2y1R = y2;
                    yR = y2;
                }
                buf[idx + 1] = yR;
            }
        }
    }
}
