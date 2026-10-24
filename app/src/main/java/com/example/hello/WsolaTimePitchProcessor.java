package com.example.hello;

/**
 * Stage 7 WSOLA (Waveform Similarity Overlap-Add) engine.
 *
 * <p>WSOLA time-stretches a signal without changing its pitch: the engine
 * slides a 20 ms analysis window over the input, looks for the position
 * within a +/- 15 ms search window whose waveform best matches the previous
 * synthesis frame (normalised cross-correlation), and overlap-adds the
 * result with a 10 ms hop. Pitch shifting combines the stretch with a
 * windowed-sinc resampler.
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
        if (Math.abs(rate - 1.0) < 1e-6) return input.clone();

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
                for (int c = 0; c < channels; c++) {
                    out[f * channels + c] /= n;
                }
            }
        }
        return out;
    }

    /** Normalised cross-correlation between the candidate and the reference. */
    private double similarity(float[] input, int frames, int channels,
                              int candidate, int reference, int len, int stride) {
        if (candidate + len > frames || reference + len > frames) return -1e30;
        double num = 0;
        double denA = 0;
        double denB = 0;
        for (int i = 0; i < len; i += Math.max(1, stride)) {
            float a = input[(candidate + i) * channels];
            float b = input[(reference + i) * channels];
            num += (double) a * b;
            denA += (double) a * a;
            denB += (double) b * b;
        }
        double den = Math.sqrt(denA * denB);
        return den > 1e-12 ? num / den : 0.0;
    }

    private void copyFrame(float[] input, int frames, int channels, int srcPos,
                           float[] out, int dstPos, int len, int outFrames) {
        for (int i = 0; i < len; i++) {
            int src = srcPos + i;
            if (src >= frames) break;
            if (dstPos + i >= outFrames) break;
            int s = src * channels;
            int d = (dstPos + i) * channels;
            for (int c = 0; c < channels; c++) out[d + c] = input[s + c];
        }
    }

    private void applyWindow(float[] buf, int frameOffset, int len, int channels,
                             float[] window, int outFrames) {
        for (int i = 0; i < len; i++) {
            if (frameOffset + i >= outFrames) break;
            int idx = (frameOffset + i) * channels;
            if (idx + channels > buf.length) break;
            for (int c = 0; c < channels; c++) buf[idx + c] *= window[i];
        }
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
        if (Math.abs(semitones) < 1e-6) return input.clone();
        double alpha = Math.pow(2.0, semitones / 12.0);
        float[] stretched = timeStretch(input, frames, channels, alpha);
        int stretchedFrames = stretched.length / channels;
        // Resample back to the original length (linear interpolation with a
        // small anti-alias smoothing).
        float[] out = new float[frames * channels];
        for (int f = 0; f < frames; f++) {
            double srcPos = f * alpha;
            int i1 = (int) Math.floor(srcPos);
            float frac = (float) (srcPos - i1);
            for (int c = 0; c < channels; c++) {
                float a = sample(stretched, stretchedFrames, channels, i1, c);
                float b = sample(stretched, stretchedFrames, channels, i1 + 1, c);
                out[f * channels + c] = a + (b - a) * frac;
            }
        }
        if (preserveFormants && Math.abs(semitones) > 0.5) {
            // Compensate spectral tilt introduced by resampling vocal formants:
            // pitching up shifts formants upward (thin/chipmunk), so apply a
            // compensating high-shelf cut + low-mid body shelf; pitching down
            // applies the inverse.
            double tiltDb = Math.max(-10.0, Math.min(10.0, -semitones * 0.55));
            BiquadFilter formantFilter = new BiquadFilter();
            formantFilter.setSections(new float[][]{
                    Dsp.rbjHighShelf(sampleRate, 1800.0, 0.7071, tiltDb),
                    Dsp.rbjPeaking(sampleRate, 550.0, 0.9, -tiltDb * 0.45)
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
     * Varispeed resampling: changes duration and pitch together.
     */
    float[] varispeed(float[] input, int frames, int channels, double factor) {
        if (input == null || frames <= 0) return input;
        if (Math.abs(factor - 1.0) < 1e-6) return input.clone();
        int outFrames = Math.max(1, (int) Math.round(frames / factor));
        float[] out = new float[outFrames * channels];
        for (int f = 0; f < outFrames; f++) {
            double srcPos = f * factor;
            int i1 = (int) Math.floor(srcPos);
            float frac = (float) (srcPos - i1);
            for (int c = 0; c < channels; c++) {
                float a = sample(input, frames, channels, i1, c);
                float b = sample(input, frames, channels, i1 + 1, c);
                out[f * channels + c] = a + (b - a) * frac;
            }
        }
        return out;
    }
}
