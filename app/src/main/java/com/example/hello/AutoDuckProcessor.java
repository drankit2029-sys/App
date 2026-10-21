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
        silenceFrames = 0;
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
                    Dsp.rbjHighPass(sampleRate, 300, 0.7071)}, channels);
            speechBandHigh.setSections(new float[][]{
                    Dsp.rbjLowPass(sampleRate, 4000, 0.7071)}, channels);
        }

        float duck = duckAmount.getFloat();
        float thresh = threshold.getFloat();
        int pauseFrames = Math.max(1,
                (int) (maxPause.getCurrentRaw() * 0.001f * sampleRate));
        float downCoef = 1f - (float) Math.exp(-1.0
                / (Math.max(0.001f, fadeDown.getCurrentRaw() * 0.001f) * sampleRate));
        float upCoef = 1f - (float) Math.exp(-1.0
                / (Math.max(0.001f, fadeUp.getCurrentRaw() * 0.001f) * sampleRate));

        final int block = 128;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] side = new float[n * ch];
            System.arraycopy(buf, offset * ch, side, 0, side.length);
            speechBand.process(side, n, ch);
            speechBandHigh.process(side, n, ch);
            float levelDb = envelope.detect(side, n, ch, false, 5f, 80f);

            boolean speaking = levelDb > thresh;
            if (speaking) {
                silenceFrames = 0;
            } else {
                silenceFrames += n;
            }
            // Gaps shorter than the maximum pause keep the duck engaged.
            boolean ducked = speaking || silenceFrames < pauseFrames;

            float target = ducked ? duck : 0f;
            float coef = (target < gainDb) ? downCoef : upCoef;
            float step = (target - gainDb) * coef;
            for (int f = 0; f < n; f++) {
                gainDb += step;
                float g = Dsp.dbToLin(gainDb);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) buf[idx + c] *= g;
            }
            offset += n;
        }
    }
}
