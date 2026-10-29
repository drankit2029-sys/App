package com.example.hello;

/**
 * Stage 6 Wahwah / Auto-Wah: swept resonant state-variable band-pass filter
 * driven by a blend of an LFO and an input envelope follower (Auto-Wah
 * sensitivity).
 */
final class WahwahProcessor extends AudioProcessor {

    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "wah_rate", "LFO Rate", " Hz", 0.1f, 6f, 1.5f));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "wah_depth", "Depth", "%", 0, 100, 5, 70));
    private final FxParameter resonance = addParameter(FxParameter.floatParam(
            "wah_res", "Resonance (Q)", "", 0.5f, 10f, 3.0f));
    private final FxParameter freqOffset = addParameter(FxParameter.intParam(
            "wah_offset", "Freq Offset", "%", 0, 100, 5, 30));
    private final FxParameter phase = addParameter(FxParameter.intParam(
            "wah_phase", "Stereo Phase", "°", 0, 360, 15, 0));
    private final FxParameter autoWah = addParameter(FxParameter.intParam(
            "wah_auto", "Auto-Wah Env", "%", 0, 100, 5, 0));
    private final FxParameter outputGain = addParameter(FxParameter.dbParam(
            "wah_gain", "Output Gain", -18f, 12f, -6f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private final float[] low = new float[2];
    private final float[] band = new float[2];
    private double lfoPhase = 0.0;
    private float env = 0f;

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
        return "Swept resonant SVF wah & envelope-triggered auto-wah";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        low[0] = low[1] = 0f;
        band[0] = band[1] = 0f;
        lfoPhase = 0.0;
        env = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        float d = depth.getCurrentRaw() / 100f;
        float q = 1.0f / Math.max(0.5f, resonance.getFloat());
        float baseFrac = freqOffset.getCurrentRaw() / 100f;
        float envMix = autoWah.getCurrentRaw() / 100f;
        float outLin = Dsp.dbToLin(outputGain.getFloat());
        double lfoInc = 2.0 * Math.PI * rate.getFloat() / sr;
        double stereoRad = Math.toRadians(phase.getCurrentRaw());

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float peak = Math.abs(buf[idx]);
            if (ch == 2) peak = Math.max(peak, Math.abs(buf[idx + 1]));
            env += (peak > env ? 0.04f : 0.002f) * (peak - env);

            for (int c = 0; c < ch; c++) {
                double ph = lfoPhase + ((c == 1 || ch == 1) ? stereoRad : 0.0);
                double lfo01 = 0.5 * (1.0 + Math.sin(ph));
                double envSweep = Math.min(1.0, env * 2.2 + peak * 0.65);
                double sweep = (1.0 - envMix) * lfo01 + envMix * envSweep;
                double norm = Math.min(1.0, baseFrac * 0.6 + d * sweep);
                double fc = 350.0 * Math.pow(8.5, norm);
                float fCoeff = (float) (2.0 * Math.sin(Math.PI * Math.min(fc, sr * 0.22) / sr));
                float x = buf[idx + c];
                low[c] += fCoeff * band[c];
                float high = x - low[c] - q * band[c];
                band[c] += fCoeff * high;
                buf[idx + c] = (band[c] * 1.6f + x * 0.25f) * outLin;
            }
            lfoPhase += lfoInc;
            if (lfoPhase > 2.0 * Math.PI) lfoPhase -= 2.0 * Math.PI;
        }
    }
}
