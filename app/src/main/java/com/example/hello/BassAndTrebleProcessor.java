package com.example.hello;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 Bass & Treble: 250 Hz low-shelf + 3.5 kHz high-shelf (+/-30 dB)
 * with output Volume and a Link-Gain toggle.
 */
final class BassAndTrebleProcessor extends AudioProcessor {

    private final FxParameter bass = addParameter(FxParameter.dbParam(
            "bass", "Bass (250 Hz)", -30f, 30f, 0f));
    private final FxParameter treble = addParameter(FxParameter.dbParam(
            "treble", "Treble (3.5 kHz)", -30f, 30f, 0f));
    private final FxParameter volume = addParameter(FxParameter.dbParam(
            "vol", "Volume", -18f, 18f, 0f));
    private final FxParameter link = addParameter(FxParameter.boolParam(
            "link", "Link Bass & Treble", false));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    BassAndTrebleProcessor() {
    }

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
        return "Classic 250 Hz / 3.5 kHz shelving tone stack with volume";
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

    @Override
    public boolean isAudible() {
        return isEnabled() && (Math.abs(bass.getFloat()) > 0.01f
                || Math.abs(treble.getFloat()) > 0.01f
                || Math.abs(volume.getFloat()) > 0.01f
                || link.getBoolean());
    }

    float[][] buildSections() {
        List<float[]> list = new ArrayList<>(3);
        double b = bass.getFloat();
        double t = treble.getFloat();
        if (link.getBoolean()) {
            // Link mirrors one knob onto the other; both knobs at 0 dB stays a
            // true 0 dB bypass instead of jumping to a hidden +3 dB boost.
            if (Math.abs(t) < 0.01) {
                t = b;
            } else if (Math.abs(b) < 0.01) {
                b = t;
            } else {
                t = 0.5 * (b + t);
            }
        }
        if (Math.abs(b) > 0.01) {
            list.add(Dsp.rbjLowShelf(sampleRate, 250.0, 0.7071, b));
        }
        if (Math.abs(t) > 0.01) {
            list.add(Dsp.rbjHighShelf(sampleRate, 3500.0, 0.7071, t));
        }
        double v = volume.getFloat();
        if (Math.abs(v) > 0.01) {
            list.add(Dsp.gainBiquad(Dsp.dbToLin(v)));
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
