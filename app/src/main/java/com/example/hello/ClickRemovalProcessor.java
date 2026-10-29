package com.example.hello;

/**
 * Stage 5 Click & Pop Removal: detects transient impulses whose second-order
 * curvature and local deviation exceed the surrounding neighborhood by a
 * configurable threshold and replaces them with cubic Hermite interpolation
 * across the surrounding clean samples.
 */
final class ClickRemovalProcessor extends AudioProcessor {

    private final FxParameter threshold = addParameter(FxParameter.intParam(
            "click_thresh", "Threshold", "", 1, 900, 10, 200));
    private final FxParameter width = addParameter(FxParameter.intParam(
            "click_width", "Max Spike Width", " samples", 1, 40, 1, 12));

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
        return "Detects & cubic-interpolates transient clicks, pops and mouth ticks";
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
        if (!isEnabled() || buf == null || frames < 16) return;
        int ch = Math.max(1, Math.min(2, channels));
        int maxWidth = Math.max(1, Math.min(40, width.getCurrentRaw()));
        int rawThresh = threshold.getCurrentRaw();
        // Map 1..900 to a second-difference / local-background ratio threshold (1.8..16.0).
        float ratioThresh = 1.8f + (rawThresh / 900.0f) * 14.2f;
        int halfWin = Math.max(32, Math.min(256, maxWidth * 4));
        if (frames <= halfWin * 2 + 4) {
            halfWin = Math.max(4, frames / 4);
        }

        for (int c = 0; c < ch; c++) {
            int f = halfWin;
            while (f < frames - halfWin) {
                float localDiffSum = 0f;
                int count = 0;
                for (int k = f - halfWin; k < f - maxWidth; k++) {
                    localDiffSum += Math.abs(buf[(k + 1) * ch + c] - buf[k * ch + c]);
                    count++;
                }
                for (int k = f + maxWidth; k < f + halfWin; k++) {
                    localDiffSum += Math.abs(buf[k * ch + c] - buf[(k - 1) * ch + c]);
                    count++;
                }
                float localSlope = count > 0 ? (localDiffSum / count) : 0f;
                float sPrev = buf[(f - 1) * ch + c];
                float sCur = buf[f * ch + c];
                float sNext = buf[(f + 1) * ch + c];
                // Second-difference impulse detector: smooth low-frequency sine
                // waves have tiny curvature relative to localSlope, whereas clicks
                // and pops have a sharp discontinuity.
                float curvature = Math.abs(sCur - 0.5f * (sPrev + sNext));
                if (curvature > (localSlope * ratioThresh + 0.008f)) {
                    int start = Math.max(2, f - 1);
                    int end = f + 1;
                    while (end < Math.min(frames - 3, start + maxWidth)) {
                        float sp = buf[(end - 1) * ch + c];
                        float sc = buf[end * ch + c];
                        float sn = buf[(end + 1) * ch + c];
                        if (Math.abs(sc - 0.5f * (sp + sn)) > localSlope * ratioThresh * 0.5f
                                || Math.abs(sc - sPrev) > localSlope * ratioThresh) {
                            end++;
                        } else {
                            break;
                        }
                    }
                    float p0 = buf[(start - 2) * ch + c];
                    float p1 = buf[(start - 1) * ch + c];
                    float p2 = buf[end * ch + c];
                    float p3 = buf[(end + 1) * ch + c];
                    int span = end - start + 1;
                    for (int i = 0; i < span; i++) {
                        float t = (i + 1) / (float) (span + 1);
                        float c0 = p1;
                        float c1 = 0.5f * (p2 - p0);
                        float c2 = p0 - 2.5f * p1 + 2f * p2 - 0.5f * p3;
                        float c3 = 0.5f * (p3 - p0) + 1.5f * (p1 - p2);
                        buf[(start + i) * ch + c] = ((c3 * t + c2) * t + c1) * t + c0;
                    }
                    f = end + 1;
                } else {
                    f++;
                }
            }
        }
    }
}
