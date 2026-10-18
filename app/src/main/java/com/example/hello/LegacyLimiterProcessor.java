package com.example.hello;

/**
 * Stage 7 of the legacy voice chain: dedicated brickwall ceiling limiter.
 * Also acts as the chain's safety limiter whenever any of the gain-producing
 * stages (compressor, warmth, presence) is active, exactly like the original
 * engine. Body is a verbatim extraction of the original {@code DspChain}
 * stage so output stays bit-identical.
 */
final class LegacyLimiterProcessor extends AudioProcessor {

    private static final int DECIM = 16;

    private final FxParameter drive = addParameter(FxParameter.intParam(
            "drive", "Drive", "%", 0, 100, 1, 50));

    /** True when a gain-producing stage upstream needs the safety ceiling. */
    private volatile boolean safetyOn = false;

    @Override
    public String getId() {
        return "limiter";
    }

    @Override
    public String getTitle() {
        return "Brickwall Ceiling Limiter";
    }

    @Override
    public String getDescription() {
        return "Loudness drive + zero-overshoot ceiling to prevent peaking";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (drive01() > 0.001f || safetyOn);
    }

    void setLegacyState(int drivePct, boolean safety) {
        drive.setCurrentRaw(drivePct);
        this.safetyOn = safety;
    }

    boolean isSafetyOn() {
        return safetyOn;
    }

    /** Raw drive is a percent; gain math needs a 0..1 fraction. */
    private float drive01() {
        return drive.getCurrentRaw() / 100f;
    }

    /**
     * Applies only the unity-gain -0.2 dBFS brickwall safety ceiling without
     * additional drive, used when the limiter's wet/dry mix is below 1.0 while
     * upstream gain stages require peak protection.
     */
    void enforceSafetyCeiling(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        int n = Math.min(buf.length, frames * ch);
        for (int i = 0; i < n; i++) {
            float y = buf[i];
            float ay = Math.abs(y);
            if (ay > 0.82f) {
                float d = ay - 0.82f;
                float lim = 0.82f + 0.165f * (d / (0.165f + d));
                buf[i] = y >= 0f ? lim : -lim;
            }
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float lAmt = drive01();
        if (lAmt <= 0.001f && !safetyOn) return;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 7: Dedicated Brickwall Ceiling Limiter
            float driveLin = lAmt > 0.001f
                    ? DspChain.fastDbToLin(lAmt * 10.5f) : 1.0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float y = buf[idx + c] * driveLin;
                    float ay = Math.abs(y);
                    if (ay > 0.82f) {
                        float d = ay - 0.82f;
                        float lim = 0.82f + 0.165f * (d / (0.165f + d));
                        y = y >= 0f ? lim : -lim;
                    }
                    buf[idx + c] = y;
                }
            }

            offset += blk;
        }
    }
}
