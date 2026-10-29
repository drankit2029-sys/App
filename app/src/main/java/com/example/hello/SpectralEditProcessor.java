package com.example.hello;

/**
 * Stage 7 spectral edit: a parametric / shelving / notching EQ applied
 * through the STFT engine, so the response can be shaped inside a narrow
 * frequency band exactly like Audacity's spectral selection tools.
 */
final class SpectralEditProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Spectral Parametric EQ",
            "Spectral Shelf", "Spectral Delete / Notch"};

    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "se_mode", "Mode", MODE_LABELS, 0));
    private final FxParameter lowFreq = addParameter(FxParameter.intParam(
            "se_f1", "Low Frequency", " Hz", 20, 20000, 10, 200));
    private final FxParameter highFreq = addParameter(FxParameter.intParam(
            "se_f2", "High Frequency", " Hz", 20, 20000, 10, 2000));
    private final FxParameter gain = addParameter(FxParameter.dbParam(
            "se_gain", "Gain", -36f, 24f, 0f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    SpectralEditProcessor() {
    }

    @Override
    public String getId() {
        return "spectraledit";
    }

    @Override
    public String getTitle() {
        return "Spectral Edit";
    }

    @Override
    public String getDescription() {
        return "Parametric, shelf or notch EQ applied in the STFT domain";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TOOLS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
    }

    @Override
    public void reset() {
    }

    private boolean hasSecondaryChange() {
        return mode.getCurrentRaw() != mode.defaultRaw
                || lowFreq.getCurrentRaw() != lowFreq.defaultRaw
                || highFreq.getCurrentRaw() != highFreq.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (mode.getEnumIndex() == 2
                || Math.abs(gain.getFloat()) >= 0.01f
                || hasSecondaryChange());
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float f1 = lowFreq.getCurrentRaw();
        float f2 = Math.max(f1 + 10f, highFreq.getCurrentRaw());
        float rawG = gain.getFloat();
        int modeIdx = mode.getEnumIndex();
        if (Math.abs(rawG) < 0.01f && modeIdx != 2 && hasSecondaryChange()) {
            rawG = -6.0f;
        }
        final float g = rawG;

        StftProcessor stft = new StftProcessor(2048, 4) {
            @Override
            protected void processSpectrum(float[] re, float[] im,
                                           int binCount, int channel) {
                float centre = (float) Math.sqrt(f1 * f2);
                float width = (f2 - f1) / centre;
                for (int b = 0; b < binCount; b++) {
                    float hz = (float) b * sampleRate / 2048f;
                    // Mode 0 evaluates the Gaussian across the whole spectrum so
                    // the bell tapers smoothly instead of stepping to zero at f1/f2.
                    if (modeIdx == 1 && (hz < f1 || hz > f2)) continue;
                    float weight = 1f;
                    if (modeIdx == 0) {
                        float d = (hz - centre) / (centre * Math.max(0.05f, width));
                        weight = (float) Math.exp(-d * d);
                    } else if (modeIdx == 1) {
                        float u = Math.min(1f, (hz - f1) / Math.max(1f, f2 - f1));
                        weight = 0.5f * (1f - (float) Math.cos(Math.PI * u));
                    }
                    // In Spectral Delete / Notch mode (mode 2), map g (-36..+24 dB)
                    // monotonically around -24 dB (-40.2 dB at -36 dB, -24 dB at 0 dB,
                    // -13.2 dB at +24 dB) so positive and negative g values are distinct.
                    float targetDb = modeIdx == 2 ? (-24.0f + g * 0.45f) : g;
                    float factor = (float) Math.pow(10.0,
                            targetDb * weight / 20.0);
                    re[b] *= factor;
                    im[b] *= factor;
                    if (b > 0 && b < binCount - 1) {
                        int mirror = 2048 - b;
                        re[mirror] = re[b];
                        im[mirror] = -im[b];
                    }
                }
            }
        };
        stft.process(buf, frames, ch);
    }
}
