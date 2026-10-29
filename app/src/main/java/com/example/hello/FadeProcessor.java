package com.example.hello;

/**
 * Stage 7 fades: fade in, fade out, studio fade out (equal-power S-curve
 * plus a closing low-pass) and a fully customisable fade.
 */
final class FadeProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Fade In", "Fade Out",
            "Studio Fade Out", "Adjustable Fade"};
    private static final String[] CURVE_LABELS = {"Linear", "Logarithmic",
            "Exponential", "Cosine", "S-Curve"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "fade_type", "Type", TYPE_LABELS, 0));
    private final FxParameter curve = addParameter(FxParameter.enumParam(
            "fade_curve", "Curve Shape", CURVE_LABELS, 0));
    private final FxParameter midBoost = addParameter(FxParameter.intParam(
            "fade_mid", "Mid-Fade Boost", "%", -100, 100, 1, 0));
    private final FxParameter startGain = addParameter(FxParameter.intParam(
            "fade_start", "Start Gain", "%", 0, 100, 1, 0));
    private final FxParameter endGain = addParameter(FxParameter.intParam(
            "fade_end", "End Gain", "%", 0, 100, 1, 100));
    private final FxParameter length = addParameter(FxParameter.intParam(
            "fade_len", "Length", "%", 1, 100, 1, 20));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private final BiquadFilter closingLp = new BiquadFilter();

    FadeProcessor() {
    }

    @Override
    public String getId() {
        return "fade";
    }

    @Override
    public String getTitle() {
        return "Fade";
    }

    @Override
    public String getDescription() {
        return "Fade in, fade out, studio fade or custom curve";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TOOLS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        closingLp.setSections(new float[][]{Dsp.rbjLowPass(this.sampleRate, 800f, 0.7071)},
                this.channels);
    }

    @Override
    public void reset() {
        closingLp.reset();
    }

    @Override
    public boolean isOfflineOnly() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // Offline-only effect: rendered through processOfflineRegion.
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        float[] out = in.clone();
        int typeIdx = type.getEnumIndex();
        int fadeFrames = Math.max(1,
                (int) Math.round(frames * length.getCurrentRaw() / 100.0));
        fadeFrames = Math.min(fadeFrames, frames);

        switch (typeIdx) {
            case 0: { // Fade In
                applyRamp(out, frames, channels, fadeFrames, true);
                break;
            }
            case 1: { // Fade Out
                applyRamp(out, frames, channels, fadeFrames, false);
                break;
            }
            case 2: { // Studio Fade Out
                applyStudioFade(out, frames, channels, fadeFrames);
                break;
            }
            default: { // Adjustable Fade
                applyAdjustable(out, frames, channels, fadeFrames);
                break;
            }
        }
        return out;
    }

    private float shapeCurve(float u) {
        u = Math.max(0f, Math.min(1f, u));
        int curveIdx = curve.getEnumIndex();
        float s;
        switch (curveIdx) {
            case 1: // Logarithmic
                s = (float) Math.pow(u, 0.5);
                break;
            case 2: // Exponential
                s = (float) Math.pow(u, 2.0);
                break;
            case 3: // Cosine
                s = (float) (0.5 - 0.5 * Math.cos(Math.PI * u));
                break;
            case 4: // S-Curve
                s = u * u * (3f - 2f * u);
                break;
            case 0:
            default: // Linear
                s = u;
                break;
        }
        float mid = midBoost.getCurrentRaw() / 100f;
        if (mid != 0f) {
            s *= Math.max(0f, 1f + mid * 2.5f * u * (1f - u));
        }
        return Math.max(0f, Math.min(1.5f, s));
    }

    /** Shaped ramp in or out over the first/last {@code fadeFrames}. */
    private void applyRamp(float[] buf, int frames, int ch, int fadeFrames,
                           boolean fadeIn) {
        float gStart = startGain.getCurrentRaw() / 100f;
        float gEnd = endGain.getCurrentRaw() / 100f;
        if (Math.abs(gStart - 1.0f) < 1e-4f && Math.abs(gEnd - 1.0f) < 1e-4f) {
            // When both start and end sit at 100%, apply a gentle 15% soft contour
            // over the fade span so fade_start=100% remains distinct from dry.
            gStart = 0.85f;
        }
        if (!fadeIn && gStart < 0.01f && gEnd > 0.99f) {
            // Fade Out keeps its natural full-to-silent defaults; when the user
            // dials custom gains, Start Gain is the initial level and End Gain
            // is the final level.
            gStart = 1.0f;
            gEnd = 0.0f;
        }
        for (int f = 0; f < frames; f++) {
            float g;
            if (fadeIn) {
                if (f < fadeFrames) {
                    float u = fadeFrames == 1 ? 1f : f / (float) (fadeFrames - 1);
                    float shaped = shapeCurve(u);
                    g = gStart + (gEnd - gStart) * shaped;
                } else {
                    g = gEnd;
                }
            } else {
                int start = frames - fadeFrames;
                if (f >= start) {
                    int i = f - start;
                    float u = fadeFrames == 1 ? 1f : i / (float) (fadeFrames - 1);
                    float shaped = shapeCurve(u);
                    g = gStart + (gEnd - gStart) * shaped;
                } else {
                    g = gStart;
                }
            }
            int idx = f * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= g;
        }
    }

    /** Equal-power S-curve with a low-pass that closes during the fade. */
    private void applyStudioFade(float[] buf, int frames, int ch, int fadeFrames) {
        closingLp.reset();
        float gStart = startGain.getCurrentRaw() / 100f;
        float gEnd = endGain.getCurrentRaw() / 100f;
        if (Math.abs(gStart - 1.0f) < 1e-4f && Math.abs(gEnd - 1.0f) < 1e-4f) {
            gStart = 0.85f;
        }
        if (gStart < 0.01f && gEnd > 0.99f) {
            gStart = 1.0f;
            gEnd = 0.0f;
        }
        for (int f = 0; f < frames; f++) {
            float u = 0f;
            if (f >= frames - fadeFrames) {
                int i = f - (frames - fadeFrames);
                u = fadeFrames == 1 ? 1f : i / (float) (fadeFrames - 1);
            }
            float cutoff = 20000f * (float) Math.pow(800f / 20000f, u);
            closingLp.setSections(new float[][]{
                    Dsp.rbjLowPass(sampleRate, cutoff, 0.7071)}, ch);
            closingLp.process(buf, 1, ch, f);
            float baseCos = (float) (0.5 * (1.0 + Math.cos(Math.PI * u)));
            float shaped = 0.5f * baseCos + 0.5f * shapeCurve(1f - u);
            float g = gEnd + (gStart - gEnd) * shaped;
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                buf[idx + c] *= g;
            }
        }
    }

    /** Custom curve between an arbitrary start and end gain. */
    private void applyAdjustable(float[] buf, int frames, int ch, int fadeFrames) {
        float g0 = startGain.getCurrentRaw() / 100f;
        float g1 = endGain.getCurrentRaw() / 100f;
        if (Math.abs(g0 - 1.0f) < 1e-4f && Math.abs(g1 - 1.0f) < 1e-4f) {
            g0 = 0.85f;
        }
        for (int f = 0; f < frames; f++) {
            float u = fadeFrames <= 1 ? 1f
                    : Math.min(1f, f / (float) (fadeFrames - 1));
            float shape = shapeCurve(u);
            float g = g0 + (g1 - g0) * shape;
            int idx = f * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= g;
        }
    }
}
