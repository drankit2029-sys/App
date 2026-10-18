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
        return drive.getFloat() > 0.001f || safetyOn;
    }

    void setLegacyState(int drivePct, boolean safety) {
        drive.setCurrentRaw(drivePct);
        this.safetyOn = safety;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        final int ch = channels;
        final float lAmt = drive.getFloat();
        if ((lAmt <= 0.001f && !safetyOn) || frames <= 0) return;

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
