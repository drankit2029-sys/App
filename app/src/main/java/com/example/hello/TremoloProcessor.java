package com.example.hello;

/**
 * Stage 6 Tremolo (LFO Amplitude Modulator) with 5 waveforms (Sine, Triangle,
 * Sawtooth, Inverse Sawtooth, Square), Depth (0..100%), Rate (0.1..20 Hz) and
 * Stereo Phase (0..360°).
 */
final class TremoloProcessor extends AudioProcessor {

    private static final String[] WAVE_LABELS = {
            "Sine", "Triangle", "Sawtooth", "Inverse Sawtooth", "Square"
    };

    private final FxParameter wave = addParameter(FxParameter.enumParam(
            "tr_wave", "Waveform", WAVE_LABELS, 0));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "tr_depth", "Depth", "%", 0, 100, 5, 50));
    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "tr_rate", "Rate", " Hz", 0.1f, 20f, 4.0f));
    private final FxParameter stereoPhase = addParameter(FxParameter.intParam(
            "tr_phase", "Starting / Stereo Phase", "°", 0, 360, 15, 0));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private double phase = 0.0;

    TremoloProcessor() {
    }

    @Override
    public String getId() {
        return "tremolo";
    }

    @Override
    public String getTitle() {
        return "Tremolo";
    }

    @Override
    public String getDescription() {
        return "5-waveform LFO amplitude modulator with stereo phase";
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
        phase = 0.0;
    }

    private boolean hasSecondaryChange() {
        return wave.getCurrentRaw() != wave.defaultRaw
                || rate.getCurrentRaw() != rate.defaultRaw
                || stereoPhase.getCurrentRaw() != stereoPhase.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (depth.getCurrentRaw() > 0 || hasSecondaryChange());
    }

    private static float lfo01(double normPhase, int waveType) {
        double p = normPhase - Math.floor(normPhase);
        switch (waveType) {
            case 1: // Triangle
                return (float) (p < 0.5 ? 2.0 * p : 2.0 * (1.0 - p));
            case 2: // Sawtooth
                return (float) p;
            case 3: // Inverse Sawtooth
                return (float) (1.0 - p);
            case 4: // Square
                return p < 0.5 ? 1.0f : 0.0f;
            case 0: // Sine
            default:
                return (float) (0.5 * (1.0 + Math.sin(2.0 * Math.PI * p)));
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int rawDepth = depth.getCurrentRaw();
        float d = rawDepth > 0 ? (rawDepth / 100f)
                : (hasSecondaryChange() ? 0.25f : 0f);
        if (d <= 0.001f) return;
        int w = wave.getEnumIndex();
        double inc = rate.getFloat() / Math.max(8000, sampleRate);
        double offset = stereoPhase.getCurrentRaw() / 360.0;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                double ph = phase + ((c == 1 || ch == 1) ? offset : 0.0);
                float mod = lfo01(ph, w);
                float gain = (1.0f - d) + d * mod;
                buf[idx + c] *= gain;
            }
            phase += inc;
            if (phase >= 1.0) phase -= 1.0;
        }
    }
}
