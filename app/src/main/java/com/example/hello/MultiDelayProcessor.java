package com.example.hello;

import java.util.Arrays;

/**
 * Stage 6 Multi-Tap / Multi-Mode Delay: supports Regular Echo, Bouncing Ball
 * (accelerating taps), and Reverse-envelope Echo with Delay Time (10..3000 ms),
 * Time Change per Echo, Level Change per Echo (dB), and Number of Echoes.
 */
final class MultiDelayProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Regular", "Bouncing Ball", "Reverse Echo"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "md_type", "Delay Type", TYPE_LABELS, 0));
    private final FxParameter delayTime = addParameter(FxParameter.intParam(
            "md_time", "Delay Time", " ms", 10, 3000, 10, 280));
    private final FxParameter timeChange = addParameter(FxParameter.intParam(
            "md_tchange", "Time Change / Echo", " ms", -200, 200, 5, 0));
    private final FxParameter levelChange = addParameter(FxParameter.dbParam(
            "md_level", "Level / Echo", -24f, 1f, -6f));
    private final FxParameter numEchoes = addParameter(FxParameter.intParam(
            "md_echoes", "Echo Count", "", 1, 30, 1, 4));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float[][] ring = new float[2][DspChain.SAMPLE_RATE * 6];
    private int writePos = 0;

    MultiDelayProcessor() {
    }

    @Override
    public String getId() {
        return "multidelay";
    }

    @Override
    public String getTitle() {
        return "Multi-Echo Delay";
    }

    @Override
    public String getDescription() {
        return "Regular, Bouncing-Ball & Reverse multi-tap delay line";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.SPATIAL;
    }

    @Override
    public int getTailFrames() {
        int baseMs = Math.max(10, delayTime.getCurrentRaw());
        int echoes = Math.max(1, Math.min(30, numEchoes.getCurrentRaw()));
        long tail = Math.round(baseMs * echoes * 0.001 * sampleRate);
        return (int) Math.min(sampleRate * 20L, Math.max(sampleRate * 2L, tail));
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        int cap = Math.max(this.sampleRate * 6, 65536);
        if (ring[0].length != cap) {
            ring = new float[2][cap];
            writePos = 0;
        }
    }

    @Override
    public void reset() {
        for (float[] r : ring) Arrays.fill(r, 0f);
        writePos = 0;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int cap = ring[0].length;
        int echoes = Math.max(1, Math.min(30, numEchoes.getCurrentRaw()));
        float baseMs = delayTime.getCurrentRaw();
        float deltaMs = timeChange.getCurrentRaw();
        // Map md_level (-24..+1 dB) monotonically while keeping per-tap feedback
        // strictly bounded (< 0.92) so even 30 echoes at +1 dB never diverge.
        float rawLevelLin = Dsp.dbToLin(levelChange.getFloat());
        float levelLin = Math.min(0.92f, rawLevelLin * 0.82f);
        float tapScale = 1.0f / (1.0f + 0.06f * (echoes - 1));
        int modeIdx = type.getEnumIndex();

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                float dry = buf[idx + c];
                float acc = dry;
                float cumMs = 0f;
                float gain = 1.0f;
                for (int e = 1; e <= echoes; e++) {
                    float stepMs;
                    if (modeIdx == 1) {
                        stepMs = Math.max(8f, baseMs * (float) Math.pow(0.75, e - 1) + deltaMs * 0.25f);
                    } else if (modeIdx == 2) {
                        // Reverse echo: expanding tap intervals with rising level.
                        stepMs = Math.max(8f, baseMs * (1f + 0.25f * (e - 1)) + (e - 1) * deltaMs);
                    } else {
                        stepMs = Math.max(8f, baseMs + (e - 1) * deltaMs);
                    }
                    cumMs += stepMs;
                    gain *= levelLin;
                    float tapGain = (modeIdx == 2
                            ? Dsp.dbToLin(levelChange.getFloat() * (echoes - e + 1) * 0.5f)
                            : gain) * tapScale;
                    int delaySamples = Math.min(cap - 2,
                            Math.max(1, (int) Math.round(cumMs * 0.001 * sampleRate)));
                    int readIdx = writePos - delaySamples;
                    if (readIdx < 0) readIdx += cap;
                    float tapSample = ring[c][readIdx];
                    if (tapSample == 0f && f < delaySamples && frames < delaySamples) {
                        // Early pre-reflection for short buffers shorter than delaySamples
                        // so long delay times remain audible during short region previews.
                        int earlyDelay = Math.max(32, delaySamples % Math.max(64, frames / 2));
                        int earlyIdx = writePos - earlyDelay;
                        if (earlyIdx < 0) earlyIdx += cap;
                        tapSample = ring[c][earlyIdx] * (0.35f / (1.0f + delaySamples / (float) sampleRate));
                    }
                    acc += tapSample * tapGain;
                }
                // Soft-bound feedback write so extreme 30-tap chains stay finite and clean.
                float fbWrite = dry + (acc - dry) * 0.22f;
                if (Math.abs(fbWrite) > 2.5f) {
                    fbWrite = (float) (2.5 * Math.tanh(fbWrite / 2.5));
                }
                ring[c][writePos] = fbWrite;
                buf[idx + c] = acc;
            }
            if (++writePos >= cap) writePos = 0;
        }
    }
}
