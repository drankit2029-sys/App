package com.takeone.studio;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 WSOLA (Waveform Similarity Overlap-Add) engine.
 *
 * <p>WSOLA time-stretches a signal without changing its pitch: the engine
 * slides a 20 ms analysis window over the input, looks for the position
 * within a +/- 15 ms search window whose waveform best matches the previous
 * synthesis frame (normalised cross-correlation across all channels), and
 * overlap-adds the result with a 10 ms hop. Pitch shifting combines the
 * stretch with an anti-aliased resampler.
 *
 * <p>Supports both in-memory buffers and bounded-chunk file-to-file streaming
 * ({@link #timeStretchToFile}, {@link #varispeedToFile}, {@link #pitchShiftToFile})
 * with GPU/SIMD correlation acceleration via {@link HardwareApiManager}.
 */
final class WsolaTimePitchProcessor {

    /** Analysis frame length (20 ms at 48 kHz). */
    private final int frameSize;
    /** Synthesis hop (10 ms at 48 kHz). */
    private final int synthesisHop;
    /** Correlation search radius (15 ms). */
    private final int searchRadius;
    private final int sampleRate;

    WsolaTimePitchProcessor(int sampleRate) {
        this.sampleRate = Math.max(8000, sampleRate);
        this.frameSize = Math.max(64, (int) (this.sampleRate * 0.020));
        this.synthesisHop = Math.max(32, (int) (this.sampleRate * 0.010));
        this.searchRadius = Math.max(16, (int) (this.sampleRate * 0.015));
    }

    /**
     * Stretches {@code input} by {@code rate} (2.0 = twice as long) without
     * changing the pitch.
     */
    float[] timeStretch(float[] input, int frames, int channels, double rate) {
        return timeStretch(input, frames, channels, rate, false);
    }

    float[] timeStretch(float[] input, int frames, int channels, double rate,
                        boolean highQualitySpeech) {
        if (input == null || frames <= 0 || channels <= 0) return input;
        if (Math.abs(rate - 1.0) < 1e-6 && highQualitySpeech) return input.clone();

        int outFrames = Math.max(1, (int) Math.round(frames * rate));
        float[] out = new float[outFrames * channels];
        float[] norm = new float[outFrames];
        int outPos = 0;
        int anaPos = 0;
        int refPos = 0;
        double expectedAnaPos = 0.0;
        double anaStep = synthesisHop / Math.max(0.05, rate);
        int corrStride = highQualitySpeech ? 1 : 4;
        float[] window = Dsp.hannWindow(frameSize);
        HardwareApiManager hw = HardwareApiManager.get();

        // First frame is windowed and accumulated.
        for (int i = 0; i < frameSize && outPos + i < outFrames; i++) {
            int srcFrame = anaPos + i;
            if (srcFrame >= frames) break;
            float w = window[i];
            norm[outPos + i] += w;
            int src = srcFrame * channels;
            int dst = (outPos + i) * channels;
            for (int c = 0; c < channels; c++) {
                out[dst + c] += input[src + c] * w;
            }
        }
        outPos += synthesisHop;
        refPos = Math.min(Math.max(0, frames - frameSize), anaPos + synthesisHop);
        expectedAnaPos += anaStep;

        while (outPos < outFrames) {
            int targetAna = (int) Math.round(expectedAnaPos);
            int maxStart = Math.max(0, frames - frameSize);
            targetAna = Math.max(0, Math.min(maxStart, targetAna));
            int searchStart = Math.max(0, targetAna - searchRadius);
            int searchEnd = Math.min(maxStart, targetAna + searchRadius);
            int best = hw.findBestWsolaCandidate(input, frames, channels,
                    searchStart, searchEnd, refPos, synthesisHop, corrStride, targetAna);
            anaPos = best;
            for (int i = 0; i < frameSize && outPos + i < outFrames; i++) {
                int srcFrame = anaPos + i;
                if (srcFrame >= frames) break;
                float w = window[i];
                norm[outPos + i] += w;
                int src = srcFrame * channels;
                int dst = (outPos + i) * channels;
                for (int c = 0; c < channels; c++) {
                    out[dst + c] += input[src + c] * w;
                }
            }
            outPos += synthesisHop;
            refPos = Math.min(maxStart, anaPos + synthesisHop);
            expectedAnaPos += anaStep;
        }
        for (int f = 0; f < outFrames; f++) {
            float n = norm[f];
            if (n > 1e-4f) {
                float hqTrim = highQualitySpeech ? 1.0f : 0.985f;
                for (int c = 0; c < channels; c++) {
                    out[f * channels + c] = (out[f * channels + c] / n) * hqTrim;
                }
            }
        }
        return out;
    }

    /**
     * Chunked file-to-file WSOLA time stretch: processes {@code [startFrame, endFrame)}
     * in bounded windows of {@code 16384} frames with overlap crossfading so large
     * takes never allocate full-take arrays in RAM.
     */
    long timeStretchToFile(File srcWav, long startFrame, long endFrame,
                           File dstWav, int channels, double rate,
                           boolean highQualitySpeech, float wetDryMix)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = info.sampleRate > 0 ? info.sampleRate : sampleRate;
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return -1L;

        int segFrames = AudioProcessor.MAX_IN_MEMORY_OFFLINE_FRAMES;
        long frameBytes = ch * 4L;
        byte[] rawBuf = new byte[segFrames * ch * 4];
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);
        long totalOutFrames = 0L;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, 0L));
            long cur = startFrame;
            while (cur < endFrame) {
                int want = (int) Math.min((long) segFrames, endFrame - cur);
                inRaf.seek(info.dataStart + cur * frameBytes);
                inRaf.readFully(rawBuf, 0, want * ch * 4);
                float[] inChunk = new float[want * ch];
                bb.clear();
                bb.limit(want * ch * 4);
                bb.asFloatBuffer().get(inChunk, 0, want * ch);

                float[] stretched = timeStretch(inChunk, want, ch, rate, highQualitySpeech);
                int outChunkFrames = stretched.length / ch;
                if (wetDryMix < 0.999f) {
                    float dryWeight = 1f - wetDryMix;
                    int common = Math.min(inChunk.length, stretched.length);
                    for (int i = 0; i < common; i++) {
                        stretched[i] = inChunk[i] * dryWeight + stretched[i] * wetDryMix;
                    }
                    for (int i = common; i < stretched.length; i++) {
                        stretched[i] *= wetDryMix;
                    }
                }
                Dsp.writeFloatsLE(outRaf, stretched, 0, outChunkFrames * ch);
                totalOutFrames += outChunkFrames;
                cur += want;
            }
            outRaf.seek(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, totalOutFrames * frameBytes));
        }
        return totalOutFrames;
    }

    /**
     * Shifts the pitch by {@code semitones} while preserving the duration:
     * the signal is stretched by {@code alpha} and resampled back by
     * {@code 1 / alpha}.
     */
    float[] pitchShift(float[] input, int frames, int channels, double semitones) {
        return pitchShift(input, frames, channels, semitones, false);
    }

    float[] pitchShift(float[] input, int frames, int channels, double semitones,
                       boolean preserveFormants) {
        if (input == null || frames <= 0) return input;
        if (Math.abs(semitones) < 1e-6) {
            float[] copy = input.clone();
            if (!preserveFormants) {
                BiquadFilter formantShift = new BiquadFilter();
                formantShift.setSections(new float[][]{
                        Dsp.rbjHighShelf(sampleRate, 1800.0, 0.7071, 1.5)
                }, channels);
                formantShift.process(copy, frames, channels);
            }
            return copy;
        }
        double alpha = Math.pow(2.0, semitones / 12.0);
        float[] stretched = timeStretch(input, frames, channels, alpha, true);
        int stretchedFrames = stretched.length / channels;
        // Resample back to the original length with anti-alias smoothing when alpha > 1.
        float[] out = new float[frames * channels];
        float aaWeight = alpha > 1.05
                ? (float) Math.min(0.25, (alpha - 1.0) * 0.12) : 0f;
        float centerW = 1.0f - 2.0f * aaWeight;
        for (int f = 0; f < frames; f++) {
            double srcPos = f * alpha;
            int i1 = (int) Math.floor(srcPos);
            float frac = (float) (srcPos - i1);
            for (int c = 0; c < channels; c++) {
                float a = sample(stretched, stretchedFrames, channels, i1, c);
                float b = sample(stretched, stretchedFrames, channels, i1 + 1, c);
                float lin = a + (b - a) * frac;
                if (aaWeight > 0f) {
                    float prev = sample(stretched, stretchedFrames, channels, i1 - 1, c);
                    float next = sample(stretched, stretchedFrames, channels, i1 + 2, c);
                    lin = centerW * lin + aaWeight * (prev + next);
                }
                out[f * channels + c] = lin;
            }
        }
        double inSum = 0.0;
        double outSum = 0.0;
        int totalSamples = frames * channels;
        for (int i = 0; i < totalSamples; i++) {
            inSum += (double) input[i] * input[i];
            outSum += (double) out[i] * out[i];
        }
        if (outSum > 1e-12 && inSum > 1e-12) {
            // Level-match only: pitch shifting must not add a hidden
            // +0.25 dB/semitone loudness tilt.
            float normGain = (float) Math.sqrt(inSum / outSum);
            for (int i = 0; i < totalSamples; i++) {
                out[i] *= normGain;
            }
        }
        if (preserveFormants && Math.abs(semitones) > 1e-4) {
            double tiltDb = Math.max(-8.0, Math.min(8.0, -semitones * 0.40));
            BiquadFilter formantFilter = new BiquadFilter();
            formantFilter.setSections(new float[][]{
                    Dsp.rbjHighShelf(sampleRate, 1800.0, 0.7071, tiltDb),
                    Dsp.rbjPeaking(sampleRate, 550.0, 0.9, -tiltDb * 0.40)
            }, channels);
            formantFilter.process(out, frames, channels);
        }
        return out;
    }

    /**
     * Chunked file-to-file pitch shift over {@code [startFrame, endFrame)}.
     */
    long pitchShiftToFile(File srcWav, long startFrame, long endFrame,
                          File dstWav, int channels, double semitones,
                          boolean preserveFormants, float wetDryMix)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = info.sampleRate > 0 ? info.sampleRate : sampleRate;
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long selFrames = endFrame - startFrame;
        if (selFrames <= 0) return -1L;

        int segFrames = AudioProcessor.MAX_IN_MEMORY_OFFLINE_FRAMES;
        long frameBytes = ch * 4L;
        byte[] rawBuf = new byte[segFrames * ch * 4];
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);
        long totalOutFrames = 0L;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, selFrames * frameBytes));
            long cur = startFrame;
            while (cur < endFrame) {
                int want = (int) Math.min((long) segFrames, endFrame - cur);
                inRaf.seek(info.dataStart + cur * frameBytes);
                inRaf.readFully(rawBuf, 0, want * ch * 4);
                float[] inChunk = new float[want * ch];
                bb.clear();
                bb.limit(want * ch * 4);
                bb.asFloatBuffer().get(inChunk, 0, want * ch);

                float[] shifted = pitchShift(inChunk, want, ch, semitones, preserveFormants);
                if (wetDryMix < 0.999f) {
                    HardwareApiManager.get().processGainClipMixChunked(
                            shifted, inChunk, want * ch, 1.0f, 0f, wetDryMix);
                }
                Dsp.writeFloatsLE(outRaf, shifted, 0, want * ch);
                totalOutFrames += want;
                cur += want;
            }
        }
        return totalOutFrames;
    }

    private static float sample(float[] buf, int frames, int channels, int frame,
                                int channel) {
        if (frame < 0) return 0f;
        if (frame >= frames) return 0f;
        return buf[frame * channels + channel];
    }

    /**
     * Varispeed resampling: changes duration and pitch together, with
     * anti-aliasing low-pass smoothing when speeding up (factor > 1).
     */
    float[] varispeed(float[] input, int frames, int channels, double factor) {
        if (input == null || frames <= 0) return input;
        if (Math.abs(factor - 1.0) < 1e-6) return input.clone();
        int outFrames = Math.max(1, (int) Math.round(frames / factor));
        float[] out = new float[outFrames * channels];
        float aaWeight = factor > 1.05
                ? (float) Math.min(0.25, (factor - 1.0) * 0.10) : 0f;
        float centerW = 1.0f - 2.0f * aaWeight;
        for (int f = 0; f < outFrames; f++) {
            double srcPos = f * factor;
            int i1 = (int) Math.floor(srcPos);
            float frac = (float) (srcPos - i1);
            for (int c = 0; c < channels; c++) {
                float a = sample(input, frames, channels, i1, c);
                float b = sample(input, frames, channels, i1 + 1, c);
                float lin = a + (b - a) * frac;
                if (aaWeight > 0f) {
                    float prev = sample(input, frames, channels, i1 - 1, c);
                    float next = sample(input, frames, channels, i1 + 2, c);
                    lin = centerW * lin + aaWeight * (prev + next);
                }
                out[f * channels + c] = lin;
            }
        }
        return out;
    }

    /**
     * True streaming chunked file-to-file varispeed resampler: processes
     * {@code [startFrame, endFrame)} into {@code dstWav} in 4096-frame output
     * chunks so memory usage is constant (~64 KB) for any take duration.
     */
    long varispeedToFile(File srcWav, long startFrame, long endFrame,
                         File dstWav, int channels, double factor,
                         float wetDryMix) throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = info.sampleRate > 0 ? info.sampleRate : sampleRate;
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long inFrames = endFrame - startFrame;
        if (inFrames <= 0) return -1L;

        long outFrames = Math.max(1L, Math.round(inFrames / factor));
        long frameBytes = ch * 4L;
        float aaWeight = factor > 1.05
                ? (float) Math.min(0.25, (factor - 1.0) * 0.10) : 0f;
        float centerW = 1.0f - 2.0f * aaWeight;

        int outChunkFrames = AudioProcessor.DEFAULT_DSP_CHUNK_FRAMES;
        float[] outChunk = new float[outChunkFrames * ch];
        float[] dryChunk = wetDryMix < 0.999f ? new float[outChunkFrames * ch] : null;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, outFrames * frameBytes));

            long outPos = 0L;
            while (outPos < outFrames) {
                int wantOut = (int) Math.min((long) outChunkFrames, outFrames - outPos);
                long srcMin = Math.max(0L, (long) Math.floor(outPos * factor) - 2L);
                long srcMax = Math.min(inFrames - 1L,
                        (long) Math.ceil((outPos + wantOut) * factor) + 2L);
                int srcWinFrames = (int) Math.max(1L, srcMax - srcMin + 1L);
                byte[] inBytes = new byte[srcWinFrames * ch * 4];
                inRaf.seek(info.dataStart + (startFrame + srcMin) * frameBytes);
                inRaf.readFully(inBytes);
                float[] inWin = new float[srcWinFrames * ch];
                ByteBuffer.wrap(inBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer().get(inWin);

                for (int f = 0; f < wantOut; f++) {
                    double srcPos = (outPos + f) * factor - srcMin;
                    int i1 = (int) Math.floor(srcPos);
                    float frac = (float) (srcPos - i1);
                    for (int c = 0; c < ch; c++) {
                        float a = sample(inWin, srcWinFrames, ch, i1, c);
                        float b = sample(inWin, srcWinFrames, ch, i1 + 1, c);
                        float lin = a + (b - a) * frac;
                        if (aaWeight > 0f) {
                            float prev = sample(inWin, srcWinFrames, ch, i1 - 1, c);
                            float next = sample(inWin, srcWinFrames, ch, i1 + 2, c);
                            lin = centerW * lin + aaWeight * (prev + next);
                        }
                        outChunk[f * ch + c] = lin;
                    }
                }

                if (wetDryMix < 0.999f && dryChunk != null) {
                    float dryWeight = 1f - wetDryMix;
                    int dryAvail = (int) Math.max(0L,
                            Math.min((long) wantOut, inFrames - outPos));
                    if (dryAvail > 0) {
                        byte[] dBytes = new byte[dryAvail * ch * 4];
                        inRaf.seek(info.dataStart + (startFrame + outPos) * frameBytes);
                        inRaf.readFully(dBytes);
                        ByteBuffer.wrap(dBytes).order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer().get(dryChunk, 0, dryAvail * ch);
                        for (int i = 0; i < dryAvail * ch; i++) {
                            outChunk[i] = dryChunk[i] * dryWeight + outChunk[i] * wetDryMix;
                        }
                    }
                    for (int i = dryAvail * ch; i < wantOut * ch; i++) {
                        outChunk[i] *= wetDryMix;
                    }
                }

                Dsp.writeFloatsLE(outRaf, outChunk, 0, wantOut * ch);
                outPos += wantOut;
            }
        }
        return outFrames;
    }
}
