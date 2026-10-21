package com.example.hello;

/**
 * Stage 5 mains-hum remover: a bank of high-Q notch biquads at the mains
 * fundamental and its harmonics.
 */
final class DeHumProcessor extends AudioProcessor {

    private final FxParameter baseFreq = addParameter(FxParameter.intParam(
            "hum_freq", "Base Frequency", " Hz", 45, 65, 1, 50));
    private final FxParameter harmonics = addParameter(FxParameter.intParam(
            "hum_harm", "Harmonics", "", 1, 10, 1, 6));
    private final FxParameter notchQ = addParameter(FxParameter.floatParam(
            "hum_q", "Notch Q", "", 5f, 60f, 25f));
    private final FxParameter depth = addParameter(FxParameter.dbParam(
            "hum_depth", "Attenuation", -48f, 0f, -36f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter filter = new BiquadFilter();

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
        return "Notch cascade at the mains frequency and its harmonics";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
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

    @Override
    public boolean isAudible() {
        return isEnabled() && depth.getFloat() < -0.01f;
    }

    private void rebuild() {
        float f0 = baseFreq.getCurrentRaw();
        int k = Math.max(1, harmonics.getCurrentRaw());
        float q = Math.max(1f, notchQ.getFloat());
        float depthDb = Math.min(-0.1f, depth.getFloat());
        java.util.List<float[]> sections = new java.util.ArrayList<>();
        for (int i = 1; i <= k; i++) {
            float f = f0 * i;
            if (f >= sampleRate * 0.45f) break;
            // A peaking EQ with negative gain is a controlled-depth notch:
            // the magnitude dips exactly `depthDb` dB at f0.
            sections.add(Dsp.rbjPeaking(sampleRate, f, q, depthDb));
        }
        filter.setSections(sections.toArray(new float[0][]), channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        rebuild();
        filter.process(buf, frames, Math.max(1, Math.min(2, channels)));
    }
}
