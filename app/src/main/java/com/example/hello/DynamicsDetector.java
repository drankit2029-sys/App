package com.example.hello;

/**
 * Shared envelope / level-detection helpers for the Stage 4 dynamics suite
 * (compressor, gate, limiter, de-esser, ducking).
 *
 * <p>Every detector here works in the log domain so attack and release times
 * behave the way an engineer expects: the ballistics are one-pole filters on
 * the dB value, not on the linear amplitude.
 */
final class DynamicsDetector {

    private DynamicsDetector() {}

    /**
     * One-pole attack/release envelope follower in dB.
     *
     * <p>The detector keeps the loudest recent peak (a "peak hold" with a slow
     * decay) so short transients still trigger the compressor, while the
     * release ballistics shape how fast the gain comes back.
     */
    static final class Envelope {
        private float envDb = -120f;
        private float holdDb = -120f;
        private final int sampleRate;

        Envelope(int sampleRate) {
            this.sampleRate = Math.max(1000, sampleRate);
        }

        void reset() {
            envDb = -120f;
            holdDb = -120f;
        }

        float currentDb() {
            return envDb;
        }

        float peakDb() {
            return holdDb;
        }

        /**
         * Feeds one block of interleaved frames and returns the detector
         * level in dB (max over channels for peak mode, RMS across the block
         * for RMS mode).
         */
        float detect(float[] buf, int frames, int channels, boolean rms,
                     float attackMs, float releaseMs) {
            int n = Math.max(1, Math.min(2, channels));
            float blockDb = -120f;
            if (rms) {
                double sum = 0;
                int count = 0;
                for (int f = 0; f < frames; f++) {
                    int idx = f * n;
                    for (int c = 0; c < n; c++) {
                        sum += (double) buf[idx + c] * buf[idx + c];
                        count++;
                    }
                }
                if (count > 0) {
                    float rmsLin = (float) Math.sqrt(sum / count);
                    blockDb = Dsp.linToDb(rmsLin);
                }
            } else {
                float peak = 0f;
                for (int f = 0; f < frames; f++) {
                    int idx = f * n;
                    for (int c = 0; c < n; c++) {
                        float a = Math.abs(buf[idx + c]);
                        if (a > peak) peak = a;
                    }
                }
                blockDb = Dsp.linToDb(peak);
            }
            // The ballistics are per-sample coefficients, but the detector
            // runs on blocks: scale them by the block length so the attack
            // and release times are independent of the block size.
            float att = blockCoeff(attackMs, frames);
            float rel = blockCoeff(releaseMs, frames);
            if (blockDb > envDb) {
                envDb += att * (blockDb - envDb);
            } else {
                envDb += rel * (blockDb - envDb);
            }
            if (envDb > holdDb) {
                holdDb = envDb;
            } else {
                holdDb += 0.0008f * (envDb - holdDb);
            }
            return envDb;
        }

        /** Attack coefficient for a given time constant in milliseconds. */
        float coeff(float ms) {
            double sec = Math.max(0.0005, ms * 0.001);
            return (float) (1.0 - Math.exp(-1.0 / (sec * sampleRate)));
        }

        /**
         * The same coefficient, scaled to a block of {@code frames} samples.
         * Applying the per-sample coefficient once per block would make the
         * ballistics roughly {@code frames} times too slow.
         */
        float blockCoeff(float ms, int frames) {
            double sec = Math.max(0.0005, ms * 0.001);
            double samples = sec * sampleRate;
            return (float) (1.0 - Math.exp(-(double) Math.max(1, frames)
                    / samples));
        }
    }

    /**
     * Soft-knee gain computer in dB, the classic Fairlight/Audacity shape.
     *
     * @param overDb    input level minus threshold, in dB
     * @param ratio     compression ratio (1 = transparent)
     * @param kneeDb    width of the soft knee
     * @return gain reduction in dB (negative)
     */
    static float softKneeGainDb(float overDb, float ratio, float kneeDb) {
        if (ratio <= 1.0001f) return 0f;
        float slope = 1f - 1f / ratio;
        if (kneeDb <= 0f) {
            return overDb <= 0f ? 0f : -slope * overDb;
        }
        float half = kneeDb * 0.5f;
        if (overDb <= -half) return 0f;
        if (overDb >= half) return -slope * overDb;
        // Quadratic knee interpolation between the two straight lines.
        float x = overDb + half;
        return -slope * x * x / (2f * kneeDb);
    }

    /**
     * Downward expander gain in dB: below {@code thresholdDb} the signal is
     * attenuated toward {@code rangeDb} using {@code ratio}.
     */
    static float expanderGainDb(float levelDb, float thresholdDb,
                                float rangeDb, float ratio) {
        if (levelDb >= thresholdDb) return 0f;
        float over = thresholdDb - levelDb;
        float slope = 1f - 1f / Math.max(1.0001f, ratio);
        float reduction = -slope * over;
        return Math.max(rangeDb, reduction);
    }

    /**
     * Zero-order-hold peak follower with a hold timer, used by the gate's
     * hysteresis state machine.
     */
    static final class PeakHold {
        private float peakDb = -120f;
        private int holdFrames = 0;
        private int holdCounter = 0;

        void reset() {
            peakDb = -120f;
            holdCounter = 0;
        }

        float currentDb() {
            return peakDb;
        }

        boolean inHold() {
            return holdCounter > 0;
        }

        /** Updates the peak and returns the level used for detection. */
        float update(float blockDb, int frames, float holdMs, int sampleRate) {
            if (blockDb > peakDb) {
                peakDb = blockDb;
                holdCounter = (int) (holdMs * 0.001 * sampleRate);
            } else {
                if (holdCounter > 0) {
                    holdCounter -= frames;
                } else {
                    peakDb = blockDb;
                }
            }
            return peakDb;
        }
    }

    /** Converts a linear amplitude into the dBFS domain with a sane floor. */
    static float toDb(float lin) {
        return Dsp.linToDb(lin);
    }

    /** Converts dBFS into a linear amplitude. */
    static float toLin(float db) {
        return Dsp.dbToLin(db);
    }
}
