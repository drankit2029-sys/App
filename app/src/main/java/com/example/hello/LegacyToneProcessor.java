package com.example.hello;

/**
 * Stage 4 of the legacy voice chain: Warmth (180 Hz chest body) &amp; Vocal
 * Presence (3.5 kHz air) applied in one pass. Both live in a single processor
 * because in the original engine they shared one per-sample loop; splitting
 * them would change the result (presence reads the warmth-blended sample).
 * Body is a verbatim extraction of the original {@code DspChain} stage.
 */
final class LegacyToneProcessor extends AudioProcessor {

    private static final int DECIM = 16;
    private static final float WARM_ALPHA = 0.0233f;
    private static final float PRES_ALPHA = 0.3678f;

    private final FxParameter warmth = addParameter(FxParameter.intParam(
            "warmth", "Warmth", "%", 0, 100, 1, 40));
    private final FxParameter presence = addParameter(FxParameter.intParam(
            "presence", "Presence", "%", 0, 100, 1, 45));

    private final float[] warmLp = new float[2];
    private final float[] presLp = new float[2];

    @Override
    public String getId() {
        return "tone";
    }

    @Override
    public String getTitle() {
        return "Warmth & Presence";
    }

    @Override
    public String getDescription() {
        return "Low-mid chest body (~180 Hz) plus vocal air & articulation (~3.5 kHz)";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            warmLp[c] = 0f;
            presLp[c] = 0f;
        }
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (warmth.getFloat() > 0.001f
                || presence.getFloat() > 0.001f);
    }

    void setLegacyAmounts(int warmthPct, int presencePct) {
        warmth.setCurrentRaw(warmthPct);
        presence.setCurrentRaw(presencePct);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        final int ch = channels;
        final float wAmt = warmth.getFloat();
        final float pAmt = presence.getFloat();
        if ((wAmt <= 0.001f && pAmt <= 0.001f) || frames <= 0) return;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 4: Warmth (180 Hz Chest Body) & Vocal Presence (3.5 kHz Air)
            float warmGain = wAmt * 1.45f; // up to +9 dB low-mid body
            float presGain = pAmt * 1.65f; // up to +10 dB air & articulation
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    warmLp[c] += WARM_ALPHA * (x - warmLp[c]);
                    presLp[c] += PRES_ALPHA * (x - presLp[c]);
                    float hi = x - presLp[c];
                    buf[idx + c] = x + warmGain * warmLp[c] + presGain * hi;
                }
            }

            offset += blk;
        }
    }
}
