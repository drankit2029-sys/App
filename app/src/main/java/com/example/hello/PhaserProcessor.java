package com.example.hello;

/**
 * Stage 6 Multi-Stage All-Pass Phaser: 2 to 24 cascaded first-order all-pass
 * stages swept across 200..4200 Hz by a stereo LFO with Rate, Depth, Center
 * Frequency, Feedback (-95..+95%), Stereo Phase offset, and Dry/Wet mix.
 */
final class PhaserProcessor extends AudioProcessor {

    private final FxParameter stages = addParameter(FxParameter.intParam(
            "ph_stages", "Stages", "", 2, 24, 2, 6));
    private final FxParameter mix = addParameter(FxParameter.intParam(
            "ph_mix", "Dry/Wet", "%", 0, 100, 5, 50));
    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "ph_rate", "LFO Rate", " Hz", 0.05f, 10f, 0.6f));
    private final FxParameter phase = addParameter(FxParameter.intParam(
            "ph_phase", "Stereo Phase", "°", 0, 360, 15, 90));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "ph_depth", "Depth", "%", 0, 100, 5, 70));
    private final FxParameter feedback = addParameter(FxParameter.intParam(
            "ph_fb", "Feedback", "%", -95, 95, 5, 40));
    private final FxParameter centerFreq = addParameter(FxParameter.intParam(
            "ph_freq", "Center Freq", " Hz", 150, 4000, 50, 800));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private final float[][] apState = new float[2][24];
    private final float[] fbSample = new float[2];
    private double lfoPhase = 0.0;

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
        return "2–24 stage all-pass phaser with stereo LFO & feedback";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            for (int s = 0; s < 24; s++) apState[c][s] = 0f;
            fbSample[c] = 0f;
        }
        lfoPhase = 0.0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (mix.getCurrentRaw() > 0
                || stages.getCurrentRaw() != stages.defaultRaw
                || rate.getCurrentRaw() != rate.defaultRaw
                || phase.getCurrentRaw() != phase.defaultRaw
                || depth.getCurrentRaw() != depth.defaultRaw
                || feedback.getCurrentRaw() != feedback.defaultRaw
                || centerFreq.getCurrentRaw() != centerFreq.defaultRaw);
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        int nStages = Math.max(2, Math.min(24, (stages.getCurrentRaw() / 2) * 2));
        int rawMix = mix.getCurrentRaw();
        float wet = rawMix > 0 ? (rawMix / 100f) : 0.25f;
        float dry = 1f - wet * 0.5f;
        int rawDepth = depth.getCurrentRaw();
        float d = rawDepth > 0 ? (rawDepth / 100f)
                : ((rate.getCurrentRaw() != rate.defaultRaw
                        || phase.getCurrentRaw() != phase.defaultRaw) ? 0.20f : 0f);
        float fb = (feedback.getCurrentRaw() / 100f) * 0.70f;
        double baseF = centerFreq.getCurrentRaw();
        double lfoInc = 2.0 * Math.PI * rate.getFloat() / sr;
        double stereoRad = Math.toRadians(phase.getCurrentRaw());

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                double ph = lfoPhase + ((c == 1 || ch == 1) ? stereoRad : 0.0);
                double mod = 0.5 * (1.0 + Math.sin(ph));
                double fHz = Math.max(120.0, Math.min(sr * 0.42,
                        baseF * Math.pow(2.0, (mod - 0.5) * 3.0 * d)));
                double tanA = Math.tan(Math.PI * fHz / sr);
                // First-order all-pass coefficient placing the 90-degree phase
                // shift directly at fHz (ap = (tan - 1)/(tan + 1) < 0 for fHz < sr/4).
                float ap = (float) ((tanA - 1.0) / (tanA + 1.0));
                float in = buf[idx + c];
                float x = in + fbSample[c] * fb;
                for (int s = 0; s < nStages; s++) {
                    float y = ap * x + apState[c][s];
                    apState[c][s] = x - ap * y;
                    x = y;
                }
                if (!Float.isFinite(x)) x = 0f;
                fbSample[c] = Math.max(-2.0f, Math.min(2.0f, x));
                buf[idx + c] = in * dry + x * wet;
            }
            lfoPhase += lfoInc;
            if (lfoPhase > 2.0 * Math.PI) lfoPhase -= 2.0 * Math.PI;
        }
    }
}
