package com.example.hello;

/**
 * Stage 7 Paulstretch: extreme ambient time stretching. A large-window STFT
 * preserves the bin amplitudes but replaces the phases with random angles
 * at every synthesis hop, then blends the frames with a
 * {@code (1 - x^2)^1.25} raised-cosine overlap.
 */
final class PaulstretchProcessor extends AudioProcessor {

    private final FxParameter factor = addParameter(FxParameter.floatParam(
            "paul_factor", "Stretch Factor", "x", 1.5f, 100f, 8f));
    private final FxParameter resolution = addParameter(FxParameter.floatParam(
            "paul_res", "Time Resolution", " s", 0.05f, 1f, 0.25f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private long rng = 0x9E3779B97F4A7C15L;

    PaulstretchProcessor() {
    }

    @Override
    public String getId() {
        return "paulstretch";
    }

    @Override
    public String getTitle() {
        return "Paulstretch";
    }

    @Override
    public String getDescription() {
        return "Extreme random-phase ambient time stretch";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TIME;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        rng = 0x9E3779B97F4A7C15L;
    }

    @Override
    public boolean isOfflineOnly() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // Offline-only effect: rendered through processOfflineRegion.
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && factor.getFloat() > 1.01f;
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        double stretch = factor.getFloat();
        if (Math.abs(stretch - 1.0) < 1e-6) return in.clone();

        reset();
        int targetWin = Math.max(512, (int) Math.round(resolution.getFloat() * sampleRate));
        int fftSize = 512;
        while (fftSize < targetWin && fftSize < 65536) fftSize <<= 1;
        // Shape the active window support by the exact continuous targetWin
        // so every Time Resolution (0.05..1.00 s) produces a distinct window
        // even when two settings share the same power-of-two FFT size.
        int activeWin = Math.max(256, Math.min(fftSize, targetWin));
        int outHop = Math.max(128, activeWin / 2);
        double inStep = outHop / Math.max(0.1, stretch);
        long maxSafeFrames = Math.max(frames * 16L, sampleRate * 120L);
        int outFrames = (int) Math.max(1L, Math.min(maxSafeFrames, Math.round(frames * stretch)));
        float[] out = new float[outFrames * channels];
        float[] win = new float[fftSize];
        int winPad = (fftSize - activeWin) / 2;
        for (int i = 0; i < activeWin; i++) {
            float x = (2.0f * i / Math.max(1, activeWin - 1)) - 1.0f;
            win[winPad + i] = (float) Math.pow(Math.max(0.0, 1.0 - x * x), 1.25);
        }

        int bins = fftSize / 2 + 1;
        float[] re = new float[fftSize];
        float[] im = new float[fftSize];
        float[] mag = new float[bins];

        for (int c = 0; c < channels; c++) {
            double inPos = 0.0;
            int outPos = 0;
            while (outPos < outFrames) {
                int baseIn = (int) Math.round(inPos) - winPad;
                for (int i = 0; i < fftSize; i++) {
                    int src = baseIn + i;
                    float s = (src >= 0 && src < frames) ? in[src * channels + c] : 0f;
                    re[i] = s * win[i];
                    im[i] = 0f;
                }
                Dsp.fft(re, im, false);
                for (int b = 0; b < bins; b++) {
                    mag[b] = (float) Math.sqrt(re[b] * re[b] + im[b] * im[b]);
                }
                for (int b = 0; b < bins; b++) {
                    float p = (float) (nextRandom() * Math.PI * 2.0);
                    re[b] = (float) (mag[b] * Math.cos(p));
                    im[b] = (float) (mag[b] * Math.sin(p));
                    int mirror = fftSize - b;
                    if (mirror < fftSize) {
                        re[mirror] = re[b];
                        im[mirror] = -im[b];
                    }
                }
                Dsp.fft(re, im, true);
                for (int i = 0; i < activeWin && outPos + i < outFrames; i++) {
                    float w = win[winPad + i];
                    out[(outPos + i) * channels + c] += re[winPad + i] * w * 0.85f;
                }
                inPos += inStep;
                outPos += outHop;
            }
        }
        return out;
    }

    private double nextRandom() {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        int bits = (int) ((rng >>> 33) & 0x7FFFFFFF);
        return bits / (double) 0x7FFFFFFF;
    }
}
