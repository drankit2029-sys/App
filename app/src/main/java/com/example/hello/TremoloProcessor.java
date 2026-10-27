package com.example.hello;

/**
 * Stage 6 tremolo: multiplies the amplitude with an LFO of the selected
 * shape.
 */
final class TremoloProcessor extends AudioProcessor {

    private static final String[] WAVEFORMS = {"Sine", "Triangle", "Sawtooth",
            "Inverse Sawtooth", "Square"};

    private final FxParameter waveform = addParameter(FxParameter.enumParam(
            "tr_wave", "Waveform", WAVEFORMS, 0));
    private final FxParameter phase = addParameter(FxParameter.intParam(
            "tr_phase", "Starting Phase", " deg", 0, 355, 5, 0));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "tr_depth", "Depth", "%", 0, 100, 1, 40));
    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "tr_rate", "Frequency", " Hz", 0.1f, 40f, 4f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private double lfoPhase = 0;

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
        return "Amplitude modulation with five LFO waveforms";
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
        lfoPhase = 0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && depth.getCurrentRaw() > 0;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int n = frames * ch;
        double inc = rate.getFloat() / sampleRate;
        double phase0 = phase.getCurrentRaw() / 360.0;
        double depthPct = depth.getCurrentRaw() / 100.0;
        int wave = waveform.getEnumIndex();

        for (int f = 0; f < frames; f++) {
            float lfo = lfoValue(wave, lfoPhase + phase0);
            float gain = 1f - (float) depthPct
                    + (float) depthPct * (0.5f * (1f + lfo));
            int idx = f * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= gain;
            lfoPhase += inc;
            if (lfoPhase > 1.0) lfoPhase -= 1.0;
        }
    }

    /** LFO value in -1..1 for the selected shape. */
    static float lfoValue(int waveform, double phase) {
        double p = phase - Math.floor(phase);
        switch (waveform) {
            case 1: { // Triangle
                return (float) (p < 0.5 ? 4 * p - 1 : 3 - 4 * p);
            }
            case 2: { // Sawtooth
                return (float) (2 * p - 1);
            }
            case 3: { // Inverse sawtooth
                return (float) (1 - 2 * p);
            }
            case 4: { // Square
                return p < 0.5 ? 1f : -1f;
            }
            case 0:
            default: {
                return (float) Math.sin(2 * Math.PI * p);
            }
        }
    }
}
