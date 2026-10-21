package com.example.hello;

/**
 * Stage 6 multi-stage all-pass phaser with an LFO sweep, feedback and an
 * output gain trim.
 */
final class PhaserProcessor extends AudioProcessor {

    private static final int[] STAGE_OPTIONS = {2, 4, 6, 8, 10, 12, 16, 24};

    private final FxParameter stages = addParameter(FxParameter.enumParam(
            "ph_stages", "Stages", new String[]{"2", "4", "6", "8", "10", "12",
                    "16", "24"}, 1));
    private final FxParameter mix = addParameter(FxParameter.intParam(
            "ph_mix", "Dry/Wet", "%", 0, 100, 1, 50));
    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "ph_rate", "LFO Frequency", " Hz", 0.05f, 4f, 0.4f));
    private final FxParameter phase = addParameter(FxParameter.intParam(
            "ph_phase", "LFO Start Phase", " deg", 0, 360, 5, 0));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "ph_depth", "Depth", "%", 0, 100, 1, 70));
    private final FxParameter feedback = addParameter(FxParameter.intParam(
            "ph_fb", "Feedback", "%", -95, 95, 1, 30));
    private final FxParameter outputGain = addParameter(FxParameter.dbParam(
            "ph_gain", "Output Gain", -18f, 18f, 0f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private float[] z1 = new float[2];
    private float[] z2 = new float[2];
    private double lfoPhase = 0;
    private float fbState = 0f;

    PhaserProcessor() {
    }

    @Override
    public String getId() {
        return "phaser";
    }

    @Override
    public String getTitle() {
        return "Phaser";
    }

    @Override
    public String getDescription() {
        return "Multi-stage all-pass phaser with LFO and feedback";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
    }

    @Override
    public void reset() {
        z1[0] = z1[1] = 0f;
        z2[0] = z2[1] = 0f;
        fbState = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && mix.getCurrentRaw() > 0;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int n = STAGE_OPTIONS[Math.max(0, Math.min(STAGE_OPTIONS.length - 1,
                stages.getEnumIndex()))];
        float wet = mix.getCurrentRaw() / 100f;
        float dry = 1f - wet;
        double rateHz = rate.getFloat();
        double depthPct = depth.getCurrentRaw() / 100.0;
        double fb = feedback.getCurrentRaw() / 100.0;
        double phase0 = phase.getCurrentRaw() * Math.PI / 180.0;
        float gain = Dsp.dbToLin(outputGain.getFloat());
        double lfoInc = rateHz / sampleRate;

        for (int f = 0; f < frames; f++) {
            double sweep = 0.5 + 0.5 * Math.sin(2 * Math.PI * (lfoPhase + phase0));
            double freq = 200.0 + sweep * depthPct * 4000.0;
            double tanA = Math.tan(Math.PI * freq / sampleRate);
            double coef = (1.0 - tanA) / (1.0 + tanA);
            for (int c = 0; c < ch; c++) {
                int idx = f * ch + c;
                float drySample = buf[idx];
                float x = drySample + fbState;
                // First-order all-pass: y = a*x + x1 - a*y1
                float ap = (float) coef;
                float y = ap * x + z1[c];
                z1[c] = x - ap * y;
                float out = y;
                for (int s = 1; s < n; s++) {
                    float t = ap * out + z2[c];
                    z2[c] = out - ap * t;
                    out = t;
                }
                fbState = out * (float) fb;
                buf[idx] = (drySample * dry + out * wet) * gain;
            }
            lfoPhase += lfoInc;
            if (lfoPhase > 1.0) lfoPhase -= 1.0;
        }
    }
}
