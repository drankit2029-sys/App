package com.example.hello;

/**
 * Stage 1 of the legacy voice chain: dynamic Butterworth high-pass filter
 * (40..160 Hz). The biquad is Direct Form I, coefficients are recomputed only
 * when the cutoff or sample rate changes, and the body is a verbatim
 * extraction of the original {@code DspChain} stage so output stays
 * bit-identical.
 */
final class LegacyHpfProcessor extends AudioProcessor {

    private static final int DECIM = 16;

    private final FxParameter cutoff = addParameter(FxParameter.intParam(
            "cutoff", "Cutoff", " Hz", 40, 160, 1, 80));

    private volatile float b0, b1, b2, a1, a2;
    private volatile int currentHz = -1;
    private volatile int currentSampleRate = DspChain.SAMPLE_RATE;
    private final float[] z1 = new float[2];
    private final float[] z2 = new float[2];

    LegacyHpfProcessor() {
        computeCoefficients(80, DspChain.SAMPLE_RATE);
    }

    private void computeCoefficients(int cutoffHz, int sr) {
        int hz = Math.max(40, Math.min(160, cutoffHz));
        int validSr = sr > 0 ? sr : DspChain.SAMPLE_RATE;
        if (hz == this.currentHz && validSr == this.currentSampleRate) return;
        this.currentHz = hz;
        this.currentSampleRate = validSr;
        double w0 = 2.0 * Math.PI * hz / validSr;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * 0.70710678);
        double a0Inv = 1.0 / (1.0 + alpha);
        this.b0 = (float) ((1.0 + cosW0) * 0.5 * a0Inv);
        this.b1 = (float) (-(1.0 + cosW0) * a0Inv);
        this.b2 = this.b0;
        this.a1 = (float) (-2.0 * cosW0 * a0Inv);
        this.a2 = (float) ((1.0 - alpha) * a0Inv);
    }

    @Override
    public String getId() {
        return "hpf";
    }

    @Override
    public String getTitle() {
        return "Low-Cut Rumble Filter";
    }

    @Override
    public String getDescription() {
        return "2nd-order Butterworth high-pass filter for mic rumble & plosives";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        int validSr = sampleRate > 0 ? sampleRate : DspChain.SAMPLE_RATE;
        computeCoefficients(cutoff.getCurrentRaw(), validSr);
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            z1[c] = 0f;
            z2[c] = 0f;
        }
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    /** Mirrors the legacy {@code hpfOn} flag: any cutoff above 0 enables it. */
    void setLegacyState(boolean on, int cutoffHz) {
        cutoff.setCurrentRaw(on ? Math.max(40, Math.min(160, cutoffHz)) : 80);
        computeCoefficients(cutoff.getCurrentRaw(), currentSampleRate);
        setEnabled(on);
    }

    boolean isOn() {
        return isEnabled();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        computeCoefficients(cutoff.getCurrentRaw(), currentSampleRate);
        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 1: Dynamic Butterworth High-Pass Filter (40..160 Hz)
            final float fb0 = this.b0, fb1 = this.b1, fb2 = this.b2;
            final float fa1 = this.a1, fa2 = this.a2;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    float y = fb0 * x + z1[c];
                    z1[c] = fb1 * x - fa1 * y + z2[c];
                    z2[c] = fb2 * x - fa2 * y;
                    buf[idx + c] = y;
                }
            }

            offset += blk;
        }
    }
}
