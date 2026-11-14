package com.takeone.studio;

import java.io.File;
import java.util.Map;

/**
 * Stage 4 Amplify: two-pass gain processor with automatic chunked peak scanning
 * and non-clipping clamp unless "Allow Clipping" is enabled.
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
    public boolean isParamApplicable(String paramId) {
        return applicable(allowClip.getBoolean(), paramId);
    }

    @Override
    public boolean isParamApplicable(String paramId, Map<String, Integer> values) {
        boolean clip = values != null && values.containsKey("amp_clip")
                ? allowClip.clampRaw(values.get("amp_clip")) != 0
                : allowClip.defaultRaw != 0;
        return applicable(clip, paramId);
    }

    private static boolean applicable(boolean allowClipping, String paramId) {
        return !"amp_peak".equals(paramId) || !allowClipping;
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
                || (!allowClip.getBoolean()
                && Math.abs(newPeak.getFloat()) > 0.01f)
                || allowClip.getBoolean());
    }

    @Override
    public void prepareOffline(File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        if (raw == null || !raw.exists()) return;
        final float[] maxHolder = {0f};
        long readFrames = EditEngine.forEachFloatChunk(raw, startFrame, endFrame,
                DEFAULT_DSP_CHUNK_FRAMES,
                (chunk, frames, ch, frameOffset) -> {
                    float p = HardwareApiManager.get()
                            .computeChunkPeak(chunk, frames * ch);
                    if (p > maxHolder[0]) maxHolder[0] = p;
                });
        if (readFrames > 0) {
            scannedPeak = maxHolder[0];
        }
    }

    /** Effective linear gain for the scanned (or in-block) peak. */
    float computeEffectiveGainDb(float peakDb) {
        float requested = gain.getFloat();
        if (allowClip.getBoolean()) {
            return requested;
        }
        float ceiling = newPeak.getFloat();
        if (Math.abs(requested) <= 0.01f && Math.abs(ceiling) > 0.01f) {
            requested = (peakDb > -60f) ? (ceiling - peakDb) : ceiling;
        }
        float effCeiling = Math.abs(ceiling) > 0.01f ? ceiling : -0.1f;
        float maxAllowed = effCeiling - peakDb;
        if (requested > maxAllowed && requested > 0f) {
            requested = maxAllowed;
        }
        return requested;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int n = Math.min(buf.length, frames * Math.max(1, channels));
        float pk = scannedPeak;
        if (pk < 0f) {
            pk = HardwareApiManager.get().computeChunkPeak(buf, n);
        }
        float peakDb = pk > 1e-7f ? Dsp.linToDb(pk) : -120f;
        float effDb = computeEffectiveGainDb(peakDb);
        float lin = Dsp.dbToLin(effDb);
        float ceil = allowClip.getBoolean() ? 0f : 0.9885f;
        HardwareApiManager.get().processGainClipMixChunked(
                buf, null, n, lin, ceil, 1.0f);
    }
}
