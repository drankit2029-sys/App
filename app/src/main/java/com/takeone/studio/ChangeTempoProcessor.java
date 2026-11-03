package com.takeone.studio;

import java.io.File;

/**
 * Stage 7 change tempo: WSOLA time-stretch that changes the duration while
 * keeping the pitch intact. Supports bounded-chunk file streaming for long takes.
 */
final class ChangeTempoProcessor extends AudioProcessor {

    private final FxParameter percent = addParameter(FxParameter.floatParam(
            "tempo_percent", "Percent Change", "%", -75f, 300f, 0f));
    private final FxParameter bpmFrom = addParameter(FxParameter.intParam(
            "tempo_bpmfrom", "BPM From", "", 30, 300, 1, 120));
    private final FxParameter bpmTo = addParameter(FxParameter.intParam(
            "tempo_bpmto", "BPM To", "", 30, 300, 1, 120));
    private final FxParameter hq = addParameter(FxParameter.boolParam(
            "tempo_hq", "High-Quality Speech Mode", true));

    private int sampleRate = DspChain.SAMPLE_RATE;

    ChangeTempoProcessor() {
    }

    @Override
    public String getId() {
        return "tempo";
    }

    @Override
    public String getTitle() {
        return "Change Tempo";
    }

    @Override
    public String getDescription() {
        return "WSOLA time stretch without changing the pitch";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TIME;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isOfflineOnly() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // Offline-only effect: rendered through processOfflineRegion.
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (Math.abs(stretchFactor() - 1.0) > 1e-6
                || hq.getCurrentRaw() != hq.defaultRaw);
    }

    /** The stretch factor: 2.0 means twice as long (half the tempo). */
    private double stretchFactor() {
        double pct = percent.getFloat();
        int from = bpmFrom.getCurrentRaw();
        int to = bpmTo.getCurrentRaw();
        double rPct = 1.0 + (pct / 100.0);
        double rBpm = (from > 1 && to > 1) ? ((double) to / from) : 1.0;
        double ratio = Math.abs(rPct - rBpm) < 1e-3
                ? rPct
                : rPct * rBpm;
        return Math.max(0.05, Math.min(20.0, 1.0 / Math.max(0.05, ratio)));
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        double factor = stretchFactor();
        boolean highQuality = hq.getBoolean();
        if (Math.abs(factor - 1.0) < 1e-6 && highQuality) return in.clone();
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sampleRate);
        return wsola.timeStretch(in, frames, channels, factor, highQuality);
    }

    @Override
    public long processOfflineRangeToFile(File srcWav, long startFrame,
                                          long endFrame, File dstWav,
                                          int sampleRate, int channels)
            throws Exception {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(this.sampleRate);
        return wsola.timeStretchToFile(srcWav, startFrame, endFrame, dstWav,
                channels, stretchFactor(), hq.getBoolean(), getWetDryMix());
    }
}
