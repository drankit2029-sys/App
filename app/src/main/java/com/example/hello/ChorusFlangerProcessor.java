package com.example.hello;

/**
 * Stage 6 chorus and flanger: a modulated delay line read with 4-point
 * cubic Hermite interpolation and a 90 degree stereo LFO offset.
 */
final class ChorusFlangerProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Chorus", "Flanger", "Doubler"};

    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "ch_mode", "Mode", MODE_LABELS, 0));
    private final FxParameter baseDelay = addParameter(FxParameter.floatParam(
            "ch_delay", "Base Delay", " ms", 1f, 35f, 18f));
    private final FxParameter rate = addParameter(FxParameter.floatParam(
            "ch_rate", "LFO Rate", " Hz", 0.05f, 8f, 0.8f));
    private final FxParameter depth = addParameter(FxParameter.intParam(
            "ch_depth", "Modulation Depth", "%", 0, 100, 1, 45));
    private final FxParameter feedback = addParameter(FxParameter.intParam(
            "ch_fb", "Feedback", "%", -90, 90, 1, 15));
    private final FxParameter mix = addParameter(FxParameter.intParam(
            "ch_mix", "Wet/Dry Mix", "%", 0, 100, 1, 50));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    /** One mono ring buffer per channel. */
    private float[][] ring = new float[2][];
    private int mask = 0;
    private int writePos = 0;
    private double lfoPhase = 0;
    private float[] feedbackState = new float[2];

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
        return "Modulated delay with cubic Hermite fractional reads";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        for (float[] r : ring) {
            if (r != null) java.util.Arrays.fill(r, 0f);
        }
        writePos = 0;
        lfoPhase = 0;
        feedbackState[0] = feedbackState[1] = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && mix.getCurrentRaw() > 0;
    }

    @Override
    public int getTailFrames() {
        return (int) (sampleRate * 0.1);
    }

    private void rebuild() {
        int size = 1;
        while (size < 8192) size <<= 1;
        if (ring[0] == null || ring[0].length != size) {
            ring[0] = new float[size];
            ring[1] = new float[size];
            mask = size - 1;
            writePos = 0;
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        int modeIdx = mode.getEnumIndex();
        double baseSec = baseDelay.getFloat() * 0.001;
        if (modeIdx == 1) {
            baseSec = Math.min(baseSec, 0.0025);
        } else if (modeIdx == 2) {
            // Doubler mode: vocal slapback doubling window (15..35 ms) with
            // subtle pitch micro-detune and zero feedback regeneration.
            baseSec = Math.max(0.015, baseSec);
        }
        double baseSamples = baseSec * sampleRate;
        double depthSamples = baseSamples * (depth.getCurrentRaw() / 100.0) * 0.9;
        if (modeIdx == 2) {
            depthSamples *= 0.25;
        }
        double rateHz = rate.getFloat();
        double lfoInc = rateHz / sampleRate;
        float wet = mix.getCurrentRaw() / 100f;
        float dry = 1f - wet;
        double fb = (modeIdx == 2) ? 0.0 : (feedback.getCurrentRaw() / 100.0);

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                int idx = f * ch + c;
                float in = buf[idx];
                // 90 degree offset between the channels.
                double lfo = Math.sin(2 * Math.PI * (lfoPhase + (c == 1 ? 0.25 : 0.0)));
                double readPos = writePos - baseSamples + lfo * depthSamples;
                float size = mask + 1;
                while (readPos < 0) readPos += size;
                while (readPos >= size) readPos -= size;
                float delayed = Dsp.hermiteInterp(ring[c], mask, (float) readPos);
                float wetSample = delayed + feedbackState[c] * (float) fb;
                feedbackState[c] = wetSample;
                buf[idx] = in * dry + wetSample * wet;
                ring[c][writePos & mask] = in + wetSample * (float) fb * 0.5f;
            }
            lfoPhase += lfoInc;
            if (lfoPhase > 1.0) lfoPhase -= 1.0;
            writePos = (writePos + 1) & mask;
        }
    }
}
