package com.example.hello;

/**
 * Stage 5 spectral noise reduction: compares each STFT frame's power
 * against a learned (or adaptively estimated) noise profile and subtracts
 * the excess, with a frequency-domain moving average to keep the result
 * free of musical birdies.
 */
final class SpectralNoiseReductionProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Reduce Noise", "Isolate Noise"};

    private final FxParameter reduction = addParameter(FxParameter.dbParam(
            "snr_reduction", "Noise Reduction", 0f, 48f, 12f));
    private final FxParameter sensitivity = addParameter(FxParameter.floatParam(
            "snr_sensitivity", "Sensitivity", "", 0f, 24f, 6f));
    private final FxParameter smoothing = addParameter(FxParameter.intParam(
            "snr_smooth", "Smoothing Bands", "", 0, 12, 1, 3));
    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "snr_mode", "Mode", MODE_LABELS, 0));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private float[] noiseProfile;
    private float[] runningMean;
    private int bins = 0;
    private boolean profileLearned = false;

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
        return "STFT spectral subtraction against a learned noise profile";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
    }

    @Override
    public void reset() {
        if (runningMean != null) java.util.Arrays.fill(runningMean, 0f);
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && reduction.getFloat() > 0.01f;
    }

    /** Learned noise profile (linear power per bin) or null. */
    public float[] getNoiseProfile() {
        return noiseProfile;
    }

    public void setNoiseProfile(float[] profile) {
        this.noiseProfile = profile;
        this.profileLearned = profile != null && profile.length > 0;
    }

    /**
     * Learns the noise profile of a steady noise-only region.
     *
     * <p>The profile is the averaged power spectrum of the region, so a
     * region of pure hiss produces a flat floor that the reduction stage
     * subtracts from every frame.
     *
     * @return the profile, or null when the region is too short
     */
    static float[] learnProfile(float[] samples, int channels) {
        if (samples == null || samples.length == 0 || channels < 1) return null;
        int ch = Math.max(1, Math.min(2, channels));
        int frames = samples.length / ch;
        return learnPower(samples, frames, ch);
    }

    /**
     * Learns the noise profile from a region (used by the "Learn" action and
     * by the automatic fallback to the quietest 300 ms).
     */
    public void learnProfile(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float[] power = learnPower(buf, frames, ch);
        if (power == null) return;
        noiseProfile = power;
        profileLearned = true;
    }

    /**
     * Averages the raw per-bin power of the learning region.
     *
     * <p>The FFT size and the unnormalised power scale deliberately match
     * {@link #processInPlace}: the submitted profile is compared directly
     * with each processing frame's power, so normalising it (or learning it
     * with a different FFT size) would move the floor and either over-
     * suppress the signal or leave the noise untouched.
     */
    private static float[] learnPower(float[] samples, int frames, int ch) {
        final int fftSize = 1024;
        final int bins = fftSize / 2 + 1;
        if (frames < fftSize) return null;
        int hop = fftSize / 4;
        float[] window = Dsp.hannWindow(fftSize);
        float[] acc = new float[bins];
        float[] re = new float[fftSize];
        float[] im = new float[fftSize];
        int used = 0;
        for (int start = 0; start + fftSize <= frames; start += hop) {
            for (int c = 0; c < ch; c++) {
                java.util.Arrays.fill(im, 0f);
                for (int n = 0; n < fftSize; n++) {
                    re[n] = samples[(start + n) * ch + c] * window[n];
                }
                Dsp.fft(re, im, false);
                for (int b = 0; b < bins; b++) {
                    acc[b] += re[b] * re[b] + im[b] * im[b];
                }
            }
            used++;
        }
        if (used == 0) return null;
        float inv = 1f / (used * (float) ch);
        for (int b = 0; b < bins; b++) {
            acc[b] = Math.max(1e-20f, acc[b] * inv);
        }
        return acc;
    }

    @Override
    public void prepareOffline(java.io.File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (raw == null || profileLearned) return;
        float[] sel = EditEngine.readFloatSelection(raw, startFrame, endFrame);
        if (sel == null || sel.length == 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int totalFrames = sel.length / ch;
        // Fall back to the quietest 300 ms window of the selection.
        int win = Math.min(totalFrames, (int) (sampleRate * 0.3));
        if (win < 64) win = totalFrames;
        if (win <= 0) return;
        int best = 0;
        float bestEnergy = Float.MAX_VALUE;
        for (int start = 0; start + win <= totalFrames; start += Math.max(1, win / 4)) {
            double sum = 0;
            for (int f = start; f < start + win; f++) {
                for (int c = 0; c < ch; c++) {
                    double v = sel[f * ch + c];
                    sum += v * v;
                }
            }
            float e = (float) (sum / (win * (double) ch));
            if (e < bestEnergy) {
                bestEnergy = e;
                best = start;
            }
        }
        float[] quiet = new float[win * ch];
        System.arraycopy(sel, best * ch, quiet, 0, quiet.length);
        learnProfile(quiet, win, ch);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        StftProcessor stft = new StftProcessor(1024, 4) {
            @Override
            protected void processSpectrum(float[] re, float[] im,
                                           int binCount, int channel) {
                applyToSpectrum(re, im, binCount);
            }
        };
        bins = stft.bins();
        if (runningMean == null || runningMean.length != bins) {
            runningMean = new float[bins];
        }
        stft.process(buf, frames, ch);
    }

    private void applyToSpectrum(float[] re, float[] im, int binCount) {
        final int spectrumSize = re.length;
        float red = Dsp.dbToLin(-reduction.getFloat());
        float sens = (float) Math.pow(10.0, sensitivity.getFloat() / 10.0);
        int smooth = smoothing.getCurrentRaw();
        boolean isolate = mode.getEnumIndex() == 1;

        float[] mags = new float[binCount];
        float[] phases = new float[binCount];
        for (int b = 0; b < binCount; b++) {
            float mag = (float) Math.sqrt(re[b] * re[b] + im[b] * im[b]);
            float ph = (float) Math.atan2(im[b], re[b]);
            mags[b] = mag;
            phases[b] = ph;
        }
        // Adaptive noise floor: the running minimum of each bin.
        for (int b = 0; b < binCount; b++) {
            float p = mags[b] * mags[b];
            if (runningMean[b] <= 0f) {
                runningMean[b] = p;
            } else if (p < runningMean[b]) {
                runningMean[b] = p * 0.6f + runningMean[b] * 0.4f;
            } else {
                runningMean[b] = p * 0.02f + runningMean[b] * 0.98f;
            }
        }
        float[] floor = new float[binCount];
        for (int b = 0; b < binCount; b++) {
            float base = profileLearned && noiseProfile != null
                    && b < noiseProfile.length ? noiseProfile[b] : runningMean[b];
            floor[b] = base * sens;
        }
        // Frequency smoothing of the gain curve.
        float[] gain = new float[binCount];
        for (int b = 0; b < binCount; b++) {
            float power = mags[b] * mags[b];
            float excess = power - floor[b];
            float g;
            if (excess <= 0f) {
                g = red;
            } else {
                g = Math.max(red, 1f - floor[b] / power);
            }
            if (isolate) {
                g = Math.min(1f, floor[b] / Math.max(1e-12f, power));
            }
            gain[b] = g;
        }
        if (smooth > 0) {
            float[] smoothed = new float[binCount];
            for (int b = 0; b < binCount; b++) {
                float sum = 0;
                int count = 0;
                for (int k = -smooth; k <= smooth; k++) {
                    int idx = b + k;
                    if (idx < 0 || idx >= binCount) continue;
                    sum += gain[idx];
                    count++;
                }
                smoothed[b] = count > 0 ? sum / count : gain[b];
            }
            gain = smoothed;
        }
        for (int b = 0; b < binCount; b++) {
            float mag = mags[b] * gain[b];
            re[b] = (float) (mag * Math.cos(phases[b]));
            im[b] = (float) (mag * Math.sin(phases[b]));
            if (b > 0 && b < binCount - 1) {
                int mirror = spectrumSize - b;
                if (mirror < re.length) {
                    re[mirror] = re[b];
                    im[mirror] = -im[b];
                }
            }
        }
    }
}
