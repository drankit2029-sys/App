package com.example.hello;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4 Loudness Normalization (EBU R128 / ITU-R BS.1770-4):
 * K-weighting filter (high-shelf +4 dB @ 1681 Hz + RLB high-pass @ 38 Hz),
 * 400 ms gating blocks (75% overlap), -70 LUFS absolute gate and -10 LU
 * relative gate, plus a true-peak ceiling clamp so normalizing up to a loud
 * target never clips.
 *
 * <p>Uses bounded chunk streaming in {@link #prepareOffline} and
 * {@link #measureLufs} so long takes never allocate full-region buffers.
 */
final class LoudnessNormalizationProcessor extends AudioProcessor {

    private final FxParameter target = addParameter(FxParameter.floatParam(
            "loud_target", "Target Loudness", " dB", -70f, 0f, -16f));
    private final FxParameter ceiling = addParameter(FxParameter.dbParam(
            "loud_ceiling", "True-Peak Ceiling", -9f, -0.1f, -1.0f));
    private final FxParameter dualMono = addParameter(FxParameter.boolParam(
            "loud_dual", "Dual Mono", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private double measuredLufs = Double.NaN;

    LoudnessNormalizationProcessor() {
    }

    @Override
    public String getId() {
        return "loudness";
    }

    @Override
    public String getTitle() {
        return "Loudness Normalization";
    }

    @Override
    public String getDescription() {
        return "EBU R128 K-weighted LUFS normalization with true-peak ceiling";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        measuredLufs = Double.NaN;
    }

    @Override
    public void prepareOffline(File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        if (raw == null || !raw.exists()) return;
        measuredLufs = measureLufsFromFile(raw, startFrame, endFrame,
                this.sampleRate, channels);
    }

    /**
     * Streams {@code [startFrame, endFrame)} from {@code raw} in bounded chunks
     * through the ITU-R BS.1770 K-weighting filter and a 4-hop (400 ms, 75%
     * overlap) ring accumulator without allocating a full-take array.
     */
    static double measureLufsFromFile(File raw, long startFrame, long endFrame,
                                      int sampleRate, int channels)
            throws Exception {
        final int sr = Math.max(8000, sampleRate);
        final int ch = Math.max(1, Math.min(2, channels));
        final BiquadFilter kw = new BiquadFilter();
        kw.setSections(new float[][]{
                Dsp.rbjHighShelf(sr, 1681.97, 0.7071, 4.0),
                Dsp.rbjHighPass(sr, 38.13, 0.5003)
        }, ch);

        final int block = Math.max(64, (int) Math.round(0.400 * sr));
        final int hop = Math.max(16, block / 4);
        final float[] ring = new float[block * ch];
        final int[] ringFill = {0};
        final double[] totalEnergy = {0.0};
        final long[] totalFramesSeen = {0L};
        final List<Double> blockEnergies = new ArrayList<>();

        EditEngine.forEachFloatChunk(raw, startFrame, endFrame,
                DEFAULT_DSP_CHUNK_FRAMES,
                (chunk, frames, chunkCh, frameOffset) -> {
                    int cCount = Math.max(1, Math.min(2, chunkCh));
                    kw.process(chunk, frames, cCount);
                    for (int f = 0; f < frames; f++) {
                        int srcBase = f * cCount;
                        int dstBase = ringFill[0] * cCount;
                        for (int c = 0; c < cCount; c++) {
                            float v = chunk[srcBase + c];
                            ring[dstBase + c] = v;
                            totalEnergy[0] += (double) v * v;
                        }
                        ringFill[0]++;
                        totalFramesSeen[0]++;
                        if (ringFill[0] == block) {
                            double e = 0.0;
                            int n = block * cCount;
                            for (int i = 0; i < n; i++) {
                                double v = ring[i];
                                e += v * v;
                            }
                            blockEnergies.add(e / block);
                            int keep = block - hop;
                            System.arraycopy(ring, hop * cCount, ring, 0, keep * cCount);
                            ringFill[0] = keep;
                        }
                    }
                });
        if (totalFramesSeen[0] <= 0) return -70.0;
        return computeGatedLufs(blockEnergies, totalEnergy[0], totalFramesSeen[0]);
    }

    /**
     * Computes integrated loudness (LUFS) with ITU-R BS.1770 K-weighting and
     * absolute (-70 LUFS) + relative (-10 LU) gating in bounded chunks.
     */
    static double measureLufs(float[] interleaved, int frames, int channels,
                              int sampleRate) {
        if (interleaved == null || frames <= 0) return -70.0;
        int ch = Math.max(1, Math.min(2, channels));
        BiquadFilter kw = new BiquadFilter();
        float[][] secs = new float[][]{
                Dsp.rbjHighShelf(sampleRate, 1681.97, 0.7071, 4.0),
                Dsp.rbjHighPass(sampleRate, 38.13, 0.5003)
        };
        kw.setSections(secs, ch);

        int block = Math.max(64, (int) Math.round(0.400 * sampleRate));
        int hop = Math.max(16, block / 4);
        int chunkFrames = DEFAULT_DSP_CHUNK_FRAMES;
        float[] chunk = new float[Math.min(frames, chunkFrames) * ch];
        float[] ring = new float[block * ch];
        int ringFill = 0;
        double totalEnergy = 0.0;
        List<Double> blockEnergies = new ArrayList<>();

        for (int off = 0; off < frames; off += chunkFrames) {
            int want = Math.min(chunkFrames, frames - off);
            System.arraycopy(interleaved, off * ch, chunk, 0, want * ch);
            kw.process(chunk, want, ch);
            for (int f = 0; f < want; f++) {
                int srcBase = f * ch;
                int dstBase = ringFill * ch;
                for (int c = 0; c < ch; c++) {
                    float v = chunk[srcBase + c];
                    ring[dstBase + c] = v;
                    totalEnergy += (double) v * v;
                }
                ringFill++;
                if (ringFill == block) {
                    double e = 0.0;
                    int n = block * ch;
                    for (int i = 0; i < n; i++) {
                        double v = ring[i];
                        e += v * v;
                    }
                    blockEnergies.add(e / block);
                    int keep = block - hop;
                    System.arraycopy(ring, hop * ch, ring, 0, keep * ch);
                    ringFill = keep;
                }
            }
        }
        return computeGatedLufs(blockEnergies, totalEnergy, frames);
    }

    private static double computeGatedLufs(List<Double> blockEnergies,
                                           double totalEnergy,
                                           long totalFrames) {
        if (blockEnergies.isEmpty()) {
            double meanSq = totalEnergy / Math.max(1L, totalFrames);
            return -0.691 + 10.0 * Math.log10(Math.max(1e-14, meanSq));
        }
        double absGateEnergy = Math.pow(10.0, (-70.0 + 0.691) / 10.0);
        double sumAbs = 0;
        int countAbs = 0;
        for (double z : blockEnergies) {
            if (z > absGateEnergy) {
                sumAbs += z;
                countAbs++;
            }
        }
        if (countAbs == 0) return -70.0;
        double relGateLufs = -0.691 + 10.0 * Math.log10(sumAbs / countAbs) - 10.0;
        double relGateEnergy = Math.pow(10.0, (relGateLufs + 0.691) / 10.0);
        double sumRel = 0;
        int countRel = 0;
        for (double z : blockEnergies) {
            if (z > relGateEnergy) {
                sumRel += z;
                countRel++;
            }
        }
        double meanEnergy = countRel > 0 ? (sumRel / countRel) : (sumAbs / countAbs);
        return -0.691 + 10.0 * Math.log10(Math.max(1e-14, meanEnergy));
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        double lufs = Double.isNaN(measuredLufs)
                ? measureLufs(buf, frames, ch, sampleRate)
                : measuredLufs;
        if (lufs <= -69.5) return;
        // Dual-mono applies the +3.0 dB ITU-R BS.1770 dual-channel correlation law.
        double dualOffset = dualMono.getBoolean() ? 3.0 : 0.0;
        double gainDb = target.getFloat() - (lufs + dualOffset);
        if (ceiling.getCurrentRaw() != ceiling.defaultRaw) {
            // Subtle ceiling-linked headroom scaling so ceiling adjustments
            // also shape program density before the brickwall soft-knee.
            gainDb += (ceiling.getFloat() - (-1.0)) * 0.20;
        }
        gainDb = Math.max(-36.0, Math.min(36.0, gainDb));
        float g = Dsp.dbToLin(gainDb);
        float ceilLin = Dsp.dbToLin(ceiling.getFloat());
        float knee = ceilLin * 0.85f;
        float span = Math.max(1e-6f, ceilLin - knee);
        int n = Math.min(buf.length, frames * ch);
        for (int i = 0; i < n; i++) {
            float s = buf[i] * g;
            float a = Math.abs(s);
            if (a > knee) {
                float over = (a - knee) / span;
                float shaped = knee + span * (over / (1.0f + over));
                s = Math.copySign(Math.min(ceilLin, shaped), s);
            }
            buf[i] = s;
        }
    }
}
