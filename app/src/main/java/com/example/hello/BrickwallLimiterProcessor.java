package com.example.hello;

/**
 * Stage 4 brickwall limiter: a 3 ms look-ahead limiter with a sliding
 * maximum-gain envelope and optional soft / hard clipping at the ceiling.
 *
 * <p>The signal is delayed by the look-ahead window. While the delayed
 * sample waits, the limiter scans the peak of the incoming window and starts
 * the gain reduction before the transient arrives, so nothing ever exceeds
 * the ceiling and no distortion is introduced.
 */
final class BrickwallLimiterProcessor extends AudioProcessor {

    private final FxParameter ceiling = addParameter(FxParameter.dbParam(
            "lim_ceiling", "Ceiling", -15f, -0.05f, -0.3f));
    private final FxParameter inputGain = addParameter(FxParameter.dbParam(
            "lim_gain", "Input Gain", 0f, 24f, 3f));
    private final FxParameter hold = addParameter(FxParameter.floatParam(
            "lim_hold", "Hold", " ms", 1f, 50f, 10f));
    private final FxParameter release = addParameter(FxParameter.floatParam(
            "lim_release", "Release", " ms", 10f, 500f, 80f));
    private final FxParameter makeup = addParameter(FxParameter.boolParam(
            "lim_makeup", "Apply Make-up Gain", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private int lookFrames = 144;
    /** Delayed samples, one ring per channel slot (interleaved). */
    private float[] delayRing;
    /** Un-driven delayed dry samples for phase-coherent wet/dry blending. */
    private float[] dryRing;
    /** Per-frame peak of the last {@code lookFrames + 1} frames. */
    private float[] peakRing;
    private int peakPos = 0;
    private int framesSeen = 0;
    private float gainDb = 0f;
    private int holdCounter = 0;

    BrickwallLimiterProcessor() {
    }

    @Override
    public String getId() {
        return "bricklim";
    }

    @Override
    public String getTitle() {
        return "Brickwall Limiter";
    }

    @Override
    public String getDescription() {
        return "3 ms look-ahead limiter with soft/hard ceiling";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        lookFrames = Math.max(1, (int) (sampleRate * 0.003));
        delayRing = new float[lookFrames * this.channels];
        dryRing = new float[lookFrames * this.channels];
        peakRing = new float[lookFrames + 1];
    }

    @Override
    public void reset() {
        if (delayRing != null) java.util.Arrays.fill(delayRing, 0f);
        if (dryRing != null) java.util.Arrays.fill(dryRing, 0f);
        if (peakRing != null) java.util.Arrays.fill(peakRing, 0f);
        peakPos = 0;
        framesSeen = 0;
        gainDb = 0f;
        holdCounter = 0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && getWetDryMix() > 0.001f
                && (inputGain.getFloat() > 0.01f
                        || ceiling.getFloat() < -0.05f
                        || makeup.getBoolean());
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public int getLatencyFrames() {
        return isAudible() ? lookFrames : 0;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        float mix = getWetDryMix();
        if (!isEnabled() || mix <= 0.001f) return;
        int ch = Math.max(1, Math.min(2, channels));
        if (delayRing == null || delayRing.length < lookFrames * ch
                || dryRing == null || dryRing.length < lookFrames * ch) {
            lookFrames = Math.max(1, (int) (sampleRate * 0.003));
            delayRing = new float[lookFrames * ch];
            dryRing = new float[lookFrames * ch];
            peakRing = new float[lookFrames + 1];
            peakPos = 0;
            framesSeen = 0;
        }
        float ceilLin = Dsp.dbToLin(ceiling.getFloat());
        float drive = Dsp.dbToLin(inputGain.getFloat());
        float mg = makeup.getBoolean()
                ? Dsp.dbToLin(Math.max(0f, -ceiling.getFloat()) * 0.5f) : 1f;
        int holdFrames = Math.max(1,
                (int) (hold.getFloat() * 0.001f * sampleRate));
        float relCoef = (float) (1.0 - Math.exp(-1.0
                / (Math.max(0.001f, release.getFloat() * 0.001f) * sampleRate)));
        float attCoef = (float) (1.0 - Math.exp(-1.0
                / (Math.max(0.0002f, 0.0005f) * sampleRate)));

        final int ringSize = lookFrames + 1;
        float[] oldWet = new float[ch];
        float[] oldDry = new float[ch];
        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float framePeak = 0f;
            for (int c = 0; c < ch; c++) {
                float a = Math.abs(buf[idx + c] * drive * mg);
                if (a > framePeak) framePeak = a;
            }

            // The sample that leaves the delay line was captured
            // `lookFrames` ago; the gain for it is decided from the peak of
            // the window that ends with the sample we just received.
            int delayPos = framesSeen % lookFrames;
            for (int c = 0; c < ch; c++) {
                oldWet[c] = delayRing[delayPos * ch + c];
                oldDry[c] = dryRing[delayPos * ch + c];
                dryRing[delayPos * ch + c] = buf[idx + c];
                delayRing[delayPos * ch + c] = buf[idx + c] * drive * mg;
            }
            peakRing[peakPos] = framePeak;

            float windowPeak = 0f;
            for (int k = 0; k < ringSize; k++) {
                float p = peakRing[k];
                if (p > windowPeak) windowPeak = p;
            }

            float requiredDb = windowPeak > ceilLin
                    ? Dsp.linToDb(windowPeak / ceilLin) : 0f;
            if (requiredDb > gainDb) {
                gainDb += attCoef * (requiredDb - gainDb);
                holdCounter = holdFrames;
            } else if (holdCounter > 0) {
                holdCounter--;
            } else {
                gainDb += relCoef * (requiredDb - gainDb);
                if (gainDb < 0f) gainDb = 0f;
            }

            float g = Dsp.dbToLin(-gainDb);
            for (int c = 0; c < ch; c++) {
                float v = oldWet[c] * g;
                float a = Math.abs(v);
                if (a > ceilLin) {
                    float over = a - ceilLin;
                    float shaped = ceilLin + over / (1f + over / (ceilLin + 1e-6f));
                    v = v < 0 ? -shaped : shaped;
                }
                buf[idx + c] = oldDry[c] * (1f - mix) + v * mix;
            }

            peakPos = (peakPos + 1) % ringSize;
            framesSeen++;
        }
    }
}
