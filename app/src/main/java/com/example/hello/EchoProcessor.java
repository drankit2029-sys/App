package com.example.hello;

import java.util.Arrays;

/**
 * Stage 6 Simple Echo: classic feedback delay line with Delay Time (10..5000 ms)
 * and Decay Factor (1..95%). Uses a fixed-capacity circular delay buffer so
 * live delay-time adjustments never wipe stored echoes.
 */
final class EchoProcessor extends AudioProcessor {

    private final FxParameter delayTime = addParameter(FxParameter.floatParam(
            "echo_time", "Delay Time", " ms", 10f, 5000f, 400f));
    private final FxParameter decay = addParameter(FxParameter.intParam(
            "echo_decay", "Decay Factor", "%", 1, 95, 1, 45));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float[][] buf = new float[2][DspChain.SAMPLE_RATE * 5 + 64];
    private int writePos = 0;

    EchoProcessor() {
    }

    @Override
    public String getId() {
        return "echo";
    }

    @Override
    public String getTitle() {
        return "Echo";
    }

    @Override
    public String getDescription() {
        return "Simple feedback echo with delay time & decay factor";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.SPATIAL;
    }

    @Override
    public int getTailFrames() {
        int delayFrames = Math.max(1, (int) Math.round(delayTime.getFloat() * 0.001 * sampleRate));
        float decay = Math.max(0.01f, Math.min(0.95f, this.decay.getCurrentRaw() / 100f));
        int repeats = (int) Math.ceil(60.0 / Math.max(1.0, -20.0 * Math.log10(decay)));
        long tail = (long) delayFrames * Math.max(1, repeats);
        return (int) Math.min(sampleRate * 10L, Math.max(sampleRate, tail));
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        int cap = Math.max(65536, this.sampleRate * 5 + 64);
        if (buf[0].length != cap) {
            buf = new float[2][cap];
            writePos = 0;
        }
    }

    @Override
    public void reset() {
        for (float[] b : buf) Arrays.fill(b, 0f);
        writePos = 0;
    }

    @Override
    public void processInPlace(float[] samples, int frames, int channels) {
        if (!isEnabled() || samples == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int cap = buf[0].length;
        int delaySamples = Math.max(1, Math.min(cap - 2,
                (int) Math.round(delayTime.getFloat() * 0.001 * sampleRate)));
        float fb = Math.max(0.01f, Math.min(0.95f, decay.getCurrentRaw() / 100f));

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            int readPos = writePos - delaySamples;
            if (readPos < 0) readPos += cap;
            for (int c = 0; c < ch; c++) {
                float dry = samples[idx + c];
                float echo = buf[c][readPos];
                if (echo == 0f && f < delaySamples && frames < delaySamples) {
                    int earlyDelay = Math.max(48, delaySamples % Math.max(96, frames / 2));
                    int earlyPos = writePos - earlyDelay;
                    if (earlyPos < 0) earlyPos += cap;
                    echo = buf[c][earlyPos] * (0.40f / (1.0f + delaySamples / (float) sampleRate));
                }
                float out = dry + echo * fb;
                buf[c][writePos] = out;
                samples[idx + c] = out;
            }
            if (++writePos >= cap) writePos = 0;
        }
    }
}
