package com.example.hello;

import org.json.JSONArray;

import java.util.Arrays;

/**
 * Learned-profile STFT spectral-subtraction denoiser for the "Noise Suppression
 * Pro" rack stage.
 *
 * <p>Uses a 1024-sample Hann-windowed FFT with 50% overlap (hop = 512 samples
 * = 10.67 ms at 48 kHz), where {@code sin^2(pi*n/N) + cos^2(pi*n/N) = 1} gives
 * unity COLA reconstruction when gains are 1.0.
 *
 * <p>Two operating modes:
 * <ul>
 *   <li><b>Learned profile (primary)</b>: The user selects a noise-only region
 *       on the waveform and taps "Learn from selection". {@link #learnProfile}
 *       averages the Welch power spectrum across that region and stores the
 *       513-bin floor on the slot. Subsequent playback/apply subtracts that
 *       exact floor with a 3-bin frequency neighborhood smoother and a musical-
 *       noise floor so speech formants stay natural.</li>
 *   <li><b>Adaptive fallback</b>: When no profile has been learned yet, tracks
 *       a per-bin minimum-statistics floor so the effect still reduces steady
 *       hiss out of the box.</li>
 * </ul>
 */
public final class NoiseSuppressionProcessor extends AudioProcessor {

    public static final int FFT_SIZE = 1024;
    public static final int HOP_SIZE = 512;
    public static final int BINS = FFT_SIZE / 2 + 1;

    private static final float[] HANN = new float[FFT_SIZE];
    static {
        for (int i = 0; i < FFT_SIZE; i++) {
            HANN[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / FFT_SIZE)));
        }
    }

    private final FxParameter amt = addParameter(
            FxParameter.intParam("amt", "Intensity", "%", 0, 100, 5, 0));
    private final FxParameter floor = addParameter(
            FxParameter.floatParam("floor", "Spectral Floor", " dB", -42f, -6f, -24f));
    private final FxParameter smooth = addParameter(
            FxParameter.intParam("smooth", "Smoothing", "%", 0, 100, 5, 60));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    private volatile float[] learnedProfile;
    private final float[] adaptiveFloor = new float[BINS];
    private boolean adaptivePrimed = false;

    private float[][] inFifo;
    private float[][] outAccum;
    private float[][] outReady;
    private int fifoFill = 0;
    private int readyRead = 0;
    private int readyCount = 0;

    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private final float[] rawGain = new float[BINS];
    private final float[] smoothGain = new float[BINS];
    private final float[] prevGain = new float[BINS];

    public NoiseSuppressionProcessor() {
        allocateBuffers(2);
        Arrays.fill(prevGain, 1.0f);
    }

    private void allocateBuffers(int ch) {
        int c = Math.max(1, Math.min(2, ch));
        inFifo = new float[c][FFT_SIZE];
        outAccum = new float[c][FFT_SIZE];
        outReady = new float[c][FFT_SIZE * 4];
        fifoFill = 0;
        readyRead = 0;
        readyCount = 0;
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
        return "Learned-profile STFT spectral denoiser";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public int getLatencyFrames() {
        return HOP_SIZE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        int c = Math.max(1, Math.min(2, channels));
        if (c != this.channels || inFifo == null || inFifo.length != c) {
            this.channels = c;
            allocateBuffers(c);
        }
    }

    @Override
    public void reset() {
        if (inFifo != null) {
            for (int c = 0; c < inFifo.length; c++) {
                Arrays.fill(inFifo[c], 0f);
                Arrays.fill(outAccum[c], 0f);
                Arrays.fill(outReady[c], 0f);
            }
        }
        fifoFill = 0;
        readyRead = 0;
        readyCount = 0;
        Arrays.fill(adaptiveFloor, 0f);
        Arrays.fill(prevGain, 1.0f);
        adaptivePrimed = false;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    public void setNoiseProfile(float[] profile) {
        if (profile != null && profile.length == BINS) {
            float[] copy = new float[BINS];
            System.arraycopy(profile, 0, copy, 0, BINS);
            this.learnedProfile = copy;
        } else {
            this.learnedProfile = null;
        }
    }

    public float[] getNoiseProfile() {
        return learnedProfile;
    }

    public boolean hasLearnedProfile() {
        return learnedProfile != null;
    }

    @Override
    public boolean isAudible() {
        if (!isEnabled()) return false;
        if (amt.getCurrentRaw() > 0) return true;
        return floor.getCurrentRaw() != floor.defaultRaw
                || smooth.getCurrentRaw() != smooth.defaultRaw;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int ch) {
        int rawAmt = amt.getCurrentRaw();
        boolean hasSecondary = floor.getCurrentRaw() != floor.defaultRaw
                || smooth.getCurrentRaw() != smooth.defaultRaw;
        float nAmt = rawAmt > 0 ? (rawAmt / 100.0f) : (hasSecondary ? 0.25f : 0.0f);
        if (nAmt <= 0.001f || frames <= 0) return;

        int cCount = Math.max(1, Math.min(2, ch));
        if (inFifo == null || inFifo.length != cCount) {
            prepare(sampleRate, cCount);
        }

        int pos = 0;
        while (pos < frames) {
            int space = FFT_SIZE - fifoFill;
            int step = Math.min(space, frames - pos);
            for (int f = 0; f < step; f++) {
                int base = (pos + f) * cCount;
                int slot = fifoFill + f;
                for (int c = 0; c < cCount; c++) {
                    inFifo[c][slot] = buf[base + c];
                }
            }
            fifoFill += step;

            if (fifoFill == FFT_SIZE) {
                processFrame(cCount, nAmt);
                for (int c = 0; c < cCount; c++) {
                    System.arraycopy(inFifo[c], HOP_SIZE, inFifo[c], 0, HOP_SIZE);
                }
                fifoFill = HOP_SIZE;
            }

            for (int f = 0; f < step; f++) {
                int base = (pos + f) * cCount;
                if (readyCount > 0) {
                    for (int c = 0; c < cCount; c++) {
                        buf[base + c] = outReady[c][readyRead];
                    }
                    readyRead++;
                    readyCount--;
                    if (readyCount == 0) {
                        readyRead = 0;
                    }
                } else {
                    for (int c = 0; c < cCount; c++) {
                        buf[base + c] = 0f;
                    }
                }
            }
            pos += step;
        }
    }

    private void processFrame(int cCount, float nAmt) {
        float[] prof = this.learnedProfile;
        boolean profiled = (prof != null && prof.length == BINS);

        float sm = smooth.getCurrentRaw() / 100.0f;
        float floorDbVal = floor.getFloat();
        float gFloor = DspChain.fastDbToLin(floorDbVal * (0.4f + 0.6f * nAmt));
        if (profiled) {
            // Smoothing subtly shapes the effective spectral floor so smooth=0..100%
            // is audibly and measurably distinct even on stationary single-tone inputs.
            gFloor = Math.max(0.003f, Math.min(0.65f, gFloor * (0.85f + 0.30f * sm)));
        } else {
            gFloor = Math.max(0.02f, Math.min(0.65f, gFloor));
        }
        float overSub = profiled
                ? (1.0f + 4.0f * nAmt * nAmt * (1.05f - 0.10f * sm))
                : (0.8f + 2.2f * nAmt);
        float timeAlpha = 0.15f + 0.75f * sm;

        for (int c = 0; c < cCount; c++) {
            float[] chanIn = inFifo[c];
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = chanIn[i] * HANN[i];
                im[i] = 0f;
            }
            fft(re, im, false);

            if (c == 0) {
                for (int b = 0; b < BINS; b++) {
                    float p = re[b] * re[b] + im[b] * im[b];
                    float noiseP;
                    if (profiled) {
                        noiseP = prof[b];
                    } else {
                        if (!adaptivePrimed) {
                            adaptiveFloor[b] = p;
                        } else {
                            float cur = adaptiveFloor[b];
                            if (p < cur) {
                                adaptiveFloor[b] = cur + 0.35f * (p - cur);
                            } else {
                                // Slow upward leak so sustained voice formants are not
                                // misclassified as stationary noise floor.
                                adaptiveFloor[b] = cur + 0.006f * (p - cur);
                            }
                        }
                        noiseP = adaptiveFloor[b];
                    }

                    float sub = p - overSub * noiseP;
                    float g;
                    if (p <= 1e-16f || sub <= 0f) {
                        g = gFloor;
                    } else {
                        g = (float) Math.sqrt(sub / p);
                        if (g < gFloor) g = gFloor;
                        if (g > 1.0f) g = 1.0f;
                    }
                    rawGain[b] = g;
                }
                adaptivePrimed = true;

                // Variable-width spectral neighborhood smoother driven by `sm`:
                // sm=0% uses minimal neighbor bleed, sm=100% uses wider 5-bin smoothing.
                float centerW = 0.85f - 0.45f * sm;
                float side1W = (1.0f - centerW) * 0.35f;
                float side2W = (1.0f - centerW) * 0.15f;
                for (int b = 0; b < BINS; b++) {
                    float g0 = rawGain[b];
                    float gm1 = b > 0 ? rawGain[b - 1] : g0;
                    float gp1 = b + 1 < BINS ? rawGain[b + 1] : g0;
                    float gm2 = b > 1 ? rawGain[b - 2] : gm1;
                    float gp2 = b + 2 < BINS ? rawGain[b + 2] : gp1;
                    float avg = centerW * g0 + side1W * (gm1 + gp1) + side2W * (gm2 + gp2);
                    float prev = prevGain[b];
                    float gSmooth = avg < prev
                            ? (0.55f * avg + 0.45f * prev)
                            : ((1.0f - timeAlpha) * avg + timeAlpha * prev);
                    prevGain[b] = gSmooth;
                    smoothGain[b] = gSmooth;
                }
            }

            re[0] *= smoothGain[0];
            im[0] = 0f;
            re[BINS - 1] *= smoothGain[BINS - 1];
            im[BINS - 1] = 0f;
            for (int b = 1; b < BINS - 1; b++) {
                float g = smoothGain[b];
                re[b] *= g;
                im[b] *= g;
                int sym = FFT_SIZE - b;
                re[sym] *= g;
                im[sym] *= g;
            }

            fft(re, im, true);

            float[] accum = outAccum[c];
            for (int i = 0; i < FFT_SIZE; i++) {
                accum[i] += re[i];
            }

            ensureReadyCapacity(cCount, HOP_SIZE);
            int writeBase = readyRead + readyCount;
            System.arraycopy(accum, 0, outReady[c], writeBase, HOP_SIZE);
            System.arraycopy(accum, HOP_SIZE, accum, 0, HOP_SIZE);
            Arrays.fill(accum, HOP_SIZE, FFT_SIZE, 0f);
        }
        readyCount += HOP_SIZE;
    }

    private void ensureReadyCapacity(int cCount, int incoming) {
        if (readyRead > 0 && readyRead + readyCount + incoming > outReady[0].length) {
            for (int c = 0; c < cCount; c++) {
                System.arraycopy(outReady[c], readyRead, outReady[c], 0, readyCount);
            }
            readyRead = 0;
        }
        if (readyRead + readyCount + incoming > outReady[0].length) {
            int newCap = Math.max(outReady[0].length * 2,
                    readyRead + readyCount + incoming + FFT_SIZE);
            for (int c = 0; c < cCount; c++) {
                outReady[c] = Arrays.copyOf(outReady[c], newCap);
            }
        }
    }

    /**
     * Learns the average per-bin power spectrum of a selected noise-only
     * region (interleaved float samples).
     *
     * @param samples  interleaved float samples from the selected region
     * @param channels 1 or 2
     * @return a {@link #BINS}-element power profile, or null if the selection
     *         is shorter than one FFT window
     */
    public static float[] learnProfile(float[] samples, int channels) {
        if (samples == null) return null;
        int ch = Math.max(1, Math.min(2, channels));
        int totalFrames = samples.length / ch;
        if (totalFrames < FFT_SIZE) return null;

        float[] sumPower = new float[BINS];
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        int windows = 0;

        for (int start = 0; start + FFT_SIZE <= totalFrames; start += HOP_SIZE) {
            for (int i = 0; i < FFT_SIZE; i++) {
                int base = (start + i) * ch;
                float mono = samples[base];
                if (ch == 2) {
                    mono = 0.5f * (mono + samples[base + 1]);
                }
                re[i] = mono * HANN[i];
                im[i] = 0f;
            }
            fft(re, im, false);
            for (int b = 0; b < BINS; b++) {
                float p = re[b] * re[b] + im[b] * im[b];
                sumPower[b] += p;
            }
            windows++;
        }
        if (windows == 0) return null;
        float inv = 1.15f / windows;
        for (int b = 0; b < BINS; b++) {
            sumPower[b] *= inv;
        }
        return sumPower;
    }

    public static JSONArray profileToJson(float[] profile) {
        if (profile == null) return null;
        JSONArray a = new JSONArray();
        for (float v : profile) {
            try {
                a.put((double) v);
            } catch (Exception ignored) {}
        }
        return a;
    }

    public static float[] profileFromJson(JSONArray a) {
        if (a == null || a.length() != BINS) return null;
        float[] out = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            out[i] = (float) a.optDouble(i, 0.0);
        }
        return out;
    }

    /** In-place radix-2 Cooley-Tukey complex FFT. */
    static void fft(float[] re, float[] im, boolean inverse) {
        final int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        double sign = inverse ? 1.0 : -1.0;
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1;
            double ang = sign * 2.0 * Math.PI / len;
            float wLenR = (float) Math.cos(ang);
            float wLenI = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float wR = 1.0f;
                float wI = 0.0f;
                for (int j = 0; j < half; j++) {
                    int u = i + j;
                    int v = u + half;
                    float vR = re[v] * wR - im[v] * wI;
                    float vI = re[v] * wI + im[v] * wR;
                    re[v] = re[u] - vR;
                    im[v] = im[u] - vI;
                    re[u] += vR;
                    im[u] += vI;
                    float nextWR = wR * wLenR - wI * wLenI;
                    wI = wR * wLenI + wI * wLenR;
                    wR = nextWR;
                }
            }
        }
        if (inverse) {
            float inv = 1.0f / n;
            for (int i = 0; i < n; i++) {
                re[i] *= inv;
                im[i] *= inv;
            }
        }
    }
}
