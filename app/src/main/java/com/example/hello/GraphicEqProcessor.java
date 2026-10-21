package com.example.hello;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 · A2: graphic EQ with switchable 10-band ISO and 31-band 1/3-octave
 * layouts. Each band is an RBJ peaking biquad at its fixed ISO center
 * frequency (Q 1.414 / 4.318); the "Output" knob is the rack dial.
 */
final class GraphicEqProcessor extends AudioProcessor {

    private static final double[] FREQ_10 = {
            31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final double[] FREQ_31 = {
            20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
            500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000,
            6300, 8000, 10000, 12500, 16000, 20000};
    private static final double Q_10 = 1.414;
    private static final double Q_31 = 4.318;
    private static final String[] BAND_LABELS = {"10 bands", "31 bands"};

    private final FxParameter out = addParameter(
            FxParameter.dbParam("out", "Output", -18f, 18f, 0f));
    private final FxParameter bands = addParameter(
            FxParameter.enumParam("bands", "Layout", BAND_LABELS, 0));
    private final FxParameter[] gains = new FxParameter[31];

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 1;

    GraphicEqProcessor() {
        for (int i = 0; i < 31; i++) {
            gains[i] = addParameter(FxParameter.dbParam(
                    "g" + i, "Band " + (i + 1), -20f, 20f, 0f));
        }
    }

    @Override
    public String getId() {
        return "geq";
    }

    @Override
    public String getTitle() {
        return "Graphic EQ";
    }

    @Override
    public String getDescription() {
        return "10-band ISO or 31-band 1/3-octave sliders with flat-neutral default";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    /** Number of active bands for the current layout (10 or 31). */
    int activeBandCount() {
        return bands.getEnumIndex() == 1 ? 31 : 10;
    }

    static double bandFreq(boolean thirtyOne, int index) {
        double[] table = thirtyOne ? FREQ_31 : FREQ_10;
        if (index < 0 || index >= table.length) return 1000;
        return table[index];
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
        int n = activeBandCount();
        for (int i = 0; i < n; i++) {
            if (Math.abs(gains[i].getFloat()) >= 0.01f) return true;
        }
        return out.getCurrentRaw() != 0;
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
        // Keep the public design view empty while every band/output is flat;
        // the chain skips an inaudible processor, so no delay lines exist to
        // preserve. As soon as any stage is audible the fixed-slot layout is
        // returned, which is what keeps live band edits from shifting state.
        if (!isAudible()) return new float[0][];
        boolean b31 = bands.getEnumIndex() == 1;
        double q = b31 ? Q_31 : Q_10;
        int n = activeBandCount();
        float[][] secs = new float[n + 1][];
        for (int i = 0; i < n; i++) {
            double g = gains[i].getFloat();
            secs[i] = Math.abs(g) < 0.01
                    ? new float[]{1f, 0f, 0f, 0f, 0f}
                    : Dsp.rbjPeaking(sampleRate, bandFreq(b31, i), q, g);
        }
        secs[n] = Dsp.gainBiquad(Dsp.dbToLin(out.getFloat()));
        return secs;
    }
}
