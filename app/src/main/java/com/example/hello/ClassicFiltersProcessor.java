package com.example.hello;

/**
 * Stage 3 · classic analog prototypes in one processor: Butterworth,
 * Chebyshev I, Chebyshev II (inverse) and Bessel, orders 1..10, low- or
 * high-pass, cutoff 20 Hz..20 kHz, ripple parameter for the Chebyshevs.
 * Cascade math lives in {@link Dsp} (verified against analytic responses).
 */
final class ClassicFiltersProcessor extends AudioProcessor {

    static final String[] TYPE_LABELS = {
            "Butterworth", "Chebyshev I", "Chebyshev II", "Bessel"};
    static final String[] MODE_LABELS = {"Low-Pass", "High-Pass"};

    private final FxParameter type = addParameter(
            FxParameter.enumParam("type", "Type", TYPE_LABELS, 0));
    private final FxParameter mode = addParameter(
            FxParameter.enumParam("mode", "Mode", MODE_LABELS, 0));
    private final FxParameter order = addParameter(
            FxParameter.intParam("order", "Order", "", 1, 10, 1, 4));
    private final FxParameter freq = addParameter(
            FxParameter.intParam("freq", "Cutoff", " Hz", 20, 20000, 1, 1000));
    private final FxParameter ripple = addParameter(
            FxParameter.floatParam("ripple", "Ripple", " dB", 0.1f, 10f, 1f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;
    private int lastTopologyKey = -1;

    @Override
    public String getId() {
        return "classic";
    }

    @Override
    public String getTitle() {
        return "Classic Filters";
    }

    @Override
    public String getDescription() {
        return "Butterworth / Chebyshev I / Chebyshev II / Bessel, order 1-10";
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
        int topoKey = (type.getEnumIndex() << 4) | mode.getEnumIndex();
        if (lastTopologyKey != -1 && lastTopologyKey != topoKey) {
            filter.reset();
        }
        lastTopologyKey = topoKey;
        filter.setSections(buildSections(), channels);
        filter.process(buf, frames, Math.max(1, Math.min(2, ch)));
    }

    float[][] buildSections() {
        int n = order.getCurrentRaw();
        double f = freq.getFloat();
        boolean hp = mode.getEnumIndex() == 1;
        switch (type.getEnumIndex()) {
            case 1:
                return Dsp.chebyshev1(n, ripple.getFloat(), sampleRate, f, hp);
            case 2:
                return Dsp.chebyshev2(n, ripple.getFloat(), sampleRate, f, hp);
            case 3:
                return Dsp.bessel(n, sampleRate, f, hp);
            default:
                return Dsp.butterworth(n, sampleRate, f, hp);
        }
    }
}
