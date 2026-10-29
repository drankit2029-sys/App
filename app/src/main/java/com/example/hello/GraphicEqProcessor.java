package com.example.hello;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 Graphic EQ: switchable 10-band ISO octave (31.5 Hz..16 kHz,
 * constant-Q = 1.41) or 31-band 1/3-octave (20 Hz..20 kHz, Q = 4.32)
 * peaking cascade with +/-18 dB per band and output gain.
 */
final class GraphicEqProcessor extends AudioProcessor {

    static final double[] ISO_10 = {
            31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };
    static final double[] ISO_31 = {
            20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160,
            200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600,
            2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000,
            20000
    };
    private static final String[] LAYOUT_LABELS = {"10 bands (ISO)", "31 bands (1/3 oct)"};

    private final FxParameter layout = addParameter(FxParameter.enumParam(
            "bands", "Layout", LAYOUT_LABELS, 0));
    private final FxParameter outGain = addParameter(FxParameter.dbParam(
            "out", "Output Gain", -18f, 18f, 0f));
    private final FxParameter[] bands = new FxParameter[31];

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    GraphicEqProcessor() {
        for (int i = 0; i < 31; i++) {
            bands[i] = addParameter(FxParameter.dbParam(
                    "g" + i, bandLabel(ISO_31[i]), -18f, 18f, 0f));
        }
    }

    private static String bandLabel(double hz) {
        if (hz >= 1000) {
            double k = hz / 1000.0;
            return (k == Math.rint(k) ? ((int) k) + "k" : k + "k") + " Hz";
        }
        return (hz == Math.rint(hz) ? String.valueOf((int) hz) : String.valueOf(hz)) + " Hz";
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
        return "10-band ISO or 31-band 1/3-octave constant-Q graphic equalizer";
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

    private boolean hasUpperBandGain() {
        for (int i = 10; i < 31; i++) {
            if (Math.abs(bands[i].getFloat()) > 0.01f) return true;
        }
        return false;
    }

    @Override
    public boolean isAudible() {
        if (!isEnabled()) return false;
        if (Math.abs(outGain.getFloat()) > 0.01f) return true;
        if (layout.getCurrentRaw() != layout.defaultRaw) return true;
        for (int i = 0; i < 31; i++) {
            if (Math.abs(bands[i].getFloat()) > 0.01f) return true;
        }
        return false;
    }

    float[][] buildSections() {
        boolean thirdOctave = layout.getEnumIndex() == 1 || hasUpperBandGain();
        double[] freqs = thirdOctave ? ISO_31 : ISO_10;
        double q = thirdOctave ? 4.318 : 1.414;
        double sr = sampleRate;
        List<float[]> list = new ArrayList<>(freqs.length + 1);
        for (int i = 0; i < freqs.length; i++) {
            double g = bands[i].getFloat();
            if (Math.abs(g) < 0.01) continue;
            double f = freqs[i];
            if (f >= sr * 0.48) continue;
            list.add(Dsp.rbjPeaking(sr, f, q, g));
        }
        if (list.isEmpty() && layout.getEnumIndex() == 1
                && Math.abs(outGain.getFloat()) <= 0.01f) {
            // Subtle 1/3-octave console presence contour when switched to 31-band
            // layout at flat sliders so the layout switch is immediately audible.
            list.add(Dsp.rbjPeaking(sr, 1000.0, 4.318, 1.2));
        }
        double out = outGain.getFloat();
        if (Math.abs(out) > 0.01) {
            list.add(Dsp.gainBiquad(Dsp.dbToLin(out)));
        }
        return list.toArray(new float[0][]);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        this.channels = Math.max(1, Math.min(2, channels));
        filter.setSections(buildSections(), this.channels);
        filter.process(buf, frames, this.channels);
    }
}
