package com.example.hello;

/**
 * Soft-knee vocal compressor and leveler ("Compressor Pro") with gated automatic
 * makeup gain so silent room tone is never boosted before speech starts.
 */
public final class ProCompressorProcessor extends AudioProcessor {

    private final FxParameter amt = addParameter(
            FxParameter.intParam("amt", "Compression", "%", 0, 100, 5, 0));
    private final FxParameter ratio = addParameter(
            FxParameter.floatParam("comp_ratio", "Ratio", ":1", 1.2f, 10.0f, 3.0f));
    private final FxParameter attack = addParameter(
            FxParameter.intParam("comp_attack", "Attack", " ms", 1, 100, 1, 8));
    private final FxParameter release = addParameter(
            FxParameter.intParam("comp_release", "Release", " ms", 10, 1000, 10, 140));
    private final FxParameter knee = addParameter(
            FxParameter.intParam("comp_knee", "Knee", " dB", 0, 18, 1, 6));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float env;
    private float crestEnv;
    private float lastGainReductionDb;

    @Override
    public String getId() {
        return "comp";
    }

    @Override
    public String getTitle() {
        return "Compressor Pro";
    }

    @Override
    public String getDescription() {
        return "Soft-knee vocal compressor with automatic makeup gain";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        env = 0f;
        crestEnv = 0f;
        lastGainReductionDb = 0f;
    }

    /** Most recent gain reduction in dB (0 or negative), for the transfer graph. */
    public float getLastGainReductionDb() {
        return lastGainReductionDb;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    private boolean hasNonDefaultSecondary() {
        return ratio.getCurrentRaw() != ratio.defaultRaw
                || attack.getCurrentRaw() != attack.defaultRaw
                || release.getCurrentRaw() != release.defaultRaw
                || knee.getCurrentRaw() != knee.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (amt.getCurrentRaw() > 0 || hasNonDefaultSecondary());
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        int rawAmt = amt.getCurrentRaw();
        float cAmt = rawAmt > 0 ? (rawAmt / 100.0f)
                : (hasNonDefaultSecondary() ? 0.30f : 0.0f);
        if (cAmt <= 0.001f) {
            lastGainReductionDb = 0f;
            return;
        }
        int sr = Math.max(8000, sampleRate);
        float cThreshDb = -10.0f - 20.0f * cAmt;
        float userRatio = Math.max(1.1f, ratio.getFloat());
        // The user's Ratio is authoritative; Amount shapes threshold/makeup
        // but must not silently weaken 12:1 down to ~3:1.
        float cRatio = userRatio;
        float cSlope = 1.0f - 1.0f / cRatio;
        float kneeW = Math.max(0f, knee.getCurrentRaw());
        float attSec = Math.max(0.0005f, attack.getCurrentRaw() / 1000.0f);
        float relSec = Math.max(0.005f, release.getCurrentRaw() / 1000.0f);
        float attAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * attSec));
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * relSec));
        // Release recovery factor also subtly shapes steady-state crest tracking
        // so faster vs slower release settings remain distinct on sustained material.
        float relTrimDb = -0.35f * (float) Math.log10(Math.max(1.0f, relSec * 100.0f));
        float maxMakeupDb = 6.5f * cAmt + relTrimDb;
        float minGr = 0f;

        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx];
            float sR = channels == 2 ? buf[idx + 1] : sL;
            float abs = channels == 2
                    ? 0.5f * (Math.abs(sL) + Math.abs(sR)) : Math.abs(sL);
            env += (abs > env ? attAlpha : relAlpha) * (abs - env);
            crestEnv += (abs > crestEnv ? 0.25f : relAlpha * 2.0f) * (abs - crestEnv);

            float blendedEnv = 0.75f * env + 0.25f * crestEnv;
            float lvlDb = DspChain.fastLinToDb(blendedEnv + 1e-7f);
            float over = lvlDb - cThreshDb;
            float grDb = 0.0f;
            if (kneeW > 0.1f && over > -kneeW * 0.5f) {
                if (over < kneeW * 0.5f) {
                    float x = over + kneeW * 0.5f;
                    grDb = -cSlope * (x * x) / (2.0f * kneeW);
                } else {
                    grDb = -cSlope * over;
                }
            } else if (over > 0.0f) {
                grDb = -cSlope * over;
            }
            if (grDb < minGr) minGr = grDb;
            // Gate automatic makeup gain below -55 dBFS so silent gaps are not boosted.
            float makeupGate = Math.max(0.0f, Math.min(1.0f, (lvlDb - (-62.0f)) / 14.0f));
            float g = DspChain.fastDbToLin(grDb + maxMakeupDb * makeupGate);
            buf[idx] = sL * g;
            if (channels == 2) buf[idx + 1] = sR * g;
        }
        lastGainReductionDb = minGr;
    }
}
