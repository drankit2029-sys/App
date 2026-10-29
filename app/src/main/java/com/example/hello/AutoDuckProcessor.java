package com.example.hello;

/**
 * Stage 4 Auto Duck / Envelope Follower: detects vocal presence above
 * Threshold and smoothly ramps between full gain and the Duck Amount with
 * user-configurable Fade-Down, Fade-Up, and Maximum Pause (hold time before
 * releasing during brief speech pauses).
 */
final class AutoDuckProcessor extends AudioProcessor {

    private final FxParameter duckAmount = addParameter(FxParameter.dbParam(
            "duck_amount", "Duck Amount", -36f, 0f, -12f));
    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "duck_thresh", "Threshold", -60f, -3f, -28f));
    private final FxParameter fadeDown = addParameter(FxParameter.intParam(
            "duck_down", "Fade Down", " ms", 10, 2000, 10, 120));
    private final FxParameter fadeUp = addParameter(FxParameter.intParam(
            "duck_up", "Fade Up", " ms", 10, 3000, 10, 400));
    private final FxParameter pause = addParameter(FxParameter.intParam(
            "duck_pause", "Max Pause", " ms", 50, 5000, 50, 500));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float env = 0f;
    private float gain = 1.0f;
    private int holdLeft = 0;

    AutoDuckProcessor() {
    }

    @Override
    public String getId() {
        return "autoduck";
    }

    @Override
    public String getTitle() {
        return "Auto Duck";
    }

    @Override
    public String getDescription() {
        return "Envelope-triggered ducking with pause hold & smooth fade curves";
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
        env = 0f;
        gain = 1.0f;
        holdLeft = 0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && Math.abs(duckAmount.getFloat()) > 0.05f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        float threshLin = Dsp.dbToLin(threshold.getFloat());
        float duckLin = Dsp.dbToLin(duckAmount.getFloat());
        int downFrames = Math.max(1, (int) (sr * (fadeDown.getCurrentRaw() / 1000.0f)));
        int upFrames = Math.max(1, (int) (sr * (fadeUp.getCurrentRaw() / 1000.0f)));
        int pauseFrames = Math.max(1, (int) (sr * (pause.getCurrentRaw() / 1000.0f)));
        float downStep = (1.0f - duckLin) / downFrames;
        float upStep = (1.0f - duckLin) / upFrames;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float peak = Math.abs(buf[idx]);
            if (ch == 2) peak = Math.max(peak, Math.abs(buf[idx + 1]));
            env += (peak > env ? 0.03f : 0.002f) * (peak - env);

            // Straight threshold detection with a true pause hold: while the
            // envelope is above Threshold the hold timer is continually
            // refreshed, and brief gaps keep ducking until Max Pause elapses.
            boolean active;
            if (env > threshLin) {
                holdLeft = pauseFrames;
                active = true;
            } else if (holdLeft > 0) {
                holdLeft--;
                active = true;
            } else {
                active = false;
            }

            if (active) {
                gain = Math.max(duckLin, gain - downStep);
            } else {
                gain = Math.min(1.0f, gain + upStep);
            }
            for (int c = 0; c < ch; c++) buf[idx + c] *= gain;
        }
    }
}
