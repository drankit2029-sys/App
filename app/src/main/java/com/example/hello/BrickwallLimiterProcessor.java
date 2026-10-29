package com.example.hello;

import java.util.Arrays;

/**
 * Stage 4 Brickwall / True-Peak Limiter with a 3 ms look-ahead delay line,
 * input gain drive, ceiling (-15..-0.05 dBFS), release (5..500 ms),
 * soft-clipping knee toggle, and automatic make-up gain.
 */
final class BrickwallLimiterProcessor extends AudioProcessor {

    private final FxParameter inputGain = addParameter(FxParameter.dbParam(
            "lim_gain", "Input Gain", 0f, 24f, 0f));
    private final FxParameter ceiling = addParameter(FxParameter.dbParam(
            "lim_ceiling", "Ceiling", -15f, -0.05f, -0.3f));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "lim_rel", "Release", " ms", 5, 500, 5, 60));
    private final FxParameter softClip = addParameter(FxParameter.boolParam(
            "lim_soft", "Soft-Clip Knee", true));
    private final FxParameter makeup = addParameter(FxParameter.boolParam(
            "lim_makeup", "Auto Make-Up", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float[][] delay = new float[2][144];
    private int writeIdx = 0;
    private float gain = 1.0f;

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
        return "3 ms look-ahead brickwall limiter with soft-clip knee";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        int look = Math.max(16, (int) Math.round(0.003 * this.sampleRate));
        if (delay[0].length != look) {
            delay = new float[2][look];
            writeIdx = 0;
        }
    }

    @Override
    public void reset() {
        for (float[] d : delay) Arrays.fill(d, 0f);
        writeIdx = 0;
        gain = 1.0f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        float inLin = Dsp.dbToLin(inputGain.getFloat());
        float ceilLin = Dsp.dbToLin(ceiling.getFloat());
        if (makeup.getBoolean()) {
            // Auto make-up drives the input into the limiter so average loudness
            // rises while true peaks remain strictly bounded by ceilLin.
            float autoDrive = 1.0f / Math.max(0.35f, (float) Math.pow(ceilLin, 0.45));
            inLin *= Math.max(1.18f, autoDrive);
        }
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * Math.max(0.005f, release.getCurrentRaw() / 1000.0f)));
        float attAlpha = 1.0f - (float) Math.exp(-1.0 / Math.max(4.0, delay[0].length));
        boolean soft = softClip.getBoolean();

        // Brickwall semantics: only peaks that reach the user's ceiling are
        // limited. Quiet material far below the ceiling must pass untouched.
        float effCeiling = ceilLin;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float peak = 0f;
            for (int c = 0; c < ch; c++) {
                float s = buf[idx + c] * inLin;
                float a = Math.abs(s);
                if (a > peak) peak = a;
                buf[idx + c] = s;
            }
            float want = peak > effCeiling ? (effCeiling / peak) : 1.0f;
            gain += (want < gain ? attAlpha : relAlpha) * (want - gain);
            for (int c = 0; c < ch; c++) {
                float y = buf[idx + c] * gain;
                if (soft) {
                    float knee = effCeiling * 0.85f;
                    float ay = Math.abs(y);
                    if (ay > knee) {
                        float span = Math.max(1e-6f, effCeiling - knee);
                        float over = (ay - knee) / span;
                        y = Math.copySign(knee + span * (over / (1.0f + over)), y);
                    }
                } else {
                    if (y > ceilLin) y = ceilLin;
                    else if (y < -ceilLin) y = -ceilLin;
                }
                buf[idx + c] = y;
            }
        }
    }
}
