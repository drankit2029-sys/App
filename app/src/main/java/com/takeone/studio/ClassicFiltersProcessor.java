package com.takeone.studio;

import java.util.ArrayList;
import java.util.Map;
import java.util.Arrays;
import java.util.List;

/**
 * Stage 3 Classic Analog-Prototype IIR Filters: Butterworth (maximally flat),
 * Chebyshev Type I (equiripple passband), Chebyshev Type II (equiripple
 * stopband), and Bessel (maximally flat group delay), orders 1..10.
 */
final class ClassicFiltersProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {
            "Butterworth", "Chebyshev I", "Chebyshev II", "Bessel"
    };
    private static final String[] PASS_LABELS = {"Low-Pass", "High-Pass"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "type", "Prototype", TYPE_LABELS, 0));
    private final FxParameter pass = addParameter(FxParameter.enumParam(
            "pass", "Passband", PASS_LABELS, 0));
    private final FxParameter order = addParameter(FxParameter.intParam(
            "order", "Order", "", 1, 10, 1, 4));
    private final FxParameter freq = addParameter(FxParameter.intParam(
            "freq", "Cutoff", " Hz", 20, 20000, 10, 1000));
    private final FxParameter ripple = addParameter(FxParameter.floatParam(
            "ripple", "Ripple", " dB", 0.1f, 6.0f, 1.0f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private int lastTopologyKey = -1;

    ClassicFiltersProcessor() {
    }

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
        return "Butterworth, Chebyshev I/II & Bessel IIR filters, orders 1–10";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public boolean isParamApplicable(String paramId) {
        return applicable(type.getEnumIndex(), paramId);
    }

    @Override
    public boolean isParamApplicable(String paramId, Map<String, Integer> values) {
        int proto = values != null && values.containsKey("type")
                ? type.clampRaw(values.get("type"))
                : type.defaultRaw;
        return applicable(proto, paramId);
    }

    private static boolean applicable(int prototype, String paramId) {
        return !"ripple".equals(paramId) || prototype == 1 || prototype == 2;
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

    float[][] buildSections() {
        int n = Math.max(1, Math.min(10, order.getCurrentRaw()));
        boolean hp = pass.getEnumIndex() == 1;
        double f0 = Math.max(20.0, Math.min(sampleRate * 0.45, freq.getCurrentRaw()));
        double r = Math.max(0.1, Math.min(6.0, ripple.getFloat()));
        int proto = type.getEnumIndex();
        float[][] base;
        switch (proto) {
            case 1:
                return Dsp.chebyshev1(n, r, sampleRate, f0, hp);
            case 2:
                return Dsp.chebyshev2(n, r, sampleRate, f0, hp);
            case 3:
                base = Dsp.bessel(n, sampleRate, f0, hp);
                break;
            case 0:
            default:
                base = Dsp.butterworth(n, sampleRate, f0, hp);
                break;
        }
        return base;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        this.channels = Math.max(1, Math.min(2, channels));
        int topoKey = (type.getEnumIndex() << 2) | pass.getEnumIndex();
        if (lastTopologyKey != -1 && lastTopologyKey != topoKey) {
            filter.reset();
        }
        lastTopologyKey = topoKey;
        filter.setSections(buildSections(), this.channels);
        filter.process(buf, frames, this.channels);
    }
}
