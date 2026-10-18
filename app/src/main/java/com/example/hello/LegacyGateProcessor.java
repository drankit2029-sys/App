package com.example.hello;

/**
 * Stage 3 of the legacy voice chain: dedicated downward expander / room gate.
 * Body is a verbatim extraction of the original {@code DspChain} stage so
 * output stays bit-identical.
 */
final class LegacyGateProcessor extends AudioProcessor {

    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Threshold", "%", 0, 100, 1, 40));

    private float env = 0.0f;
    private float gainCur = 1.0f;
    private float gainTarget = 1.0f;

    @Override
    public String getId() {
        return "gate";
    }

    @Override
    public String getTitle() {
        return "Expander / Room Gate";
    }

    @Override
    public String getDescription() {
        return "Attenuates room echo and breaths between phrases";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
        env = 0f;
        gainCur = 1.0f;
        gainTarget = 1.0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt01() > 0.001f;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    /** Raw amount is a percent; gain math needs a 0..1 fraction. */
    private float amt01() {
        return amt.getCurrentRaw() / 100f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float gAmt = amt01();
        if (gAmt <= 0.001f) return;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 3: Dedicated Downward Expander / Room Gate
            float blkPeak = 0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float a = Math.abs(buf[idx + c]);
                    if (a > blkPeak) blkPeak = a;
                }
            }
            float alphaG = blkPeak > env ? 0.35f : 0.015f;
            env += alphaG * (blkPeak - env);
            // Threshold ranges from -52 dBFS (0.0025) to -18 dBFS (0.125)
            float threshLin = DspChain.fastDbToLin(-52.0f + 34.0f * gAmt);
            if (env >= threshLin) {
                gainTarget = 1.0f;
            } else {
                float ratio = env / Math.max(1e-6f, threshLin);
                float minFloor = 1.0f - 0.92f * gAmt;
                gainTarget = Math.max(minFloor, ratio * ratio);
            }
            float dGate = (gainTarget - gainCur) * INV_DECIM;
            for (int f = 0; f < blk; f++) {
                gainCur += dGate;
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] *= gainCur;
                }
            }

            offset += blk;
        }
    }
}
