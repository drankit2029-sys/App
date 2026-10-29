package com.example.hello;

import java.util.Arrays;

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
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float[] prof = learnedProfile;
        if (prof == null && frames >= FFT_SIZE) {
            prof = StftProcessor.welchSpectrum(buf, frames, ch, FFT_SIZE, 0.55f);
        }
        final float[] activeProf = prof;
        final float floorLin = Dsp.dbToLin(-Math.max(0f, reduction.getFloat()));
        final float overSub = Dsp.dbToLin(sensitivity.getFloat());
        final int smoothBins = Math.max(0, Math.min(12, freqSmooth.getCurrentRaw()));
        final boolean isolate = mode.getEnumIndex() == 1;

        StftProcessor stft = new StftProcessor(FFT_SIZE, 4) {
            private final float[] rawG = new float[BINS];
            private final float[] smG = new float[BINS];

            @Override
            protected void processSpectrum(float[] re, float[] im, int binCount, int channel) {
                float scale = 1.0f / ((float) FFT_SIZE * FFT_SIZE * 0.25f);
                for (int b = 0; b < binCount; b++) {
                    float p = (re[b] * re[b] + im[b] * im[b]) * scale;
                    float n = activeProf != null && b < activeProf.length
                            ? activeProf[b] * overSub : 1e-7f * overSub;
                    float g;
                    if (p <= 1e-16f || p <= n) {
                        g = floorLin;
                    } else {
                        g = (float) Math.sqrt(Math.max(0f, (p - n) / p));
                        if (g < floorLin) g = floorLin;
                        if (g > 1f) g = 1f;
                    }
                    rawG[b] = g;
                }
                if (smoothBins > 0) {
                    for (int b = 0; b < binCount; b++) {
                        int lo = Math.max(0, b - smoothBins);
                        int hi = Math.min(binCount - 1, b + smoothBins);
                        float sum = 0f;
                        for (int k = lo; k <= hi; k++) sum += rawG[k];
                        smG[b] = sum / (hi - lo + 1);
                    }
                } else {
                    System.arraycopy(rawG, 0, smG, 0, binCount);
                }
                for (int b = 0; b < binCount; b++) {
                    // In Isolate Noise mode, complement the smoothed reduction
                    // mask (1 - smG[b]) so both snr_reduction and snr_smooth
                    // shape the isolated noise residual.
                    float g = isolate ? Math.max(0f, 1.0f - smG[b]) : smG[b];
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
        stft.process(buf, frames, ch);
    }
}
