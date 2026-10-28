package com.example.hello;

/**
 * Stage 4 level tool: linear gain with an automatic non-clipping peak
 * pre-scan.
 *
 * <p>The offline pass measures the true peak of the range first
 * ({@link #prepareOffline}); the render pass then applies the gain, clamped
 * so the result never exceeds {@code newPeakDbFS} unless the user explicitly
 * allows clipping.
 */
final class AmplifyProcessor extends AudioProcessor {

    private final FxParameter gain = addParameter(FxParameter.dbParam(
            "amp_gain", "Amplification", -50f, 50f, 0f));
    private final FxParameter newPeak = addParameter(FxParameter.dbParam(
            "amp_peak", "New Peak", -50f, 12f, 0f));
    private final FxParameter allowClip = addParameter(FxParameter.boolParam(
            "amp_clip", "Allow Clipping", false));

    private float appliedGainDb = 0f;
    private float measuredPeakDb = -120f;
    private boolean scanned = false;

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
        return "Linear gain with an automatic non-clipping peak scan";
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
        appliedGainDb = 0f;
        scanned = false;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (Math.abs(gain.getFloat()) > 0.01f
                || Math.abs(newPeak.getFloat()) > 0.01f);
    }

    private float computeEffectiveGainDb(float peakDb) {
        float requested = gain.getFloat();
        float ceiling = newPeak.getFloat();
        float maxAllowed = ceiling - peakDb;
        if (Math.abs(requested) <= 0.01f && Math.abs(ceiling) > 0.01f) {
            if (ceiling < peakDb) {
                // Target peak is below the signal's current peak: attenuate to ceiling.
                requested = maxAllowed;
            } else {
                // Smooth relative trim around 0 dB without jumping by -peakDb.
                requested = ceiling;
            }
        } else if (Math.abs(ceiling) > 0.01f && ceiling < 0f) {
            requested = Math.min(requested, maxAllowed);
        }
        if (!allowClip.getBoolean() && requested > maxAllowed) {
            requested = maxAllowed;
        }
        return requested;
    }

    /**
     * Scans the range for its true peak so the render pass can clamp the
     * requested gain to a non-clipping value.
     */
    @Override
    public void prepareOffline(java.io.File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (raw == null) return;
        float[] sel = EditEngine.readFloatSelection(raw, startFrame, endFrame);
        if (sel == null || sel.length == 0) return;
        float peak = 0f;
        for (int i = 0; i < sel.length; i++) {
            float a = Math.abs(sel[i]);
            if (a > peak) peak = a;
        }
        measuredPeakDb = Dsp.linToDb(Math.max(1e-6f, peak));
        appliedGainDb = computeEffectiveGainDb(measuredPeakDb);
        scanned = true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int n = Math.min(buf.length, frames * Math.max(1, Math.min(2, channels)));
        float db;
        if (scanned) {
            db = appliedGainDb;
        } else {
            float peak = 0f;
            for (int i = 0; i < n; i++) {
                float a = Math.abs(buf[i]);
                if (a > peak) peak = a;
            }
            db = computeEffectiveGainDb(Dsp.linToDb(Math.max(1e-6f, peak)));
        }
        if (Math.abs(db) <= 1e-4f) return;
        float g = Dsp.dbToLin(db);
        for (int i = 0; i < n; i++) buf[i] *= g;
    }
}
