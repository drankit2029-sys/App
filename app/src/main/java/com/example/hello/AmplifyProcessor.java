package com.example.hello;

import java.io.File;

/**
 * Stage 4 Amplify: two-pass gain processor with automatic peak scanning and
 * non-clipping clamp unless "Allow Clipping" is enabled.
 */
final class AmplifyProcessor extends AudioProcessor {

    private final FxParameter gain = addParameter(FxParameter.dbParam(
            "amp_gain", "Amplification", -50f, 50f, 0f));
    private final FxParameter newPeak = addParameter(FxParameter.dbParam(
            "amp_peak", "New Peak Amplitude", -40f, 0f, 0f));
    private final FxParameter allowClip = addParameter(FxParameter.boolParam(
            "amp_clip", "Allow Clipping", false));

    private float scannedPeak = -1f;

    AmplifyProcessor() {
    }

    @Override
    public String getId() {
        return "amplify";
    }

    @Override
    public String getTitle() {
        return "Amplify";
    }

    @Override
    public String getDescription() {
        return "Two-pass gain with automatic non-clipping peak scan";
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
        scannedPeak = -1f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (Math.abs(gain.getFloat()) > 0.01f
                || Math.abs(newPeak.getFloat()) > 0.01f
                || allowClip.getBoolean());
    }

    @Override
    public void prepareOffline(File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (raw == null) return;
        float[] slice = EditEngine.readFloatSelection(raw, startFrame, endFrame);
        if (slice == null || slice.length == 0) return;
        float pk = 0f;
        for (float v : slice) {
            float a = Math.abs(v);
            if (a > pk) pk = a;
        }
        scannedPeak = pk;
    }

    /** Effective linear gain for the scanned (or in-block) peak. */
    float computeEffectiveGainDb(float peakDb) {
        float requested = gain.getFloat();
        float ceiling = newPeak.getFloat();
        if (Math.abs(requested) <= 0.01f && Math.abs(ceiling) > 0.01f) {
            requested = (peakDb > -60f) ? (ceiling - peakDb) : ceiling;
        } else if (Math.abs(requested) <= 0.01f && allowClip.getBoolean()) {
            requested = 1.5f;
        }
        if (!allowClip.getBoolean()) {
            float effCeiling = Math.abs(ceiling) > 0.01f ? ceiling : -0.1f;
            float maxAllowed = effCeiling - peakDb;
            if (requested > maxAllowed && requested > 0f) {
                requested = maxAllowed;
            }
        }
        return requested;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int n = Math.min(buf.length, frames * Math.max(1, channels));
        float pk = scannedPeak;
        if (pk < 0f) {
            pk = 0f;
            for (int i = 0; i < n; i++) {
                float a = Math.abs(buf[i]);
                if (a > pk) pk = a;
            }
        }
        float peakDb = pk > 1e-7f ? Dsp.linToDb(pk) : -120f;
        float effDb = computeEffectiveGainDb(peakDb);
        float lin = Dsp.dbToLin(effDb);
        boolean clipAllowed = allowClip.getBoolean();
        for (int i = 0; i < n; i++) {
            float v = buf[i] * lin;
            if (!clipAllowed) {
                if (v > 0.9885f) v = 0.9885f;
                else if (v < -0.9885f) v = -0.9885f;
            }
            buf[i] = v;
        }
    }
}
