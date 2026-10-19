package com.example.hello;

/**
 * Stage 4 of the legacy voice chain: Warmth (180 Hz chest body) &amp; Vocal
 * Presence (3.5 kHz air) applied in one pass when adjacent, or split across
 * non-adjacent rack positions when reordered around another active processor.
 * At 48 kHz ({@link DspChain#SAMPLE_RATE}), the combined pass is a verbatim
 * extraction of the original {@code DspChain} stage for bit-identical output.
 */
final class LegacyToneProcessor extends AudioProcessor {

    private static final int DECIM = 16;
    private static final float WARM_ALPHA_48K = 0.0233f;
    private static final float PRES_ALPHA_48K = 0.3678f;
    private static final double WARM_CUTOFF_HZ = 180.0;
    private static final double PRES_CUTOFF_HZ = 3500.0;

    private final FxParameter warmth = addParameter(FxParameter.intParam(
            "warmth", "Warmth", "%", 0, 100, 1, 40));
    private final FxParameter presence = addParameter(FxParameter.intParam(
            "presence", "Presence", "%", 0, 100, 1, 45));

    private final float[] warmLp = new float[2];
    private final float[] presLp = new float[2];
    private volatile float warmAlpha = WARM_ALPHA_48K;
    private volatile float presAlpha = PRES_ALPHA_48K;

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
        if (sampleRate <= 0 || sampleRate == DspChain.SAMPLE_RATE) {
            warmAlpha = WARM_ALPHA_48K;
            presAlpha = PRES_ALPHA_48K;
        } else {
            warmAlpha = (float) (1.0 - Math.exp(
                    -2.0 * Math.PI * WARM_CUTOFF_HZ / sampleRate));
            presAlpha = (float) (1.0 - Math.exp(
                    -2.0 * Math.PI * PRES_CUTOFF_HZ / sampleRate));
        }
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
        return isEnabled() && (warmth01() > 0.001f
                || presence01() > 0.001f);
    }

    void setLegacyAmounts(int warmthPct, int presencePct) {
        warmth.setCurrentRaw(warmthPct);
        presence.setCurrentRaw(presencePct);
    }

    /** Raw amounts are percents; gain math needs 0..1 fractions. */
    private float warmth01() {
        return warmth.getCurrentRaw() / 100f;
    }

    private float presence01() {
        return presence.getCurrentRaw() / 100f;
    }

    /**
     * Processes only the 180 Hz Warmth stage when Warmth and Presence are
     * separated by another active processor in the user's rack order.
     */
    void processWarmthOnly(float[] buf, int frames, int channels, float mix) {
        if (buf == null || frames <= 0 || mix <= 0.0f) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float wAmt = warmth01();
        if (wAmt <= 0.001f) return;
        final float wAlpha = this.warmAlpha;
        final float warmGain = wAmt * 1.45f * Math.min(1.0f, mix);
        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    warmLp[c] += wAlpha * (x - warmLp[c]);
                    buf[idx + c] = x + warmGain * warmLp[c];
                }
            }
            offset += blk;
        }
    }

    /**
     * Processes only the 3.5 kHz Vocal Presence stage when Warmth and Presence
     * are separated by another active processor in the user's rack order.
     */
    void processPresenceOnly(float[] buf, int frames, int channels, float mix) {
        if (buf == null || frames <= 0 || mix <= 0.0f) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float pAmt = presence01();
        if (pAmt <= 0.001f) return;
        final float pAlpha = this.presAlpha;
        final float presGain = pAmt * 1.65f * Math.min(1.0f, mix);
        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    presLp[c] += pAlpha * (x - presLp[c]);
                    float hi = x - presLp[c];
                    buf[idx + c] = x + presGain * hi;
                }
            }
            offset += blk;
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float wAmt = warmth01();
        final float pAmt = presence01();
        if (wAmt <= 0.001f && pAmt <= 0.001f) return;
        final float wAlpha = this.warmAlpha;
        final float pAlpha = this.presAlpha;

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
                    warmLp[c] += wAlpha * (x - warmLp[c]);
                    presLp[c] += pAlpha * (x - presLp[c]);
                    float hi = x - presLp[c];
                    buf[idx + c] = x + warmGain * warmLp[c] + presGain * hi;
                }
            }

            offset += blk;
        }
    }
}
