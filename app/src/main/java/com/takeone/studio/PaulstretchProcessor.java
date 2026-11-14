package com.takeone.studio;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 Paulstretch: extreme ambient time stretching. A large-window STFT
 * preserves the bin amplitudes but replaces the phases with random angles
 * at every synthesis hop, then blends the frames with a
 * {@code (1 - x^2)^1.25} raised-cosine overlap.
 *
 * <p>Supports bounded-chunk file-to-file streaming via
 * {@link #processOfflineRangeToFile} so extreme stretches on long takes never
 * allocate full-region buffers in RAM.
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

    @Override
    public long processOfflineRangeToFile(File srcWav, long startFrame,
                                          long endFrame, File dstWav,
                                          int sampleRate, int channels)
            throws Exception {
        if (sampleRate > 0) this.sampleRate = sampleRate;

        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = info.sampleRate > 0 ? info.sampleRate : this.sampleRate;
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long frames = endFrame - startFrame;
        if (frames <= 0) return -1L;

        reset();
        double stretch = factor.getFloat();
        int targetWin = Math.max(512, (int) Math.round(resolution.getFloat() * sr));
        int fftSize = 512;
        while (fftSize < targetWin && fftSize < 16384) fftSize <<= 1;
        int activeWin = Math.max(256, Math.min(fftSize, targetWin));
        int outHop = Math.max(128, activeWin / 2);
        double inStep = outHop / Math.max(0.1, stretch);
        long outFrames = Math.max(1L, Math.round(frames * stretch));
        long maxOutFrames = (sr > 0 ? sr : 48000L) * 3600L * 4L;
        if (outFrames > maxOutFrames) {
            return -1L;
        }
        long frameBytes = ch * 4L;
        int winPad = (fftSize - activeWin) / 2;

        float[] win = new float[fftSize];
        for (int i = 0; i < activeWin; i++) {
            float x = (2.0f * i / Math.max(1, activeWin - 1)) - 1.0f;
            win[winPad + i] = (float) Math.pow(Math.max(0.0, 1.0 - x * x), 1.25);
        }

        int bins = fftSize / 2 + 1;
        float[] re = new float[fftSize];
        float[] im = new float[fftSize];
        float[] mag = new float[bins];
        float[][] ola = new float[ch][activeWin];
        float[] hopOut = new float[outHop * ch];
        byte[] rawWinBytes = new byte[fftSize * ch * 4];
        float[] inWin = new float[fftSize * ch];
        float mix = getWetDryMix();

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, outFrames * frameBytes));

            double inPos = 0.0;
            long outPos = 0L;
            while (outPos < outFrames) {
                long baseIn = Math.round(inPos) - winPad;
                long readStart = Math.max(0L, Math.min(frames, baseIn));
                long readEnd = Math.max(readStart, Math.min(frames, baseIn + fftSize));
                int avail = (int) (readEnd - readStart);
                java.util.Arrays.fill(inWin, 0f);
                if (avail > 0) {
                    inRaf.seek(info.dataStart + (startFrame + readStart) * frameBytes);
                    inRaf.readFully(rawWinBytes, 0, avail * ch * 4);
                    int dstOff = (int) (readStart - baseIn) * ch;
                    ByteBuffer.wrap(rawWinBytes, 0, avail * ch * 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer()
                            .get(inWin, dstOff, avail * ch);
                }

                for (int c = 0; c < ch; c++) {
                    for (int i = 0; i < fftSize; i++) {
                        re[i] = inWin[i * ch + c] * win[i];
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
                    for (int i = 0; i < activeWin; i++) {
                        ola[c][i] += re[winPad + i] * win[winPad + i] * 0.85f;
                    }
                }

                int emit = (int) Math.min((long) outHop, outFrames - outPos);
                float[] dryWin = mix < 1f ? new float[(emit + 2) * ch] : null;
                if (dryWin != null) {
                    long d0 = startFrame + (long) (outPos / Math.max(0.1, stretch));
                    long d1 = startFrame
                            + (long) ((outPos + emit - 1) / Math.max(0.1, stretch)) + 1;
                    int dLen = (int) Math.min(frames, Math.max(0L, d1 - d0 + 1));
                    if (dLen > 0) {
                        byte[] db = new byte[dLen * ch * 4];
                        inRaf.seek(info.dataStart + d0 * frameBytes);
                        inRaf.readFully(db, 0, dLen * ch * 4);
                        ByteBuffer.wrap(db, 0, dLen * ch * 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer()
                                .get(dryWin, 0, Math.min(dLen * ch, dryWin.length));
                    }
                }
                for (int f = 0; f < emit; f++) {
                    for (int c = 0; c < ch; c++) {
                        float drySample = 0f;
                        if (dryWin != null) {
                            double p = (outPos + f) / Math.max(0.1, stretch)
                                    - (outPos / Math.max(0.1, stretch));
                            int i0 = (int) p;
                            float frac = (float) (p - i0);
                            int a = Math.max(0, Math.min((emit + 1), i0)) * ch + c;
                            int b = Math.max(0, Math.min((emit + 1), i0 + 1)) * ch + c;
                            drySample = dryWin[a] * (1f - frac) + dryWin[b] * frac;
                        }
                        hopOut[f * ch + c] = ola[c][f] * mix + drySample * (1f - mix);
                    }
                }
                Dsp.writeFloatsLE(outRaf, hopOut, 0, emit * ch);

                int keep = activeWin - outHop;
                for (int c = 0; c < ch; c++) {
                    if (keep > 0) {
                        System.arraycopy(ola[c], outHop, ola[c], 0, keep);
                    }
                    java.util.Arrays.fill(ola[c], Math.max(0, keep), activeWin, 0f);
                }
                inPos += inStep;
                outPos += emit;
            }
        }
        return outFrames;
    }

    private double nextRandom() {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        int bits = (int) ((rng >>> 33) & 0x7FFFFFFF);
        return bits / (double) 0x7FFFFFFF;
    }
}
