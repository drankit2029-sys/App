package com.example.hello;

/**
 * Professional brickwall ceiling limiter: zero-latency peak detection with
 * 4x inter-sample interpolation, programme-dependent release and an
 * adjustable ceiling. It keeps the legacy safety role for the rack: whenever
 * an upstream gain stage is active the limiter can enforce the ceiling even
 * with its own mix at zero.
 */
final class ProLimiterProcessor extends AudioProcessor {

    private static final int DECIM = 16;

    private final FxParameter drive = addParameter(FxParameter.intParam(
            "drive", "Drive", "%", 0, 100, 1, 50));
    private final FxParameter ceiling = addParameter(FxParameter.dbParam(
            "limiter_ceiling", "Ceiling", -6f, 0f, -0.2f));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "limiter_release", "Release", " ms", 20, 500, 5, 80));

    private volatile boolean safetyOn = false;

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float gainCur = 1f;
    private final float[] prev = new float[2];

    ProLimiterProcessor() {
    }

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
        return "True-peak ceiling, drive and programme release";
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
        gainCur = 1f;
        prev[0] = prev[1] = 0f;
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

    private float drive01() {
        return drive.getCurrentRaw() / 100f;
    }

    private float ceilingLin() {
        return DspChain.fastDbToLin(ceiling.getCurrentRaw() / 100f);
    }

    /**
     * Unity-gain ceiling pass used when the limiter's wet/dry mix is below
     * 1.0 while upstream gain stages still need peak protection.
     */
    void enforceSafetyCeiling(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        float c = ceilingLin();
        int n = Math.min(buf.length, frames * Math.max(1,
                Math.min(2, channels)));
        for (int i = 0; i < n; i++) {
            buf[i] = softCeiling(buf[i], c);
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float lAmt = drive01();
        if (lAmt <= 0.001f && !safetyOn) return;

        final float c = ceilingLin();
        final float driveLin = lAmt > 0.001f
                ? DspChain.fastDbToLin(lAmt * 10.5f) : 1.0f;
        final float relSec = Math.max(1, release.getCurrentRaw()) * 0.001f;
        final float relCoef = (float) (1.0 - Math.exp(-1.0
                / (relSec * sampleRate)));

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                float peak = 0f;
                for (int cc = 0; cc < ch; cc++) {
                    float x = buf[idx + cc] * driveLin;
                    float p = prev[cc];
                    float tp = Math.max(Math.abs(x), Math.abs(p));
                    tp = Math.max(tp, Math.abs(0.25f * x + 0.75f * p));
                    tp = Math.max(tp, Math.abs(0.5f * x + 0.5f * p));
                    tp = Math.max(tp, Math.abs(0.75f * x + 0.25f * p));
                    peak = Math.max(peak, tp);
                }
                float desired = peak > c ? c / peak : 1f;
                if (desired < gainCur) {
                    gainCur = desired;
                } else {
                    gainCur += relCoef * (desired - gainCur);
                }
                for (int cc = 0; cc < ch; cc++) {
                    float x = buf[idx + cc] * driveLin;
                    prev[cc] = x;
                    buf[idx + cc] = softCeiling(x * gainCur, c);
                }
            }
            offset += blk;
        }
    }

    private static float softCeiling(float y, float c) {
        float ay = Math.abs(y);
        float knee = c * 0.85f;
        if (ay <= knee) return y;
        float range = c - knee;
        float d = ay - knee;
        float lim = knee + range * (d / (range + d));
        return y >= 0f ? lim : -lim;
    }
}
