package com.example.hello;

/**
 * Professional compressor / leveler: stereo-linked RMS + peak detection,
 * soft-knee gain computer, auto makeup and independent attack/release.
 * The primary "Compression" control keeps the legacy threshold curve so old
 * presets still sit at the same operating point; Ratio and Knee refine it.
 */
final class ProCompressorProcessor extends AudioProcessor {

    private static final int BLOCK = 16;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Compression", "%", 0, 100, 1, 40));
    private final FxParameter ratio = addParameter(FxParameter.floatParam(
            "comp_ratio", "Ratio", ":1", 1.0f, 12.0f, 6.0f));
    private final FxParameter attack = addParameter(FxParameter.intParam(
            "comp_attack", "Attack", " ms", 1, 100, 1, 10));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "comp_release", "Release", " ms", 40, 600, 5, 160));
    private final FxParameter knee = addParameter(FxParameter.intParam(
            "comp_knee", "Knee", " dB", 0, 18, 1, 6));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float envDb = -120f;
    private float gainCur = 1f;

    ProCompressorProcessor() {
    }

    @Override
    public String getId() {
        return "comp";
    }

    @Override
    public String getTitle() {
        return "Compressor Pro";
    }

    @Override
    public String getDescription() {
        return "Soft-knee leveler with ratio, attack, release and knee";
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
        envDb = -120f;
        gainCur = 1f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt.getCurrentRaw() > 0;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float amount = amt.getCurrentRaw() / 100f;
        if (amount <= 0.001f || !isEnabled()) return;

        final float threshDb = -2f - 21f * amount;
        final float effRatio = 1f + (ratio.getCurrentRaw() / 100f - 1f) * amount;
        final float slope = effRatio > 0.01f ? 1f - 1f / effRatio : 0.99f;
        final float kneeDb = knee.getCurrentRaw();
        final float makeupDb = -threshDb * slope * 0.5f;
        float attMs = attack.getCurrentRaw();
        float relMs = release.getCurrentRaw();
        final float aAtt = (float) (1.0 - Math.exp(-BLOCK
                / (attMs * 0.001 * sampleRate)));
        final float aRel = (float) (1.0 - Math.exp(-BLOCK
                / (relMs * 0.001 * sampleRate)));

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(BLOCK, frames - offset);
            float peak = 0f;
            float sumSq = 0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    peak = Math.max(peak, Math.abs(x));
                    sumSq += x * x;
                }
            }
            float rms = (float) Math.sqrt(sumSq / (blk * ch));
            float det = Math.max(peak, rms * 1.15f);
            float inDb = DspChain.fastLinToDb(det);
            float a = inDb > envDb ? aAtt : aRel;
            envDb += a * (inDb - envDb);

            float over = envDb - threshDb;
            float gainDb;
            if (over <= -kneeDb * 0.5f) {
                gainDb = 0f;
            } else if (kneeDb > 0f && over < kneeDb * 0.5f) {
                float t = over + kneeDb * 0.5f;
                gainDb = -slope * t * t / (2f * kneeDb);
            } else {
                gainDb = -slope * over;
            }
            FxTelemetry.publishGainReductionDb(gainDb);
            float target = DspChain.fastDbToLin(gainDb + makeupDb);
            float step = (target - gainCur) / blk;

            for (int f = 0; f < blk; f++) {
                gainCur += step;
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] *= gainCur;
                }
            }
            offset += blk;
        }
    }
}
