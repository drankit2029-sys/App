package com.example.hello;

/**
 * Stage 3 · surgical notch for hum (50/60 Hz) and resonances. RBJ notch
 * with an adjustable Q; deep null exactly at the tuned frequency.
 */
final class NotchFilterProcessor extends AudioProcessor {

    private static final String[] PRESET_LABELS = {"Custom", "50 Hz hum", "60 Hz hum"};

    private final FxParameter preset = addParameter(
            FxParameter.enumParam("preset", "Quick Pick", PRESET_LABELS, 0));
    private final FxParameter freq = addParameter(
            FxParameter.intParam("freq", "Frequency", " Hz", 20, 20000, 1, 60));
    private final FxParameter q = addParameter(
            FxParameter.floatParam("q", "Q", "", 0.1f, 30f, 5f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;

    @Override
    public String getId() {
        return "notch";
    }

    @Override
    public String getTitle() {
        return "Notch Filter";
    }

    @Override
    public String getDescription() {
        return "Surgical null for mains hum and ringing resonances";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sr, int ch) {
        sampleRate = Math.max(8000, sr);
        channels = Math.max(1, Math.min(2, ch));
        filter.setSections(buildSections(), channels);
    }

    @Override
    public void reset() {
        filter.reset();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int ch) {
        if (buf == null || frames <= 0) return;
        filter.setSections(buildSections(), channels);
        filter.process(buf, frames, Math.max(1, Math.min(2, ch)));
    }

    /** Applies the hum quick-picks deterministically at process time. */
    float[][] buildSections() {
        int p = preset.getEnumIndex();
        double f = p == 1 ? 50.0 : p == 2 ? 60.0 : freq.getFloat();
        return new float[][]{Dsp.rbjNotch(sampleRate, f, q.getFloat())};
    }
}
