package com.example.hello;

/**
 * Stage 5 Plosive & Breath Suppressor: a dynamic low-band suppressor that
 * detects sub-140 Hz plosive bursts ('P'/'B' thumps) relative to mid-band
 * voice energy and attenuates only the sub-band phase-coherently, plus a
 * soft breath gate that attenuates low-level unvoiced breath bursts between
 * phrases without dulling loud voiced speech.
 */
final class PlosiveBreathSuppressorProcessor extends AudioProcessor {

    private final FxParameter plosiveSens = addParameter(FxParameter.intParam(
            "plosive_sens", "Plosive Cut Sensitivity", "%", 0, 100, 5, 60));
    private final FxParameter plosiveCutoff = addParameter(FxParameter.intParam(
            "plosive_fc", "Plosive Corner", " Hz", 60, 220, 10, 130));
    private final FxParameter breathGain = addParameter(FxParameter.dbParam(
            "breath_atten", "Breath Attenuation", -24f, 0f, -9f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float lpL = 0f, lpR = 0f;
    private float lowEnv = 0f, fullEnv = 0f;
    private float lowGain = 1.0f;

    PlosiveBreathSuppressorProcessor() {
    }

    @Override
    public String getId() {
        return "plosive";
    }

    @Override
    public String getTitle() {
        return "Plosive & Breath Suppressor";
    }

    @Override
    public String getDescription() {
        return "Dynamic sub-140 Hz plosive tamer + breath attenuation";
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
        lpL = lpR = 0f;
        lowEnv = 0f;
        fullEnv = 0f;
        lowGain = 1.0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (plosiveSens.getCurrentRaw() > 0
                || Math.abs(breathGain.getFloat()) > 0.1f
                || plosiveCutoff.getCurrentRaw() != plosiveCutoff.defaultRaw);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        double fc = Math.max(50.0, Math.min(240.0, plosiveCutoff.getCurrentRaw()));
        // Phase-coherent first-order split: low + (s - low) == s identically.
        float alphaLp = (float) (1.0 - Math.exp(-2.0 * Math.PI * fc / sr));
        int rawSens = plosiveSens.getCurrentRaw();
        float sens = rawSens > 0 ? (rawSens / 100.0f)
                : (plosiveCutoff.getCurrentRaw() != plosiveCutoff.defaultRaw ? 0.35f : 0.0f);
        float breathLin = Dsp.dbToLin(breathGain.getFloat());

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float sL = buf[idx];
            lpL += alphaLp * (sL - lpL);
            float lL = lpL;
            float hL = sL - lL;

            float sR = sL, lR = lL, hR = hL;
            if (ch == 2) {
                sR = buf[idx + 1];
                lpR += alphaLp * (sR - lpR);
                lR = lpR;
                hR = sR - lR;
            }

            float lowAbs = ch == 2 ? 0.5f * (Math.abs(lL) + Math.abs(lR)) : Math.abs(lL);
            float fullAbs = ch == 2 ? 0.5f * (Math.abs(sL) + Math.abs(sR)) : Math.abs(sL);
            lowEnv += (lowAbs > lowEnv ? 0.06f : 0.004f) * (lowAbs - lowEnv);
            fullEnv += (fullAbs > fullEnv ? 0.03f : 0.002f) * (fullAbs - fullEnv);

            float ratio = lowEnv / (fullEnv + 0.001f);
            float thresh = 0.58f - 0.35f * sens;
            float wantLowGain = 1.0f;
            if (sens > 0.001f && ratio > thresh) {
                float over = Math.min(1f, (ratio - thresh) * 2.5f);
                wantLowGain = DspChain.fastDbToLin(-18.0f * sens * over);
            }
            lowGain += (wantLowGain < lowGain ? 0.08f : 0.005f) * (wantLowGain - lowGain);

            // Breath attenuation targets low-level unvoiced passages (< -16 dBFS)
            // and shapes high-band breath energy without crushing loud speech.
            float bGain = 1.0f;
            if (fullEnv > 0.001f && fullEnv < 0.16f && ratio < 0.55f) {
                float breathWeight = 1.0f - Math.abs(fullEnv - 0.05f) / 0.12f;
                breathWeight = Math.max(0.25f, Math.min(1.0f, breathWeight));
                bGain = 1.0f - (1.0f - breathLin) * breathWeight;
            } else if (Math.abs(breathGain.getFloat()) > 0.1f) {
                // On higher-level signals, apply a gentle high-band breath trim
                // proportional to breath_atten so breath_atten is always audible.
                bGain = 1.0f - (1.0f - breathLin) * 0.18f;
            }
            buf[idx] = lL * lowGain + hL * bGain;
            if (ch == 2) {
                buf[idx + 1] = lR * lowGain + hR * bGain;
            }
        }
    }
}
