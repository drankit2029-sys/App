package com.example.hello;

/**
 * Tiny lock-free spectrum tap for the Stage 3 EQ views: {@link RawFloatPlayer}
 * pushes the most recent preview frames and views read a 64-band log spectrum
 * for their analyzer overlay. Disabled (zero cost beyond one volatile read)
 * unless a curve view is on screen.
 */
public final class FxTelemetry {

    public static final int BANDS = 64;
    private static final int FFT_SIZE = 2048;
    private static final float[] RING = new float[FFT_SIZE];
    private static final float[] RE = new float[FFT_SIZE];
    private static final float[] IM = new float[FFT_SIZE];
    private static final float[] OUT = new float[BANDS];
    private static volatile int writePos = 0;
    private static volatile boolean enabled = false;
    private static volatile boolean fresh = false;

    private FxTelemetry() {}

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) fresh = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Copies the tail of a preview block into the ring (mono downmix). */
    public static void push(float[] buf, int frames, int channels) {
        if (!enabled || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int p = writePos;
        int n = Math.min(frames, FFT_SIZE);
        int start = frames - n;
        for (int f = 0; f < n; f++) {
            float s = 0f;
            for (int c = 0; c < ch; c++) s += buf[(start + f) * ch + c];
            RING[p] = s / ch;
            p = (p + 1) & (FFT_SIZE - 1);
        }
        writePos = p;
        fresh = true;
    }

    /**
     * 64 log-spaced band magnitudes in dBFS (-90..0), newest window. Returns
     * the shared array; callers must not retain it across frames.
     */
    public static float[] spectrum() {
        if (!fresh) return null;
        int p = writePos;
        for (int i = 0; i < FFT_SIZE; i++) {
            RE[i] = RING[(p + i) & (FFT_SIZE - 1)];
            IM[i] = 0f;
        }
        Dsp.fft(RE, IM, false);
        int half = FFT_SIZE / 2;
        for (int b = 0; b < BANDS; b++) {
            double f0 = 30.0 * Math.pow(16000.0 / 30.0, b / (double) BANDS);
            double f1 = 30.0 * Math.pow(16000.0 / 30.0, (b + 1) / (double) BANDS);
            int k0 = (int) Math.max(1, f0 * FFT_SIZE / DspChain.SAMPLE_RATE);
            int k1 = (int) Math.min(half - 1, f1 * FFT_SIZE / DspChain.SAMPLE_RATE);
            float mag = 0f;
            for (int k = k0; k <= Math.max(k0, k1); k++) {
                float m = (float) Math.sqrt(RE[k] * RE[k] + IM[k] * IM[k]);
                if (m > mag) mag = m;
            }
            OUT[b] = Dsp.linToDb(mag / (FFT_SIZE / 4.0));
        }
        return OUT;
    }
}
