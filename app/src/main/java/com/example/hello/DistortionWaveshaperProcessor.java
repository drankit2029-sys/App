package com.example.hello;

/**
 * Stage 6 Distortion & Waveshaper (11 curves): Hard Clip, Soft Clip (tanh),
 * Soft Overdrive (cubic), Medium Overdrive, Hard Overdrive, Cubic Waveshaper,
 * Even Harmonics (Tube Asymmetry), Expand & Compress, Leveller, Rectifier
 * (Half/Full Wave), and Hard Limiter, with 1x/2x/4x interpolated oversampling
 * and post-filter Tone LP.
 */
final class DistortionWaveshaperProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {
            "Hard Clipping", "Soft Clipping (Tanh)", "Soft Overdrive",
            "Medium Overdrive", "Hard Overdrive", "Cubic Waveshaper",
            "Even Harmonics (Tube)", "Expand & Compress", "Leveller",
            "Rectifier", "Hard Limiter"
    };
    private static final String[] OS_LABELS = {"1x (Off)", "2x", "4x"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "dist_type", "Curve Type", TYPE_LABELS, 1));
    private final FxParameter drive = addParameter(FxParameter.intParam(
            "dist_drive", "Drive", "%", 0, 100, 5, 45));
    private final FxParameter dcOffset = addParameter(FxParameter.intParam(
            "dist_dc", "Asymmetry / DC", "%", -100, 100, 5, 0));
    private final FxParameter clipLevel = addParameter(FxParameter.dbParam(
            "dist_clip", "Clipping Level", -24f, 0f, -3f));
    private final FxParameter softness = addParameter(FxParameter.intParam(
            "dist_param", "Curve Parameter", "%", 0, 100, 5, 50));
    private final FxParameter oversample = addParameter(FxParameter.enumParam(
            "dist_os", "Oversampling", OS_LABELS, 1));
    private final FxParameter toneFreq = addParameter(FxParameter.intParam(
            "dist_tone", "Post Tone LP", " Hz", 800, 18000, 200, 8000));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private final BiquadFilter toneLp = new BiquadFilter();
    private final float[] prevX = new float[2];

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
        return "11-curve waveshaper with oversampling & post-tone filter";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        rebuildTone(channels);
    }

    private void rebuildTone(int ch) {
        double fc = Math.min(toneFreq.getCurrentRaw(), sampleRate * 0.45);
        toneLp.setSections(new float[][]{
                Dsp.rbjLowPass(sampleRate, fc, 0.7071)
        }, Math.max(1, Math.min(2, ch)));
    }

    @Override
    public void reset() {
        toneLp.reset();
        prevX[0] = prevX[1] = 0f;
    }

    private boolean hasSecondaryChange() {
        return type.getCurrentRaw() != type.defaultRaw
                || dcOffset.getCurrentRaw() != dcOffset.defaultRaw
                || clipLevel.getCurrentRaw() != clipLevel.defaultRaw
                || softness.getCurrentRaw() != softness.defaultRaw
                || oversample.getCurrentRaw() != oversample.defaultRaw
                || toneFreq.getCurrentRaw() != toneFreq.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (drive.getCurrentRaw() > 0 || hasSecondaryChange());
    }

    private static double shape(double x, int curve, double param) {
        switch (curve) {
            case 0: // Hard Clipping
                return Math.max(-1.0, Math.min(1.0, x));
            case 1: // Soft Clipping (Tanh)
                return Math.tanh(x * (1.0 + param));
            case 2: // Soft Overdrive
                if (Math.abs(x) < 0.333) return 2.0 * x;
                if (Math.abs(x) < 0.666) {
                    double s = Math.signum(x);
                    double a = 2.0 - 3.0 * Math.abs(x);
                    return s * (3.0 - a * a) / 3.0;
                }
                return Math.signum(x);
            case 3: // Medium Overdrive
                return Math.atan(x * (1.5 + 2.0 * param)) * (2.0 / Math.PI);
            case 4: // Hard Overdrive
                return Math.tanh(x * (2.5 + 3.0 * param));
            case 5: // Cubic Waveshaper
                double c = Math.max(-1.2, Math.min(1.2, x));
                return c - (c * c * c) / 3.0 * (0.5 + 0.5 * param);
            case 6: // Even Harmonics (Tube Asymmetry)
                return Math.tanh(x + 0.35 * (0.3 + param) * x * x) - 0.12 * param;
            case 7: // Expand & Compress
                return Math.copySign(Math.pow(Math.min(1.5, Math.abs(x)), 0.65 - 0.25 * param), x);
            case 8: // Leveller
                return x / (1.0 + (0.6 + param) * Math.abs(x));
            case 9: // Rectifier (blend half/full wave by param)
                return (1.0 - param) * Math.max(0.0, x) + param * Math.abs(x);
            case 10: // Hard Limiter (brickwall ceiling with fast pre-knee, distinct from Hard Clipping)
            default: {
                double lim = 0.72 - 0.15 * param;
                double driven = x * (1.15 + 0.25 * param);
                return Math.max(-lim, Math.min(lim, driven));
            }
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuildTone(ch);
        int curve = type.getEnumIndex();
        int rawDrive = drive.getCurrentRaw();
        double driveAmt = rawDrive > 0 ? (rawDrive / 100.0)
                : (hasSecondaryChange() ? 0.22 : 0.0);
        double preGain = Dsp.dbToLin(driveAmt * 30.0);
        double ceil = Dsp.dbToLin(clipLevel.getFloat());
        double dc = (dcOffset.getCurrentRaw() / 100.0) * 0.35;
        double param = softness.getCurrentRaw() / 100.0;
        int os = oversample.getEnumIndex();
        int subSteps = os == 2 ? 4 : (os == 1 ? 2 : 1);

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                float curIn = buf[idx + c];
                float prevIn = prevX[c];
                prevX[c] = curIn;
                double acc = 0.0;
                for (int s = 1; s <= subSteps; s++) {
                    double frac = s / (double) subSteps;
                    double interp = prevIn + (curIn - prevIn) * frac;
                    double x = (interp * preGain + dc) / Math.max(0.02, ceil);
                    acc += shape(x, curve, param);
                }
                double y = (acc / subSteps) * ceil;
                buf[idx + c] = (float) Math.max(-1.0, Math.min(1.0, y));
            }
        }
        toneLp.process(buf, frames, ch);
    }
}
