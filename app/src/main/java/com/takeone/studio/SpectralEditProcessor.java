package com.takeone.studio;

import java.util.Map;

/**
 * Stage 7 spectral edit: a parametric / shelving / notching EQ applied
 * through the STFT engine, so the response can be shaped inside a narrow
 * frequency band exactly like Audacity's spectral selection tools.
 */
final class SpectralEditProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Spectral Parametric EQ",
            "Spectral Shelf", "Spectral Delete / Notch"};
    private static final int FFT_SIZE = 2048;

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
    private volatile int streamMode;
    private volatile float streamF1;
    private volatile float streamF2;
    private volatile float streamGain;

    private final StftProcessor stft = new StftProcessor(FFT_SIZE, 4) {
        @Override
        protected void processSpectrum(float[] re, float[] im,
                                       int binCount, int channel) {
            float centre = (float) Math.sqrt(streamF1 * streamF2);
            float width = (streamF2 - streamF1) / centre;
            for (int b = 0; b < binCount; b++) {
                float hz = (float) b * sampleRate / FFT_SIZE;
                if ((streamMode == 1 || streamMode == 2)
                        && (hz < streamF1 || hz > streamF2)) continue;
                float weight = 1f;
                if (streamMode == 0) {
                    float d = (hz - centre) / (centre * Math.max(0.05f, width));
                    weight = (float) Math.exp(-d * d);
                } else if (streamMode == 1) {
                    float u = Math.min(1f,
                            (hz - streamF1) / Math.max(1f, streamF2 - streamF1));
                    weight = 0.5f * (1f - (float) Math.cos(Math.PI * u));
                }
                float targetDb = streamMode == 2 ? -48.0f : streamGain;
                float factor = (float) Math.pow(10.0,
                        targetDb * weight / 20.0);
                re[b] *= factor;
                im[b] *= factor;
                if (b > 0 && b < binCount - 1) {
                    int mirror = FFT_SIZE - b;
                    re[mirror] = re[b];
                    im[mirror] = -im[b];
                }
            }
        }
    };

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
    public boolean isParamApplicable(String paramId) {
        return applicable(mode.getEnumIndex(), paramId);
    }

    @Override
    public boolean isParamApplicable(String paramId, Map<String, Integer> values) {
        int rawMode = values != null && values.containsKey("se_mode")
                ? mode.clampRaw(values.get("se_mode"))
                : mode.defaultRaw;
        return applicable(rawMode, paramId);
    }

    private static boolean applicable(int modeIndex, String paramId) {
        return !"se_gain".equals(paramId) || modeIndex != 2;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
    }

    @Override
    public void reset() {
        stft.reset();
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (mode.getEnumIndex() == 2
                || Math.abs(gain.getFloat()) >= 0.01f);
    }

    @Override
    public int getLatencyFrames() {
        return isAudible() ? FFT_SIZE : 0;
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        streamF1 = lowFreq.getCurrentRaw();
        streamF2 = Math.max(streamF1 + 10f, highFreq.getCurrentRaw());
        streamGain = gain.getFloat();
        streamMode = mode.getEnumIndex();
        stft.processStreaming(buf, frames, ch, getWetDryMix());
    }
}
