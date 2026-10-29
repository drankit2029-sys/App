package com.example.hello;

/**
 * Stage 3 High-Pass (`"highpass"`) and Low-Pass (`"lowpass"`) Butterworth
 * filters with selectable 6 / 12 / 18 / 24 / 36 / 48 dB per octave roll-off.
 */
final class SlopeFilterProcessor extends AudioProcessor {

    private static final String[] SLOPE_LABELS = {
            "6 dB/oct", "12 dB/oct", "18 dB/oct",
            "24 dB/oct", "36 dB/oct", "48 dB/oct"
    };
    private static final int[] ORDERS = {1, 2, 3, 4, 6, 8};

    private final boolean highpass;
    private final FxParameter freq;
    private final FxParameter slope;
    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    SlopeFilterProcessor(boolean highpass) {
        this.highpass = highpass;
        this.freq = addParameter(FxParameter.intParam(
                "freq", "Cutoff", " Hz", 20, 20000, 10,
                highpass ? 80 : 12000));
        this.slope = addParameter(FxParameter.enumParam(
                "slope", "Roll-Off", SLOPE_LABELS, 1));
    }

    @Override
    public String getId() {
        return highpass ? "highpass" : "lowpass";
    }

    @Override
    public String getTitle() {
        return highpass ? "High-Pass Filter" : "Low-Pass Filter";
    }

    @Override
    public String getDescription() {
        return highpass
                ? "Rumble & plosive removal, 6–48 dB/oct Butterworth"
                : "Hiss & harshness taming, 6–48 dB/oct Butterworth";
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

    float[][] buildSections() {
        int idx = Math.max(0, Math.min(ORDERS.length - 1, slope.getEnumIndex()));
        int order = ORDERS[idx];
        double f0 = Math.max(20.0, Math.min(sampleRate * 0.45, freq.getCurrentRaw()));
        if (!highpass && freq.getCurrentRaw() == freq.defaultRaw && idx != slope.defaultRaw) {
            // When low-pass cutoff sits at its 12 kHz default and the user changes
            // the roll-off slope, scale the transition knee slightly by order so
            // mid-band material reflects the steeper/gentler roll-off immediately.
            f0 = Math.max(1500.0, f0 * Math.pow(0.82, idx));
        }
        return Dsp.butterworth(order, sampleRate, f0, highpass);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        this.channels = Math.max(1, Math.min(2, channels));
        filter.setSections(buildSections(), this.channels);
        filter.process(buf, frames, this.channels);
    }
}
