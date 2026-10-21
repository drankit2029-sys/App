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
    private BiquadFilter closingLp = new BiquadFilter();
    private long regionFrames = 0;
    private long frameCounter = 0;

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
        closingLp.setSections(new float[][]{Dsp.rbjLowPass(sampleRate, 800f, 0.7071)},
                channels);
    }

    @Override
    public void reset() {
        frameCounter = 0;
        closingLp.reset();
    }

    @Override
    public boolean isOfflineOnly() {
        return true;
    }

    /**
     * Offline-only effect: it is rendered through
     * {@link #processOfflineRegion} instead of the realtime chain.
     */
    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // No realtime path: the realtime rack skips offline-only processors.
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        // The fade is rendered into a copy so the caller keeps the original
        // region untouched, exactly like the other offline tools.
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

    /** Linear ramp in or out over the first/last {@code fadeFrames}. */
    private void applyRamp(float[] buf, int frames, int ch, int fadeFrames,
                           boolean fadeIn) {
        for (int f = 0; f < frames; f++) {
            float g = 1f;
            if (fadeIn) {
                if (f < fadeFrames) {
                    g = fadeFrames == 1 ? 1f : f / (float) (fadeFrames - 1);
                }
            } else {
                int start = frames - fadeFrames;
                if (f >= start) {
                    int i = f - start;
                    g = fadeFrames == 1 ? 0f : 1f - i / (float) (fadeFrames - 1);
                }
            }
            int idx = f * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= g;
        }
    }

    /** Equal-power S-curve with a low-pass that closes during the fade. */
    private void applyStudioFade(float[] buf, int frames, int ch, int fadeFrames) {
        closingLp.reset();
        for (int f = 0; f < frames; f++) {
            float u = 0f;
            if (f >= frames - fadeFrames) {
                int i = f - (frames - fadeFrames);
                u = fadeFrames == 1 ? 1f : i / (float) (fadeFrames - 1);
            }
            float g = (float) (0.5 * (1.0 + Math.cos(Math.PI * u)));
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                buf[idx + c] *= g;
            }
            if (u > 0f) {
                // Sweep the low-pass from 20 kHz down to 800 Hz.
                float cutoff = 20000f * (float) Math.pow(800f / 20000f, u);
                closingLp.setSections(new float[][]{
                        Dsp.rbjLowPass(sampleRate, cutoff, 0.7071)}, ch);
                closingLp.process(buf, f + 1, ch);
            }
        }
    }

    /** Custom curve between an arbitrary start and end gain. */
    private void applyAdjustable(float[] buf, int frames, int ch, int fadeFrames) {
        float g0 = startGain.getCurrentRaw() / 100f;
        float g1 = endGain.getCurrentRaw() / 100f;
        float mid = midBoost.getCurrentRaw() / 100f;
        int curveIdx = curve.getEnumIndex();
        for (int f = 0; f < frames; f++) {
            float u = fadeFrames <= 1 ? 1f
                    : Math.min(1f, f / (float) (fadeFrames - 1));
            float shape;
            switch (curveIdx) {
                case 1: // Logarithmic
                    shape = (float) Math.pow(u, 0.5);
                    break;
                case 2: // Exponential
                    shape = (float) Math.pow(u, 2.0);
                    break;
                case 3: // Cosine
                    shape = (float) (0.5 - 0.5 * Math.cos(Math.PI * u));
                    break;
                case 4: // S-Curve
                    shape = u * u * (3f - 2f * u);
                    break;
                case 0:
                default: // Linear
                    shape = u;
                    break;
            }
            float g = g0 + (g1 - g0) * shape;
            if (mid != 0f) {
                // Bell-shaped mid-fade boost.
                g *= 1f + mid * 4f * shape * (1f - shape);
            }
            int idx = f * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= g;
        }
    }
}
