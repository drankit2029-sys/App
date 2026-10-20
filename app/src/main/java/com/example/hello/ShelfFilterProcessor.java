package com.example.hello;

/**
 * Stage 3 · single shelf (high or low) with adjustable frequency, gain and
 * slope (RBJ S parameter).
 */
final class ShelfFilterProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Low Shelf", "High Shelf"};

    private final FxParameter type = addParameter(
            FxParameter.enumParam("type", "Type", TYPE_LABELS, 0));
    private final FxParameter freq = addParameter(
            FxParameter.intParam("freq", "Frequency", " Hz", 20, 20000, 1, 200));
    private final FxParameter gain = addParameter(
            FxParameter.dbParam("gain", "Gain", -24f, 24f, 0f));
    private final FxParameter slope = addParameter(
            FxParameter.floatParam("slope", "Slope", "", 0.1f, 2f, 0.7f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;
    private int lastTopologyKey = -1;

    @Override
    public String getId() {
        return "shelf";
    }

    @Override
    public String getTitle() {
        return "Shelf Filter";
    }

    @Override
    public String getDescription() {
        return "Broad high/low tonal shelf with adjustable slope";
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
    public boolean isAudible() {
        return isEnabled() && Math.abs(gain.getFloat()) >= 0.01f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int ch) {
        if (buf == null || frames <= 0) return;
        int topoKey = type.getEnumIndex();
        if (lastTopologyKey != -1 && lastTopologyKey != topoKey) {
            filter.reset();
        }
        lastTopologyKey = topoKey;
        filter.setSections(buildSections(), channels);
        filter.process(buf, frames, Math.max(1, Math.min(2, ch)));
    }

    float[][] buildSections() {
        double f = freq.getFloat(), g = gain.getFloat(), s = slope.getFloat();
        return new float[][]{type.getEnumIndex() == 1
                ? Dsp.rbjHighShelf(sampleRate, f, s, g)
                : Dsp.rbjLowShelf(sampleRate, f, s, g)};
    }
}
