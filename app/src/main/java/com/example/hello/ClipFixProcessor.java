package com.example.hello;

/**
 * Stage 5 Clip Fix (De-Clipper): detects flat-topped clipped runs above the
 * user's Clipping Threshold (% of maximum peak), reduces overall gain by the
 * requested headroom to make room for reconstructed peaks, and reconstructs
 * every clipped run using a smooth cubic Hermite arch matching the entry and
 * exit slopes.
 */
final class ClipFixProcessor extends AudioProcessor {

    private final FxParameter threshold = addParameter(FxParameter.intParam(
            "clip_thresh", "Clipping Threshold", "%", 80, 100, 1, 95));
    private final FxParameter headroom = addParameter(FxParameter.dbParam(
            "clip_headroom", "Restore Headroom", -18f, 0f, -6f));

    ClipFixProcessor() {
    }

    @Override
    public String getId() {
        return "clipfix";
    }

    @Override
    public String getTitle() {
        return "Clip Fix";
    }

    @Override
    public String getDescription() {
        return "Reconstructs flat-topped clipped peaks with cubic spline arches";
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
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames < 8) return;
        int ch = Math.max(1, Math.min(2, channels));
        float peak = 0f;
        int total = Math.min(buf.length, frames * ch);
        for (int i = 0; i < total; i++) {
            float a = Math.abs(buf[i]);
            if (a > peak) peak = a;
        }
        if (peak < 1e-4f) return;
        // Map 80..100% onto 0.78..0.98 of detected peak so even clip_thresh=100%
        // catches flat-topped crests at peak while remaining distinct from 95%.
        float normThresh = (threshold.getCurrentRaw() - 80) / 20.0f;
        float clipLevel = peak * (0.78f + 0.20f * normThresh);
        float atten = Dsp.dbToLin(headroom.getFloat());

        boolean repaired = false;
        for (int c = 0; c < ch; c++) {
            int f = 2;
            while (f < frames - 2) {
                float s = buf[f * ch + c];
                float eps = Math.max(1e-7f, clipLevel * 1e-4f);
                boolean flatPair = f + 1 < frames - 2
                        && Math.signum(buf[(f + 1) * ch + c]) == Math.signum(s)
                        && Math.abs(buf[(f + 1) * ch + c] - s) <= eps;
                if (Math.abs(s) >= clipLevel && flatPair) {
                    int start = f;
                    int end = f;
                    float sign = s >= 0f ? 1f : -1f;
                    // Extend across the flat plateau only: consecutive samples
                    // must stay within float-rounding distance. A natural crest
                    // curves away from the ceiling and is left untouched.
                    while (end + 1 < frames - 2
                            && Math.abs(buf[(end + 1) * ch + c]) >= clipLevel * 0.98f
                            && Math.signum(buf[(end + 1) * ch + c]) == sign
                            && Math.abs(buf[(end + 1) * ch + c] - s) <= eps) {
                        end++;
                    }
                    int runLen = end - start + 1;
                    if (runLen >= 2 && runLen <= 256) {
                        float y0 = buf[(start - 1) * ch + c];
                        float y1 = buf[(end + 1) * ch + c];
                        float m0 = (y0 - buf[(start - 2) * ch + c]) * (runLen + 1);
                        float m1 = (buf[(end + 2) * ch + c] - y1) * (runLen + 1);
                        for (int i = 0; i < runLen; i++) {
                            float t = (i + 1) / (float) (runLen + 1);
                            float t2 = t * t;
                            float t3 = t2 * t;
                            float h00 = 2 * t3 - 3 * t2 + 1;
                            float h10 = t3 - 2 * t2 + t;
                            float h01 = -2 * t3 + 3 * t2;
                            float h11 = t3 - t2;
                            float arch = h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
                            float minArch = sign * clipLevel
                                    * (1.0f + (0.28f - 0.08f * normThresh) * (float) Math.sin(Math.PI * t));
                            if (sign > 0 && arch < minArch) arch = minArch;
                            if (sign < 0 && arch > minArch) arch = minArch;
                            buf[(start + i) * ch + c] = arch;
                        }
                        repaired = true;
                    }
                    f = end + 1;
                } else {
                    f++;
                }
            }
        }
        // Only spend headroom when a clipped crest was actually rebuilt.
        if (repaired) {
            for (int i = 0; i < total; i++) {
                buf[i] *= atten;
            }
        }
    }
}
