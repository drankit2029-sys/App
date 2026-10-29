package com.example.hello;

/**
 * Stage 4 full-featured dynamics Compressor with Threshold, Noise-Floor gate
 * (freezes gain on silence so room noise is not pumped up), Ratio, Attack,
 * Release, Knee, Peak vs RMS detection, Upward vs Downward mode, and Make-Up
 * Gain.
 */
final class CompressorProcessor extends AudioProcessor {

    private static final String[] DET_LABELS = {"Peak", "RMS"};

    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "dyncomp_thresh", "Threshold", -60f, -1f, -18f));
    private final FxParameter noiseFloor = addParameter(FxParameter.dbParam(
            "dyncomp_floor", "Noise Floor", -80f, -20f, -55f));
    private final FxParameter ratio = addParameter(FxParameter.floatParam(
            "dyncomp_ratio", "Ratio", ":1", 1.1f, 20f, 3.0f));
    private final FxParameter attack = addParameter(FxParameter.intParam(
            "dyncomp_att", "Attack", " ms", 1, 200, 1, 10));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "dyncomp_rel", "Release", " ms", 10, 2000, 10, 150));
    private final FxParameter knee = addParameter(FxParameter.dbParam(
            "dyncomp_knee", "Knee Width", 0f, 20f, 6f));
    private final FxParameter detector = addParameter(FxParameter.enumParam(
            "dyncomp_det", "Detector", DET_LABELS, 1));
    private final FxParameter upward = addParameter(FxParameter.boolParam(
            "dyncomp_upward", "Upward Compression", false));
    private final FxParameter makeup = addParameter(FxParameter.dbParam(
            "dyncomp_makeup", "Make-Up Gain", -12f, 24f, 0f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float envLin = 0f;
    private float currentGainDb = 0f;
    private float lastGainReductionDb = 0f;

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
        return "Soft-knee Peak/RMS compressor with noise-floor freeze & upward mode";
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
        envLin = 0f;
        currentGainDb = 0f;
        lastGainReductionDb = 0f;
    }

    public float getLastGainReductionDb() {
        return lastGainReductionDb;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        float threshDb = threshold.getFloat();
        float floorDb = noiseFloor.getFloat();
        float r = Math.max(1.05f, ratio.getFloat());
        float slope = 1.0f - 1.0f / r;
        float kneeW = Math.max(0f, knee.getFloat());
        boolean rmsMode = detector.getEnumIndex() == 1;
        boolean up = upward.getBoolean();
        float makeupDb = makeup.getFloat();

        float attAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * Math.max(0.0005f, attack.getCurrentRaw() / 1000.0f)));
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * Math.max(0.005f, release.getCurrentRaw() / 1000.0f)));
        float minGr = 0f;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float sL = buf[idx];
            float sR = ch == 2 ? buf[idx + 1] : sL;
            float det;
            if (rmsMode) {
                det = 0.5f * (sL * sL + sR * sR);
                envLin += (det > envLin ? attAlpha : relAlpha) * (det - envLin);
            } else {
                det = Math.max(Math.abs(sL), Math.abs(sR));
                envLin += (det > envLin ? attAlpha : relAlpha) * (det - envLin);
            }
            float amp = rmsMode ? (float) Math.sqrt(Math.max(0f, envLin)) : envLin;
            float lvlDb = DspChain.fastLinToDb(amp + 1e-7f);

            if (lvlDb > floorDb) {
                float targetGrDb = 0f;
                if (up) {
                    if (lvlDb < threshDb) {
                        float under = Math.min(24f, threshDb - lvlDb);
                        targetGrDb = -slope * under * 0.5f;
                    } else {
                        // Above threshold in upward mode, use a gentler top-end knee
                        // so toggling upward mode is also audible on loud passages.
                        float over = lvlDb - threshDb;
                        targetGrDb = -slope * over * 0.65f;
                    }
                } else {
                    float over = lvlDb - threshDb;
                    if (kneeW > 0.1f && over > -kneeW * 0.5f) {
                        if (over < kneeW * 0.5f) {
                            float x = over + kneeW * 0.5f;
                            targetGrDb = -slope * (x * x) / (2.0f * kneeW);
                        } else {
                            targetGrDb = -slope * over;
                        }
                    } else if (over > 0f) {
                        targetGrDb = -slope * over;
                    }
                }
                currentGainDb += (targetGrDb < currentGainDb ? attAlpha : relAlpha)
                        * (targetGrDb - currentGainDb);
            } else {
                float floorScale = Math.max(0f, Math.min(1f, (floorDb + 80f) / 60f));
                float bleedTarget = -floorScale * 3.0f;
                currentGainDb += relAlpha * (bleedTarget - currentGainDb);
            }
            if (currentGainDb < minGr) minGr = currentGainDb;
            float g = DspChain.fastDbToLin(currentGainDb + makeupDb);
            buf[idx] = sL * g;
            if (ch == 2) buf[idx + 1] = sR * g;
        }
        lastGainReductionDb = minGr;
    }
}
