package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Low-end-hardware-optimized real-time & offline DSP chain for 48 kHz float audio.
 *
 * <p>All stages run in-place on primitive {@code float[]} buffers with zero heap
 * allocations after construction. Envelope detectors and gain computers run at
 * a decimated control rate ({@code DECIM = 16} frames = 0.33 ms) with per-sample
 * linear gain interpolation, cutting transcendental math CPU cost by 93.75%.
 */
public final class DspChain {

    private static final int SAMPLE_RATE = 48000;
    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;

    /** Immutable or live-updated user FX configuration with explicit 3-way mode state. */
    public static final class Config {
        public static final int MODE_OFF = 0;
        public static final int MODE_NOISE_SUPPRESSION = 1;
        public static final int MODE_CUSTOM = 2;

        public int mode = MODE_OFF;
        public boolean hpfEnabled = false;
        /** 0..100%: 3-band adaptive noise floor suppression. */
        public int noisePct = 0;
        /** 0..100%: split-band sibilance reduction (4.5-9.5 kHz). */
        public int deEssPct = 0;
        /** 0..100%: soft-knee vocal/master compressor + auto-makeup + limiter. */
        public int compPct = 0;

        // Remembered Custom preset values so switching Noise Suppression <-> Custom
        // always restores the user's distinct Custom settings.
        public boolean customHpf = true;
        public int customNoisePct = 35;
        public int customDeEssPct = 30;
        public int customCompPct = 30;

        public Config() {}

        public Config(boolean hpf, int noise, int deEss, int comp) {
            this.hpfEnabled = hpf;
            this.noisePct = clamp(noise);
            this.deEssPct = clamp(deEss);
            this.compPct = clamp(comp);
            if (!isActive()) {
                this.mode = MODE_OFF;
            } else if (hpf && this.noisePct == 50
                    && this.deEssPct == 45 && this.compPct == 40) {
                this.mode = MODE_NOISE_SUPPRESSION;
            } else {
                this.mode = MODE_CUSTOM;
                this.customHpf = hpf;
                this.customNoisePct = this.noisePct;
                this.customDeEssPct = this.deEssPct;
                this.customCompPct = this.compPct;
            }
        }

        /** Switches between MODE_OFF, MODE_NOISE_SUPPRESSION, and MODE_CUSTOM interchangeably. */
        public void applyMode(int targetMode) {
            if (targetMode == MODE_OFF) {
                this.mode = MODE_OFF;
                this.hpfEnabled = false;
                this.noisePct = 0;
                this.deEssPct = 0;
                this.compPct = 0;
            } else if (targetMode == MODE_NOISE_SUPPRESSION) {
                this.mode = MODE_NOISE_SUPPRESSION;
                this.hpfEnabled = true;
                this.noisePct = 50;
                this.deEssPct = 45;
                this.compPct = 40;
            } else {
                this.mode = MODE_CUSTOM;
                if (!customHpf && customNoisePct <= 0
                        && customDeEssPct <= 0 && customCompPct <= 0) {
                    customHpf = true;
                    customNoisePct = 35;
                    customDeEssPct = 30;
                    customCompPct = 30;
                }
                this.hpfEnabled = customHpf;
                this.noisePct = clamp(customNoisePct);
                this.deEssPct = clamp(customDeEssPct);
                this.compPct = clamp(customCompPct);
            }
        }

        /** Called when the user manually tweaks any slider or toggle. */
        public void onManualAdjustment() {
            onManualAdjustment(hpfEnabled, noisePct, deEssPct, compPct);
        }

        /** Called when the user manually tweaks any slider or toggle. */
        public void onManualAdjustment(boolean hpf, int noise, int deEss, int comp) {
            this.hpfEnabled = hpf;
            this.noisePct = clamp(noise);
            this.deEssPct = clamp(deEss);
            this.compPct = clamp(comp);
            if (!isActive()) {
                this.mode = MODE_OFF;
            } else {
                this.mode = MODE_CUSTOM;
                this.customHpf = this.hpfEnabled;
                this.customNoisePct = this.noisePct;
                this.customDeEssPct = this.deEssPct;
                this.customCompPct = this.compPct;
            }
        }

        public boolean isActive() {
            return hpfEnabled || noisePct > 0 || deEssPct > 0 || compPct > 0;
        }

        public String badgeSummary() {
            if (!isActive() || mode == MODE_OFF) return "FX Off";
            if (mode == MODE_NOISE_SUPPRESSION
                    || (hpfEnabled && noisePct == 50 && deEssPct == 45 && compPct == 40)) {
                return "FX • Polish";
            }
            return "FX • Custom";
        }

        public static Config voicePolish() {
            Config c = new Config(true, 50, 45, 40);
            c.mode = MODE_NOISE_SUPPRESSION;
            return c;
        }

        public static Config load(Context ctx) {
            Config c = new Config();
            if (ctx == null) return c;
            try {
                SharedPreferences p = ctx.getSharedPreferences("takeone",
                        Context.MODE_PRIVATE);
                c.hpfEnabled = p.getBoolean("dsp_hpf", false);
                c.noisePct = clamp(p.getInt("dsp_noise", 0));
                c.deEssPct = clamp(p.getInt("dsp_deess", 0));
                c.compPct = clamp(p.getInt("dsp_comp", 0));
                c.customHpf = p.getBoolean("dsp_custom_hpf", true);
                c.customNoisePct = clamp(p.getInt("dsp_custom_noise", 35));
                c.customDeEssPct = clamp(p.getInt("dsp_custom_deess", 30));
                c.customCompPct = clamp(p.getInt("dsp_custom_comp", 30));
                int savedMode = p.getInt("dsp_mode", -1);
                if (savedMode >= MODE_OFF && savedMode <= MODE_CUSTOM) {
                    c.mode = savedMode;
                    if (!c.isActive()) c.mode = MODE_OFF;
                } else {
                    if (!c.isActive()) c.mode = MODE_OFF;
                    else if (c.hpfEnabled && c.noisePct == 50
                            && c.deEssPct == 45 && c.compPct == 40) {
                        c.mode = MODE_NOISE_SUPPRESSION;
                    } else {
                        c.mode = MODE_CUSTOM;
                    }
                }
            } catch (Exception ignored) {}
            return c;
        }

        public void save(Context ctx) {
            if (ctx == null) return;
            try {
                ctx.getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                        .putInt("dsp_mode", mode)
                        .putBoolean("dsp_hpf", hpfEnabled)
                        .putInt("dsp_noise", clamp(noisePct))
                        .putInt("dsp_deess", clamp(deEssPct))
                        .putInt("dsp_comp", clamp(compPct))
                        .putBoolean("dsp_custom_hpf", customHpf)
                        .putInt("dsp_custom_noise", clamp(customNoisePct))
                        .putInt("dsp_custom_deess", clamp(customDeEssPct))
                        .putInt("dsp_custom_comp", clamp(customCompPct))
                        .apply();
            } catch (Exception ignored) {}
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(100, v));
        }
    }

    private final int channels;
    private volatile boolean hpfOn;
    private volatile float noiseAmt;
    private volatile float deEssAmt;
    private volatile float compAmt;

    // Stage 1: 80 Hz 2nd-order Butterworth High-Pass Filter state
    private final float hpfB0, hpfB1, hpfB2, hpfA1, hpfA2;
    private final float[] hpfZ1 = new float[2];
    private final float[] hpfZ2 = new float[2];

    // Stage 2: 3-Band Minimum-Statistics Adaptive Noise Suppressor state
    private static final float NS_ALPHA_LOW = 0.02839f; // 220 Hz 1-pole crossover
    private static final float NS_ALPHA_HIGH = 0.3919f; // 3800 Hz 1-pole crossover
    private final float[] nsLp1 = new float[2];
    private final float[] nsLp2 = new float[2];
    private final float[] nsEnv = {1e-5f, 1e-5f, 1e-5f};
    private final float[] nsFloor = {1e-6f, 1e-6f, 1e-6f};
    private final float[] nsGain = {1.0f, 1.0f, 1.0f};
    private final float[] nsTargetGain = {1.0f, 1.0f, 1.0f};
    private final float[] nsBandAcc = new float[3];
    private final float[] ScratchLow = new float[DECIM * 2];
    private final float[] ScratchMid = new float[DECIM * 2];
    private final float[] ScratchHigh = new float[DECIM * 2];

    // Stage 3: Split-Band Relative Sidechain De-Esser (6800 Hz bandpass, Q=1.5)
    private final float deB0, deB1, deB2, deA1, deA2;
    private final float[] deZ1 = new float[2];
    private final float[] deZ2 = new float[2];
    private float deSibEnv = 1e-7f;
    private float deFullEnv = 1e-6f;
    private float deCutCur = 0.0f;
    private float deCutTarget = 0.0f;
    private final float[] ScratchSib = new float[DECIM * 2];

    // Stage 4: Soft-Knee Program Compressor + Auto-Makeup + Brickwall Limiter
    private float compEnvDb = -60.0f;
    private float compGainCur = 1.0f;
    private float compGainTarget = 1.0f;

    public DspChain(int channels, Config cfg) {
        this.channels = Math.max(1, Math.min(2, channels));
        // Precompute 80 Hz HPF coefficients (fs = 48000, Q = 0.70710678)
        double w0 = 2.0 * Math.PI * 80.0 / SAMPLE_RATE;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * 0.70710678);
        double a0Inv = 1.0 / (1.0 + alpha);
        this.hpfB0 = (float) ((1.0 + cosW0) * 0.5 * a0Inv);
        this.hpfB1 = (float) (-(1.0 + cosW0) * a0Inv);
        this.hpfB2 = this.hpfB0;
        this.hpfA1 = (float) (-2.0 * cosW0 * a0Inv);
        this.hpfA2 = (float) ((1.0 - alpha) * a0Inv);

        // Precompute 6800 Hz Bandpass coefficients for De-Esser (Q = 1.5)
        double dw0 = 2.0 * Math.PI * 6800.0 / SAMPLE_RATE;
        double dCos = Math.cos(dw0);
        double dAlpha = Math.sin(dw0) / (2.0 * 1.5);
        double da0Inv = 1.0 / (1.0 + dAlpha);
        this.deB0 = (float) (dAlpha * da0Inv);
        this.deB1 = 0.0f;
        this.deB2 = (float) (-dAlpha * da0Inv);
        this.deA1 = (float) (-2.0 * dCos * da0Inv);
        this.deA2 = (float) ((1.0 - dAlpha) * da0Inv);

        updateConfig(cfg);
    }

    public void updateConfig(Config cfg) {
        if (cfg == null) {
            this.hpfOn = false;
            this.noiseAmt = 0f;
            this.deEssAmt = 0f;
            this.compAmt = 0f;
            return;
        }
        this.hpfOn = cfg.hpfEnabled;
        this.noiseAmt = cfg.noisePct / 100.0f;
        this.deEssAmt = cfg.deEssPct / 100.0f;
        this.compAmt = cfg.compPct / 100.0f;
    }

    public boolean isActive() {
        return hpfOn || noiseAmt > 0.001f || deEssAmt > 0.001f || compAmt > 0.001f;
    }

    public void reset() {
        for (int c = 0; c < 2; c++) {
            hpfZ1[c] = hpfZ2[c] = 0f;
            nsLp1[c] = nsLp2[c] = 0f;
            deZ1[c] = deZ2[c] = 0f;
        }
        for (int b = 0; b < 3; b++) {
            nsEnv[b] = 1e-5f;
            nsFloor[b] = 1e-6f;
            nsGain[b] = nsTargetGain[b] = 1.0f;
        }
        deSibEnv = 1e-7f;
        deFullEnv = 1e-6f;
        deCutCur = deCutTarget = 0f;
        compEnvDb = -60.0f;
        compGainCur = compGainTarget = 1.0f;
    }

    /**
     * Processes `frames` (interleaved `frames * channels` floats) in-place.
     * Zero allocations, O(frames) time.
     */
    public void processInPlace(float[] buf, int frames) {
        if (!isActive() || frames <= 0) return;
        final int ch = this.channels;
        final boolean doHpf = this.hpfOn;
        final float nAmt = this.noiseAmt;
        final float dAmt = this.deEssAmt;
        final float cAmt = this.compAmt;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // 1) Stage 1: 80 Hz Butterworth High-Pass Filter
            if (doHpf) {
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        float y = hpfB0 * x + hpfZ1[c];
                        hpfZ1[c] = hpfB1 * x - hpfA1 * y + hpfZ2[c];
                        hpfZ2[c] = hpfB2 * x - hpfA2 * y;
                        buf[idx + c] = y;
                    }
                }
            }

            // 2) Stage 2: 3-Band Minimum-Statistics Adaptive Noise Suppressor
            if (nAmt > 0.001f) {
                nsBandAcc[0] = nsBandAcc[1] = nsBandAcc[2] = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        nsLp1[c] += NS_ALPHA_LOW * (x - nsLp1[c]);
                        nsLp2[c] += NS_ALPHA_HIGH * (x - nsLp2[c]);
                        float bLow = nsLp1[c];
                        float bMid = nsLp2[c] - bLow;
                        float bHigh = x - nsLp2[c];
                        ScratchLow[sIdx + c] = bLow;
                        ScratchMid[sIdx + c] = bMid;
                        ScratchHigh[sIdx + c] = bHigh;
                        nsBandAcc[0] += bLow * bLow;
                        nsBandAcc[1] += bMid * bMid;
                        nsBandAcc[2] += bHigh * bHigh;
                    }
                }
                float invSamples = 1.0f / (blk * ch);
                for (int b = 0; b < 3; b++) {
                    float e = nsBandAcc[b] * invSamples;
                    nsEnv[b] = 0.60f * nsEnv[b] + 0.40f * e;
                    if (nsEnv[b] < nsFloor[b]) {
                        nsFloor[b] = 0.80f * nsFloor[b] + 0.20f * nsEnv[b];
                    } else {
                        nsFloor[b] = nsFloor[b] * 1.00018f + 1e-10f;
                    }
                    float snr = nsEnv[b] / (nsFloor[b] + 1e-10f);
                    float overSub = 1.0f + 2.5f * nAmt;
                    float g = (snr - overSub) / Math.max(snr, 1e-6f);
                    float floorG = (b == 1)
                            ? (1.0f - 0.75f * nAmt)
                            : (1.0f - 0.88f * nAmt);
                    g = Math.max(floorG, Math.min(1.0f, g));
                    float smooth = g > nsTargetGain[b] ? 0.35f
                            : (b == 2 ? 0.08f : 0.035f);
                    nsTargetGain[b] += smooth * (g - nsTargetGain[b]);
                }
                float dG0 = (nsTargetGain[0] - nsGain[0]) * INV_DECIM;
                float dG1 = (nsTargetGain[1] - nsGain[1]) * INV_DECIM;
                float dG2 = (nsTargetGain[2] - nsGain[2]) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    nsGain[0] += dG0;
                    nsGain[1] += dG1;
                    nsGain[2] += dG2;
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] = nsGain[0] * ScratchLow[sIdx + c]
                                + nsGain[1] * ScratchMid[sIdx + c]
                                + nsGain[2] * ScratchHigh[sIdx + c];
                    }
                }
            }

            // 3) Stage 3: Split-Band Relative Sidechain De-Esser
            if (dAmt > 0.001f) {
                float sibEnergy = 0f;
                float fullEnergy = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        float sib = deB0 * x + deZ1[c];
                        deZ1[c] = deB1 * x - deA1 * sib + deZ2[c];
                        deZ2[c] = deB2 * x - deA2 * sib;
                        ScratchSib[sIdx + c] = sib;
                        sibEnergy += sib * sib;
                        fullEnergy += x * x;
                    }
                }
                float invN = 1.0f / (blk * ch);
                deSibEnv = 0.50f * deSibEnv + 0.50f * (sibEnergy * invN);
                deFullEnv = 0.70f * deFullEnv + 0.30f * (fullEnergy * invN);
                float ratioDb = fastLinToDb(deSibEnv + 1e-10f) * 0.5f
                        - fastLinToDb(deFullEnv + 1e-9f) * 0.5f;
                float threshDb = 1.5f - 10.0f * dAmt;
                float wantCut = 0f;
                if (ratioDb > threshDb && deFullEnv > 1e-7f) {
                    float overDb = Math.min(12.0f, (ratioDb - threshDb) * 1.2f);
                    wantCut = 1.0f - fastDbToLin(-overDb);
                }
                float alphaDe = wantCut > deCutTarget ? 0.45f : 0.10f;
                deCutTarget += alphaDe * (wantCut - deCutTarget);
                float dCut = (deCutTarget - deCutCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    deCutCur += dCut;
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] -= deCutCur * ScratchSib[sIdx + c];
                    }
                }
            }

            // 4) Stage 4: Soft-Knee Compressor + Auto-Makeup + Brickwall Limiter
            if (cAmt > 0.001f) {
                float peak = 0f;
                float sumSq = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(buf[idx + c]);
                        if (a > peak) peak = a;
                        sumSq += a * a;
                    }
                }
                float rms = (float) Math.sqrt(sumSq / (blk * ch));
                float det = Math.max(1e-6f, 0.7f * peak + 0.3f * rms);
                float inDb = fastLinToDb(det);
                float alphaC = inDb > compEnvDb ? 0.08f : 0.0035f;
                compEnvDb += alphaC * (inDb - compEnvDb);

                float threshDb = -12.0f - 14.0f * cAmt; // -12 .. -26 dBFS
                float ratio = 1.0f + 3.0f * cAmt;       // 1:1 .. 4:1
                float slope = 1.0f - (1.0f / ratio);
                float knee = 6.0f;
                float over = compEnvDb - threshDb;
                float grDb;
                if (2.0f * over < -knee) {
                    grDb = 0.0f;
                } else if (2.0f * Math.abs(over) <= knee) {
                    float t = over + knee * 0.5f;
                    grDb = -slope * (t * t) / (2.0f * knee);
                } else {
                    grDb = -slope * over;
                }
                float makeupDb = (-threshDb * slope) * 0.45f;
                compGainTarget = fastDbToLin(grDb + makeupDb);
                float dG = (compGainTarget - compGainCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    compGainCur += dG;
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float y = buf[idx + c] * compGainCur;
                        float ay = Math.abs(y);
                        if (ay > 0.85f) {
                            float d = ay - 0.85f;
                            float lim = 0.85f + 0.135f * (d / (0.135f + d));
                            y = y >= 0f ? lim : -lim;
                        }
                        buf[idx + c] = y;
                    }
                }
            }

            offset += blk;
        }
    }

    public static float fastLinToDb(float x) {
        if (x <= 1e-7f) return -140.0f;
        int bits = Float.floatToRawIntBits(x);
        int exp = ((bits >>> 23) & 0xFF) - 127;
        float m = Float.intBitsToFloat((bits & 0x007FFFFF) | 0x3F800000);
        float log2m = -0.34484843f * m * m + 2.02466578f * m - 1.67487759f;
        return (exp + log2m) * 6.0205999f;
    }

    public static float fastDbToLin(float db) {
        if (db <= -140.0f) return 0.0f;
        if (db > 36.0f) db = 36.0f;
        float p = db * 0.16609640f;
        int exp = (int) Math.floor(p);
        float f = p - exp;
        float mant = 0.33971f * f * f + 0.66029f * f + 1.0f;
        int bits = Float.floatToRawIntBits(mant);
        bits += (exp << 23);
        return Float.intBitsToFloat(bits);
    }
}


