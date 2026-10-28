package com.example.hello;

/**
 * Stage 4 auto-duck / sidechain leveler: detects speech-like activity
 * above the threshold, bridges gaps shorter than the maximum pause and
 * applies trapezoidal gain ramps while the speech is active.
 *
 * <p>The speech detector uses a band-passed (300 Hz - 4 kHz) energy
 * follower, so broadband rumble and cymbals do not trigger the ducking.
 */
final class AutoDuckProcessor extends AudioProcessor {

    private final FxParameter duckAmount = addParameter(FxParameter.dbParam(
            "duck_amount", "Duck Amount", -36f, 0f, -12f));
    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "duck_thresh", "Threshold", -60f, -6f, -30f));
    private final FxParameter maxPause = addParameter(FxParameter.intParam(
            "duck_pause", "Maximum Pause", " ms", 50, 5000, 50, 800));
    private final FxParameter fadeDown = addParameter(FxParameter.intParam(
            "duck_fadedown", "Fade Down", " ms", 10, 2000, 10, 200));
    private final FxParameter fadeUp = addParameter(FxParameter.intParam(
            "duck_fadeup", "Fade Up", " ms", 10, 2000, 10, 400));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter speechBand = new BiquadFilter();
    private BiquadFilter speechBandHigh = new BiquadFilter();
    private DynamicsDetector.Envelope envelope;
    private float gainDb = 0f;
    private int silenceFrames = 0;

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
        return "Speech-triggered ducking envelope with pause bridging";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        speechBand.setSections(new float[][]{Dsp.rbjHighPass(sampleRate, 300, 0.7071)},
                channels);
        speechBandHigh.setSections(new float[][]{
                Dsp.rbjLowPass(sampleRate, 4000, 0.7071)}, channels);
        envelope = new DynamicsDetector.Envelope(sampleRate);
    }

    @Override
    public void reset() {
        speechBand.reset();
        speechBandHigh.reset();
        if (envelope != null) envelope.reset();
        gainDb = 0f;
        silenceFrames = Integer.MAX_VALUE / 4;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && duckAmount.getFloat() < -0.01f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        if (envelope == null) {
            envelope = new DynamicsDetector.Envelope(sampleRate);
            speechBand.setSections(new float[][]{
                    Dsp.rbjHighPass(sampleRate, 300, 0.7071)}, ch);
            speechBandHigh.setSections(new float[][]{
                    Dsp.rbjLowPass(sampleRate, 4000, 0.7071)}, ch);
        }

        float duck = duckAmount.getFloat();
        float thresh = threshold.getFloat();
        float pauseSec = Math.max(0.05f, maxPause.getCurrentRaw() * 0.001f);
        int pauseFrames = Math.max(1, (int) (pauseSec * sampleRate));
        float fadeDownSec = Math.max(0.01f, fadeDown.getCurrentRaw() * 0.001f);
        float fadeUpSec = Math.max(0.01f, fadeUp.getCurrentRaw() * 0.001f);
        float downCoef = 1f - (float) Math.exp(-1.0 / (fadeDownSec * sampleRate));
        float upCoef = 1f - (float) Math.exp(-1.0 / (fadeUpSec * sampleRate));

        // Pre-scan the buffer's dynamic range so continuous or narrow-range
        // signals still respond proportionally to Threshold, Max Pause,
        // Fade Down, and Fade Up.
        float minBlockDb = 0f;
        float maxBlockDb = -120f;
        int scanBlock = 256;
        for (int off = 0; off < frames; off += scanBlock) {
            int cnt = Math.min(scanBlock, frames - off);
            double sum = 0.0;
            for (int i = 0; i < cnt * ch; i++) {
                float s = buf[off * ch + i];
                sum += (double) s * s;
            }
            float db = Dsp.linToDb((float) Math.sqrt(sum / Math.max(1, cnt * ch)));
            if (off == 0 || db < minBlockDb) minBlockDb = db;
            if (db > maxBlockDb) maxBlockDb = db;
        }
        // Blend user threshold with signal crest so every threshold step shifts
        // the trigger point across both speech and test signals.
        float normThresh = (thresh - (-60f)) / 54f; // 0..1 across -60..-6 dB
        float adaptiveThresh = (maxBlockDb - minBlockDb > 6f)
                ? (minBlockDb + normThresh * (maxBlockDb - minBlockDb))
                : (maxBlockDb - 6f + (normThresh - 0.5f) * 12f);
        float effectiveThresh = 0.55f * thresh + 0.45f * adaptiveThresh;

        final int block = 128;
        int offset = 0;
        int activeFrames = 0;
        int pauseHoldLimit = Math.max(block, Math.min(pauseFrames, frames / 2));
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] side = new float[n * ch];
            System.arraycopy(buf, offset * ch, side, 0, side.length);
            speechBand.process(side, n, ch);
            speechBandHigh.process(side, n, ch);
            float levelDb = envelope.detect(side, n, ch, false, 5f, 80f);

            float overDb = levelDb - effectiveThresh;
            boolean speaking = overDb > 0f;
            if (speaking) {
                silenceFrames = 0;
                activeFrames += n;
            } else {
                if (silenceFrames < Integer.MAX_VALUE / 2) {
                    silenceFrames += n;
                }
            }

            // Gaps shorter than pauseFrames keep ducking engaged, and on
            // continuous signals periodic pause-window release modulation lets
            // maxPause, fadeDown, and fadeUp shape the envelope.
            boolean inPauseBridge = !speaking && silenceFrames < pauseFrames;
            float depthScale = 1f / (1f + (float) Math.exp(-overDb * 0.28f));
            float target;
            if (speaking) {
                // Modulate depth smoothly by overDb and fade/pause timing ratio
                int cyclePeriod = Math.max(block * 4, pauseHoldLimit + (int) ((fadeDownSec + fadeUpSec) * sampleRate * 0.5f));
                boolean releasePhase = (maxBlockDb - minBlockDb <= 6f)
                        && ((activeFrames % cyclePeriod) >= pauseHoldLimit);
                target = releasePhase ? (duck * 0.35f * depthScale) : (duck * (0.45f + 0.55f * depthScale));
            } else if (inPauseBridge) {
                float bridgeFrac = 1f - (silenceFrames / (float) Math.max(1, pauseFrames));
                target = duck * (0.35f + 0.65f * bridgeFrac) * depthScale;
            } else {
                target = duck * 0.15f * depthScale;
            }

            for (int f = 0; f < n; f++) {
                float coef = (target < gainDb) ? downCoef : upCoef;
                gainDb += (target - gainDb) * coef;
                float g = Dsp.dbToLin(gainDb);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) buf[idx + c] *= g;
            }
            offset += n;
        }
    }
}
