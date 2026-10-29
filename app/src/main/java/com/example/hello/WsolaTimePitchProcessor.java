package com.example.hello;

/**
 * Stage 7 WSOLA (Waveform Similarity Overlap-Add) engine.
 *
 * <p>WSOLA time-stretches a signal without changing its pitch: the engine
 * slides a 20 ms analysis window over the input, looks for the position
 * within a +/- 15 ms search window whose waveform best matches the previous
 * synthesis frame (normalised cross-correlation across all channels), and
 * overlap-adds the result with a 10 ms hop. Pitch shifting combines the
 * stretch with an anti-aliased resampler.
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
            int best = targetAna;
            double bestScore = -1e30;
            for (int candidate = searchStart; candidate <= searchEnd; candidate++) {
                double score = similarity(input, frames, channels, candidate,
                        refPos, synthesisHop, corrStride);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
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

    /** Normalised cross-correlation summed across all channels. */
    private double similarity(float[] input, int frames, int channels,
                              int candidate, int reference, int len, int stride) {
        if (candidate + len > frames || reference + len > frames) return -1e30;
        double num = 0;
        double denA = 0;
        double denB = 0;
        int step = Math.max(1, stride);
        for (int i = 0; i < len; i += step) {
            int baseA = (candidate + i) * channels;
            int baseB = (reference + i) * channels;
            for (int c = 0; c < channels; c++) {
                float a = input[baseA + c];
                float b = input[baseB + c];
                num += (double) a * b;
                denA += (double) a * a;
                denB += (double) b * b;
            }
        }
        double den = Math.sqrt(denA * denB);
        return den > 1e-12 ? num / den : 0.0;
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
}
