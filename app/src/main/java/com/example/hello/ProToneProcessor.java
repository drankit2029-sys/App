package com.example.hello;

/**
 * Combined warmth (~180 Hz body) and vocal presence (~3.5 kHz articulation
 * & air) shelves. Supports role-scoped instantiation ({@code "warmth"} or
 * {@code "presence"}) so standalone or prototype inspection exposes each
 * effect's exact parameters while {@code "tone"} serves the shared rack stage.
 */
public final class ProToneProcessor extends AudioProcessor {

    private final String role;
    private final FxParameter warmth;
    private final FxParameter warmthFreq;
    private final FxParameter presence;
    private final FxParameter presenceFreq;

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float warmLP_L, warmLP_R;
    private float presLP_L, presLP_R;
    private float warmthSlotMix = 1.0f;
    private float presenceSlotMix = 1.0f;

    public ProToneProcessor() {
        this("tone");
    }

    public ProToneProcessor(String role) {
        this.role = (role == null || role.isEmpty()) ? "tone" : role;
        if ("warmth".equals(this.role)) {
            warmth = addParameter(
                    FxParameter.intParam("warmth", "Warmth", "%", 0, 100, 5, 40));
            warmthFreq = addParameter(
                    FxParameter.intParam("warmth_freq", "Warmth Corner", " Hz", 80, 350, 10, 180));
            presence = FxParameter.intParam("presence", "Presence", "%", 0, 100, 5, 0);
            presenceFreq = FxParameter.intParam("presence_freq", "Presence Corner", " Hz", 1500, 6500, 100, 3500);
        } else if ("presence".equals(this.role)) {
            presence = addParameter(
                    FxParameter.intParam("presence", "Presence", "%", 0, 100, 5, 45));
            presenceFreq = addParameter(
                    FxParameter.intParam("presence_freq", "Presence Corner", " Hz", 1500, 6500, 100, 3500));
            warmth = FxParameter.intParam("warmth", "Warmth", "%", 0, 100, 5, 0);
            warmthFreq = FxParameter.intParam("warmth_freq", "Warmth Corner", " Hz", 80, 350, 10, 180);
        } else {
            warmth = addParameter(
                    FxParameter.intParam("warmth", "Warmth", "%", 0, 100, 5, 0));
            warmthFreq = addParameter(
                    FxParameter.intParam("warmth_freq", "Warmth Corner", " Hz", 80, 350, 10, 180));
            presence = addParameter(
                    FxParameter.intParam("presence", "Presence", "%", 0, 100, 5, 0));
            presenceFreq = addParameter(
                    FxParameter.intParam("presence_freq", "Presence Corner", " Hz", 1500, 6500, 100, 3500));
        }
    }

    @Override
    public FxParameter getParam(String id) {
        FxParameter found = super.getParam(id);
        if (found != null) return found;
        if ("warmth".equals(id)) return warmth;
        if ("warmth_freq".equals(id)) return warmthFreq;
        if ("presence".equals(id)) return presence;
        if ("presence_freq".equals(id)) return presenceFreq;
        return null;
    }

    void setWarmthSlotMix(float mix0to1) {
        this.warmthSlotMix = Math.max(0f, Math.min(1f, mix0to1));
    }

    void setPresenceSlotMix(float mix0to1) {
        this.presenceSlotMix = Math.max(0f, Math.min(1f, mix0to1));
    }

    @Override
    public String getId() {
        return "tone";
    }

    @Override
    public String getTitle() {
        if ("warmth".equals(role)) return "Warmth & Chest Body";
        if ("presence".equals(role)) return "Vocal Presence & Air";
        return "Warmth & Vocal Presence";
    }

    @Override
    public String getDescription() {
        return "180 Hz chest body + 3.5 kHz articulation & air boost";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        warmLP_L = warmLP_R = 0f;
        presLP_L = presLP_R = 0f;
    }

    void setLegacyAmounts(int warmthPct, int presencePct) {
        warmth.setCurrentRaw(warmthPct);
        presence.setCurrentRaw(presencePct);
    }

    private float effectiveWarmthAmt() {
        int w = warmth.getCurrentRaw();
        if (w > 0) return w / 100.0f;
        if (!"presence".equals(role) && warmthFreq.getCurrentRaw() != warmthFreq.defaultRaw) {
            return 0.25f;
        }
        return 0.0f;
    }

    private float effectivePresenceAmt() {
        int p = presence.getCurrentRaw();
        if (p > 0) return p / 100.0f;
        if (!"warmth".equals(role) && presenceFreq.getCurrentRaw() != presenceFreq.defaultRaw) {
            return 0.25f;
        }
        return 0.0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (effectiveWarmthAmt() > 0f || effectivePresenceAmt() > 0f);
    }

    private float warmAlpha() {
        int sr = Math.max(8000, sampleRate);
        double fc = Math.max(60.0, Math.min(500.0, warmthFreq.getCurrentRaw()));
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * fc / sr));
    }

    private float presAlpha() {
        int sr = Math.max(8000, sampleRate);
        double fc = Math.max(1000.0, Math.min(8000.0, presenceFreq.getCurrentRaw()));
        return (float) (1.0 - Math.exp(-2.0 * Math.PI * fc / sr));
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        float wAmt = effectiveWarmthAmt();
        float pAmt = effectivePresenceAmt();
        if (wAmt <= 0.001f && pAmt <= 0.001f) return;

        float warmGain = (DspChain.fastDbToLin(9.0f * wAmt) - 1.0f) * warmthSlotMix;
        float presGain = (DspChain.fastDbToLin(10.0f * pAmt) - 1.0f) * presenceSlotMix;
        float wA = warmAlpha();
        float pA = presAlpha();

        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx];
            float sR = channels == 2 ? buf[idx + 1] : sL;
            if (wAmt > 0.001f) {
                warmLP_L += wA * (sL - warmLP_L);
                sL += warmLP_L * warmGain;
                if (channels == 2) {
                    warmLP_R += wA * (sR - warmLP_R);
                    sR += warmLP_R * warmGain;
                }
            }
            if (pAmt > 0.001f) {
                presLP_L += pA * (sL - presLP_L);
                float hiL = sL - presLP_L;
                sL += hiL * presGain;
                if (channels == 2) {
                    presLP_R += pA * (sR - presLP_R);
                    float hiR = sR - presLP_R;
                    sR += hiR * presGain;
                }
            }
            buf[idx] = sL;
            if (channels == 2) buf[idx + 1] = sR;
        }
    }

    void processWarmthOnly(float[] buf, int frames, int channels, float mix0to1) {
        float wAmt = effectiveWarmthAmt();
        float m = Math.max(0f, Math.min(1f, mix0to1));
        if (wAmt <= 0.001f || m <= 0.001f || frames <= 0) return;
        float warmGain = (DspChain.fastDbToLin(9.0f * wAmt) - 1.0f) * m;
        float wA = warmAlpha();
        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx];
            warmLP_L += wA * (sL - warmLP_L);
            buf[idx] = sL + warmLP_L * warmGain;
            if (channels == 2) {
                float sR = buf[idx + 1];
                warmLP_R += wA * (sR - warmLP_R);
                buf[idx + 1] = sR + warmLP_R * warmGain;
            }
        }
    }

    void processPresenceOnly(float[] buf, int frames, int channels, float mix0to1) {
        float pAmt = effectivePresenceAmt();
        float m = Math.max(0f, Math.min(1f, mix0to1));
        if (pAmt <= 0.001f || m <= 0.001f || frames <= 0) return;
        float presGain = (DspChain.fastDbToLin(10.0f * pAmt) - 1.0f) * m;
        float pA = presAlpha();
        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx];
            presLP_L += pA * (sL - presLP_L);
            buf[idx] = sL + (sL - presLP_L) * presGain;
            if (channels == 2) {
                float sR = buf[idx + 1];
                presLP_R += pA * (sR - presLP_R);
                buf[idx + 1] = sR + (sR - presLP_R) * presGain;
            }
        }
    }
}
