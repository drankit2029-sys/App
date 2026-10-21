package com.example.hello;

/**
 * Stage 6 simple feedback echo: {@code y[n] = x[n] + decay * y[n - D]} on a
 * circular buffer of {@code D} samples.
 */
final class EchoProcessor extends AudioProcessor {

    private final FxParameter delayTime = addParameter(FxParameter.intParam(
            "echo_time", "Delay Time", " ms", 10, 5000, 10, 500));
    private final FxParameter decay = addParameter(FxParameter.floatParam(
            "echo_decay", "Decay Factor", "", 0.01f, 0.95f, 0.4f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private float[] buffer = new float[0];
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
        return "Simple feedback echo with adjustable decay";
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
        if (buffer != null) java.util.Arrays.fill(buffer, 0f);
        writePos = 0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && decay.getFloat() > 0.001f;
    }

    @Override
    public int getTailFrames() {
        return (int) (sampleRate * 2.0);
    }

    private void rebuild() {
        int frames = Math.max(1,
                (int) (delayTime.getCurrentRaw() * 0.001f * sampleRate));
        if (buffer.length != frames * channels) {
            buffer = new float[frames * channels];
            writePos = 0;
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        float d = decay.getFloat();
        int size = buffer.length / ch;
        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            int readPos = (writePos - size + size) % size;
            float delayedL = buffer[readPos * ch];
            float delayedR = ch > 1 ? buffer[readPos * ch + 1] : delayedL;
            float inL = buf[idx];
            float inR = ch > 1 ? buf[idx + 1] : inL;
            float outL = inL + delayedL * d;
            float outR = inR + delayedR * d;
            buf[idx] = outL;
            if (ch > 1) buf[idx + 1] = outR;
            buffer[writePos * ch] = outL;
            if (ch > 1) buffer[writePos * ch + 1] = outR;
            writePos = (writePos + 1) % size;
        }
    }
}
