package com.example.hello;

/**
 * Stage 4 level tool: peak / RMS normalization with DC-offset removal.
 *
 * <p>The offline pass measures the per-channel mean and peak of the range
 * (optionally removing the DC offset before measuring the peak), then the
 * render pass subtracts the offset and applies the exact gain. When the
 * stereo channels are linked the quieter channel dictates the gain so the
 * image stays balanced.
 */
final class NormalizeProcessor extends AudioProcessor {

    private final FxParameter target = addParameter(FxParameter.dbParam(
            "norm_target", "Target Peak", -60f, 0f, -1f));
    private final FxParameter removeDc = addParameter(FxParameter.boolParam(
            "norm_dc", "Remove DC Offset", true));
    private final FxParameter normalizePeak = addParameter(FxParameter.boolParam(
            "norm_peak", "Normalize Peak", true));
    private final FxParameter independent = addParameter(FxParameter.boolParam(
            "norm_indep", "Independent Channels", false));
    private final FxParameter normalizeRms = addParameter(FxParameter.boolParam(
            "norm_rms", "Normalize RMS", false));

    private float[] dcOffset = new float[2];
    private float[] gainLin = new float[]{1f, 1f};
    private boolean scanned = false;
    // Real-time fallback state (no pre-scan available).
    private float[] realtimePeak = new float[2];
    private float[] realtimeGain = new float[]{1f, 1f};
    private float[] dcState = new float[2];
    private float[] prevIn = new float[2];

    NormalizeProcessor() {
    }

    @Override
    public String getId() {
        return "normalize";
    }

    @Override
    public String getTitle() {
        return "Normalize";
    }

    @Override
    public String getDescription() {
        return "Peak or RMS normalization with DC-offset removal";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
        scanned = false;
        gainLin[0] = 1f;
        gainLin[1] = 1f;
        dcOffset[0] = 0f;
        dcOffset[1] = 0f;
        realtimePeak[0] = realtimePeak[1] = 0f;
        realtimeGain[0] = realtimeGain[1] = 1f;
        dcState[0] = dcState[1] = 0f;
        prevIn[0] = prevIn[1] = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
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

        double[] mean = new double[ch];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) mean[c] += sel[f * ch + c];
        }
        for (int c = 0; c < ch; c++) mean[c] /= frames;

        float[] peak = new float[ch];
        double[] sumSq = new double[ch];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                float v = sel[f * ch + c] - (float) mean[c];
                float a = Math.abs(v);
                if (a > peak[c]) peak[c] = a;
                sumSq[c] += (double) v * v;
            }
        }
        float[] rms = new float[ch];
        for (int c = 0; c < ch; c++) {
            rms[c] = (float) Math.sqrt(sumSq[c] / frames);
        }

        float targetLin = Dsp.dbToLin(target.getFloat());
        float[] g = new float[ch];
        for (int c = 0; c < ch; c++) {
            if (normalizeRms.getBoolean()) {
                g[c] = rms[c] > 1e-9f ? targetLin / rms[c] : 1f;
            } else if (normalizePeak.getBoolean()) {
                g[c] = peak[c] > 1e-9f ? targetLin / peak[c] : 1f;
            } else {
                g[c] = 1f;
            }
        }
        if (!independent.getBoolean()) {
            float min = g[0];
            for (int c = 1; c < ch; c++) min = Math.min(min, g[c]);
            for (int c = 0; c < ch; c++) g[c] = min;
        }
        for (int c = 0; c < ch; c++) {
            gainLin[c] = g[c];
            dcOffset[c] = removeDc.getBoolean() ? (float) mean[c] : 0f;
        }
        scanned = true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        if (!scanned) {
            // Compute exact DC offset, peak and RMS on the buffer when no
            // offline file pre-scan was run (e.g. unit matrix or live block).
            double[] mean = new double[ch];
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < ch; c++) mean[c] += buf[f * ch + c];
            }
            for (int c = 0; c < ch; c++) mean[c] /= frames;
            float[] peak = new float[ch];
            double[] sumSq = new double[ch];
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < ch; c++) {
                    float v = buf[f * ch + c] - (float) mean[c];
                    float a = Math.abs(v);
                    if (a > peak[c]) peak[c] = a;
                    sumSq[c] += (double) v * v;
                }
            }
            float targetLin = Dsp.dbToLin(target.getFloat());
            float[] g = new float[ch];
            for (int c = 0; c < ch; c++) {
                float rms = (float) Math.sqrt(sumSq[c] / frames);
                if (normalizeRms.getBoolean()) {
                    g[c] = rms > 1e-9f ? targetLin / rms : 1f;
                } else if (normalizePeak.getBoolean()) {
                    g[c] = peak[c] > 1e-9f ? targetLin / peak[c] : 1f;
                } else {
                    g[c] = 1f;
                }
            }
            if (!independent.getBoolean()) {
                float min = g[0];
                for (int c = 1; c < ch; c++) min = Math.min(min, g[c]);
                for (int c = 0; c < ch; c++) g[c] = min;
            }
            for (int f = 0; f < frames; f++) {
                int idx = f * ch;
                for (int c = 0; c < ch; c++) {
                    float dc = removeDc.getBoolean() ? (float) mean[c] : 0f;
                    buf[idx + c] = (buf[idx + c] - dc) * g[c];
                }
            }
            return;
        }
        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            for (int c = 0; c < ch; c++) {
                float v = buf[idx + c] - dcOffset[c];
                buf[idx + c] = v * gainLin[c];
            }
        }
    }

    /**
     * Real-time fallback: tracks a decaying peak estimate per channel and
     * eases the gain towards {@code target / peak}. The DC offset is
     * removed with a one-pole high-pass so it works without a pre-scan.
     */
    private void runRealtime(float[] buf, int frames, int ch) {
        float targetLin = Dsp.dbToLin(target.getFloat());
        final int block = 64;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] peak = new float[ch];
            for (int f = 0; f < n; f++) {
                for (int c = 0; c < ch; c++) {
                    float v = buf[(offset + f) * ch + c];
                    float a = Math.abs(v);
                    if (a > peak[c]) peak[c] = a;
                }
            }
            for (int c = 0; c < ch; c++) {
                // The peak estimate decays so a transient does not pin the
                // gain down for the rest of the take.
                realtimePeak[c] = Math.max(peak[c], realtimePeak[c] * 0.9995f);
                float target = realtimePeak[c] > 1e-6f
                        ? targetLin / realtimePeak[c] : 1f;
                if (!normalizePeak.getBoolean() && !normalizeRms.getBoolean()) {
                    target = 1f;
                }
                realtimeGain[c] += (target - realtimeGain[c]) * 0.05f;
            }
            for (int f = 0; f < n; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float v = buf[idx + c];
                    // One-pole DC blocker.
                    dcState[c] = 0.995f * (dcState[c] + v - prevIn[c]);
                    prevIn[c] = v;
                    float out = removeDc.getBoolean() ? dcState[c] : v;
                    buf[idx + c] = out * realtimeGain[c];
                }
            }
            offset += n;
        }
    }
}
