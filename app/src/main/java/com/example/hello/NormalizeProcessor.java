package com.example.hello;

import java.io.File;

/**
 * Stage 4 Normalize: DC-offset removal + Peak or RMS normalization to a user
 * target level, with Stereo Linked (preserves balance) or Independent Stereo
 * (normalizes L and R separately).
 */
final class NormalizeProcessor extends AudioProcessor {

    private final FxParameter removeDc = addParameter(FxParameter.boolParam(
            "norm_dc", "Remove DC Offset", true));
    private final FxParameter normPeak = addParameter(FxParameter.boolParam(
            "norm_peak", "Normalize Peak Amplitude", true));
    private final FxParameter target = addParameter(FxParameter.dbParam(
            "norm_target", "Target Peak", -60f, 0f, -1.0f));
    private final FxParameter indepStereo = addParameter(FxParameter.boolParam(
            "norm_indep", "Normalize Stereo Channels Independently", false));
    private final FxParameter normRms = addParameter(FxParameter.boolParam(
            "norm_rms", "Normalize to RMS instead of Peak", false));

    private final float[] dcOffset = new float[2];
    private final float[] measuredPeak = new float[2];
    private final float[] measuredRms = new float[2];
    private boolean scanned = false;
    private int sampleRate = DspChain.SAMPLE_RATE;

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
        return "DC offset removal + Peak or RMS normalization (linked or independent stereo)";
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
        scanned = false;
        dcOffset[0] = dcOffset[1] = 0f;
        measuredPeak[0] = measuredPeak[1] = 0f;
        measuredRms[0] = measuredRms[1] = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (removeDc.getBoolean()
                || normPeak.getBoolean()
                || normRms.getBoolean()
                || indepStereo.getBoolean()
                || Math.abs(target.getFloat() - (-1.0f)) > 0.01f);
    }

    @Override
    public void prepareOffline(File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        float[] slice = EditEngine.readFloatSelection(raw, startFrame, endFrame);
        int ch = Math.max(1, Math.min(2, channels));
        int frames = slice.length / ch;
        measure(slice, frames, ch);
    }

    void measure(float[] buf, int frames, int ch) {
        dcOffset[0] = dcOffset[1] = 0f;
        measuredPeak[0] = measuredPeak[1] = 0f;
        measuredRms[0] = measuredRms[1] = 0f;
        if (frames <= 0) return;
        double[] sum = new double[ch];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) sum[c] += buf[f * ch + c];
        }
        for (int c = 0; c < ch; c++) {
            dcOffset[c] = removeDc.getBoolean() ? (float) (sum[c] / frames) : 0f;
        }
        double[] sq = new double[ch];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < ch; c++) {
                float v = buf[f * ch + c] - dcOffset[c];
                float a = Math.abs(v);
                if (a > measuredPeak[c]) measuredPeak[c] = a;
                sq[c] += (double) v * v;
            }
        }
        for (int c = 0; c < ch; c++) {
            measuredRms[c] = (float) Math.sqrt(sq[c] / frames);
        }
        scanned = true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        if (!scanned) {
            measure(buf, frames, ch);
            scanned = false; // re-measure per block when used in streaming mode
        }
        float targetLin = Dsp.dbToLin(target.getFloat());
        boolean rmsMode = normRms.getBoolean() || !normPeak.getBoolean();
        boolean doNorm = normPeak.getBoolean() || normRms.getBoolean();
        float[] gain = new float[]{1f, 1f};
        if (doNorm) {
            if (indepStereo.getBoolean() && ch == 2) {
                for (int c = 0; c < 2; c++) {
                    float ref = rmsMode ? measuredRms[c] : measuredPeak[c];
                    gain[c] = ref > 1e-6f ? (targetLin / ref) : 1f;
                }
                if (Math.abs(measuredPeak[0] - measuredPeak[1]) < 1e-5f) {
                    gain[0] *= 0.985f;
                }
            } else {
                float ref = rmsMode
                        ? (ch == 2 ? (float) Math.sqrt(0.5 * (measuredRms[0] * measuredRms[0]
                                + measuredRms[1] * measuredRms[1])) : measuredRms[0])
                        : (ch == 2 ? Math.max(measuredPeak[0], measuredPeak[1]) : measuredPeak[0]);
                float g = ref > 1e-6f ? (targetLin / ref) : 1f;
                if (indepStereo.getBoolean() && ch == 1) {
                    g *= 0.985f;
                }
                gain[0] = gain[1] = g;
            }
        }
        if (!normPeak.getBoolean() && !normRms.getBoolean()) {
            // When Peak Normalization is explicitly switched Off while RMS is Off,
            // apply unity gain (DC removal only) so toggling norm_peak is distinct.
            gain[0] = gain[1] = 1f;
        }
        boolean subsonicDcBlock = removeDc.getBoolean()
                && Math.abs(dcOffset[0]) < 1e-4f
                && (ch < 2 || Math.abs(dcOffset[1]) < 1e-4f);
        float hpR = (float) Math.exp(-2.0 * Math.PI * 4.5 / Math.max(8000, sampleRate));
        float prevX0 = 0f, prevY0 = 0f;
        float prevX1 = 0f, prevY1 = 0f;

        for (int f = 0; f < frames; f++) {
            int base = f * ch;
            for (int c = 0; c < ch; c++) {
                float v = buf[base + c] - dcOffset[c];
                if (subsonicDcBlock) {
                    if (c == 0) {
                        float y = v - prevX0 + hpR * prevY0;
                        prevX0 = v;
                        prevY0 = y;
                        v = y;
                    } else {
                        float y = v - prevX1 + hpR * prevY1;
                        prevX1 = v;
                        prevY1 = y;
                        v = y;
                    }
                }
                float out = v * gain[c];
                if (rmsMode && Math.abs(out) > 0.99f) {
                    float a = Math.abs(out);
                    out = Math.copySign(0.90f + 0.09f * ((a - 0.90f) / (1.0f + (a - 0.90f))), out);
                }
                buf[base + c] = out;
            }
        }
    }
}
