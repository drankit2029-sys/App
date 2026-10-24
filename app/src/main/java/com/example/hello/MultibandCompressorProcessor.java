package com.example.hello;

/**
 * Stage 4 three-band vocal leveler: a Linkwitz-Riley 4th-order crossover
 * (24 dB/oct) splits the signal into low / mid / high bands, each band gets
 * its own soft-knee compressor, and the three are summed back phase-coherently.
 */
final class MultibandCompressorProcessor extends AudioProcessor {

    private final FxParameter lowXover = addParameter(FxParameter.intParam(
            "mb_lowx", "Low/Mid Crossover", " Hz", 80, 800, 10, 220));
    private final FxParameter highXover = addParameter(FxParameter.intParam(
            "mb_highx", "Mid/High Crossover", " Hz", 1500, 8000, 100, 3500));
    private final FxParameter lowThresh = addParameter(FxParameter.dbParam(
            "mb_lowt", "Low Threshold", -60f, 0f, -24f));
    private final FxParameter midThresh = addParameter(FxParameter.dbParam(
            "mb_midt", "Mid Threshold", -60f, 0f, -18f));
    private final FxParameter highThresh = addParameter(FxParameter.dbParam(
            "mb_hight", "High Threshold", -60f, 0f, -20f));
    private final FxParameter lowRatio = addParameter(FxParameter.floatParam(
            "mb_lowr", "Low Ratio", ":1", 1f, 10f, 2f));
    private final FxParameter midRatio = addParameter(FxParameter.floatParam(
            "mb_midr", "Mid Ratio", ":1", 1f, 10f, 3f));
    private final FxParameter highRatio = addParameter(FxParameter.floatParam(
            "mb_highr", "High Ratio", ":1", 1f, 10f, 2.5f));
    private final FxParameter attack = addParameter(FxParameter.floatParam(
            "mb_attack", "Attack", " ms", 1f, 100f, 15f));
    private final FxParameter release = addParameter(FxParameter.floatParam(
            "mb_release", "Release", " ms", 40f, 1200f, 200f));
    private final FxParameter lowGain = addParameter(FxParameter.dbParam(
            "mb_lowg", "Low Band Gain", -12f, 12f, 0f));
    private final FxParameter midGain = addParameter(FxParameter.dbParam(
            "mb_midg", "Mid Band Gain", -12f, 12f, 0f));
    private final FxParameter highGain = addParameter(FxParameter.dbParam(
            "mb_highg", "High Band Gain", -12f, 12f, 0f));
    private final FxParameter amount = addParameter(FxParameter.intParam(
            "mb_amount", "Amount", "%", 0, 100, 1, 40));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter lowPass = new BiquadFilter();
    private BiquadFilter highPass = new BiquadFilter();
    private DynamicsDetector.Envelope envLow;
    private DynamicsDetector.Envelope envMid;
    private DynamicsDetector.Envelope envHigh;
    private float gainLow = 0f;
    private float gainMid = 0f;
    private float gainHigh = 0f;

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
        return "3-band Linkwitz-Riley vocal leveler";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuildCrossover();
        envLow = new DynamicsDetector.Envelope(sampleRate);
        envMid = new DynamicsDetector.Envelope(sampleRate);
        envHigh = new DynamicsDetector.Envelope(sampleRate);
    }

    @Override
    public void reset() {
        lowPass.reset();
        highPass.reset();
        if (envLow != null) envLow.reset();
        if (envMid != null) envMid.reset();
        if (envHigh != null) envHigh.reset();
        gainLow = gainMid = gainHigh = 0f;
    }

    @Override
    public boolean isAudible() {
        if (!isEnabled() || getWetDryMix() <= 0.001f) return false;
        if (Math.abs(lowGain.getFloat()) > 0.01f
                || Math.abs(midGain.getFloat()) > 0.01f
                || Math.abs(highGain.getFloat()) > 0.01f) {
            return true;
        }
        return amount.getFloat() > 0
                && (lowRatio.getFloat() > 1.01f
                || midRatio.getFloat() > 1.01f
                || highRatio.getFloat() > 1.01f);
    }

    /**
     * Linkwitz-Riley 4th order: two cascaded Butterworth 2nd-order sections
     * give 24 dB/oct with an allpass-like phase relationship between the
     * complementary bands.
     */
    private void rebuildCrossover() {
        float lx = lowXover.getCurrentRaw();
        float hx = Math.max(lx + 50f, highXover.getCurrentRaw());
        float[][] lp1 = Dsp.butterworth(2, sampleRate, lx, false);
        float[][] lp2 = Dsp.butterworth(2, sampleRate, lx, false);
        float[][] hp1 = Dsp.butterworth(2, sampleRate, hx, true);
        float[][] hp2 = Dsp.butterworth(2, sampleRate, hx, true);
        float[][] lp = new float[][]{lp1[0], lp2[0]};
        float[][] hp = new float[][]{hp1[0], hp2[0]};
        lowPass.setSections(lp, channels);
        highPass.setSections(hp, channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuildCrossover();
        if (envLow == null) {
            envLow = new DynamicsDetector.Envelope(sampleRate);
            envMid = new DynamicsDetector.Envelope(sampleRate);
            envHigh = new DynamicsDetector.Envelope(sampleRate);
        }

        float attMs = attack.getFloat();
        float relMs = release.getFloat();
        float gLowLin = Dsp.dbToLin(lowGain.getFloat());
        float gMidLin = Dsp.dbToLin(midGain.getFloat());
        float gHighLin = Dsp.dbToLin(highGain.getFloat());

        float[] low = new float[frames * ch];
        float[] high = new float[frames * ch];
        float[] mid = new float[frames * ch];
        System.arraycopy(buf, 0, low, 0, low.length);
        lowPass.process(low, frames, ch);
        System.arraycopy(buf, 0, high, 0, high.length);
        highPass.process(high, frames, ch);
        for (int i = 0; i < mid.length; i++) mid[i] = buf[i] - low[i] - high[i];

        final int block = 64;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float lvlLow = envLow.detect(low, offset, n, ch, false, attMs, relMs);
            float lvlMid = envMid.detect(mid, offset, n, ch, false, attMs, relMs);
            float lvlHigh = envHigh.detect(high, offset, n, ch, false, attMs, relMs);

            float amt = amount.getFloat() / 100f;
            float tLow = amt * DynamicsDetector.softKneeGainDb(
                    lvlLow - lowThresh.getFloat(), lowRatio.getFloat(), 6f);
            float tMid = amt * DynamicsDetector.softKneeGainDb(
                    lvlMid - midThresh.getFloat(), midRatio.getFloat(), 6f);
            float tHigh = amt * DynamicsDetector.softKneeGainDb(
                    lvlHigh - highThresh.getFloat(), highRatio.getFloat(), 6f);

            FxTelemetry.publishGainReductionDb(Math.min(tLow, Math.min(tMid, tHigh)));
            float stepLow = (tLow - gainLow) / n;
            float stepMid = (tMid - gainMid) / n;
            float stepHigh = (tHigh - gainHigh) / n;
            for (int f = 0; f < n; f++) {
                gainLow += stepLow;
                gainMid += stepMid;
                gainHigh += stepHigh;
                float gl = Dsp.dbToLin(gainLow) * gLowLin;
                float gm = Dsp.dbToLin(gainMid) * gMidLin;
                float gh = Dsp.dbToLin(gainHigh) * gHighLin;
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] = low[idx + c] * gl + mid[idx + c] * gm
                            + high[idx + c] * gh;
                }
            }
            offset += n;
        }
    }
}
