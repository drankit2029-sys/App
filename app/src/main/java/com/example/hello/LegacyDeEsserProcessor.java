package com.example.hello;

/**
 * Stage 5 of the legacy voice chain: split-band relative-sidechain de-esser
 * (6.8 kHz bandpass, Q=1.5). Body is a verbatim extraction of the original
 * {@code DspChain} stage so output stays bit-identical.
 */
final class LegacyDeEsserProcessor extends AudioProcessor {

    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Reduction", "%", 0, 100, 1, 45));

    private volatile float b0, b1, b2, a1, a2;
    private volatile int currentSampleRate = -1;
    private final float[] z1 = new float[2];
    private final float[] z2 = new float[2];
    private float sibEnv = 1e-7f;
    private float fullEnv = 1e-6f;
    private float cutCur = 0.0f;
    private float cutTarget = 0.0f;
    private final float[] scratchSib = new float[DECIM * 2];

    LegacyDeEsserProcessor() {
        computeCoefficients(DspChain.SAMPLE_RATE);
    }

    private void computeCoefficients(int sr) {
        int validSr = sr > 0 ? sr : DspChain.SAMPLE_RATE;
        if (validSr == this.currentSampleRate) return;
        this.currentSampleRate = validSr;
        double dw0 = 2.0 * Math.PI * 6800.0 / validSr;
        double dCos = Math.cos(dw0);
        double dAlpha = Math.sin(dw0) / (2.0 * 1.5);
        double da0Inv = 1.0 / (1.0 + dAlpha);
        this.b0 = (float) (dAlpha * da0Inv);
        this.b1 = 0.0f;
        this.b2 = (float) (-dAlpha * da0Inv);
        this.a1 = (float) (-2.0 * dCos * da0Inv);
        this.a2 = (float) ((1.0 - dAlpha) * da0Inv);
    }

    @Override
    public String getId() {
        return "deess";
    }

    @Override
    public String getTitle() {
        return "De-Esser (4.5–9.5 kHz)";
    }

    @Override
    public String getDescription() {
        return "Split-band sibilance control for harsh 'S' & 'T' sounds";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        computeCoefficients(sampleRate);
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            z1[c] = 0f;
            z2[c] = 0f;
        }
        sibEnv = 1e-7f;
        fullEnv = 1e-6f;
        cutCur = 0.0f;
        cutTarget = 0.0f;
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
        final float dAmt = amt01();
        if (dAmt <= 0.001f) return;

        final float fb0 = this.b0, fb1 = this.b1, fb2 = this.b2;
        final float fa1 = this.a1, fa2 = this.a2;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 5: Split-Band Relative Sidechain De-Esser
            float sibEnergy = 0f;
            float fullEnergy = 0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                int sIdx = f * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    float sib = fb0 * x + z1[c];
                    z1[c] = fb1 * x - fa1 * sib + z2[c];
                    z2[c] = fb2 * x - fa2 * sib;
                    scratchSib[sIdx + c] = sib;
                    sibEnergy += sib * sib;
                    fullEnergy += x * x;
                }
            }
            float invN = 1.0f / (blk * ch);
            sibEnv = 0.50f * sibEnv + 0.50f * (sibEnergy * invN);
            fullEnv = 0.70f * fullEnv + 0.30f * (fullEnergy * invN);
            float ratioDb = DspChain.fastLinToDb(sibEnv + 1e-10f) * 0.5f
                    - DspChain.fastLinToDb(fullEnv + 1e-9f) * 0.5f;
            float threshDb = 1.0f - 11.5f * dAmt;
            float wantCut = 0f;
            if (ratioDb > threshDb && fullEnv > 1e-7f) {
                float overDb = Math.min(14.0f, (ratioDb - threshDb) * 1.4f);
                wantCut = 1.0f - DspChain.fastDbToLin(-overDb);
            }
            float alphaDe = wantCut > cutTarget ? 0.50f : 0.12f;
            cutTarget += alphaDe * (wantCut - cutTarget);
            float dCut = (cutTarget - cutCur) * INV_DECIM;
            for (int f = 0; f < blk; f++) {
                cutCur += dCut;
                int idx = (offset + f) * ch;
                int sIdx = f * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] -= cutCur * scratchSib[sIdx + c];
                }
            }

            offset += blk;
        }
    }
}
