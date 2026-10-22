package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renders the live FX rack over a take and reports the resulting peak
 * envelope, so the studio can draw the effect-applied waveform while the
 * user is still dialling the effects in.
 *
 * <p>The render is chunked and bounded so a long take does not block the
 * UI thread, and the result is bucketed with the same grid the waveform view
 * uses so the overlay lines up with the dry bars.
 */
final class FxPreviewPeaks {

    private FxPreviewPeaks() {}

    /**
     * Renders {@code chain} over {@code raw} and returns the peak envelope
     * in {@code buckets} buckets (the same count the waveform view holds).
     *
     * @return the peaks, or null when the render could not be completed
     */
    static float[] render(File raw, DspChain chain, int buckets,
                           int sampleRate) {
        if (raw == null || chain == null || buckets <= 0) return null;
        try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(raf);
            long onDisk = Math.max(0, raf.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            int ch = Math.max(1, h.channels);
            long totalFrames = dataBytes / 4 / ch;
            if (totalFrames <= 0) return null;

            int bCount = Math.min(buckets, (int) Math.max(1L,
                    Math.min(totalFrames, 8192L)));
            float[] peaks = new float[bCount];
            int framesPerRead = Math.max(1, 65536 / 4 / ch);
            byte[] in = new byte[framesPerRead * ch * 4];
            ByteBuffer fb = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            raf.seek(h.dataStart);
            chain.reset();
            long frame = 0;
            while (frame < totalFrames) {
                int want = (int) Math.min(framesPerRead, totalFrames - frame);
                raf.readFully(in, 0, want * ch * 4);
                fb.clear();
                fb.limit(want * ch);
                for (int f = 0; f < want; f++) {
                    float s = 0f;
                    for (int c = 0; c < ch; c++) {
                        s += fb.get(f * ch + c);
                    }
                    s /= ch;
                    int b = (int) ((frame + f) * bCount / totalFrames);
                    if (b >= bCount) b = bCount - 1;
                    if (b < 0) b = 0;
                    float a = Math.abs(s);
                    if (a > peaks[b]) peaks[b] = a;
                }
                float[] block = new float[want * ch];
                fb.clear();
                fb.limit(want * ch);
                for (int i = 0; i < want * ch; i++) {
                    block[i] = fb.get();
                }
                chain.processInPlace(block, want);
                for (int f = 0; f < want; f++) {
                    float peak = 0f;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(block[f * ch + c]);
                        if (a > peak) peak = a;
                    }
                    int b = (int) ((frame + f) * bCount / totalFrames);
                    if (b >= bCount) b = bCount - 1;
                    if (b < 0) b = 0;
                    if (peak > peaks[b]) peaks[b] = peak;
                }
                frame += want;
            }
            return peaks;
        } catch (Exception e) {
            return null;
        }
    }
}
