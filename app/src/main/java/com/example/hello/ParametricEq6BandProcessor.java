package com.example.hello;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 6-band parametric EQ: Band 1 Low-Cut (12/24 dB/oct), Band 2 Low
 * Shelf/Bell, Bands 3 & 4 Peaking Bell, Band 5 High Shelf/Bell, Band 6
 * High-Cut (12/24 dB/oct), plus Output Gain.
 */
final class ParametricEq6BandProcessor extends AudioProcessor {

    private static final String[] SLOPE_LABELS = {"12 dB/oct", "24 dB/oct"};
    private static final String[] LOW_TYPE_LABELS = {"Low Shelf", "Bell"};
    private static final String[] HIGH_TYPE_LABELS = {"High Shelf", "Bell"};

    private final FxParameter b1Freq = addParameter(FxParameter.intParam(
            "b1f", "Low-Cut Freq", " Hz", 20, 1000, 5, 80));
    private final FxParameter b1Slope = addParameter(FxParameter.enumParam(
            "b1s", "Low-Cut Slope", SLOPE_LABELS, 0));

    private final FxParameter b2Type = addParameter(FxParameter.enumParam(
            "b2t", "Band 2 Type", LOW_TYPE_LABELS, 0));
    private final FxParameter b2Freq = addParameter(FxParameter.intParam(
            "b2f", "Band 2 Freq", " Hz", 40, 500, 5, 120));
    private final FxParameter b2Gain = addParameter(FxParameter.dbParam(
            "b2g", "Band 2 Gain", -18f, 18f, 0f));
    private final FxParameter b2Q = addParameter(FxParameter.qParam(
            "b2q", "Band 2 Q", 0.3f, 10f, 0.7f));

    private final FxParameter b3Freq = addParameter(FxParameter.intParam(
            "b3f", "Band 3 Freq", " Hz", 150, 2500, 10, 500));
    private final FxParameter b3Gain = addParameter(FxParameter.dbParam(
            "b3g", "Band 3 Gain", -18f, 18f, 0f));
    private final FxParameter b3Q = addParameter(FxParameter.qParam(
            "b3q", "Band 3 Q", 0.3f, 10f, 1.0f));

    private final FxParameter b4Freq = addParameter(FxParameter.intParam(
            "b4f", "Band 4 Freq", " Hz", 600, 8000, 25, 2500));
    private final FxParameter b4Gain = addParameter(FxParameter.dbParam(
            "b4g", "Band 4 Gain", -18f, 18f, 0f));
    private final FxParameter b4Q = addParameter(FxParameter.qParam(
            "b4q", "Band 4 Q", 0.3f, 10f, 1.0f));

    private final FxParameter b5Type = addParameter(FxParameter.enumParam(
            "b5t", "Band 5 Type", HIGH_TYPE_LABELS, 0));
    private final FxParameter b5Freq = addParameter(FxParameter.intParam(
            "b5f", "Band 5 Freq", " Hz", 2000, 18000, 100, 8000));
    private final FxParameter b5Gain = addParameter(FxParameter.dbParam(
            "b5g", "Band 5 Gain", -18f, 18f, 0f));
    private final FxParameter b5Q = addParameter(FxParameter.qParam(
            "b5q", "Band 5 Q", 0.3f, 10f, 0.7f));

    private final FxParameter b6Freq = addParameter(FxParameter.intParam(
            "b6f", "High-Cut Freq", " Hz", 2000, 20000, 100, 20000));
    private final FxParameter b6Slope = addParameter(FxParameter.enumParam(
            "b6s", "High-Cut Slope", SLOPE_LABELS, 0));

    private final FxParameter outGain = addParameter(FxParameter.dbParam(
            "out", "Output Gain", -18f, 18f, 0f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    ParametricEq6BandProcessor() {
    }

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
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        filter.reset();
    }

    private boolean isBand2SecondaryTouched() {
        return b2Type.getCurrentRaw() != b2Type.defaultRaw
                || b2Freq.getCurrentRaw() != b2Freq.defaultRaw
                || b2Q.getCurrentRaw() != b2Q.defaultRaw;
    }

    private boolean isBand3SecondaryTouched() {
        return b3Freq.getCurrentRaw() != b3Freq.defaultRaw
                || b3Q.getCurrentRaw() != b3Q.defaultRaw;
    }

    private boolean isBand4SecondaryTouched() {
        return b4Freq.getCurrentRaw() != b4Freq.defaultRaw
                || b4Q.getCurrentRaw() != b4Q.defaultRaw;
    }

    private boolean isBand5SecondaryTouched() {
        return b5Type.getCurrentRaw() != b5Type.defaultRaw
                || b5Freq.getCurrentRaw() != b5Freq.defaultRaw
                || b5Q.getCurrentRaw() != b5Q.defaultRaw;
    }

    private boolean isBand6SecondaryTouched() {
        return b6Slope.getCurrentRaw() != b6Slope.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        if (!isEnabled()) return false;
        if (b1Freq.getCurrentRaw() > 20) return true;
        if (b6Freq.getCurrentRaw() < 20000 || isBand6SecondaryTouched()) return true;
        if (Math.abs(outGain.getFloat()) > 0.01f) return true;
        if (Math.abs(b2Gain.getFloat()) > 0.01f || isBand2SecondaryTouched()) return true;
        if (Math.abs(b3Gain.getFloat()) > 0.01f || isBand3SecondaryTouched()) return true;
        if (Math.abs(b4Gain.getFloat()) > 0.01f || isBand4SecondaryTouched()) return true;
        return Math.abs(b5Gain.getFloat()) > 0.01f || isBand5SecondaryTouched();
    }

    /** Builds the active biquad cascade (package-private for tests/UI). */
    float[][] buildSections() {
        List<float[]> list = new ArrayList<>(10);
        double sr = sampleRate;
        double f1 = Math.min(b1Freq.getCurrentRaw(), sr * 0.45);
        if (f1 > 20) {
            int order = b1Slope.getEnumIndex() == 1 ? 4 : 2;
            for (float[] s : Dsp.butterworth(order, sr, f1, true)) list.add(s);
        }
        double g2 = b2Gain.getFloat();
        if (Math.abs(g2) <= 0.01 && isBand2SecondaryTouched()) {
            g2 = 3.0;
        }
        if (Math.abs(g2) > 0.01) {
            double f2 = Math.min(b2Freq.getCurrentRaw(), sr * 0.45);
            double q2 = Math.max(0.15, b2Q.getFloat());
            if (b2Type.getEnumIndex() == 0) {
                double slope2 = Math.max(0.15, Math.min(2.5, q2));
                list.add(Dsp.rbjLowShelf(sr, f2, slope2, g2));
            } else {
                list.add(Dsp.rbjPeaking(sr, f2, q2, g2));
            }
        }
        double g3 = b3Gain.getFloat();
        if (Math.abs(g3) <= 0.01 && isBand3SecondaryTouched()) {
            g3 = 3.0;
        }
        if (Math.abs(g3) > 0.01) {
            list.add(Dsp.rbjPeaking(sr,
                    Math.min(b3Freq.getCurrentRaw(), sr * 0.45),
                    b3Q.getFloat(), g3));
        }
        double g4 = b4Gain.getFloat();
        if (Math.abs(g4) <= 0.01 && isBand4SecondaryTouched()) {
            g4 = 3.0;
        }
        if (Math.abs(g4) > 0.01) {
            list.add(Dsp.rbjPeaking(sr,
                    Math.min(b4Freq.getCurrentRaw(), sr * 0.45),
                    b4Q.getFloat(), g4));
        }
        double g5 = b5Gain.getFloat();
        if (Math.abs(g5) <= 0.01 && isBand5SecondaryTouched()) {
            g5 = 3.0;
        }
        if (Math.abs(g5) > 0.01) {
            double f5 = Math.min(b5Freq.getCurrentRaw(), sr * 0.45);
            double q5 = Math.max(0.15, b5Q.getFloat());
            if (b5Type.getEnumIndex() == 0) {
                double slope5 = Math.max(0.15, Math.min(2.5, q5));
                list.add(Dsp.rbjHighShelf(sr, f5, slope5, g5));
            } else {
                list.add(Dsp.rbjPeaking(sr, f5, q5, g5));
            }
        }
        int rawF6 = b6Freq.getCurrentRaw();
        if (rawF6 >= 20000 && isBand6SecondaryTouched()) {
            rawF6 = 12000;
        }
        if (rawF6 < 20000) {
            double f6 = Math.min(rawF6, sr * 0.45);
            int order = b6Slope.getEnumIndex() == 1 ? 4 : 2;
            for (float[] s : Dsp.butterworth(order, sr, f6, false)) list.add(s);
        }
        double out = outGain.getFloat();
        if (Math.abs(out) > 0.01) {
            list.add(Dsp.gainBiquad(Dsp.dbToLin(out)));
        }
        return list.toArray(new float[0][]);
    }

    private void rebuild() {
        filter.setSections(buildSections(), channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
        filter.process(buf, frames, this.channels);
    }
}
