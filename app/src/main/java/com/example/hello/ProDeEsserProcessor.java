package com.example.hello;

/**
 * Professional de-esser: a 4th-order sibilance sidechain (two cascaded
 * constant-peak band-passes) drives smooth, programme-dependent gain
 * reduction. Band centre and width are adjustable, and the reduction can be
 * applied to the sibilance band only (split mode) or to the whole signal
 * (broadband mode).
 *
 * <p>The detector compares band energy to full-band energy, so it follows a
 * quiet close-mic take and a loud take equally well without a fixed level
 * threshold. Stereo channels share one gain so the image stays stable.
 */
final class ProDeEsserProcessor extends AudioProcessor {

    private static final int BLOCK = 16;
    private static final float INV_BLOCK = 1.0f / BLOCK;
    private static final float EPS = 1e-12f;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Reduction", "%", 0, 100, 1, 45));
    private final FxParameter freq = addParameter(FxParameter.intParam(
            "deess_freq", "Frequency", " Hz", 4500, 9500, 100, 6800));
    private final FxParameter width = addParameter(FxParameter.floatParam(
            "deess_width", "Width", " Q", 0.6f, 3.0f, 1.5f));
    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "deess_mode", "Mode", new String[]{"Split band", "Broadband"}, 0));

    private final float[][] b0 = new float[2][2];
    private final float[][] a1 = new float[2][2];
    private final float[][] a2 = new float[2][2];
    private final float[][] z1 = new float[2][2];
    private final float[][] z2 = new float[2][2];
    private final float[] scratchSib = new float[BLOCK * 2];

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int cachedFreq = -1;
    private int cachedWidth = -1;
    private int cachedRate = -1;

    private float sibEnv = 1e-7f;
    private float fullEnv = 1e-6f;
    private float cutCur = 0f;
    private float cutTarget = 0f;

    ProDeEsserProcessor() {
        updateCoefficients();
    }

    @Override
    public String getId() {
        return "deess";
    }

    @Override
    public String getTitle() {
        return "De-Esser Pro";
    }

    @Override
    public String getDescription() {
        return "4th-order adaptive sibilance control, split or broadband";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        updateCoefficients();
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            for (int s = 0; s < 2; s++) {
                z1[c][s] = 0f;
                z2[c][s] = 0f;
            }
        }
        sibEnv = 1e-7f;
        fullEnv = 1e-6f;
        cutCur = 0f;
        cutTarget = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt01() > 0.001f;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    private float amt01() {
        return amt.getCurrentRaw() / 100f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled()) return;
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float amount = amt01();
        if (amount <= 0.001f) return;
        updateCoefficients();
        final boolean broadband = mode.getCurrentRaw() != 0;
        final float maxCutDb = 6f + 18f * amount;
        final float threshDb = 1.0f - 11.5f * amount;
        final float aSibAtt = blockCoef(0.0015f);
        final float aSibRel = blockCoef(0.080f);
        final float aFullAtt = blockCoef(0.010f);
        final float aFullRel = blockCoef(0.150f);

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(BLOCK, frames - offset);

            float sibEnergy = 0f;
            float fullEnergy = 0f;
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                int sIdx = f * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    float sib = bandPass(x, c);
                    scratchSib[sIdx + c] = sib;
                    sibEnergy += sib * sib;
                    fullEnergy += x * x;
                }
            }
            float invN = 1.0f / (blk * ch);
            sibEnv += (sibEnergy * invN > sibEnv ? aSibAtt : aSibRel)
                    * (sibEnergy * invN - sibEnv);
            fullEnv += (fullEnergy * invN > fullEnv ? aFullAtt : aFullRel)
                    * (fullEnergy * invN - fullEnv);

            float ratioDb = 0.5f * DspChain.fastLinToDb(sibEnv + EPS)
                    - 0.5f * DspChain.fastLinToDb(fullEnv + 1e-9f);
            float wantCut = 0f;
            if (ratioDb > threshDb && fullEnv > 1e-7f) {
                float overDb = Math.min(maxCutDb,
                        (ratioDb - threshDb) * 1.4f);
                wantCut = 1f - DspChain.fastDbToLin(-overDb);
            }
            float alpha = wantCut > cutTarget ? 0.50f : 0.12f;
            cutTarget += alpha * (wantCut - cutTarget);
            float dCut = (cutTarget - cutCur) * INV_BLOCK;

            for (int f = 0; f < blk; f++) {
                cutCur += dCut;
                int idx = (offset + f) * ch;
                int sIdx = f * ch;
                for (int c = 0; c < ch; c++) {
                    if (broadband) {
                        buf[idx + c] *= 1f - cutCur;
                    } else {
                        buf[idx + c] -= cutCur * scratchSib[sIdx + c];
                    }
                }
            }
            offset += blk;
        }
    }

    /** One biquad stage; the second stage is run right after in bandPass. */
    private float bandPass(float x, int c) {
        float y = b0[c][0] * x + z1[c][0];
        z1[c][0] = -a1[c][0] * y + z2[c][0];
        z2[c][0] = -b0[c][0] * x - a2[c][0] * y;
        float z = b0[c][1] * y + z1[c][1];
        z1[c][1] = -a1[c][1] * z + z2[c][1];
        z2[c][1] = -b0[c][1] * y - a2[c][1] * z;
        return z;
    }

    private float blockCoef(float seconds) {
        return (float) (1.0 - Math.exp(-BLOCK / (seconds * sampleRate)));
    }

    private void updateCoefficients() {
        int hz = Math.max(4500, Math.min(9500, freq.getCurrentRaw()));
        int wRaw = width.clampRaw(width.getCurrentRaw());
        if (hz == cachedFreq && wRaw == cachedWidth
                && sampleRate == cachedRate) {
            return;
        }
        cachedFreq = hz;
        cachedWidth = wRaw;
        cachedRate = sampleRate;
        double q = wRaw / 100.0;
        double w0 = 2.0 * Math.PI * hz / sampleRate;
        double cos = Math.cos(w0);
        double sin = Math.sin(w0);
        double alpha = sin / (2.0 * q);
        double a0Inv = 1.0 / (1.0 + alpha);
        float nb0 = (float) (alpha * a0Inv);
        float na1 = (float) (-2.0 * cos * a0Inv);
        float na2 = (float) ((1.0 - alpha) * a0Inv);
        for (int c = 0; c < 2; c++) {
            for (int s = 0; s < 2; s++) {
                b0[c][s] = nb0;
                a1[c][s] = na1;
                a2[c][s] = na2;
            }
        }
    }
}
