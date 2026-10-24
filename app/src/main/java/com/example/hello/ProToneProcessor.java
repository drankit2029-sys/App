package com.example.hello;

/**
 * Professional tone stage: musical low/high shelves built from RBJ biquads
 * with adjustable corner frequencies. Warmth defaults to 180 Hz and presence
 * to 3.5 kHz, so existing presets keep their character while secondary
 * controls let the engineer move the shelves to suit the voice.
 *
 * <p>The warmth and presence halves can be processed separately around a
 * compressor (the rack's split-tone ordering), each with its own mix weight.
 */
final class ProToneProcessor extends AudioProcessor {

    private static final int BLOCK = 16;

    private final FxParameter warmth = addParameter(FxParameter.intParam(
            "warmth", "Warmth", "%", 0, 100, 1, 40));
    private final FxParameter presence = addParameter(FxParameter.intParam(
            "presence", "Presence", "%", 0, 100, 1, 45));
    private final FxParameter warmthFreq = addParameter(FxParameter.intParam(
            "warmth_freq", "Warmth Freq", " Hz", 120, 300, 5, 180));
    private final FxParameter presenceFreq = addParameter(FxParameter.intParam(
            "presence_freq", "Presence Freq", " Hz", 2500, 6000, 50, 3500));

    private final float[] warmTarget = new float[5];
    private final float[] warmCur = new float[5];
    private final float[] presTarget = new float[5];
    private final float[] presCur = new float[5];

    private final float[] warmZ1 = new float[2];
    private final float[] warmZ2 = new float[2];
    private final float[] presZ1 = new float[2];
    private final float[] presZ2 = new float[2];

    private int sampleRate = DspChain.SAMPLE_RATE;
    private volatile float warmthSlotMix = 1f;
    private volatile float presenceSlotMix = 1f;
    private float cachedWarmMix = -1f;
    private float cachedPresMix = -1f;
    private int cachedWarmFreq = -1;
    private int cachedWarmRaw = -1;
    private int cachedPresFreq = -1;
    private int cachedPresRaw = -1;
    private int cachedRate = -1;
    private int cachedPresRate = -1;

    ProToneProcessor() {
        updateTargets(0f, 0f);
        snapAll();
    }

    @Override
    public String getId() {
        return "tone";
    }

    @Override
    public String getTitle() {
        return "Warmth & Presence Pro";
    }

    @Override
    public String getDescription() {
        return "Musical low/high shelves with movable corners";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        cachedRate = -1;
        cachedPresRate = -1;
        updateTargets(1f, 1f);
        snapAll();
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            warmZ1[c] = warmZ2[c] = 0f;
            presZ1[c] = presZ2[c] = 0f;
        }
        cachedWarmMix = cachedPresMix = -1f;
        updateTargets(1f, 1f);
        snapAll();
    }

    @Override
    public boolean isAudible() {
        return isEnabled()
                && getWetDryMix() > 0.001f
                && ((warmth.getCurrentRaw() > 0 && warmthSlotMix > 0.001f)
                        || (presence.getCurrentRaw() > 0 && presenceSlotMix > 0.001f));
    }

    void setLegacyAmounts(int warmthPct, int presencePct) {
        warmth.setCurrentRaw(warmthPct);
        presence.setCurrentRaw(presencePct);
    }

    void setWarmthSlotMix(float mix) {
        this.warmthSlotMix = clampMix(mix);
    }

    void setPresenceSlotMix(float mix) {
        this.presenceSlotMix = clampMix(mix);
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return warmthSlotMix < 0.999f || presenceSlotMix < 0.999f;
    }

    void processWarmthOnly(float[] buf, int frames, int channels, float mix) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        float m = clampMix(mix);
        if (m <= 0f) return;
        float[] dry = m < 1f ? buf.clone() : null;
        updateTargets(1f, 0f);
        int ch = Math.max(1, Math.min(2, channels));
        processBand(buf, frames, ch, warmCur, warmTarget, warmZ1, warmZ2);
        blendDryWet(buf, dry, frames * ch, m);
    }

    void processPresenceOnly(float[] buf, int frames, int channels, float mix) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        float m = clampMix(mix);
        if (m <= 0f) return;
        float[] dry = m < 1f ? buf.clone() : null;
        updateTargets(0f, 1f);
        int ch = Math.max(1, Math.min(2, channels));
        processBand(buf, frames, ch, presCur, presTarget, presZ1, presZ2);
        blendDryWet(buf, dry, frames * ch, m);
    }

    private static void blendDryWet(float[] wet, float[] dry, int n, float mix) {
        if (dry == null || mix >= 1f) return;
        float d = 1f - mix;
        int count = Math.min(Math.min(n, wet.length), dry.length);
        for (int i = 0; i < count; i++) wet[i] = dry[i] * d + wet[i] * mix;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        updateTargets(warmthSlotMix, presenceSlotMix);
        if (warmth.getCurrentRaw() > 0 && warmthSlotMix > 0.001f) {
            processBand(buf, frames, ch, warmCur, warmTarget,
                    warmZ1, warmZ2);
        }
        if (presence.getCurrentRaw() > 0 && presenceSlotMix > 0.001f) {
            processBand(buf, frames, ch, presCur, presTarget,
                    presZ1, presZ2);
        }
    }

    private static float clampMix(float mix) {
        if (mix <= 0f) return 0f;
        return Math.min(1f, mix);
    }

    private void processBand(float[] buf, int frames, int ch,
                             float[] cur, float[] target,
                             float[] z1, float[] z2) {
        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(BLOCK, frames - offset);
            glide(cur, target);
            float b0 = cur[0], b1 = cur[1], b2 = cur[2];
            float a1 = cur[3], a2 = cur[4];
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    float y = b0 * x + z1[c];
                    z1[c] = b1 * x - a1 * y + z2[c];
                    z2[c] = b2 * x - a2 * y;
                    buf[idx + c] = y;
                }
            }
            offset += blk;
        }
    }

    private void glide(float[] cur, float[] target) {
        for (int i = 0; i < cur.length; i++) {
            float d = target[i] - cur[i];
            if (Math.abs(d) > 1e-7f) {
                cur[i] += d * 0.35f;
            } else {
                cur[i] = target[i];
            }
        }
    }

    private void snapAll() {
        System.arraycopy(warmTarget, 0, warmCur, 0, 5);
        System.arraycopy(presTarget, 0, presCur, 0, 5);
    }

    private void updateTargets(float warmMix, float presMix) {
        float wMix = clampMix(warmMix);
        float pMix = clampMix(presMix);
        int wf = Math.max(120, Math.min(300, warmthFreq.getCurrentRaw()));
        int pf = Math.max(2500, Math.min(6000, presenceFreq.getCurrentRaw()));
        int warmRaw = warmth.getCurrentRaw();
        int presRaw = presence.getCurrentRaw();
        if (wMix != cachedWarmMix || wf != cachedWarmFreq
                || warmRaw != cachedWarmRaw || sampleRate != cachedRate) {
            cachedWarmMix = wMix;
            cachedWarmFreq = wf;
            cachedWarmRaw = warmRaw;
            cachedRate = sampleRate;
            writeShelf(warmTarget, true, wf,
                    warmth.getCurrentRaw() / 100f * 9f * wMix);
        }
        if (pMix != cachedPresMix || pf != cachedPresFreq
                || presRaw != cachedPresRaw || sampleRate != cachedPresRate) {
            cachedPresMix = pMix;
            cachedPresFreq = pf;
            cachedPresRaw = presRaw;
            cachedPresRate = sampleRate;
            writeShelf(presTarget, false, pf,
                    presence.getCurrentRaw() / 100f * 10f * pMix);
        }
    }

    private void writeShelf(float[] out, boolean low, int hz, float gainDb) {
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * hz / sampleRate;
        double cos = Math.cos(w0);
        double sin = Math.sin(w0);
        double alpha = sin / Math.sqrt(2.0);
        double sqrtA = Math.sqrt(a);
        double b0, b1, b2, a0, a1, a2;
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cos + 2 * sqrtA * alpha);
            b1 = 2 * a * ((a - 1) - (a + 1) * cos);
            b2 = a * ((a + 1) - (a - 1) * cos - 2 * sqrtA * alpha);
            a0 = (a + 1) + (a - 1) * cos + 2 * sqrtA * alpha;
            a1 = -2 * ((a - 1) + (a + 1) * cos);
            a2 = (a + 1) + (a - 1) * cos - 2 * sqrtA * alpha;
        } else {
            b0 = a * ((a + 1) + (a - 1) * cos + 2 * sqrtA * alpha);
            b1 = -2 * a * ((a - 1) + (a + 1) * cos);
            b2 = a * ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha);
            a0 = (a + 1) - (a - 1) * cos + 2 * sqrtA * alpha;
            a1 = 2 * ((a - 1) - (a + 1) * cos);
            a2 = (a + 1) - (a - 1) * cos - 2 * sqrtA * alpha;
        }
        double inv = 1.0 / a0;
        out[0] = (float) (b0 * inv);
        out[1] = (float) (b1 * inv);
        out[2] = (float) (b2 * inv);
        out[3] = (float) (a1 * inv);
        out[4] = (float) (a2 * inv);
    }
}
