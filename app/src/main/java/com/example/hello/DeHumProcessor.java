package com.example.hello;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 5 De-Hum (Harmonic Comb Notch Cascade): removes 50 Hz or 60 Hz mains
 * hum and up to 10 harmonics using high-Q RBJ notch sections.
 */
final class DeHumProcessor extends AudioProcessor {

    private final FxParameter baseFreq = addParameter(FxParameter.intParam(
            "hum_freq", "Base Frequency", " Hz", 45, 65, 1, 50));
    private final FxParameter harmonics = addParameter(FxParameter.intParam(
            "hum_harm", "Harmonics", "", 1, 10, 1, 8));
    private final FxParameter q = addParameter(FxParameter.qParam(
            "hum_q", "Notch Q", 5f, 40f, 20f));

    private final BiquadFilter filter = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    DeHumProcessor() {
    }

    @Override
    public String getId() {
        return "dehum";
    }

    @Override
    public String getTitle() {
        return "De-Hum";
    }

    @Override
    public String getDescription() {
        return "Harmonic comb notch filter for 50/60 Hz mains hum & buzz";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
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
        double f0 = baseFreq.getCurrentRaw();
        int hCount = Math.max(1, Math.min(10, harmonics.getCurrentRaw()));
        double qVal = Math.max(2.0, q.getFloat());
        // Scale fundamental Q slightly with harmonic count so higher harmonic
        // cascades tighten skirt selectivity across all harmonics (1..10).
        double effBaseQ = Math.max(2.0, qVal * (0.85 + 0.025 * hCount));
        List<float[]> secs = new ArrayList<>(hCount);
        for (int h = 1; h <= hCount; h++) {
            double fh = f0 * h;
            if (fh >= sampleRate * 0.45) break;
            secs.add(Dsp.rbjNotch(sampleRate, fh, h == 1 ? effBaseQ : qVal));
        }
        return secs.toArray(new float[0][]);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        this.channels = Math.max(1, Math.min(2, channels));
        filter.setSections(buildSections(), this.channels);
        filter.process(buf, frames, this.channels);
    }
}
