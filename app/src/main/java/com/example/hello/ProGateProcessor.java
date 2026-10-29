package com.example.hello;

/**
 * Professional downward expander / room gate: a key-filtered peak detector
 * with 3 dB hysteresis, an adjustable hold time and a smooth release. The
 * threshold is driven by the familiar "Threshold" percentage, while Range,
 * Hold and Release refine the behaviour without touching preset values.
 *
 * <p>The key path is high-passed at 80 Hz so desk rumble and plosives do not
 * hold the gate open. Stereo channels share one detector and one gain.
 */
final class ProGateProcessor extends AudioProcessor {

    private static final int BLOCK = 16;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Threshold", "%", 0, 100, 1, 40));
    private final FxParameter range = addParameter(FxParameter.intParam(
            "gate_range", "Range", " dB", 6, 40, 1, 20));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "gate_release", "Release", " ms", 40, 400, 5, 160));
    private final FxParameter hold = addParameter(FxParameter.intParam(
            "gate_hold", "Hold", " ms", 0, 200, 5, 40));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float env = 0f;
    private float gainCur = 1f;
    private boolean open = false;
    private int holdLeft = 0;
    private float keyLp = 0f;

    private float attackCoef = 0.01f;
    private float releaseCoef = 0.0002f;
    private float decayCoef = 0.999f;
    private int holdSamples = 0;

    ProGateProcessor() {
        recalcCoefs();
    }

    @Override
    public String getId() {
        return "gate";
    }

    @Override
    public String getTitle() {
        return "Room Gate Pro";
    }

    @Override
    public String getDescription() {
        return "Expander with hysteresis, hold and musical release";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        recalcCoefs();
    }

    @Override
    public void reset() {
        env = 0f;
        gainCur = 1f;
        open = false;
        holdLeft = 0;
        keyLp = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt.getCurrentRaw() > 0
                && range.getCurrentRaw() > 0;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    private void recalcCoefs() {
        attackCoef = (float) (1.0 - Math.exp(-1.0 / (0.002 * sampleRate)));
        float relSec = Math.max(1, release.getCurrentRaw()) * 0.001f;
        releaseCoef = (float) (1.0 - Math.exp(-1.0 / (relSec * sampleRate)));
        decayCoef = (float) Math.exp(-BLOCK / (0.060 * sampleRate));
        holdSamples = Math.round(hold.getCurrentRaw() * 0.001f * sampleRate);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float amount = amt.getCurrentRaw() / 100f;
        if (amount <= 0.001f || !isEnabled()) return;
        recalcCoefs();

        final float openDb = -52f + 34f * amount + 1.5f;
        final float closeDb = -52f + 34f * amount - 1.5f;
        final float openLin = DspChain.fastDbToLin(openDb);
        final float closeLin = DspChain.fastDbToLin(closeDb);
        final float floorGain = DspChain.fastDbToLin(-range.getCurrentRaw());
        final float keyCoef = (float) (1.0 - Math.exp(
                -2.0 * Math.PI * 80.0 / sampleRate));

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(BLOCK, frames - offset);
            float peak = 0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                float mono = ch == 1 ? buf[idx]
                        : 0.5f * (buf[idx] + buf[idx + 1]);
                keyLp += keyCoef * (mono - keyLp);
                peak = Math.max(peak, Math.abs(mono - keyLp));
            }
            env = Math.max(peak, env * decayCoef);

            if (env >= openLin) {
                open = true;
                holdLeft = holdSamples;
            } else if (open) {
                if (env < closeLin) {
                    holdLeft -= blk;
                    if (holdLeft <= 0) open = false;
                } else {
                    holdLeft = holdSamples;
                }
            }

            float target = open ? 1f : floorGain;
            for (int f = 0; f < blk; f++) {
                float a = target > gainCur ? attackCoef : releaseCoef;
                gainCur += a * (target - gainCur);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] *= gainCur;
                }
            }
            offset += blk;
        }
    }
}
