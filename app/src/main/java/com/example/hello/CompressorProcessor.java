package com.example.hello;

/**
 * Stage 4 dynamics compressor: full log-domain gain computer with a
 * noise-floor freeze, RMS or peak detection, auto make-up and an optional
 * upward (expander-like) mode.
 *
 * <p>The transfer curve is the classic soft-knee shape:
 * {@code out = in + (1 - 1/ratio) * (in - threshold)} above the knee, with a
 * quadratic interpolation across the knee itself.
 */
final class CompressorProcessor extends AudioProcessor {

    private static final String[] DETECT_LABELS = {"RMS", "Peak"};

    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "dyncomp_thresh", "Threshold", -60f, -1f, -18f));
    private final FxParameter noiseFloor = addParameter(FxParameter.dbParam(
            "dyncomp_floor", "Noise Floor", -80f, -20f, -50f));
    private final FxParameter ratio = addParameter(FxParameter.floatParam(
            "dyncomp_ratio", "Ratio", ":1", 1.1f, 20f, 3f));
    private final FxParameter knee = addParameter(FxParameter.dbParam(
            "dyncomp_knee", "Knee Width", 0f, 12f, 6f));
    private final FxParameter attack = addParameter(FxParameter.floatParam(
            "dyncomp_attack", "Attack Time", " ms", 0.5f, 250f, 12f));
    private final FxParameter release = addParameter(FxParameter.floatParam(
            "dyncomp_release", "Release Time", " ms", 10f, 2500f, 180f));
    private final FxParameter detect = addParameter(FxParameter.enumParam(
            "dyncomp_detect", "Detection Mode", DETECT_LABELS, 0));
    private final FxParameter autoMakeup = addParameter(FxParameter.boolParam(
            "dyncomp_makeup", "Auto Make-up Gain", true));
    private final FxParameter upward = addParameter(FxParameter.boolParam(
            "dyncomp_upward", "Upward (Expand) Mode", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private DynamicsDetector.Envelope envelope;
    private float gainDb = 0f;
    private float makeupDb = 0f;
    private float avgReductionDb = 0f;

    CompressorProcessor() {
    }

    @Override
    public String getId() {
        return "dyncomp";
    }

    @Override
    public String getTitle() {
        return "Compressor";
    }

    @Override
    public String getDescription() {
        return "Soft-knee dynamics compressor with noise-floor freeze";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        envelope = new DynamicsDetector.Envelope(sampleRate);
    }

    @Override
    public void reset() {
        if (envelope != null) envelope.reset();
        gainDb = 0f;
        makeupDb = 0f;
        avgReductionDb = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (ratio.getFloat() > 1.01f || upward.getBoolean());
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        if (envelope == null) envelope = new DynamicsDetector.Envelope(sampleRate);
        int ch = Math.max(1, Math.min(2, channels));

        float thresh = threshold.getFloat();
        float floorDb = noiseFloor.getFloat();
        float r = ratio.getFloat();
        float kneeDb = knee.getFloat();
        boolean rms = detect.getEnumIndex() == 0;
        float attMs = attack.getFloat();
        float relMs = release.getFloat();

        // Detection is done on 64-frame blocks so the ballistics are stable.
        final int block = 64;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float levelDb = envelope.detect(buf, offset, n, ch, rms, attMs,
                    relMs);

            float over = 0f;
            if (upward.getBoolean()) {
                // Upward mode expands below the threshold.
                if (levelDb < thresh) {
                    over = levelDb - thresh;
                }
            } else if (levelDb > thresh) {
                over = levelDb - thresh;
            }

            float targetGain;
            if (upward.getBoolean()) {
                if (over < 0f) {
                    float slope = 1f - 1f / Math.max(1.0001f, r);
                    targetGain = Math.max(-60f, slope * over);
                } else {
                    targetGain = 0f;
                }
            } else {
                targetGain = DynamicsDetector.softKneeGainDb(over, r, kneeDb);
            }

            float reduction = Math.max(0f, -targetGain);
            if (levelDb >= floorDb) {
                avgReductionDb += (reduction - avgReductionDb) * 0.002f;
            }
            makeupDb = (autoMakeup.getBoolean() && !upward.getBoolean())
                    ? Math.min(24f, avgReductionDb * 0.5f) : 0f;

            // Noise-floor freeze: attenuate make-up gain and apply gentle
            // downward floor damping when signal drops below floorDb.
            float floorScale = 1f;
            float floorAttenDb = 0f;
            if (levelDb < floorDb) {
                float below = floorDb - levelDb;
                floorScale = Math.max(0f, 1f - below / 12f);
                floorAttenDb = -Math.min(12f, below * 0.25f);
            }
            float target = targetGain + makeupDb * floorScale + floorAttenDb;
            float step = (target - gainDb) / n;
            FxTelemetry.publishGainReductionDb(targetGain);
            for (int f = 0; f < n; f++) {
                gainDb += step;
                float g = Dsp.dbToLin(gainDb);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] *= g;
                }
            }
            offset += n;
        }
    }
}
