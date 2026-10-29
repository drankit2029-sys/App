package com.example.hello;

import java.util.Arrays;

/**
 * Stage 6 Chorus, Flanger & ADT Doubler: fractional-delay line with 4-point
 * cubic Hermite interpolation, Mode selector (Chorus 8–25 ms / Flanger
 * 0.4–6 ms / Doubler 10–35 ms), LFO Rate, Depth, Feedback and Wet/Dry Mix.
 */
final class ChorusFlangerProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Chorus", "Flanger", "Doubler (ADT)"};
    private static final int RING_SIZE = 4096;
    private static final int RING_MASK = RING_SIZE - 1;

    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "ch_mode", "Mode", MODE_LABELS, 0));
    private final FxParameter delay = addParameter(FxParameter.floatParam(
            "ch_delay", "Base Delay", " ms", 1f, 35f, 15f));
    private final FxParameter depth = addParameter(FxParameter.floatParam(
            "ch_depth", "Mod Depth", " ms", 0.1f, 10f, 2.5f));
    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "ch_rate", "LFO Rate", " Hz", 0.05f, 8f, 0.8f));
    private final FxParameter feedback = addParameter(FxParameter.intParam(
            "ch_fb", "Feedback", "%", -90, 90, 5, 20));
    private final FxParameter mix = addParameter(FxParameter.intParam(
            "ch_mix", "Wet/Dry Mix", "%", 0, 100, 5, 50));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private final float[][] ring = new float[2][RING_SIZE];
    private int writePos = 0;
    private double lfoPhase = 0.0;

    ChorusFlangerProcessor() {
    }

    @Override
    public String getId() {
        return "chorus";
    }

    @Override
    public String getTitle() {
        return "Chorus & Flanger";
    }

    @Override
    public String getDescription() {
        return "Cubic-Hermite fractional-delay Chorus, Flanger & ADT Doubler";
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
        for (float[] r : ring) Arrays.fill(r, 0f);
        writePos = 0;
        lfoPhase = 0.0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (mix.getCurrentRaw() > 0
                || mode.getCurrentRaw() != mode.defaultRaw
                || delay.getCurrentRaw() != delay.defaultRaw
                || depth.getCurrentRaw() != depth.defaultRaw
                || rate.getCurrentRaw() != rate.defaultRaw
                || feedback.getCurrentRaw() != feedback.defaultRaw);
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        int m = mode.getEnumIndex();
        // Map Base Delay (1..35 ms) continuously in all three modes so scrubbing
        // ch_delay never hits a dead flat-top clamp in Flanger or Doubler mode.
        float rawDelay = delay.getFloat();
        float normDelay = (rawDelay - 1.0f) / 34.0f;
        float baseDelayMs = m == 1
                ? (0.4f + normDelay * 5.6f)
                : (m == 2 ? (10.0f + normDelay * 25.0f) : rawDelay);
        float depthMs = m == 1
                ? Math.min(3.5f, depth.getFloat())
                : depth.getFloat();
        // Smoothly map -90..+90% feedback into [-0.62, +0.62] with soft-saturation
        // so negative feedback never diverges while remaining responsive across -90..+90%.
        float fb = (feedback.getCurrentRaw() / 90.0f) * 0.62f;
        int rawMix = mix.getCurrentRaw();
        float wet = rawMix > 0 ? (rawMix / 100f) : 0.25f;
        float dry = 1f - wet * 0.4f;
        double lfoInc = 2.0 * Math.PI * rate.getFloat() / sr;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                double ph = lfoPhase + (c == 1 ? Math.PI * 0.5 : 0.0);
                float modMs = (float) Math.sin(ph) * depthMs;
                float totalSamples = Math.max(2f, Math.min(RING_SIZE - 8f,
                        (baseDelayMs + modMs) * 0.001f * sr));
                float readPos = writePos - totalSamples;
                float delayed = Dsp.hermiteInterp(ring[c], RING_MASK, readPos);
                float in = buf[idx + c];
                float fbIn = in + delayed * fb;
                if (Math.abs(fbIn) > 1.2f) {
                    fbIn = (float) (1.2 * Math.tanh(fbIn / 1.2));
                }
                ring[c][writePos & RING_MASK] = fbIn;
                buf[idx + c] = in * dry + delayed * wet;
            }
            writePos++;
            lfoPhase += lfoInc;
            if (lfoPhase > 2.0 * Math.PI) lfoPhase -= 2.0 * Math.PI;
        }
    }
}
