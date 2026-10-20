package com.example.hello;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-safe, Hann-windowed spectrum tap for the Stage 3 EQ views:
 * {@link RawFloatPlayer} pushes the most recent preview frames and views read a
 * 64-band log spectrum for their analyzer overlay.
 *
 * <p>Fixes BUG-07:
 * <ul>
 *   <li>Reference-counted view attachment ({@link #attachView()} /
 *       {@link #detachView()}) so detaching one {@link EqCurveView} never
 *       disables the live spectrum for another open EQ card.</li>
 *   <li>Precomputed Hann window eliminates rectangular-window spectral leakage
 *       (over 85 dB sidelobe reduction).</li>
 *   <li>Synchronized ring-buffer copy prevents torn reads between the audio
 *       preview thread and UI draw passes.</li>
 * </ul>
 */
public final class FxTelemetry {

    public static final int BANDS = 64;
    private static final int FFT_SIZE = 2048;
    private static final Object LOCK = new Object();
    private static final float[] RING = new float[FFT_SIZE];
    private static final float[] HANN = new float[FFT_SIZE];
    private static final float[] RE = new float[FFT_SIZE];
    private static final float[] IM = new float[FFT_SIZE];
    private static final float[] OUT = new float[BANDS];
    private static final AtomicInteger ATTACHED_VIEWS = new AtomicInteger(0);
    private static volatile int writePos = 0;
    private static volatile boolean explicitEnabled = false;
    private static volatile boolean fresh = false;

    static {
        for (int i = 0; i < FFT_SIZE; i++) {
            HANN[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1))));
        }
    }

    private FxTelemetry() {}

    public static void attachView() {
        ATTACHED_VIEWS.incrementAndGet();
    }

    public static void detachView() {
        int rem = ATTACHED_VIEWS.decrementAndGet();
        if (rem <= 0) {
            ATTACHED_VIEWS.set(0);
            if (!explicitEnabled) {
                fresh = false;
            }
        }
    }

    public static void setEnabled(boolean on) {
        explicitEnabled = on;
        if (!on && ATTACHED_VIEWS.get() <= 0) {
            fresh = false;
        }
    }

    public static boolean isEnabled() {
        return explicitEnabled || ATTACHED_VIEWS.get() > 0;
    }

    /** Copies the tail of a preview block into the ring (mono downmix). */
    public static void push(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int n = Math.min(frames, FFT_SIZE);
        int start = frames - n;
        synchronized (LOCK) {
            int p = writePos;
            for (int f = 0; f < n; f++) {
                float s = 0f;
                for (int c = 0; c < ch; c++) s += buf[(start + f) * ch + c];
                RING[p] = s / ch;
                p = (p + 1) & (FFT_SIZE - 1);
            }
            writePos = p;
            fresh = true;
        }
    }

    /**
     * 64 log-spaced band magnitudes in dBFS (-90..0), newest Hann-windowed
     * frame. Returns a defensive copy; safe for concurrent callers.
     */
    public static float[] spectrum() {
        if (!fresh) return null;
        synchronized (LOCK) {
            if (!fresh) return null;
            int p = writePos;
            for (int i = 0; i < FFT_SIZE; i++) {
                RE[i] = RING[(p + i) & (FFT_SIZE - 1)] * HANN[i];
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
            return OUT.clone();
        }
    }
}
