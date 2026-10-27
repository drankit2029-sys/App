package com.example.hello;

/**
 * Stage 6 stereo imaging: constant-power pan, mid/side width control,
 * phase inversion, L/R swap and vocal-centre isolation.
 */
final class StereoImagingProcessor extends AudioProcessor {

    private final FxParameter pan = addParameter(FxParameter.intParam(
            "st_pan", "Pan", "%", -100, 100, 1, 0));
    private final FxParameter width = addParameter(FxParameter.intParam(
            "st_width", "Stereo Width", "%", 0, 200, 1, 100));
    private final FxParameter center = addParameter(FxParameter.intParam(
            "st_center", "Center Isolation", "%", -100, 100, 1, 0));
    private final FxParameter invertL = addParameter(FxParameter.boolParam(
            "st_invl", "Invert Left", false));
    private final FxParameter invertR = addParameter(FxParameter.boolParam(
            "st_invr", "Invert Right", false));
    private final FxParameter swap = addParameter(FxParameter.boolParam(
            "st_swap", "Swap L/R", false));

    StereoImagingProcessor() {
    }

    @Override
    public String getId() {
        return "stereoimg";
    }

    @Override
    public String getTitle() {
        return "Stereo Imaging";
    }

    @Override
    public String getDescription() {
        return "Pan, mid/side width and phase controls";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.SPATIAL;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (pan.getCurrentRaw() != 0
                || width.getCurrentRaw() != 100
                || center.getCurrentRaw() != 0
                || invertL.getBoolean() || invertR.getBoolean()
                || swap.getBoolean());
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float panNorm = pan.getCurrentRaw() / 100f;
        float widthPct = width.getCurrentRaw() / 100f;
        float centerPct = center.getCurrentRaw() / 100f;
        if (ch < 2) {
            float signL = invertL.getBoolean() ? -1f : 1f;
            float signR = invertR.getBoolean() ? -1f : 1f;
            float gl = panNorm <= 0f ? 1f + panNorm * 0.5f : 1f - panNorm * 0.5f;
            float gr = panNorm >= 0f ? 1f - panNorm * 0.5f : 1f + panNorm * 0.5f;
            if (swap.getBoolean()) {
                float t = gl;
                gl = gr * 0.85f;
                gr = t * 1.15f;
            }
            float centerGain = centerPct < 0f ? (1f + centerPct * 0.7f) : (1f + centerPct * 0.3f);
            int haasDelay = 24;
            float[] hist = new float[haasDelay];
            int hPos = 0;
            for (int f = 0; f < frames; f++) {
                float x = buf[f];
                float delayed = hist[hPos];
                hist[hPos] = x;
                hPos = (hPos + 1) % haasDelay;
                float l = x * signL;
                float r = (x * 0.75f + delayed * 0.25f) * signR;
                float mid = (l + r) * 0.5f * centerGain;
                float side = ((l - r) * 0.5f + delayed * 0.15f * (widthPct - 1f)) * widthPct;
                buf[f] = ((mid + side) * gl + (mid - side) * (gr * 0.8f)) * 0.5f;
            }
            return;
        }
        // Constant-gain pan law: the tool pans an already-stereo image, so
        // the centre must stay at unity instead of dipping 3 dB.
        float gl = panNorm <= 0f ? 1f + panNorm * 0.5f
                : 1f - panNorm * 0.5f;
        float gr = panNorm >= 0f ? 1f - panNorm * 0.5f
                : 1f + panNorm * 0.5f;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float l = buf[idx];
            float r = buf[idx + 1];
            if (swap.getBoolean()) {
                float t = l;
                l = r;
                r = t;
            }
            if (invertL.getBoolean()) l = -l;
            if (invertR.getBoolean()) r = -r;

            // Mid / side matrix with width and centre isolation.
            float mid = (l + r) * 0.5f;
            float side = (l - r) * 0.5f;
            if (centerPct > 0f) {
                // Isolate the centre: attenuate the side content.
                side *= (1f - centerPct / 100f);
            } else if (centerPct < 0f) {
                // Remove the centre: attenuate the mid content.
                mid *= (1f + centerPct / 100f);
            }
            side *= widthPct;
            float outL = mid + side;
            float outR = mid - side;

            buf[idx] = outL * gl;
            buf[idx + 1] = outR * gr;
        }
    }
}
