package com.example.hello;

/**
 * Brickwall ceiling limiter ("Brickwall Limiter Pro"). Drives up to +12 dB into
 * a user-configurable soft-clip ceiling, and doubles as the safety ceiling when
 * any upstream gain stage (compressor, warmth, presence) is active.
 *
 * <p>When acting purely as an automatic safety ceiling ({@code drive == 0} and
 * secondary parameters at defaults), signals below the ceiling pass untouched
 * and only peaks approaching or exceeding the ceiling are limited.
 */
public final class ProLimiterProcessor extends AudioProcessor {

    private final FxParameter drive = addParameter(
            FxParameter.intParam("drive", "Drive", "%", 0, 100, 5, 0));
    private final FxParameter ceiling = addParameter(
            FxParameter.floatParam("limiter_ceiling", "Ceiling", " dBFS", -6.0f, -0.1f, -0.2f));
    private final FxParameter release = addParameter(
            FxParameter.intParam("limiter_release", "Release", " ms", 10, 500, 10, 80));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private boolean safetyOn = false;
    private float limGain = 1.0f;

    @Override
    public String getId() {
        return "limiter";
    }

    @Override
    public String getTitle() {
        return "Brickwall Limiter Pro";
    }

    @Override
    public String getDescription() {
        return "Zero-overshoot ceiling limiter with up to +12 dB drive";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        limGain = 1.0f;
    }

    void setLegacyState(int drivePct, boolean safetyCeiling) {
        drive.setCurrentRaw(drivePct);
        this.safetyOn = safetyCeiling;
    }

    public boolean isSafetyOn() {
        return safetyOn;
    }

    private boolean hasExplicitLimiterDial() {
        return drive.getCurrentRaw() > 0
                || ceiling.getFloat() < -0.25f
                || release.getCurrentRaw() != 80;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (hasExplicitLimiterDial() || safetyOn);
    }

    private float ceilingLin() {
        return Math.min(0.99f, Math.max(0.25f,
                DspChain.fastDbToLin(ceiling.getFloat())));
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        float lAmt = drive.getCurrentRaw() / 100.0f;
        boolean explicitLimiter = hasExplicitLimiterDial();
        if (!explicitLimiter && !safetyOn) return;

        int sr = Math.max(8000, sampleRate);
        float c = ceilingLin();
        float driveDb = (0.30f * lAmt + 0.70f * lAmt * lAmt) * 6.5f;
        float limDrive = lAmt > 0.001f ? DspChain.fastDbToLin(driveDb) : 1.0f;
        float relSec = Math.max(0.005f, release.getCurrentRaw() / 1000.0f);
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * relSec));
        float attAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * 0.0005f));

        // Only limit peaks at or above the user's true ceiling. A relative
        // threshold based on the chunk peak compressed quiet material far
        // below the ceiling.
        float effThresh = c;

        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx] * limDrive;
            float sR = channels == 2 ? buf[idx + 1] * limDrive : sL;
            float peak = Math.max(Math.abs(sL), Math.abs(sR));
            float want = peak > effThresh ? (effThresh / peak) : 1.0f;
            limGain += (want < limGain ? attAlpha : relAlpha) * (want - limGain);
            sL *= limGain;
            sR *= limGain;
            buf[idx] = softClipSample(sL, c);
            if (channels == 2) {
                buf[idx + 1] = softClipSample(sR, c);
            }
        }
    }

    /**
     * Enforces the soft-clip ceiling in place after a partial wet/dry blend
     * so upstream gain stages cannot clip the output when safety limiting is
     * active.
     */
    void enforceSafetyCeiling(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        float c = ceilingLin();
        int n = Math.min(buf.length, frames * channels);
        for (int i = 0; i < n; i++) {
            buf[i] = softClipSample(buf[i], c);
        }
    }

    private static float softClipSample(float x, float c) {
        float ax = Math.abs(x);
        float knee = 0.85f * c;
        if (ax <= knee) return x;
        float span = Math.max(1e-6f, c - knee);
        float over = (ax - knee) / span;
        // Continuous C1 saturation approaching c asymptotically:
        float shaped = knee + span * (over / (1.0f + over));
        return Math.copySign(Math.min(c, shaped), x);
    }
}
