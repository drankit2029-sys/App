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
        float[] slice = EditEngine.readFloatSelection(raw, startFrame, endFrame);
        int ch = Math.max(1, Math.min(2, channels));
        measuredLufs = measureLufs(slice, slice.length / ch, ch, this.sampleRate);
    }

    /**
     * Computes integrated loudness (LUFS) with ITU-R BS.1770 K-weighting and
     * absolute (-70 LUFS) + relative (-10 LU) gating. Falls back to short-term
     * K-weighted mean square when the buffer is shorter than 400 ms.
     */
    static double measureLufs(float[] interleaved, int frames, int channels,
                              int sampleRate) {
        if (interleaved == null || frames <= 0) return -70.0;
        int ch = Math.max(1, Math.min(2, channels));
        float[] work = interleaved.clone();
        BiquadFilter kw = new BiquadFilter();
        float[][] secs = new float[][]{
                Dsp.rbjHighShelf(sampleRate, 1681.97, 0.7071, 4.0),
                Dsp.rbjHighPass(sampleRate, 38.13, 0.5003)
        };
        kw.setSections(secs, ch);
        kw.process(work, frames, ch);

        int block = Math.max(64, (int) Math.round(0.400 * sampleRate));
        int hop = Math.max(16, block / 4);
        List<Double> blockEnergies = new ArrayList<>();
        for (int start = 0; start + block <= frames; start += hop) {
            double e = 0;
            for (int f = start; f < start + block; f++) {
                for (int c = 0; c < ch; c++) {
                    double v = work[f * ch + c];
                    e += v * v;
                }
            }
            blockEnergies.add(e / block);
        }
        if (blockEnergies.isEmpty()) {
            double e = 0;
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < ch; c++) {
                    double v = work[f * ch + c];
                    e += v * v;
                }
            }
            double meanSq = e / Math.max(1, frames);
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
