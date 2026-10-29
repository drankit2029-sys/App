package com.example.hello;

/**
 * Stage 4 Dedicated De-Esser (Split-Band & Broadband) with Center Frequency,
 * Q/Bandwidth, Threshold, Max Reduction dB, and Split-Band vs Broadband mode.
 */
final class DeEsserProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Split-Band", "Broadband"};

    private final FxParameter freq = addParameter(FxParameter.intParam(
            "deess_fc", "Frequency", " Hz", 3000, 12000, 100, 6800));
    private final FxParameter q = addParameter(FxParameter.qParam(
            "deess_q", "Bandwidth Q", 0.5f, 10f, 2.0f));
    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "deess_thresh", "Threshold", -48f, 0f, -18f));
    private final FxParameter maxReduction = addParameter(FxParameter.dbParam(
            "deess_max", "Max Reduction", 0f, 24f, 12f));
    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "deess_splitmode", "Mode", MODE_LABELS, 0));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private final BiquadFilter bp = new BiquadFilter();
    private float sibEnv = 0f;
    private float fullEnv = 0f;

    DeEsserProcessor() {
    }

    @Override
    public String getId() {
        return "deesssplit";
    }

    @Override
    public String getTitle() {
        return "De-Esser (Split Band)";
    }

    @Override
    public String getDescription() {
        return "Precision split-band or broadband sibilance compressor";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        rebuild(channels);
    }

    private void rebuild(int ch) {
        double fc = Math.min(freq.getCurrentRaw(), sampleRate * 0.45);
        bp.setSections(new float[][]{
                Dsp.rbjBandPass(sampleRate, fc, q.getFloat())
        }, Math.max(1, Math.min(2, ch)));
    }

    @Override
    public void reset() {
        bp.reset();
        sibEnv = 0f;
        fullEnv = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && maxReduction.getFloat() > 0.05f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild(ch);
        float[] sib = buf.clone();
        bp.process(sib, frames, ch);

        float threshLin = Dsp.dbToLin(threshold.getFloat());
        float maxCut = Math.max(0f, maxReduction.getFloat());
        boolean broadband = mode.getEnumIndex() == 1;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float sL = buf[idx];
            float sR = ch == 2 ? buf[idx + 1] : sL;
            float bL = sib[idx];
            float bR = ch == 2 ? sib[idx + 1] : bL;

            float sibAbs = ch == 2 ? 0.5f * (Math.abs(bL) + Math.abs(bR)) : Math.abs(bL);
            float fullAbs = ch == 2 ? 0.5f * (Math.abs(sL) + Math.abs(sR)) : Math.abs(sL);
            sibEnv += (sibAbs > sibEnv ? 0.08f : 0.003f) * (sibAbs - sibEnv);
            fullEnv += (fullAbs > fullEnv ? 0.04f : 0.0015f) * (fullAbs - fullEnv);

            float rel = sibEnv / (fullEnv + 0.001f);
            float cutDb = 0f;
            if (sibEnv > threshLin * 0.25f && rel > 0.18f) {
                float over = Math.min(1f,
                        (sibEnv - threshLin * 0.25f) / Math.max(1e-5f, threshLin) + (rel - 0.18f));
                cutDb = Math.min(maxCut, maxCut * over);
            }
            float gainLin = DspChain.fastDbToLin(-cutDb);
            if (broadband) {
                buf[idx] = sL * gainLin;
                if (ch == 2) buf[idx + 1] = sR * gainLin;
            } else {
                // Phase-coherent bandpass sibilance subtraction so deess_q
                // directly controls the width of the notched sibilant band
                // and low frequencies pass untouched.
                float cut = 1.0f - gainLin;
                buf[idx] = sL - bL * cut;
                if (ch == 2) {
                    buf[idx + 1] = sR - bR * cut;
                }
            }
        }
    }
}
