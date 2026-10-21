package com.example.hello;

/**
 * Stage 7 change pitch: shifts the pitch without changing the tempo by
 * time-stretching the signal with WSOLA and resampling it back.
 */
final class ChangePitchProcessor extends AudioProcessor {

    private final FxParameter semitones = addParameter(FxParameter.floatParam(
            "pitch_semitones", "Semitones", "", -24f, 24f, 0f));
    private final FxParameter cents = addParameter(FxParameter.intParam(
            "pitch_cents", "Cents", "", -100, 100, 1, 0));
    private final FxParameter percent = addParameter(FxParameter.floatParam(
            "pitch_percent", "Percent Change", "%", -50f, 100f, 0f));
    private final FxParameter preserveFormant = addParameter(FxParameter.boolParam(
            "pitch_formant", "Preserve Formants", true));

    private int sampleRate = DspChain.SAMPLE_RATE;

    ChangePitchProcessor() {
    }

    @Override
    public String getId() {
        return "changepitch";
    }

    @Override
    public String getTitle() {
        return "Change Pitch";
    }

    @Override
    public String getDescription() {
        return "WSOLA pitch shift that keeps the tempo";
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
        return isEnabled() && Math.abs(totalSemitones()) > 0.001f;
    }

    private float totalSemitones() {
        float s = semitones.getFloat() + cents.getFloat() / 100f;
        float pct = percent.getFloat();
        if (pct != 0f) {
            s += (float) (12.0 * Math.log(pct / 100.0 + 1.0) / Math.log(2.0));
        }
        return s;
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sampleRate);
        return wsola.pitchShift(in, frames, channels, totalSemitones());
    }
}
