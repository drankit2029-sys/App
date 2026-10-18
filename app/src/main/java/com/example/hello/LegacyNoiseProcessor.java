package com.example.hello;

/**
 * Stage 2 of the legacy voice chain: 3-band adaptive (minimum-statistics)
 * noise suppressor. Body is a verbatim extraction of the original
 * {@code DspChain} stage so output stays bit-identical, including the
 * per-DECIM-block gain smoothing.
 */
final class LegacyNoiseProcessor extends AudioProcessor {

    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;
    private static final float NS_ALPHA_LOW = 0.02839f;
    private static final float NS_ALPHA_HIGH = 0.3919f;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Intensity", "%", 0, 100, 1, 50));

    private final float[] lp1 = new float[2];
    private final float[] lp2 = new float[2];
    private final float[] env = {1e-5f, 1e-5f, 1e-5f};
    private final float[] floor = {1e-4f, 1e-4f, 1e-4f};
    private boolean seeded = false;
    private final float[] gain = {1.0f, 1.0f, 1.0f};
    private final float[] targetGain = {1.0f, 1.0f, 1.0f};
    private final float[] bandAcc = new float[3];
    private final float[] scratchLow = new float[DECIM * 2];
    private final float[] scratchMid = new float[DECIM * 2];
    private final float[] scratchHigh = new float[DECIM * 2];

    @Override
    public String getId() {
        return "noise";
    }

    @Override
    public String getTitle() {
        return "Noise Suppression";
    }

    @Override
    public String getDescription() {
        return "3-band adaptive floor gate for hiss & background hum";
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
        for (int c = 0; c < 2; c++) {
            lp1[c] = 0f;
            lp2[c] = 0f;
        }
        for (int b = 0; b < 3; b++) {
            env[b] = 1e-5f;
            floor[b] = 1e-4f;
            gain[b] = 1.0f;
            targetGain[b] = 1.0f;
        }
        seeded = false;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt.getFloat() > 0.001f;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        final int ch = channels;
        final float nAmt = amt.getFloat();
        if (nAmt <= 0.001f || frames <= 0) return;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 2: 3-Band Adaptive Noise Suppressor
            bandAcc[0] = bandAcc[1] = bandAcc[2] = 0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                int sIdx = f * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    lp1[c] += NS_ALPHA_LOW * (x - lp1[c]);
                    lp2[c] += NS_ALPHA_HIGH * (x - lp2[c]);
                    float bLow = lp1[c];
                    float bMid = lp2[c] - bLow;
                    float bHigh = x - lp2[c];
                    scratchLow[sIdx + c] = bLow;
                    scratchMid[sIdx + c] = bMid;
                    scratchHigh[sIdx + c] = bHigh;
                    bandAcc[0] += bLow * bLow;
                    bandAcc[1] += bMid * bMid;
                    bandAcc[2] += bHigh * bHigh;
                }
            }
            float invSamples = 1.0f / (blk * ch);
            if (!seeded) {
                seeded = true;
                for (int b = 0; b < 3; b++) {
                    float e = Math.max(1e-7f, bandAcc[b] * invSamples);
                    env[b] = e;
                    floor[b] = Math.max(1e-6f, e * 0.35f);
                }
            }
            for (int b = 0; b < 3; b++) {
                float e = bandAcc[b] * invSamples;
                env[b] = 0.60f * env[b] + 0.40f * e;
                if (env[b] < floor[b]) {
                    floor[b] = 0.75f * floor[b] + 0.25f * env[b];
                } else {
                    floor[b] = floor[b] * 1.0006f + 1e-9f;
                }
                float snr = env[b] / (floor[b] + 1e-10f);
                float overSub = 1.0f + 3.8f * nAmt;
                float g = (snr - overSub) / Math.max(snr, 1e-6f);
                float floorG = (b == 1)
                        ? (1.0f - 0.85f * nAmt)
                        : (1.0f - 0.94f * nAmt);
                g = Math.max(floorG, Math.min(1.0f, g));
                float smooth = g > targetGain[b] ? 0.40f
                        : (b == 2 ? 0.10f : 0.045f);
                targetGain[b] += smooth * (g - targetGain[b]);
            }
            float dG0 = (targetGain[0] - gain[0]) * INV_DECIM;
            float dG1 = (targetGain[1] - gain[1]) * INV_DECIM;
            float dG2 = (targetGain[2] - gain[2]) * INV_DECIM;
            for (int f = 0; f < blk; f++) {
                gain[0] += dG0;
                gain[1] += dG1;
                gain[2] += dG2;
                int idx = (offset + f) * ch;
                int sIdx = f * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] = gain[0] * scratchLow[sIdx + c]
                            + gain[1] * scratchMid[sIdx + c]
                            + gain[2] * scratchHigh[sIdx + c];
                }
            }

            offset += blk;
        }
    }
}
