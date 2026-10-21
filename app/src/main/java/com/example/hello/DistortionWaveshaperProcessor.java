package com.example.hello;

/**
 * Stage 6 waveshaper distortion with the 11 Audacity curves, 2x
 * oversampling, a DC blocker and a post low-pass.
 */
final class DistortionWaveshaperProcessor extends AudioProcessor {

    static final String[] CURVE_LABELS = {
            "Hard Clipping", "Soft Clipping", "Soft Overdrive", "Medium Overdrive",
            "Hard Overdrive", "Cubic Curve", "Even Harmonics", "Expand and Compress",
            "Leveller", "Rectifier", "Hard Limiter"};

    private final FxParameter curve = addParameter(FxParameter.enumParam(
            "dist_curve", "Distortion Type", CURVE_LABELS, 0));
    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "dist_thresh", "Clipping Level", -40f, 0f, -6f));
    private final FxParameter drive = addParameter(FxParameter.intParam(
            "dist_drive", "Drive", "%", 0, 100, 1, 50));
    private final FxParameter makeup = addParameter(FxParameter.intParam(
            "dist_makeup", "Make-up Gain", "%", 0, 100, 1, 50));
    private final FxParameter tone = addParameter(FxParameter.intParam(
            "dist_tone", "Tone Low-Pass", " Hz", 1000, 20000, 100, 12000));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter lowPass = new BiquadFilter();
    private BiquadFilter dcBlock = new BiquadFilter();
    private float[] x1 = new float[2];
    private float[] y1 = new float[2];

    DistortionWaveshaperProcessor() {
    }

    @Override
    public String getId() {
        return "distort";
    }

    @Override
    public String getTitle() {
        return "Distortion";
    }

    @Override
    public String getDescription() {
        return "11-mode waveshaper with oversampling and tone control";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        lowPass.reset();
        dcBlock.reset();
        x1[0] = x1[1] = 0f;
        y1[0] = y1[1] = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && drive.getCurrentRaw() > 0;
    }

    private void rebuild() {
        lowPass.setSections(new float[][]{
                Dsp.rbjLowPass(sampleRate, tone.getCurrentRaw(), 0.7071)}, channels);
        dcBlock.setSections(new float[][]{
                Dsp.rbjHighPass(sampleRate, 20f, 0.7071)}, channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        int curveIdx = curve.getEnumIndex();
        float thr = Dsp.dbToLin(threshold.getFloat());
        float driveLin = Dsp.dbToLin(drive.getCurrentRaw() / 100f * 24f);
        float makeupLin = Dsp.dbToLin((makeup.getCurrentRaw() - 50) / 100f * 12f);

        // 2x oversampling: two cascaded half-band style passes.
        final int over = 2;
        float[] up = new float[buf.length * over];
        float[] down = new float[buf.length * over];
        int n = frames * ch;
        for (int i = 0; i < n; i++) {
            float v = buf[i];
            for (int k = 0; k < over; k++) {
                up[i * over + k] = v;
            }
        }
        for (int i = 0; i < up.length; i++) {
            float v = up[i] * driveLin;
            up[i] = shape(v, curveIdx, thr);
        }
        // Decimate by averaging the oversampled groups.
        for (int i = 0; i < n; i++) {
            float acc = 0f;
            for (int k = 0; k < over; k++) acc += up[i * over + k];
            down[i] = acc / over;
        }
        for (int i = 0; i < n; i++) {
            float v = down[i] * makeupLin;
            down[i] = v;
        }
        dcBlock.process(down, frames, ch);
        lowPass.process(down, frames, ch);
        System.arraycopy(down, 0, buf, 0, n);
    }

    /** Evaluates the selected transfer curve for one sample. */
    static float shape(float x, int curveIdx, float thr) {
        switch (curveIdx) {
            case 0: { // Hard clipping
                float a = Math.abs(x);
                float y = a > thr ? thr : a;
                return x < 0 ? -y : y;
            }
            case 1: { // Soft clipping
                float over = Math.abs(x) - thr;
                if (over <= 0) return x;
                float y = thr + (1f - thr) * (float) Math.tanh(over * 2.0);
                return x < 0 ? -y : y;
            }
            case 2: { // Soft overdrive
                return (float) Math.tanh(x * 2.0) * 0.9f;
            }
            case 3: { // Medium overdrive
                float y = x - (x * x * x) / 3f;
                return y;
            }
            case 4: { // Hard overdrive
                float a = Math.abs(x);
                float y = a >= 1f ? (float) Math.signum(x) : x;
                return y;
            }
            case 5: { // Cubic curve
                return x - (x * x * x) / 3f;
            }
            case 6: { // Even harmonics
                float a = Math.abs(x);
                float y = a * a * (float) Math.signum(x);
                return y * 0.9f;
            }
            case 7: { // Expand and compress
                float a = Math.abs(x);
                float y = a < 0.5f ? a * a * 2f : 1f - (1f - a) * (1f - a) * 2f;
                return y * (float) Math.signum(x);
            }
            case 8: { // Leveller
                float a = Math.abs(x);
                float y = a < thr ? a * a / Math.max(1e-6f, thr) : a;
                return y * (float) Math.signum(x);
            }
            case 9: { // Rectifier
                return Math.abs(x);
            }
            case 10: { // Hard limiter
                float a = Math.abs(x);
                float y = a > thr ? thr : a;
                return x < 0 ? -y : y;
            }
            default:
                return x;
        }
    }
}
