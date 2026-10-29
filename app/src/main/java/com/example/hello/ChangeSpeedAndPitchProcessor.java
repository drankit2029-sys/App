package com.example.hello;

/**
 * Stage 7 change speed and pitch: a varispeed resampler that changes the
 * duration and the pitch together.
 */
final class ChangeSpeedAndPitchProcessor extends AudioProcessor {

    private final FxParameter speed = addParameter(FxParameter.floatParam(
            "speed_mult", "Speed Multiplier", "x", 0.25f, 4f, 1f));
    private final FxParameter percent = addParameter(FxParameter.floatParam(
            "speed_percent", "Percent Change", "%", -75f, 300f, 0f));

    private int sampleRate = DspChain.SAMPLE_RATE;

    ChangeSpeedAndPitchProcessor() {
    }

    @Override
    public String getId() {
        return "speedpitch";
    }

    @Override
    public String getTitle() {
        return "Change Speed and Pitch";
    }

    @Override
    public String getDescription() {
        return "Varispeed resampler: duration and pitch change together";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TIME;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isOfflineOnly() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // Offline-only effect: rendered through processOfflineRegion.
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && Math.abs(speedFactor() - 1.0) > 1e-6;
    }

    /** Speed multiplier: 2.0 plays twice as fast (half the duration). */
    private double speedFactor() {
        double s = speed.getFloat();
        double pct = percent.getFloat();
        double pctFactor = Math.max(0.20, 1.0 + pct / 100.0);
        double factor = s * pctFactor;
        return Math.max(0.1, Math.min(8.0, factor));
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        double factor = speedFactor();
        if (Math.abs(factor - 1.0) < 1e-6) return in.clone();
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sampleRate);
        return wsola.varispeed(in, frames, channels, factor);
    }
}
