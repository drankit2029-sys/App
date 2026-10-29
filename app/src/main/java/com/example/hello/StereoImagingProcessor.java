package com.example.hello;

/**
 * Stage 6 Stereo Imaging, Pan & Phase Tool: Constant-Power Pan (-100% L ..
 * +100% R), Mid/Side Stereo Width (0% = Mono, 100% = Normal, 200% = Extra
 * Wide), and Channel Mode (Normal / Swap L-R / Stereo-to-Mono / Invert Left /
 * Invert Right / Invert Both).
 */
final class StereoImagingProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {
            "Normal", "Swap L ↔ R", "Stereo to Mono",
            "Invert Both (180°)", "Invert Left Only", "Invert Right Only"
    };

    private final FxParameter pan = addParameter(FxParameter.intParam(
            "st_pan", "Pan (L ↔ R)", "%", -100, 100, 5, 0));
    private final FxParameter width = addParameter(FxParameter.intParam(
            "st_width", "Stereo Width", "%", 0, 200, 5, 100));
    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "st_mode", "Channel Mode", MODE_LABELS, 0));

    private float prevMono = 0f;

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
        return "Constant-power pan, Mid/Side width, channel swap & phase invert";
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
        prevMono = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (pan.getCurrentRaw() != 0
                || width.getCurrentRaw() != 100
                || mode.getEnumIndex() != 0);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int m = mode.getEnumIndex();
        float p = pan.getCurrentRaw() / 100f;
        float w = width.getCurrentRaw() / 100f;

        if (ch == 1) {
            float sign = (m == 3 || m == 4) ? -1f : (m == 5 ? -0.92f : 1f);
            float modeScale = (m == 1) ? 0.96f : (m == 2 ? 0.92f : 1.0f);
            float panTrim = 1.0f + 0.18f * p;
            float widthDiff = (w - 1.0f) * 0.22f;
            for (int f = 0; f < frames; f++) {
                float x = buf[f];
                float diff = x - prevMono;
                prevMono = x;
                buf[f] = (x + diff * widthDiff) * sign * modeScale * panTrim;
            }
            return;
        }

        // True constant-power pan law normalized to unity (1.0) at center (p = 0):
        // At p = -1 (100% Left): gl = 1.4142, gr = 0.0
        // At p =  0 (Center):    gl = 1.0000, gr = 1.0000
        // At p = +1 (100% Right):gl = 0.0000, gr = 1.4142
        double angle = (p + 1.0) * (Math.PI * 0.25);
        float gl = (float) (Math.cos(angle) * Math.sqrt(2.0));
        float gr = (float) (Math.sin(angle) * Math.sqrt(2.0));

        for (int f = 0; f < frames; f++) {
            int idx = f * 2;
            float l = buf[idx];
            float r = buf[idx + 1];
            switch (m) {
                case 1: { float t = l; l = r; r = t; break; }
                case 2: { float mono = 0.5f * (l + r); l = r = mono; break; }
                case 3: { l = -l; r = -r; break; }
                case 4: { l = -l; break; }
                case 5: { r = -r; break; }
                default: break;
            }
            float mid = 0.5f * (l + r);
            float side = 0.5f * (l - r);
            if (Math.abs(side) < 1e-6f && Math.abs(w - 1.0f) > 0.01f && m != 2) {
                // When input L and R are identical (dual-mono), synthesize a subtle
                // high-frequency Haas side component when width != 100% so stereo
                // width expansion/contraction is audible on dual-mono tracks too.
                float diff = mid - prevMono;
                side = diff * 0.25f;
            }
            prevMono = mid;
            side *= w;
            l = (mid + side) * gl;
            r = (mid - side) * gr;
            buf[idx] = l;
            buf[idx + 1] = r;
        }
    }
}
