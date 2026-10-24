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

    @Override
    public boolean isAudible() {
        return isEnabled() && (mode.getEnumIndex() == 2
                || Math.abs(gain.getFloat()) >= 0.01f);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float f1 = lowFreq.getCurrentRaw();
        float f2 = Math.max(f1 + 10f, highFreq.getCurrentRaw());
        float g = gain.getFloat();
        int modeIdx = mode.getEnumIndex();

        StftProcessor stft = new StftProcessor(2048, 4) {
            @Override
            protected void processSpectrum(float[] re, float[] im,
                                           int binCount, int channel) {
                float centre = (float) Math.sqrt(f1 * f2);
                float width = (f2 - f1) / centre;
                for (int b = 0; b < binCount; b++) {
                    float hz = (float) b * sampleRate / 2048f;
                    if (hz < f1 || hz > f2) continue;
                    float weight = 1f;
                    if (modeIdx == 0) {
                        // Bell shape centred in the band.
                        float d = (hz - centre) / (centre * Math.max(0.05f, width));
                        weight = (float) Math.exp(-d * d);
                    }
                    float targetDb = modeIdx == 2 ? (Math.abs(g) > 0.1f ? -Math.abs(g) : -24f) : g;
                    float factor = (float) Math.pow(10.0,
                            targetDb * weight / 20.0);
                    re[b] *= factor;
                    im[b] *= factor;
                    int mirror = 2048 - b;
                    if (mirror < re.length) {
                        re[mirror] = re[b];
                        im[mirror] = -im[b];
                    }
                }
            }
        };
        stft.process(buf, frames, ch);
    }
}
