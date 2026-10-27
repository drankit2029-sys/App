package com.example.hello;

/**
 * Stage 6 configurable multi-echo delay: a regular multi-tap delay, a
 * bouncing-ball pattern or its reverse, with per-echo level scaling and a
 * high-cut damping filter on the feedback path.
 */
final class MultiDelayProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Regular", "Bouncing Ball",
            "Reverse Bouncing Ball"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "md_type", "Delay Type", TYPE_LABELS, 0));
    private final FxParameter level = addParameter(FxParameter.dbParam(
            "md_level", "Delay Level", -24f, 1f, -6f));
    private final FxParameter delayTime = addParameter(FxParameter.intParam(
            "md_time", "Delay Time", " ms", 10, 3000, 10, 300));
    private final FxParameter echoes = addParameter(FxParameter.intParam(
            "md_echoes", "Number of Echoes", "", 1, 30, 1, 5));
    private final FxParameter highCut = addParameter(FxParameter.intParam(
            "md_hicut", "High-Cut Damping", " Hz", 1000, 20000, 100, 8000));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private float[] delayLine = new float[0];
    private int delayFrames = 0;
    private int writePos = 0;
    private BiquadFilter damp = new BiquadFilter();

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
        return "Regular, bouncing-ball or reverse multi-tap echo";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.SPATIAL;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        if (delayLine != null) java.util.Arrays.fill(delayLine, 0f);
        writePos = 0;
        damp.reset();
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public int getTailFrames() {
        return delayFrames * 4;
    }

    private void rebuild() {
        delayFrames = Math.max(1,
                (int) (delayTime.getCurrentRaw() * 0.001f * sampleRate));
        int taps = Math.max(1, echoes.getCurrentRaw());
        int need = Math.max(delayFrames * (taps + 2), delayFrames * 2 + 1);
        if (delayLine.length < need * channels) {
            delayLine = new float[need * channels];
            writePos = 0;
        }
        damp.setSections(new float[][]{
                Dsp.rbjLowPass(sampleRate, highCut.getCurrentRaw(), 0.7071)},
                channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        int taps = Math.max(1, echoes.getCurrentRaw());
        float levelLin = Dsp.dbToLin(level.getFloat());
        int typeIdx = type.getEnumIndex();
        int maxFrames = delayLine.length / ch;

        for (int f = 0; f < frames; f++) {
            int base = f * ch;
            float inL = buf[base];
            float inR = ch > 1 ? buf[base + 1] : inL;
            float wetL = 0f;
            float wetR = 0f;
            float ampSum = 0f;
            for (int k = 1; k <= taps; k++) {
                int offset;
                float amp;
                if (typeIdx == 0) {
                    offset = delayFrames * k;
                    amp = (float) Math.pow(levelLin, k);
                } else if (typeIdx == 1) {
                    // Bouncing ball: shortening intervals as echoes decay.
                    double ratio = 1.0 - Math.pow(0.82, k);
                    offset = Math.max(1, (int) Math.round(delayFrames * taps * ratio));
                    amp = (float) Math.pow(levelLin, k);
                } else {
                    // Reverse bouncing ball: expanding intervals with rising level.
                    offset = delayFrames * k;
                    amp = (float) Math.pow(levelLin, (taps - k + 1));
                }
                ampSum += Math.abs(amp);
                if (offset >= maxFrames) offset = maxFrames - 1;
                int pos = (writePos - offset + maxFrames) % maxFrames;
                wetL += delayLine[pos * ch] * amp;
                if (ch > 1) wetR += delayLine[pos * ch + 1] * amp;
            }
            // Damping on the feedback path.
            float[] dampBuf = new float[ch];
            dampBuf[0] = wetL;
            if (ch > 1) dampBuf[1] = wetR;
            damp.process(dampBuf, 1, ch);
            wetL = dampBuf[0];
            wetR = ch > 1 ? dampBuf[1] : wetL;

            float outL = inL + wetL;
            float outR = inR + wetR;
            if (!Float.isFinite(outL) || Math.abs(outL) > 1e4f) {
                outL = inL;
                wetL = 0f;
            }
            if (!Float.isFinite(outR) || Math.abs(outR) > 1e4f) {
                outR = inR;
                wetR = 0f;
            }
            buf[base] = outL;
            if (ch > 1) buf[base + 1] = outR;

            // The delay line is the feedback path: its loop gain is the sum of
            // the tap amplitudes. With a level above 0 dB and many echoes that
            // sum exceeds unity and the repeats compound without bound, so the
            // stored signal is attenuated to keep the loop stable while the
            // audible taps keep the requested level.
            float regen = ampSum > 0.9f ? 0.9f / ampSum : 1f;
            float storeL = inL + wetL * regen;
            float storeR = inR + wetR * regen;
            delayLine[writePos * ch] = Float.isFinite(storeL) ? storeL : 0f;
            if (ch > 1) {
                delayLine[writePos * ch + 1] = Float.isFinite(storeR) ? storeR : 0f;
            }
            writePos = (writePos + 1) % (delayLine.length / ch);
        }
    }
}
