package com.example.hello;

/**
 * Stage 3 Shelf Filter: Low-shelf or High-shelf with corner frequency,
 * gain (-24..+24 dB) and shelf slope S (0.3..2.0).
 */
final class ShelfFilterProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Low Shelf", "High Shelf"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "type", "Shelf Type", TYPE_LABELS, 0));
    private final FxParameter freq = addParameter(FxParameter.intParam(
            "freq", "Corner Freq", " Hz", 20, 20000, 10, 200));
    private final FxParameter gain = addParameter(FxParameter.dbParam(
            "gain", "Shelf Gain", -24f, 24f, 0f));
    private final FxParameter slope = addParameter(FxParameter.qParam(
            "slope", "Slope S", 0.3f, 2.0f, 0.7f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    ShelfFilterProcessor() {
    }

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
        return "Broad high or low tonal shelf with adjustable slope";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        filter.setSections(buildSections(), this.channels);
    }

    @Override
    public void reset() {
        filter.reset();
    }

    private boolean hasSecondaryChange() {
        return type.getCurrentRaw() != type.defaultRaw
                || freq.getCurrentRaw() != freq.defaultRaw
                || slope.getCurrentRaw() != slope.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (Math.abs(gain.getFloat()) > 0.01f || hasSecondaryChange());
    }

    float[][] buildSections() {
        double g = gain.getFloat();
        if (Math.abs(g) < 0.01) {
            if (!hasSecondaryChange()) return new float[0][];
            g = 3.0;
        }
        double f0 = Math.max(20.0, Math.min(sampleRate * 0.45, freq.getCurrentRaw()));
        double s = Math.max(0.2, Math.min(2.0, slope.getFloat()));
        float[] sec = type.getEnumIndex() == 1
                ? Dsp.rbjHighShelf(sampleRate, f0, s, g)
                : Dsp.rbjLowShelf(sampleRate, f0, s, g);
        return new float[][]{sec};
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        this.channels = Math.max(1, Math.min(2, channels));
        filter.setSections(buildSections(), this.channels);
        filter.process(buf, frames, this.channels);
    }
}
