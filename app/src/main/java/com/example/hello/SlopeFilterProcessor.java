package com.example.hello;

/**
 * Stage 3 · steep Butterworth high-pass / low-pass with selectable slope
 * (6..48 dB per octave). One class serves both directions so the two rack
 * entries stay behaviorally identical.
 */
final class SlopeFilterProcessor extends AudioProcessor {

    static final String[] SLOPE_LABELS = {
            "6 dB/oct", "12 dB/oct", "18 dB/oct", "24 dB/oct",
            "36 dB/oct", "48 dB/oct"};
    private static final int[] SLOPE_ORDERS = {1, 2, 3, 4, 6, 8};

    private final boolean highpass;
    private final int defaultFreq;
    private final String id;
    private final String title;
    private final String description;

    private final FxParameter freq;
    private final FxParameter slope = addParameter(
            FxParameter.enumParam("slope", "Slope", SLOPE_LABELS, 1));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;

    SlopeFilterProcessor(boolean highpass) {
        this.highpass = highpass;
        this.id = highpass ? "highpass" : "lowpass";
        this.defaultFreq = highpass ? 80 : 12000;
        this.title = highpass ? "High-Pass Filter" : "Low-Pass Filter";
        this.description = highpass
                ? "Rumble & plosive removal, 6-48 dB per octave Butterworth"
                : "Hiss & harshness taming, 6-48 dB per octave Butterworth";
        this.freq = addParameter(FxParameter.intParam(
                "freq", "Cutoff", " Hz", 20, 20000, 1, defaultFreq));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getDescription() {
        return description;
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
        if (!isEnabled() || buf == null || frames <= 0) return;
        int c = Math.max(1, Math.min(2, ch));
        this.channels = c;
        filter.setSections(buildSections(), c);
        filter.process(buf, frames, c);
    }

    float[][] buildSections() {
        int n = SLOPE_ORDERS[Math.max(0, Math.min(
                SLOPE_ORDERS.length - 1, slope.getEnumIndex()))];
        return Dsp.butterworth(n, sampleRate, freq.getFloat(), highpass);
    }
}
