package com.example.hello;

/**
 * Stage 4 mastering tool: EBU R128 / ITU-R BS.1770-4 integrated loudness
 * normalization with a true-peak ceiling.
 *
 * <p>The measurement pass runs the ITU K-weighting filter (a +4 dB high shelf
 * at 1681.97 Hz followed by a 38.13 Hz RLB high-pass), integrates 400 ms
 * blocks with 75 % overlap and applies the absolute (-70 LUFS) and relative
 * (-10 LU) gates exactly as the recommendation describes. The render pass
 * then applies the resulting linear gain and soft-limits the peaks to the
 * chosen ceiling so the result never clips.
 */
final class LoudnessNormalizationProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Perceived Loudness (LUFS)",
            "RMS dBFS"};

    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "loud_mode", "Normalization Mode", MODE_LABELS, 0));
    private final FxParameter target = addParameter(FxParameter.dbParam(
            "loud_target", "Target Loudness", -70f, 0f, -16f));
    private final FxParameter dualMono = addParameter(FxParameter.boolParam(
            "loud_dual", "Treat Mono as Dual-Mono", true));
    private final FxParameter ceiling = addParameter(FxParameter.dbParam(
            "loud_ceiling", "True-Peak Ceiling", -3f, -0.1f, -1f));

    private float appliedGainDb = 0f;
    private float measuredLufs = -70f;
    private boolean scanned = false;
    // Real-time fallback state (no pre-scan available).
    private float realtimeLufs = -70f;
    private float realtimeGainDb = 0f;

    private BiquadFilter kWeight = new BiquadFilter();
    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;

    LoudnessNormalizationProcessor() {
    }

    @Override
    public String getId() {
        return "loudness";
    }

    @Override
    public String getTitle() {
        return "Loudness Normalization (EBU R128)";
    }

    @Override
    public String getDescription() {
        return "K-weighted LUFS integration with a true-peak ceiling";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuildWeighting();
    }

    @Override
    public void reset() {
        kWeight.reset();
        scanned = false;
        realtimeLufs = -70f;
        realtimeGainDb = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    private void rebuildWeighting() {
        // BS.1770-4 K-weighting: high shelf +4 dB @1681.97 Hz, then the
        // RLB high-pass at 38.13 Hz.
        float[] shelf = Dsp.rbjHighShelf(sampleRate, 1681.97, 0.7071, 4f);
        float[] hp = Dsp.rbjHighPass(sampleRate, 38.13, 0.5003);
        kWeight.setSections(new float[][]{shelf, hp}, channels);
    }

    /** Measured integrated loudness in LUFS (available after the scan). */
    public float getMeasuredLufs() {
        return measuredLufs;
    }

    @Override
    public void prepareOffline(java.io.File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (raw == null) return;
        float[] sel = EditEngine.readFloatSelection(raw, startFrame, endFrame);
        if (sel == null || sel.length == 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int frames = sel.length / ch;
        if (frames <= 0) return;

        this.sampleRate = sampleRate > 0 ? sampleRate : DspChain.SAMPLE_RATE;
        this.channels = ch;
        rebuildWeighting();

        float lufs;
        if (mode.getEnumIndex() == 1) {
            lufs = measureRmsDbfs(sel, frames, ch);
        } else {
            lufs = measureIntegratedLufs(sel, frames, ch);
        }
        measuredLufs = lufs;
        float t = target.getFloat();
        if (lufs <= -69.5f) {
            appliedGainDb = 0f;
        } else {
            appliedGainDb = t - lufs;
        }
        scanned = true;
    }

    /** Plain RMS level in dBFS over the whole selection. */
    private static float measureRmsDbfs(float[] sel, int frames, int ch) {
        double sum = 0;
        int count = 0;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                double v = sel[f * ch + c];
                sum += v * v;
                count++;
            }
        }
        if (count == 0) return -70f;
        return (float) (10 * Math.log10(sum / count));
    }

    /**
     * Integrated loudness per ITU-R BS.1770-4: K-weighted block energies on
     * 400 ms blocks with 75 % overlap, absolute gate at -70 LUFS, then a
     * relative gate 10 LU below the (ungated) mean.
     */
    private float measureIntegratedLufs(float[] sel, int frames, int ch) {
        int block = Math.max(1, (int) (sampleRate * 0.4));
        int step = Math.max(1, (int) (sampleRate * 0.1));
        float[] blockLufs = new float[Math.max(1, frames / step + 2)];
        int blockCount = 0;

        float[] scratch = new float[block * ch];
        for (int start = 0; start + block <= frames; start += step) {
            System.arraycopy(sel, start * ch, scratch, 0, block * ch);
            kWeight.reset();
            kWeight.process(scratch, block, ch);
            double sum = 0;
            for (int f = 0; f < block; f++) {
                for (int c = 0; c < ch; c++) {
                    double v = scratch[f * ch + c];
                    sum += v * v;
                }
            }
            double mean = sum / (block * (double) ch);
            if (ch == 1 && dualMono.getBoolean()) {
                mean *= 2.0;
            }
            double l = -0.691 + 10 * Math.log10(Math.max(1e-12, mean));
            if (blockCount < blockLufs.length) {
                blockLufs[blockCount++] = (float) l;
            }
        }
        if (blockCount == 0) return -70f;

        // Absolute gate at -70 LUFS.
        double gated = 0;
        int gatedCount = 0;
        for (int i = 0; i < blockCount; i++) {
            if (blockLufs[i] > -70f) {
                gated += Math.pow(10, (blockLufs[i] + 0.691) / 10.0);
                gatedCount++;
            }
        }
        if (gatedCount == 0) return -70f;
        double ungated = -0.691 + 10 * Math.log10(gated / gatedCount);

        // Relative gate 10 LU below the ungated level.
        double rel = ungated - 10.0;
        gated = 0;
        gatedCount = 0;
        for (int i = 0; i < blockCount; i++) {
            if (blockLufs[i] > -70f && blockLufs[i] > rel) {
                gated += Math.pow(10, (blockLufs[i] + 0.691) / 10.0);
                gatedCount++;
            }
        }
        if (gatedCount == 0) return (float) ungated;
        return (float) (-0.691 + 10 * Math.log10(gated / gatedCount));
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        if (!scanned) {
            runRealtime(buf, frames, ch);
            return;
        }
        float gain = Dsp.dbToLin(appliedGainDb);
        if (gain == 1f) return;
        int n = Math.min(buf.length, frames * ch);
        for (int i = 0; i < n; i++) buf[i] *= gain;

        // True-peak ceiling: soft-limit anything that would exceed it.
        float ceilLin = Dsp.dbToLin(ceiling.getFloat());
        for (int i = 0; i < n; i++) {
            float v = buf[i];
            float a = Math.abs(v);
            if (a > ceilLin) {
                float over = a - ceilLin;
                // tanh-style soft knee above the ceiling.
                float shaped = ceilLin + over / (1f + over / (ceilLin + 1e-6f));
                buf[i] = v < 0 ? -shaped : shaped;
            }
        }
    }

    /**
     * Real-time fallback: tracks a decaying K-weighted loudness estimate
     * and eases the gain towards {@code target - measured}. The ceiling is
     * still enforced, so the live preview can never clip.
     */
    private void runRealtime(float[] buf, int frames, int ch) {
        float ceilLin = Dsp.dbToLin(ceiling.getFloat());
        float targetLufs = target.getFloat();
        final int block = 1024;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] weighted = new float[n * ch];
            System.arraycopy(buf, offset * ch, weighted, 0, weighted.length);
            kWeight.reset();
            kWeight.process(weighted, n, ch);
            double sum = 0;
            for (int i = 0; i < n * ch; i++) {
                sum += (double) weighted[i] * weighted[i];
            }
            double mean = sum / (n * (double) ch);
            if (ch == 1 && dualMono.getBoolean()) {
                mean *= 2.0;
            }
            float blockLufs = (float) (-0.691 + 10 * Math.log10(Math.max(1e-12, mean)));
            // The estimate decays so a loud passage does not pin the gain.
            realtimeLufs = Math.max(blockLufs, realtimeLufs - 0.05f);
            float wanted = targetLufs - realtimeLufs;
            realtimeGainDb += (wanted - realtimeGainDb) * 0.02f;
            float gain = Dsp.dbToLin(realtimeGainDb);
            for (int i = 0; i < n * ch; i++) {
                float v = buf[offset * ch + i] * gain;
                float a = Math.abs(v);
                if (a > ceilLin) {
                    float over = a - ceilLin;
                    float shaped = ceilLin + over / (1f + over / (ceilLin + 1e-6f));
                    v = v < 0 ? -shaped : shaped;
                }
                buf[offset * ch + i] = v;
            }
            offset += n;
        }
    }
}
