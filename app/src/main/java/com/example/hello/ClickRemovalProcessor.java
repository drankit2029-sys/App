package com.example.hello;

/**
 * Stage 5 de-clicker: detects isolated sample spikes by comparing the local
 * energy of a narrow window against its surrounding context and replaces the
 * offending samples with a cubic Hermite interpolation across the gap.
 */
final class ClickRemovalProcessor extends AudioProcessor {

    private final FxParameter threshold = addParameter(FxParameter.intParam(
            "click_thresh", "Threshold", "", 1, 900, 1, 200));
    private final FxParameter maxWidth = addParameter(FxParameter.intParam(
            "click_width", "Max Spike Width", " ms", 1, 40, 1, 10));

    private int sampleRate = DspChain.SAMPLE_RATE;

    ClickRemovalProcessor() {
    }

    @Override
    public String getId() {
        return "declick";
    }

    @Override
    public String getTitle() {
        return "Click Removal";
    }

    @Override
    public String getDescription() {
        return "Detects and repairs isolated digital clicks and pops";
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
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        // The threshold is a percentage: a sample is a click when its
        // deviation from the linear prediction of its neighbours exceeds this
        // multiple of the local RMS.
        float thr = threshold.getCurrentRaw() / 100f;
        int maxSpike = Math.max(1,
                (int) (maxWidth.getCurrentRaw() * 0.001f * sampleRate));

        for (int c = 0; c < ch; c++) {
            boolean[] bad = new boolean[frames];
            boolean any = false;
            for (int f = 16; f < frames - 16; f++) {
                // Local RMS over a 33-sample context.
                double sum = 0;
                for (int k = -16; k <= 16; k++) {
                    double v = buf[(f + k) * ch + c];
                    sum += v * v;
                }
                float rms = (float) Math.sqrt(sum / 33.0);
                // Deviation from the straight line through the neighbours.
                float predicted = 0.5f * (buf[(f - 1) * ch + c]
                        + buf[(f + 1) * ch + c]);
                float residual = Math.abs(buf[f * ch + c] - predicted);
                float ratio = residual / Math.max(1e-6f, rms);
                if (ratio > thr) {
                    bad[f] = true;
                    any = true;
                }
            }
            if (!any) continue;
            // Merge consecutive flagged samples into runs and interpolate.
            int f = 0;
            while (f < frames) {
                if (!bad[f]) {
                    f++;
                    continue;
                }
                int start = f;
                while (f < frames && bad[f]) f++;
                int end = f; // exclusive
                int width = end - start;
                if (width > maxSpike) continue;
                if (start < 1 || end >= frames - 1) continue;
                float y0 = buf[(start - 1) * ch + c];
                float y1 = buf[end * ch + c];
                // Derivative from the outer neighbours.
                float d0 = (buf[end * ch + c] - buf[(start - 2) * ch + c])
                        / Math.max(1, width + 1);
                float d1 = (buf[(end + 1) * ch + c] - buf[(start - 1) * ch + c])
                        / Math.max(1, width + 1);
                for (int i = start; i < end; i++) {
                    float t = (i - start + 1f) / (width + 1f);
                    // Cubic Hermite between the two boundary samples.
                    float t2 = t * t;
                    float t3 = t2 * t;
                    float h00 = 2 * t3 - 3 * t2 + 1;
                    float h10 = t3 - 2 * t2 + t;
                    float h01 = -2 * t3 + 3 * t2;
                    float h11 = t3 - t2;
                    buf[i * ch + c] = h00 * y0 + h10 * d0 * (width + 1)
                            + h01 * y1 + h11 * d1 * (width + 1);
                }
            }
        }
    }
}
