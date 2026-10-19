package com.example.hello;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 · A1: Fairlight-style 6-band parametric EQ. Band 1 is a low cut
 * (12/24/36/48 dB per octave), bands 2 and 5 switch between bell and shelf,
 * bands 3 and 4 are bells, band 6 is a high cut. Every knob drives a
 * recomputed biquad cascade; the primary "Output" knob is the rack dial.
 */
final class ParametricEq6BandProcessor extends AudioProcessor {

    private static final String[] CUT_LABELS = {
            "12 dB/oct", "24 dB/oct", "36 dB/oct", "48 dB/oct"};
    private static final String[] SHELF_LABELS = {"Bell", "Shelf"};

    private final FxParameter out = addParameter(
            FxParameter.dbParam("out", "Output", -18f, 18f, 0f));
    private final FxParameter b1f = addParameter(
            FxParameter.intParam("b1f", "Low Cut", " Hz", 20, 500, 1, 80));
    private final FxParameter b1s = addParameter(
            FxParameter.enumParam("b1s", "Cut Slope", CUT_LABELS, 1));
    private final FxParameter b2t = addParameter(
            FxParameter.enumParam("b2t", "Band 2 Type", SHELF_LABELS, 0));
    private final FxParameter b2f = addParameter(
            FxParameter.intParam("b2f", "Band 2 Freq", " Hz", 40, 1000, 1, 350));
    private final FxParameter b2g = addParameter(
            FxParameter.dbParam("b2g", "Band 2 Gain", -18f, 18f, 0f));
    private final FxParameter b2q = addParameter(
            FxParameter.floatParam("b2q", "Band 2 Q", "", 0.1f, 12f, 0.7f));
    private final FxParameter b3f = addParameter(
            FxParameter.intParam("b3f", "Band 3 Freq", " Hz", 100, 2000, 1, 800));
    private final FxParameter b3g = addParameter(
            FxParameter.dbParam("b3g", "Band 3 Gain", -18f, 18f, 0f));
    private final FxParameter b3q = addParameter(
            FxParameter.floatParam("b3q", "Band 3 Q", "", 0.1f, 12f, 0.7f));
    private final FxParameter b4f = addParameter(
            FxParameter.intParam("b4f", "Band 4 Freq", " Hz", 500, 8000, 1, 2500));
    private final FxParameter b4g = addParameter(
            FxParameter.dbParam("b4g", "Band 4 Gain", -18f, 18f, 0f));
    private final FxParameter b4q = addParameter(
            FxParameter.floatParam("b4q", "Band 4 Q", "", 0.1f, 12f, 0.7f));
    private final FxParameter b5t = addParameter(
            FxParameter.enumParam("b5t", "Band 5 Type", SHELF_LABELS, 0));
    private final FxParameter b5f = addParameter(
            FxParameter.intParam("b5f", "Band 5 Freq", " Hz", 1000, 16000, 1, 6500));
    private final FxParameter b5g = addParameter(
            FxParameter.dbParam("b5g", "Band 5 Gain", -18f, 18f, 0f));
    private final FxParameter b5q = addParameter(
            FxParameter.floatParam("b5q", "Band 5 Q", "", 0.1f, 12f, 0.7f));
    private final FxParameter b6f = addParameter(
            FxParameter.intParam("b6f", "High Cut", " Hz", 4000, 20000, 1, 20000));
    private final FxParameter b6s = addParameter(
            FxParameter.enumParam("b6s", "Cut Slope", CUT_LABELS, 1));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;

    @Override
    public String getId() {
        return "eq6";
    }

    @Override
    public String getTitle() {
        return "6-Band Parametric EQ";
    }

    @Override
    public String getDescription() {
        return "Console EQ: low/high cut plus four musical bell/shelf bands";
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

    /** Cascade: low cut, four bands, high cut, output trim. */
    float[][] buildSections() {
        double sr = sampleRate;
        List<float[]> secs = new ArrayList<>();
        int lowN = orderForSlope(b1s.getEnumIndex());
        if (b1f.getCurrentRaw() > 20 || lowN > 0) {
            for (float[] s : Dsp.butterworth(lowN, sr, b1f.getFloat(), true)) {
                secs.add(s);
            }
        }
        addBand(secs, sr, b2t.getEnumIndex() == 1, false, b2f.getFloat(),
                b2g.getFloat(), b2q.getFloat());
        addBand(secs, sr, false, false, b3f.getFloat(), b3g.getFloat(),
                b3q.getFloat());
        addBand(secs, sr, false, false, b4f.getFloat(), b4g.getFloat(),
                b4q.getFloat());
        addBand(secs, sr, b5t.getEnumIndex() == 1, true, b5f.getFloat(),
                b5g.getFloat(), b5q.getFloat());
        int highN = orderForSlope(b6s.getEnumIndex());
        if (b6f.getCurrentRaw() < 20000) {
            for (float[] s : Dsp.butterworth(highN, sr, b6f.getFloat(), false)) {
                secs.add(s);
            }
        }
        if (out.getCurrentRaw() != 0) {
            secs.add(Dsp.gainBiquad(Dsp.dbToLin(out.getFloat())));
        }
        return secs.toArray(new float[0][]);
    }

    private void addBand(List<float[]> secs, double sr, boolean shelf,
                         boolean highShelf, double f, double g, double q) {
        if (Math.abs(g) < 0.01) return;
        if (shelf) {
            secs.add(highShelf ? Dsp.rbjHighShelf(sr, f, 1.0, g)
                    : Dsp.rbjLowShelf(sr, f, 1.0, g));
        } else {
            secs.add(Dsp.rbjPeaking(sr, f, q, g));
        }
    }

    private static int orderForSlope(int slopeIndex) {
        switch (slopeIndex) {
            case 0: return 2;
            case 1: return 4;
            case 2: return 6;
            default: return 8;
        }
    }
}
