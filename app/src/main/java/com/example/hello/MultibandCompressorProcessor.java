package com.example.hello;

/**
 * Stage 4 3-band Multiband Compressor: splits the spectrum at Low/Mid
 * (~200 Hz) and Mid/High (~3.2 kHz), runs an independent soft-knee envelope
 * compressor per band with automatic band makeup, and sums the three bands.
 */
final class MultibandCompressorProcessor extends AudioProcessor {

    private final FxParameter lowCross = addParameter(FxParameter.intParam(
            "mb_xlow", "Low Crossover", " Hz", 80, 500, 10, 200));
    private final FxParameter highCross = addParameter(FxParameter.intParam(
            "mb_xhigh", "High Crossover", " Hz", 1500, 8000, 100, 3200));
    private final FxParameter lowThresh = addParameter(FxParameter.dbParam(
            "mb_lowt", "Low Threshold", -48f, -3f, -20f));
    private final FxParameter lowRatio = addParameter(FxParameter.floatParam(
            "mb_lowr", "Low Ratio", ":1", 1.2f, 10f, 3.0f));
    private final FxParameter midThresh = addParameter(FxParameter.dbParam(
            "mb_midt", "Mid Threshold", -48f, -3f, -18f));
    private final FxParameter midRatio = addParameter(FxParameter.floatParam(
            "mb_midr", "Mid Ratio", ":1", 1.2f, 10f, 2.5f));
    private final FxParameter highThresh = addParameter(FxParameter.dbParam(
            "mb_hight", "High Threshold", -48f, -3f, -22f));
    private final FxParameter highRatio = addParameter(FxParameter.floatParam(
            "mb_highr", "High Ratio", ":1", 1.2f, 10f, 3.5f));
    private final FxParameter amount = addParameter(FxParameter.intParam(
            "mb_amount", "Overall Amount", "%", 0, 100, 5, 65));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float lpLowL, lpLowR;
    private float lpHighL, lpHighR;
    private final float[] env = new float[3];

    MultibandCompressorProcessor() {
    }

    @Override
    public String getId() {
        return "mbcomp";
    }

    @Override
    public String getTitle() {
        return "Multiband Compressor";
    }

    @Override
    public String getDescription() {
        return "3-band crossover compressor for balanced lows, mids & highs";
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
        lpLowL = lpLowR = 0f;
        lpHighL = lpHighR = 0f;
        env[0] = env[1] = env[2] = 0f;
    }

    private boolean hasSecondaryChange() {
        return lowCross.getCurrentRaw() != lowCross.defaultRaw
                || highCross.getCurrentRaw() != highCross.defaultRaw
                || lowThresh.getCurrentRaw() != lowThresh.defaultRaw
                || lowRatio.getCurrentRaw() != lowRatio.defaultRaw
                || midThresh.getCurrentRaw() != midThresh.defaultRaw
                || midRatio.getCurrentRaw() != midRatio.defaultRaw
                || highThresh.getCurrentRaw() != highThresh.defaultRaw
                || highRatio.getCurrentRaw() != highRatio.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (amount.getCurrentRaw() > 0 || hasSecondaryChange());
    }

    private static float bandGain(float level, float threshDb, float ratio, float amt) {
        float db = DspChain.fastLinToDb(level + 1e-7f);
        float over = db - threshDb;
        float grDb = 0f;
        if (over > 0f) {
            float slope = (1.0f - 1.0f / Math.max(1.1f, ratio)) * amt;
            grDb = -slope * over;
        }
        float makeupDb = Math.min(6f, Math.max(0f, -threshDb * 0.12f * amt))
                + (ratio - 2.5f) * 0.25f * amt;
        return DspChain.fastDbToLin(grDb + makeupDb);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        int rawAmt = amount.getCurrentRaw();
        float amt = rawAmt > 0 ? (rawAmt / 100f) : (hasSecondaryChange() ? 0.35f : 0f);
        if (!isEnabled() || amt <= 0.001f || buf == null || frames <= 0) return;

        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        // Exact 1st-order complementary Linkwitz-Riley-style split so
        // low + mid + high == sL with zero phase cancellation at flat gain.
        double fLow = Math.max(60.0, Math.min(800.0, lowCross.getCurrentRaw()));
        double fHigh = Math.max(1000.0, Math.min(10000.0, highCross.getCurrentRaw()));
        float aLow = (float) (1.0 - Math.exp(-2.0 * Math.PI * fLow / sr));
        float aHigh = (float) (1.0 - Math.exp(-2.0 * Math.PI * fHigh / sr));

        float attA = 1.0f - (float) Math.exp(-1.0 / (sr * 0.008));
        float relA = 1.0f - (float) Math.exp(-1.0 / (sr * 0.120));

        float lt = lowThresh.getFloat(), lr = lowRatio.getFloat();
        float mt = midThresh.getFloat(), mr = midRatio.getFloat();
        float ht = highThresh.getFloat(), hr = highRatio.getFloat();

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float sL = buf[idx];
            float sR = ch == 2 ? buf[idx + 1] : sL;

            lpLowL += aLow * (sL - lpLowL);
            lpHighL += aHigh * (sL - lpHighL);
            float lowL = lpLowL;
            float midL = lpHighL - lpLowL;
            float highL = sL - lpHighL;

            float lowR = lowL, midR = midL, highR = highL;
            if (ch == 2) {
                lpLowR += aLow * (sR - lpLowR);
                lpHighR += aHigh * (sR - lpHighR);
                lowR = lpLowR;
                midR = lpHighR - lpLowR;
                highR = sR - lpHighR;
            }

            float e0 = Math.max(Math.abs(lowL), Math.abs(lowR));
            float e1 = Math.max(Math.abs(midL), Math.abs(midR));
            float e2 = Math.max(Math.abs(highL), Math.abs(highR));
            // Cross-band sidechain coupling (15%) so low/high threshold & ratio
            // controls also gently stabilize adjacent mid-band energy.
            float c0 = e0 + 0.18f * e1;
            float c1 = e1 + 0.12f * (e0 + e2);
            float c2 = e2 + 0.18f * e1;

            env[0] += (c0 > env[0] ? attA : relA) * (c0 - env[0]);
            env[1] += (c1 > env[1] ? attA : relA) * (c1 - env[1]);
            env[2] += (c2 > env[2] ? attA : relA) * (c2 - env[2]);

            float g0 = bandGain(env[0], lt, lr, amt);
            float g1 = bandGain(env[1], mt, mr, amt);
            float g2 = bandGain(env[2], ht, hr, amt);

            buf[idx] = lowL * g0 + midL * g1 + highL * g2;
            if (ch == 2) {
                buf[idx + 1] = lowR * g0 + midR * g1 + highR * g2;
            }
        }
    }
}
