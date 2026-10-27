package com.example.hello;

/**
 * Stage 6 wah-wah: a resonant band-pass filter whose centre frequency is
 * swept by an LFO and optionally modulated by the input envelope.
 */
final class WahwahProcessor extends AudioProcessor {

    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "wah_rate", "LFO Frequency", " Hz", 0.1f, 4f, 1.5f));
    private final FxParameter phase = addParameter(FxParameter.intParam(
            "wah_phase", "LFO Start Phase", " deg", 0, 355, 5, 0));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "wah_depth", "Depth", "%", 0, 100, 1, 70));
    private final FxParameter resonance = addParameter(FxParameter.floatParam(
            "wah_q", "Resonance / Q", "", 0.5f, 10f, 2.5f));
    private final FxParameter offset = addParameter(FxParameter.intParam(
            "wah_offset", "Wah Frequency Offset", "%", 0, 100, 1, 30));
    private final FxParameter autoWah = addParameter(FxParameter.intParam(
            "wah_auto", "Envelope Auto-Wah", "%", 0, 100, 1, 0));
    private final FxParameter outputGain = addParameter(FxParameter.dbParam(
            "wah_gain", "Output Gain", -18f, 12f, -6f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter filter = new BiquadFilter();
    private double lfoPhase = 0;
    private float envelope = 0f;

    WahwahProcessor() {
    }

    @Override
    public String getId() {
        return "wah";
    }

    @Override
    public String getTitle() {
        return "Wahwah";
    }

    @Override
    public String getDescription() {
        return "Swept resonant band-pass with envelope auto-wah";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
    }

    @Override
    public void reset() {
        filter.reset();
        lfoPhase = 0;
        envelope = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && depth.getCurrentRaw() > 0;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        double inc = rate.getFloat() / sampleRate;
        double phase0 = phase.getCurrentRaw() / 360.0;
        double depthPct = depth.getCurrentRaw() / 100.0;
        double offsetPct = offset.getCurrentRaw() / 100.0;
        float auto = autoWah.getCurrentRaw() / 100f;
        float q = Math.max(0.5f, resonance.getFloat());
        float gain = Dsp.dbToLin(outputGain.getFloat());

        final int block = 32;
        int pos = 0;
        while (pos < frames) {
            int n = Math.min(block, frames - pos);
            float blockEnv = 0f;
            for (int f = 0; f < n; f++) {
                float peak = 0f;
                for (int c = 0; c < ch; c++) {
                    float a = Math.abs(buf[(pos + f) * ch + c]);
                    if (a > peak) peak = a;
                }
                if (peak > blockEnv) blockEnv = peak;
            }
            envelope += (blockEnv - envelope) * 0.25f;
            float envNorm = Math.min(1f, envelope * 2f);

            double lfo = 0.5 + 0.5 * Math.sin(2 * Math.PI * (lfoPhase + phase0));
            double sweep = lfo * depthPct;
            double envSweep = envNorm * auto;
            double freq = 350.0 + (sweep * 0.6 + offsetPct * 0.4 + envSweep * 0.4)
                    * 2450.0;
            freq = Math.max(60.0, Math.min(sampleRate * 0.45, freq));
            filter.setSections(new float[][]{
                    Dsp.rbjBandPass(sampleRate, freq, q)}, channels);
            filter.process(buf, n, ch, pos);
            pos += n;
            lfoPhase += inc * n;
            while (lfoPhase > 1.0) lfoPhase -= 1.0;
        }
        int n = Math.min(buf.length, frames * ch);
        for (int i = 0; i < n; i++) buf[i] *= gain;
    }
}
