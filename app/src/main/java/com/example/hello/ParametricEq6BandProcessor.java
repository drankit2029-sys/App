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
    public boolean isAudible() {
        if (!isEnabled()) return false;
        return b1f.getCurrentRaw() > 20 || b6f.getCurrentRaw() < 20000
                || out.getCurrentRaw() != 0
                || Math.abs(b2g.getFloat()) >= 0.01f
                || Math.abs(b3g.getFloat()) >= 0.01f
                || Math.abs(b4g.getFloat()) >= 0.01f
                || Math.abs(b5g.getFloat()) >= 0.01f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int ch) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int c = Math.max(1, Math.min(2, ch));
        this.channels = c;
        filter.setSections(buildSections(), c);
        filter.process(buf, frames, c);
    }

    private static final float[] IDENTITY = {1f, 0f, 0f, 0f, 0f};
    private static final int LOW_SLOTS = 8;
    private static final int HIGH_SLOTS = 8;

    /** Cascade with fixed slots so changing a band never shifts biquad state. */
    float[][] buildSections() {
        // Keep the public design view empty while every band/output is flat;
        // the chain skips an inaudible processor, so no delay lines exist to
        // preserve. As soon as any stage is audible the fixed-slot layout is
        // returned, which is what keeps live band edits from shifting state.
        if (!isAudible()) return new float[0][];
        double sr = sampleRate;
        float[][] secs = new float[LOW_SLOTS + 4 + HIGH_SLOTS + 1][];
        int lowN = b1f.getCurrentRaw() > 20
                ? orderForSlope(b1s.getEnumIndex()) : 0;
        float[][] low = lowN > 0
                ? Dsp.butterworth(lowN, sr, b1f.getFloat(), true)
                : new float[0][];
        for (int i = 0; i < LOW_SLOTS; i++) {
            secs[i] = i < low.length ? low[i] : IDENTITY;
        }
        secs[LOW_SLOTS] = bandSection(b2t.getEnumIndex() == 1, false,
                b2f.getFloat(), b2g.getFloat(), b2q.getFloat());
        secs[LOW_SLOTS + 1] = bandSection(false, false,
                b3f.getFloat(), b3g.getFloat(), b3q.getFloat());
        secs[LOW_SLOTS + 2] = bandSection(false, false,
                b4f.getFloat(), b4g.getFloat(), b4q.getFloat());
        secs[LOW_SLOTS + 3] = bandSection(b5t.getEnumIndex() == 1, true,
                b5f.getFloat(), b5g.getFloat(), b5q.getFloat());
        int highOff = LOW_SLOTS + 4;
        int highN = b6f.getCurrentRaw() < 20000
                ? orderForSlope(b6s.getEnumIndex()) : 0;
        float[][] high = highN > 0
                ? Dsp.butterworth(highN, sr, b6f.getFloat(), false)
                : new float[0][];
        for (int i = 0; i < HIGH_SLOTS; i++) {
            secs[highOff + i] = i < high.length ? high[i] : IDENTITY;
        }
        secs[highOff + HIGH_SLOTS] = Dsp.gainBiquad(Dsp.dbToLin(out.getFloat()));
        return secs;
    }

    private float[] bandSection(boolean shelf, boolean highShelf,
                                double f, double g, double q) {
        if (Math.abs(g) < 0.01) return IDENTITY;
        if (shelf) {
            return highShelf ? Dsp.rbjHighShelf(sampleRate, f, 1.0, g)
                    : Dsp.rbjLowShelf(sampleRate, f, 1.0, g);
        }
        return Dsp.rbjPeaking(sampleRate, f, q, g);
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
