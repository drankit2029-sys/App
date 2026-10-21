package com.example.hello;

import org.json.JSONArray;

/**
 * Professional spectral noise suppressor with a learned noise profile.
 *
 * <p>Workflow: the user selects a noise-only region of the take and taps
 * <em>Learn from selection</em> in the FX rack; {@link #learnProfile(float[], int)}
 * averages the short-time spectrum of that region into a per-bin noise power
 * profile which is persisted with the slot. Applying the effect then performs
 * overlap-add spectral subtraction against that profile, so hiss, hum and room
 * tone are attenuated far more musically than the old percentage-driven
 * three-band gate, while loud voice bins pass through untouched.
 *
 * <p>When no profile has been learned the processor falls back to an adaptive
 * per-bin minimum tracker, so presets still denoise out of the box. The stage
 * is a fixed-latency STFT processor: {@link #FFT_SIZE} frames of algorithmic
 * delay, reported through {@link #getLatencyFrames()} so offline range commits
 * prime and drain it exactly.
 */
final class NoiseSuppressionProcessor extends AudioProcessor {

    static final int FFT_SIZE = 2048;
    static final int HOP = 512;
    static final int BINS = FFT_SIZE / 2 + 1;

    private static final float OLA_SCALE = 2f / 3f;
    private static final float POWER_FLOOR = 1e-14f;

    private static final float[] WINDOW = buildWindow();

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Intensity", "%", 0, 100, 1, 50));
    private final FxParameter smooth = addParameter(FxParameter.intParam(
            "smooth", "Smoothing", "%", 0, 100, 1, 50));

    private final float[][] inHist = {
            new float[FFT_SIZE], new float[FFT_SIZE]};
    private final float[][] ola = {
            new float[FFT_SIZE], new float[FFT_SIZE]};
    private final float[][] re = {
            new float[FFT_SIZE], new float[FFT_SIZE]};
    private final float[][] im = {
            new float[FFT_SIZE], new float[FFT_SIZE]};
    private final float[] gainPrev = new float[BINS];
    private final float[] noiseEst = new float[BINS];

    private float[][] outQ = {new float[0], new float[0]};
    private int outCount = 0;
    private int histFill = 0;
    private long inputConsumed = 0L;
    private long genuinePopped = 0L;
    private boolean noiseInit = false;

    private volatile float[] profile;

    NoiseSuppressionProcessor() {
        java.util.Arrays.fill(gainPrev, 1f);
    }

    private static float[] buildWindow() {
        float[] w = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            w[i] = (float) (0.5 - 0.5 * Math.cos(
                    2.0 * Math.PI * i / FFT_SIZE));
        }
        return w;
    }

    @Override
    public String getId() {
        return "noise";
    }

    @Override
    public String getTitle() {
        return "Noise Suppression Pro";
    }

    @Override
    public String getDescription() {
        return "Learned-profile spectral denoiser: analyze a noise-only "
                + "selection, then apply";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt.getCurrentRaw() > 0
                && getWetDryMix() > 0.001f;
    }

    @Override
    public int getLatencyFrames() {
        return isAudible() ? FFT_SIZE : 0;
    }

    /**
     * The spectral stage has no decaying tail: its FFT_SIZE is the analysis
     * latency, which is already reported by {@link #getLatencyFrames()} and
     * compensated by the offline range commit. Reporting it as a tail too
     * would make the engine flush a phantom decay after every apply.
     */
    @Override
    public int getTailFrames() {
        return 0;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            java.util.Arrays.fill(inHist[c], 0f);
            java.util.Arrays.fill(ola[c], 0f);
        }
        java.util.Arrays.fill(gainPrev, 1f);
        java.util.Arrays.fill(noiseEst, 0f);
        noiseInit = false;
        histFill = 0;
        outCount = 0;
        inputConsumed = 0L;
        genuinePopped = 0L;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    /** Installs (or clears with {@code null}) a learned noise power profile. */
    void setNoiseProfile(float[] p) {
        profile = (p != null && p.length == BINS) ? p : null;
    }

    float[] getNoiseProfile() {
        return profile;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float mix = getWetDryMix();
        if (amt.getCurrentRaw() <= 0 || mix <= 0.001f) return;

        ensureOutCapacity(outCount + frames + 2 * HOP);
        int off = 0;
        while (off < frames) {
            int take = Math.min(FFT_SIZE - histFill, frames - off);
            if (take > 0) {
                for (int f = 0; f < take; f++) {
                    for (int c = 0; c < ch; c++) {
                        inHist[c][histFill + f] = buf[(off + f) * ch + c];
                    }
                }
                histFill += take;
                off += take;
                inputConsumed += take;
            }
            if (histFill < FFT_SIZE) continue;

            processFrame(ch, mix);

            for (int c = 0; c < ch; c++) {
                System.arraycopy(inHist[c], HOP, inHist[c], 0,
                        FFT_SIZE - HOP);
            }
            histFill = FFT_SIZE - HOP;
        }

        long ready = Math.max(0L, inputConsumed - FFT_SIZE);
        int readyInQueue = (int) Math.min(outCount,
                Math.max(0L, ready - genuinePopped));
        int give = Math.min(frames, readyInQueue);
        for (int f = 0; f < give; f++) {
            for (int c = 0; c < ch; c++) {
                buf[f * ch + c] = outQ[c][f];
            }
        }
        for (int f = give; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                buf[f * ch + c] = 0f;
            }
        }
        genuinePopped += give;
        if (give > 0) {
            int left = outCount - give;
            for (int c = 0; c < ch; c++) {
                System.arraycopy(outQ[c], give, outQ[c], 0, left);
            }
            outCount = left;
        }
    }

    private void processFrame(int ch, float mix) {
        final float amt01 = amt.getCurrentRaw() / 100f;
        final float smooth01 = smooth.getCurrentRaw() / 100f;
        final float overSub = 1f + 2f * amt01;
        final float floorGain = Dsp.dbToLin(-(6.0 + 24.0 * amt01));
        final float floorSq = floorGain * floorGain;
        final float[] prof = profile;

        for (int c = 0; c < ch; c++) {
            java.util.Arrays.fill(im[c], 0f);
            for (int n = 0; n < FFT_SIZE; n++) {
                re[c][n] = inHist[c][n] * WINDOW[n];
            }
            Dsp.fft(re[c], im[c], false);
        }

        for (int k = 0; k <= BINS - 1; k++) {
            float pow = 0f;
            for (int c = 0; c < ch; c++) {
                pow += re[c][k] * re[c][k] + im[c][k] * im[c][k];
            }
            pow /= ch;

            float pn;
            if (prof != null) {
                pn = Math.max(POWER_FLOOR, prof[k]);
            } else {
                if (!noiseInit) {
                    noiseEst[k] = Math.max(POWER_FLOOR, pow);
                } else {
                    // Symmetric slow tracker: converged on stationary noise it
                    // sits at the mean bin power, which is exactly what the
                    // subtraction expects (a minimum tracker would bias far
                    // below the mean and leave the noise almost untouched).
                    float track = 0.01f + 0.04f * smooth01;
                    noiseEst[k] += track * (pow - noiseEst[k]);
                    if (noiseEst[k] < POWER_FLOOR) noiseEst[k] = POWER_FLOOR;
                }
                pn = Math.max(POWER_FLOOR, noiseEst[k]);
            }

            float snr = pow / pn;
            float g2 = 1f - overSub / Math.max(snr, 1e-6f);
            float g = (float) Math.sqrt(Math.max(g2, floorSq));
            g = Math.min(1f, g);

            float prev = gainPrev[k];
            float mem = g < prev ? (0.95f - 0.75f * smooth01)
                    : (0.55f + 0.40f * smooth01);
            float gOut = prev * mem + g * (1f - mem);
            gainPrev[k] = gOut;

            re[0][k] *= gOut;
            im[0][k] *= gOut;
            if (ch > 1) {
                re[1][k] *= gOut;
                im[1][k] *= gOut;
            }
            if (k > 0 && k < BINS - 1) {
                int m = FFT_SIZE - k;
                re[0][m] *= gOut;
                im[0][m] *= gOut;
                if (ch > 1) {
                    re[1][m] *= gOut;
                    im[1][m] *= gOut;
                }
            }
        }
        noiseInit = true;

        for (int c = 0; c < ch; c++) {
            Dsp.fft(re[c], im[c], true);
            for (int n = 0; n < FFT_SIZE; n++) {
                ola[c][n] += re[c][n] * WINDOW[n] * OLA_SCALE;
            }
        }

        for (int n = 0; n < HOP; n++) {
            for (int c = 0; c < ch; c++) {
                float wet = ola[c][n];
                float dry = inHist[c][n];
                outQ[c][outCount + n] = mix * wet + (1f - mix) * dry;
            }
        }
        outCount += HOP;

        for (int c = 0; c < ch; c++) {
            System.arraycopy(ola[c], HOP, ola[c], 0, FFT_SIZE - HOP);
            java.util.Arrays.fill(ola[c], FFT_SIZE - HOP, FFT_SIZE, 0f);
        }
    }

    private void ensureOutCapacity(int capacity) {
        if (outQ[0].length >= capacity) return;
        float[][] grown = {new float[capacity], new float[capacity]};
        if (outCount > 0) {
            System.arraycopy(outQ[0], 0, grown[0], 0, outCount);
            System.arraycopy(outQ[1], 0, grown[1], 0, outCount);
        }
        outQ = grown;
    }

    /**
     * Learns a per-bin noise power profile from interleaved samples of a
     * noise-only selection. Returns {@code null} when the selection is too
     * short for one full analysis frame.
     */
    static float[] learnProfile(float[] interleaved, int channels) {
        if (interleaved == null || interleaved.length == 0) return null;
        int ch = Math.max(1, Math.min(2, channels));
        int totalFrames = interleaved.length / ch;
        if (totalFrames < FFT_SIZE) return null;

        float[] acc = new float[BINS];
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        int frameCount = 0;
        for (int start = 0; start + FFT_SIZE <= totalFrames; start += HOP) {
            for (int c = 0; c < ch; c++) {
                java.util.Arrays.fill(im, 0f);
                for (int n = 0; n < FFT_SIZE; n++) {
                    re[n] = interleaved[(start + n) * ch + c] * WINDOW[n];
                }
                Dsp.fft(re, im, false);
                for (int k = 0; k < BINS; k++) {
                    acc[k] += re[k] * re[k] + im[k] * im[k];
                }
            }
            frameCount++;
        }
        if (frameCount == 0) return null;

        float inv = 1f / (frameCount * ch);
        for (int k = 0; k < BINS; k++) {
            acc[k] = Math.max(POWER_FLOOR, acc[k] * inv);
        }
        float[] smoothProfile = new float[BINS];
        smoothProfile[0] = acc[0];
        smoothProfile[BINS - 1] = acc[BINS - 1];
        for (int k = 1; k < BINS - 1; k++) {
            smoothProfile[k] = (acc[k - 1] + 2f * acc[k] + acc[k + 1]) * 0.25f;
        }
        return smoothProfile;
    }

    static JSONArray profileToJson(float[] p) {
        JSONArray a = new JSONArray();
        if (p == null) return a;
        for (float v : p) {
            a.put(Math.round(Dsp.linToDb(v) * 10f));
        }
        return a;
    }

    static float[] profileFromJson(JSONArray a) {
        if (a == null || a.length() != BINS) return null;
        float[] p = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            p[i] = Dsp.dbToLin(a.optInt(i, -1200) / 10.0);
        }
        return p;
    }
}
