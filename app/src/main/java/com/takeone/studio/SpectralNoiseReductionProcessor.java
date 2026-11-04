package com.takeone.studio;

import java.io.File;

/**
 * Stage 5 Spectral Noise Reduction: learns a per-bin noise power profile from a
 * noise-only selection and applies spectral gating with configurable Noise
 * Reduction (dB), Sensitivity (over-subtraction factor), Frequency Smoothing
 * (bins), and Reduce vs Isolate-Noise mode.
 */
final class SpectralNoiseReductionProcessor extends AudioProcessor {

    public static final int FFT_SIZE = 1024;
    public static final int BINS = FFT_SIZE / 2 + 1;
    private static final String[] MODE_LABELS = {"Reduce Noise", "Isolate Noise"};

    private final FxParameter reduction = addParameter(FxParameter.dbParam(
            "snr_reduction", "Noise Reduction", 0f, 48f, 18f));
    private final FxParameter sensitivity = addParameter(FxParameter.floatParam(
            "snr_sens", "Sensitivity", " dB", 0f, 24f, 6f));
    private final FxParameter freqSmooth = addParameter(FxParameter.intParam(
            "snr_smooth", "Freq Smoothing", " bins", 0, 12, 1, 2));
    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "snr_mode", "Mode", MODE_LABELS, 0));

    private volatile float[] learnedProfile;
    private volatile float[] autoProfile;
    private volatile float[] streamProfile;
    private volatile float streamFloor;
    private volatile float streamOver;
    private volatile int streamSmooth;
    private volatile boolean streamIsolate;

    private final StftProcessor stft = new StftProcessor(FFT_SIZE, 4) {
        private final float[] rawG = new float[BINS];
        private final float[] smG = new float[BINS];

        @Override
        protected void processSpectrum(float[] re, float[] im, int binCount, int channel) {
            float[] profile = streamProfile;
            float scale = 1.0f / ((float) FFT_SIZE * FFT_SIZE * 0.25f);
            for (int b = 0; b < binCount; b++) {
                float p = (re[b] * re[b] + im[b] * im[b]) * scale;
                float n = profile != null && b < profile.length
                        ? profile[b] * streamOver : 1e-7f * streamOver;
                float g;
                if (p <= 1e-16f || p <= n) {
                    g = streamFloor;
                } else {
                    g = (float) Math.sqrt(Math.max(0f, (p - n) / p));
                    if (g < streamFloor) g = streamFloor;
                    if (g > 1f) g = 1f;
                }
                rawG[b] = g;
            }
            if (streamSmooth > 0) {
                for (int b = 0; b < binCount; b++) {
                    int lo = Math.max(0, b - streamSmooth);
                    int hi = Math.min(binCount - 1, b + streamSmooth);
                    float sum = 0f;
                    for (int k = lo; k <= hi; k++) sum += rawG[k];
                    smG[b] = sum / (hi - lo + 1);
                }
            } else {
                System.arraycopy(rawG, 0, smG, 0, binCount);
            }
            for (int b = 0; b < binCount; b++) {
                float g = streamIsolate ? Math.max(0f, 1.0f - smG[b]) : smG[b];
                re[b] *= g;
                im[b] *= g;
                if (b > 0 && b < binCount - 1) {
                    int sym = FFT_SIZE - b;
                    re[sym] *= g;
                    im[sym] *= g;
                }
            }
        }
    };

    SpectralNoiseReductionProcessor() {
    }

    @Override
    public String getId() {
        return "specnr";
    }

    @Override
    public String getTitle() {
        return "Spectral Noise Reduction";
    }

    @Override
    public String getDescription() {
        return "Learned-profile STFT spectral gating with frequency smoothing";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
        stft.reset();
        autoProfile = null;
    }

    @Override
    public void prepareOffline(File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (learnedProfile != null || raw == null) return;
        long end = Math.min(endFrame, startFrame + 16384L);
        if (end <= startFrame) return;
        float[] samples = EditEngine.readFloatSelection(raw, startFrame, end);
        int ch = Math.max(1, Math.min(2, channels));
        if (samples == null) return;
        int frames = samples.length / ch;
        if (frames >= FFT_SIZE) {
            autoProfile = StftProcessor.welchSpectrum(
                    samples, frames, ch, FFT_SIZE, 0.55f);
        }
    }

    public void setNoiseProfile(float[] profile) {
        if (profile != null && profile.length == BINS) {
            this.learnedProfile = profile.clone();
        } else {
            this.learnedProfile = null;
        }
    }

    public float[] getNoiseProfile() {
        return learnedProfile;
    }

    public static float[] learnProfile(float[] samples, int channels) {
        if (samples == null) return null;
        int ch = Math.max(1, Math.min(2, channels));
        int frames = samples.length / ch;
        if (frames < FFT_SIZE) return null;
        return StftProcessor.welchSpectrum(samples, frames, ch, FFT_SIZE, 1.15f);
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (reduction.getFloat() > 0.1f || mode.getEnumIndex() == 1);
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
        float[] prof = learnedProfile;
        if (prof == null) prof = autoProfile;
        if (prof == null && frames >= FFT_SIZE) {
            prof = StftProcessor.welchSpectrum(buf, frames, ch, FFT_SIZE, 0.55f);
            autoProfile = prof;
        }
        streamProfile = prof;
        streamFloor = Dsp.dbToLin(-Math.max(0f, reduction.getFloat()));
        streamOver = Dsp.dbToLin(sensitivity.getFloat());
        streamSmooth = Math.max(0, Math.min(12, freqSmooth.getCurrentRaw()));
        streamIsolate = mode.getEnumIndex() == 1;
        stft.processStreaming(buf, frames, ch, getWetDryMix());
    }
}
