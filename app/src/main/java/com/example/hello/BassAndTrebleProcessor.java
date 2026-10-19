package com.example.hello;

/**
 * Stage 3 · A4: tone-control style bass & treble shelves (250 Hz / 3.5 kHz)
 * plus a master volume trim. With "Link" on, the treble follows the bass
 * knob for one-hand tilting.
 */
final class BassAndTrebleProcessor extends AudioProcessor {

    private static final double BASS_HZ = 250.0;
    private static final double TREBLE_HZ = 3500.0;
    private static final double SHELF_S = 0.9;

    private final FxParameter bass = addParameter(
            FxParameter.dbParam("bass", "Bass", -30f, 30f, 0f));
    private final FxParameter treble = addParameter(
            FxParameter.dbParam("treble", "Treble", -30f, 30f, 0f));
    private final FxParameter volume = addParameter(
            FxParameter.dbParam("vol", "Volume", -18f, 18f, 0f));
    private final FxParameter link = addParameter(
            FxParameter.boolParam("link", "Link Bass/Treble", false));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;

    @Override
    public String getId() {
        return "basstreble";
    }

    @Override
    public String getTitle() {
        return "Bass & Treble";
    }

    @Override
    public String getDescription() {
        return "Classic tone stack: 250 Hz bass shelf, 3.5 kHz treble shelf, volume";
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

    float[][] buildSections() {
        double b = bass.getFloat();
        double t = link.getBoolean() ? b : treble.getFloat();
        float[][] secs = new float[3][];
        secs[0] = Dsp.rbjLowShelf(sampleRate, BASS_HZ, SHELF_S, b);
        secs[1] = Dsp.rbjHighShelf(sampleRate, TREBLE_HZ, SHELF_S, t);
        secs[2] = Dsp.gainBiquad(Dsp.dbToLin(volume.getFloat()));
        return secs;
    }
}
