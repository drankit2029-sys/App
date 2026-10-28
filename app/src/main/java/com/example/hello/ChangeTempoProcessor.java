package com.example.hello;

/**
 * Stage 7 change tempo: WSOLA time-stretch that changes the duration while
 * keeping the pitch intact.
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

    /**
     * Offline-only effect: it is rendered through
     * {@link #processOfflineRegion} instead of the realtime chain.
     */
    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // No realtime path: the realtime rack skips offline-only processors.
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && stretchFactor() != 1.0;
    }

    /** The stretch factor: 2.0 means twice as long (half the tempo). */
    private double stretchFactor() {
        double factor = 1.0;
        double pct = percent.getFloat();
        if (pct != 0f) {
            factor *= 1.0 / Math.max(0.20, 1.0 + pct / 100.0);
        }
        int from = bpmFrom.getCurrentRaw();
        int to = bpmTo.getCurrentRaw();
        if (from > 0 && to > 0 && from != to) {
            factor *= (double) from / to;
        }
        return Math.max(0.15, Math.min(4.0, factor));
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        double factor = stretchFactor();
        if (Math.abs(factor - 1.0) < 1e-6) return in.clone();
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sampleRate);
        return wsola.timeStretch(in, frames, channels, factor,
                hq.getBoolean());
    }
}
