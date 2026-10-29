package com.example.hello;

/**
 * Split-band relative sibilance suppressor ("De-Esser Pro"). Uses a phase-coherent
 * resonant band-pass detector/subtractor in Split-band mode (or broadband attenuation
 * in Broadband mode) so sibilance is reduced without phase-cancelling or boosting
 * the 3.5 kHz crossover skirts.
 */
public final class ProDeEsserProcessor extends AudioProcessor {

    private final FxParameter amt = addParameter(
            FxParameter.intParam("amt", "Reduction", "%", 0, 100, 5, 0));
    private final FxParameter freq = addParameter(
            FxParameter.intParam("deess_freq", "Center Freq", " Hz", 3500, 10000, 250, 6500));
    private final FxParameter width = addParameter(
            FxParameter.intParam("deess_width", "Bandwidth", "%", 10, 100, 5, 50));
    private final FxParameter mode = addParameter(
            FxParameter.enumParam("deess_mode", "Mode",
                    new String[]{"Split-band", "Broadband"}, 0));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float bpB0, bpB1, bpB2, bpA1, bpA2;
    private int cachedFreq = -1;
    private int cachedWidth = -1;
    private int cachedSr = -1;

    private float bpZ1L, bpZ2L;
    private float bpZ1R, bpZ2R;
    private float sibEnv, fullEnv;

    public ProDeEsserProcessor() {
        updateCoefficients();
    }

    private void updateCoefficients() {
        int fc = Math.max(2000, Math.min(12000, freq.getCurrentRaw()));
        int wPct = Math.max(10, Math.min(100, width.getCurrentRaw()));
        int sr = Math.max(8000, sampleRate);
        if (fc == cachedFreq && wPct == cachedWidth && sr == cachedSr) return;
        cachedFreq = fc;
        cachedWidth = wPct;
        cachedSr = sr;

        double w01 = (wPct - 10) / 90.0;
        double q = Math.max(0.35, 2.4 - 1.85 * w01);
        float[] coeffs = Dsp.rbjBandPass(sr, Math.min(fc, sr * 0.45), q);
        bpB0 = coeffs[0];
        bpB1 = coeffs[1];
        bpB2 = coeffs[2];
        bpA1 = coeffs[3];
        bpA2 = coeffs[4];
    }

    @Override
    public String getId() {
        return "deess";
    }

    @Override
    public String getTitle() {
        return "De-Esser Pro";
    }

    @Override
    public String getDescription() {
        return "Split-band relative sibilance suppressor";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        cachedSr = -1;
        updateCoefficients();
    }

    @Override
    public void reset() {
        bpZ1L = bpZ2L = 0f;
        bpZ1R = bpZ2R = 0f;
        sibEnv = fullEnv = 0f;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    private boolean hasNonDefaultSecondary() {
        return freq.getCurrentRaw() != freq.defaultRaw
                || width.getCurrentRaw() != width.defaultRaw
                || mode.getCurrentRaw() != mode.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (amt.getCurrentRaw() > 0 || hasNonDefaultSecondary());
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        int rawAmt = amt.getCurrentRaw();
        float dAmt = rawAmt > 0 ? (rawAmt / 100.0f)
                : (hasNonDefaultSecondary() ? 0.30f : 0.0f);
        if (dAmt <= 0.001f || frames <= 0) return;

        updateCoefficients();
        boolean broadband = mode.getEnumIndex() == 1;
        float maxCutDb = 20.0f * dAmt;
        float ratioThresh = 0.28f - 0.14f * dAmt;

        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx];
            float sibL = bpB0 * sL + bpZ1L;
            bpZ1L = bpB1 * sL - bpA1 * sibL + bpZ2L;
            bpZ2L = bpB2 * sL - bpA2 * sibL;

            float sR = sL;
            float sibR = sibL;
            if (channels == 2) {
                sR = buf[idx + 1];
                sibR = bpB0 * sR + bpZ1R;
                bpZ1R = bpB1 * sR - bpA1 * sibR + bpZ2R;
                bpZ2R = bpB2 * sR - bpA2 * sibR;
            }

            float sibAbs = channels == 2
                    ? 0.5f * (Math.abs(sibL) + Math.abs(sibR))
                    : Math.abs(sibL);
            float fullAbs = channels == 2
                    ? 0.5f * (Math.abs(sL) + Math.abs(sR))
                    : Math.abs(sL);
            sibEnv += (sibAbs > sibEnv ? 0.08f : 0.004f) * (sibAbs - sibEnv);
            fullEnv += (fullAbs > fullEnv ? 0.04f : 0.0015f) * (fullAbs - fullEnv);

            float sibRatio = sibEnv / (fullEnv + 0.0015f);
            float g = 1.0f;
            if (sibRatio > ratioThresh) {
                float over = Math.min(1.0f, (sibRatio - ratioThresh) * 2.2f);
                g = DspChain.fastDbToLin(-maxCutDb * over);
            }
            if (broadband) {
                buf[idx] = sL * g;
                if (channels == 2) buf[idx + 1] = sR * g;
            } else {
                // Phase-coherent sibilance band subtraction:
                // at g=1 output is exact dry sL; at g<1 only the resonant band is attenuated.
                float cut = 1.0f - g;
                buf[idx] = sL - sibL * cut;
                if (channels == 2) {
                    buf[idx + 1] = sR - sibR * cut;
                }
            }
        }
    }
}
