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
        int need = delayFrames * 2 + 1;
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

        for (int f = 0; f < frames; f++) {
            int base = f * ch;
            float inL = buf[base];
            float inR = ch > 1 ? buf[base + 1] : inL;
            float wetL = 0f;
            float wetR = 0f;
            for (int k = 1; k <= taps; k++) {
                int offset;
                float amp;
                if (typeIdx == 0) {
                    offset = delayFrames * k;
                    amp = (float) Math.pow(levelLin, k);
                } else {
                    // Bouncing ball: geometric spacing between taps.
                    offset = (int) (delayFrames * k * (1f + 0.35f * k));
                    amp = (float) Math.pow(levelLin, k);
                }
                if (offset >= delayLine.length / ch) break;
                int pos = (writePos - offset + delayLine.length / ch)
                        % (delayLine.length / ch);
                wetL += delayLine[pos * ch] * amp;
                if (ch > 1) wetR += delayLine[pos * ch + 1] * amp;
            }
            if (typeIdx == 2) {
                float tL = wetL;
                float tR = wetR;
                wetL = tR;
                wetR = tL;
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
            buf[base] = outL;
            if (ch > 1) buf[base + 1] = outR;

            delayLine[writePos * ch] = outL;
            if (ch > 1) delayLine[writePos * ch + 1] = outR;
            writePos = (writePos + 1) % (delayLine.length / ch);
        }
    }
}
