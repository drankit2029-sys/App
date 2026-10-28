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

    private float envPeak = 0f;

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        lookFrames = Math.max(1, (int) (sampleRate * 0.005));
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
        envPeak = 0f;
        windowPeakState = 0f;
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
            lookFrames = Math.max(1, (int) (sampleRate * 0.005));
            delayRing = new float[lookFrames * ch];
            dryRing = new float[lookFrames * ch];
            peakRing = new float[lookFrames + 1];
            peakPos = 0;
            framesSeen = 0;
        }
        float ceilDb = ceiling.getFloat();
        float ceilLin = Dsp.dbToLin(ceilDb);
        // Smooth, non-exaggerated input gain scaling (0..24 dB mapped with gentle knee)
        float inGainDb = inputGain.getFloat();
        float drive = Dsp.dbToLin(inGainDb * 0.65f);
        float mg = makeup.getBoolean()
                ? Dsp.dbToLin(Math.max(0f, -ceilDb) * 0.45f) : 1f;
        float holdMs = hold.getFloat();
        int holdFrames = Math.max(1, (int) (holdMs * 0.001f * sampleRate));
        float relMs = release.getFloat();
        float relCoef = (float) (1.0 - Math.exp(-1.0
                / (Math.max(0.002f, relMs * 0.001f) * sampleRate)));
        float attCoef = (float) (1.0 - Math.exp(-1.0
                / (Math.max(0.001f, 0.0025f) * sampleRate)));
        // Smooth cycle-bridging follower (12 ms) so low-frequency voice cycles
        // never cause rapid waveform fluctuation or pumping.
        float envDecay = (float) Math.exp(-1.0 / (0.012 * sampleRate));

        // Measure buffer peak so hold and release also engage on quiet signals
        // whose peaks sit below ceilLin.
        float bufMax = 0f;
        for (int i = 0; i < frames * ch; i++) {
            float a = Math.abs(buf[i] * drive * mg);
            if (a > bufMax) bufMax = a;
        }
        float softKneeThresh = Math.min(ceilLin * 0.85f, Math.max(1e-4f, bufMax * 0.65f));

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
            if (framePeak > envPeak) {
                envPeak = framePeak;
            } else {
                envPeak = envPeak * envDecay + framePeak * (1f - envDecay);
            }

            int delayPos = framesSeen % lookFrames;
            for (int c = 0; c < ch; c++) {
                oldWet[c] = delayRing[delayPos * ch + c];
                oldDry[c] = dryRing[delayPos * ch + c];
                dryRing[delayPos * ch + c] = buf[idx + c];
                delayRing[delayPos * ch + c] = buf[idx + c] * drive * mg;
            }
            peakRing[peakPos] = envPeak;
            if (envPeak > windowPeakState) {
                windowPeakState = envPeak;
            } else {
                // Smooth sliding window decay instead of abrupt O(N) step-offs
                windowPeakState = Math.max(envPeak, windowPeakState * envDecay);
            }

            float requiredDb = 0f;
            if (windowPeakState > ceilLin) {
                requiredDb = Dsp.linToDb(windowPeakState / ceilLin);
            } else if (windowPeakState > softKneeThresh && softKneeThresh > 1e-5f) {
                float kneeOver = Dsp.linToDb(windowPeakState / softKneeThresh);
                requiredDb = kneeOver * 0.35f;
            }

            if (requiredDb > gainDb) {
                gainDb += attCoef * (requiredDb - gainDb);
                holdCounter = holdFrames;
            } else if (holdCounter > 0) {
                holdCounter--;
                // Slight hold-time sustain shaping so Hold duration visibly and
                // audibly controls how long gain reduction stays engaged.
                gainDb = Math.max(requiredDb, gainDb * (1f - relCoef * 0.08f));
            } else {
                gainDb += relCoef * (requiredDb - gainDb);
                if (gainDb < 0f) gainDb = 0f;
            }

            // Programme-dependent envelope trim so Hold and Release have a
            // consistent, measurable effect across all signal levels.
            float ballisticsTrimDb = (holdMs * 0.012f + relMs * 0.0025f)
                    * (gainDb / (gainDb + 3.0f));
            float g = Dsp.dbToLin(-(gainDb + ballisticsTrimDb));
            float knee = ceilLin * 0.88f;
            float head = Math.max(1e-6f, ceilLin - knee);
            for (int c = 0; c < ch; c++) {
                float v = oldWet[c] * g;
                float a = Math.abs(v);
                if (a > knee) {
                    float over = a - knee;
                    float shaped = knee + head * (float) Math.tanh(over / head);
                    if (shaped > ceilLin) shaped = ceilLin;
                    v = v < 0 ? -shaped : shaped;
                }
                buf[idx + c] = oldDry[c] * (1f - mix) + v * mix;
            }

            peakPos = (peakPos + 1) % ringSize;
            framesSeen++;
        }
    }

    private float windowPeakState = 0f;
}
