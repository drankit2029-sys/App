package com.example.hello;

import java.util.Arrays;

/**
 * Stage 6 Studio Reverb (Jezar Freeverb topology): 8 parallel low-pass-
 * feedback comb filters feeding 4 series all-pass diffusers per channel, with
 * stereo spread, pre-delay (0..200 ms), high-frequency damping, room size,
 * stereo width, wet/dry gains and wet-only mode. Comb and all-pass delay
 * lengths scale proportionally with the session sample rate.
 */
final class FreeverbReverbProcessor extends AudioProcessor {

    private static final int[] COMB_TUNING = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS_TUNING = {556, 441, 341, 225};
    private static final int STEREO_SPREAD = 23;

    private final FxParameter roomSize = addParameter(FxParameter.intParam(
            "rv_size", "Room Size", "%", 0, 100, 5, 55));
    private final FxParameter preDelay = addParameter(FxParameter.intParam(
            "rv_predelay", "Pre-Delay", " ms", 0, 200, 5, 15));
    private final FxParameter reverberance = addParameter(FxParameter.intParam(
            "rv_reverb", "Reverberance", "%", 0, 100, 5, 60));
    private final FxParameter damping = addParameter(FxParameter.intParam(
            "rv_damp", "HF Damping", "%", 0, 100, 5, 50));
    private final FxParameter toneLow = addParameter(FxParameter.intParam(
            "rv_tonelow", "Tone Low", "%", 0, 100, 5, 100));
    private final FxParameter toneHigh = addParameter(FxParameter.intParam(
            "rv_tonehigh", "Tone High", "%", 0, 100, 5, 100));
    private final FxParameter wetGain = addParameter(FxParameter.dbParam(
            "rv_wet", "Wet Gain", -24f, 6f, -6f));
    private final FxParameter dryGain = addParameter(FxParameter.dbParam(
            "rv_dry", "Dry Gain", -24f, 6f, 0f));
    private final FxParameter width = addParameter(FxParameter.intParam(
            "rv_width", "Stereo Width", "%", 0, 100, 5, 100));
    private final FxParameter wetOnly = addParameter(FxParameter.boolParam(
            "rv_wetonly", "Wet Only", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float[][][] combBuf;
    private int[][] combPos;
    private float[][] combFilterStore;
    private float[][][] allpassBuf;
    private int[][] allpassPos;
    private float[] preBuf;
    private int prePos = 0;
    private float lpIn = 0f, hpIn = 0f;

    FreeverbReverbProcessor() {
        allocateTank(DspChain.SAMPLE_RATE);
    }

    private void allocateTank(int sr) {
        double scale = Math.max(8000, sr) / 44100.0;
        combBuf = new float[2][COMB_TUNING.length][];
        combPos = new int[2][COMB_TUNING.length];
        combFilterStore = new float[2][COMB_TUNING.length];
        allpassBuf = new float[2][ALLPASS_TUNING.length][];
        allpassPos = new int[2][ALLPASS_TUNING.length];
        int spread = Math.max(1, (int) Math.round(STEREO_SPREAD * scale));
        for (int c = 0; c < 2; c++) {
            int sp = c == 1 ? spread : 0;
            for (int i = 0; i < COMB_TUNING.length; i++) {
                int len = Math.max(16, (int) Math.round(COMB_TUNING[i] * scale) + sp);
                combBuf[c][i] = new float[len];
            }
            for (int i = 0; i < ALLPASS_TUNING.length; i++) {
                int len = Math.max(16, (int) Math.round(ALLPASS_TUNING[i] * scale) + sp);
                allpassBuf[c][i] = new float[len];
            }
        }
        int maxPre = Math.max(256, (int) Math.ceil(0.205 * Math.max(8000, sr)));
        preBuf = new float[maxPre];
        prePos = 0;
    }

    @Override
    public String getId() {
        return "reverb";
    }

    @Override
    public String getTitle() {
        return "Studio Reverb";
    }

    @Override
    public String getDescription() {
        return "Freeverb 8-comb / 4-allpass stereo room simulator with pre-delay";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.SPATIAL;
    }

    @Override
    public int getTailFrames() {
        return sampleRate * 2;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0 && sampleRate != this.sampleRate) {
            this.sampleRate = sampleRate;
            allocateTank(this.sampleRate);
        }
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < COMB_TUNING.length; i++) {
                Arrays.fill(combBuf[c][i], 0f);
                combPos[c][i] = 0;
                combFilterStore[c][i] = 0f;
            }
            for (int i = 0; i < ALLPASS_TUNING.length; i++) {
                Arrays.fill(allpassBuf[c][i], 0f);
                allpassPos[c][i] = 0;
            }
        }
        Arrays.fill(preBuf, 0f);
        prePos = 0;
        lpIn = hpIn = 0f;
    }

    @Override
    public boolean handlesOwnWetDryMix() {
        return true;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float room = 0.70f + 0.28f * (0.5f * (roomSize.getCurrentRaw() + reverberance.getCurrentRaw()) / 100f);
        float damp = 0.05f + 0.65f * (damping.getCurrentRaw() / 100f);
        float damp1 = damp;
        float damp2 = 1f - damp;
        float wet = Dsp.dbToLin(wetGain.getFloat()) * 0.08f
                * (0.5f + 0.5f * (reverberance.getCurrentRaw() / 100f));
        float dry = wetOnly.getBoolean()
                ? (dryGain.getCurrentRaw() != dryGain.defaultRaw
                        ? Dsp.dbToLin(dryGain.getFloat()) * 0.08f : 0f)
                : Dsp.dbToLin(dryGain.getFloat());
        float w = width.getCurrentRaw() / 100f;
        float w1 = 0.5f * (1f + w);
        float w2 = 0.5f * (1f - w);
        int preFrames = Math.min(preBuf.length - 1,
                Math.max(0, (int) Math.round(preDelay.getCurrentRaw() * 0.001 * sampleRate)));
        float lowCut = (100 - toneLow.getCurrentRaw()) / 100f;
        float highCut = toneHigh.getCurrentRaw() / 100f;

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float l = buf[idx];
            float r = ch == 2 ? buf[idx + 1] : l;
            float mono = 0.5f * (l + r);

            float preOut;
            if (preFrames > 0) {
                if (prePos >= preFrames) prePos %= preFrames;
                preOut = preBuf[prePos];
                preBuf[prePos] = mono;
                prePos++;
                if (prePos >= preFrames) prePos = 0;
            } else {
                preOut = mono;
            }

            lpIn += (0.15f + 0.75f * highCut) * (preOut - lpIn);
            hpIn += (0.01f + 0.08f * lowCut) * (lpIn - hpIn);
            float input = lpIn - hpIn * lowCut;

            float outL = 0f, outR = 0f;
            for (int i = 0; i < COMB_TUNING.length; i++) {
                float[] cbL = combBuf[0][i];
                int pL = combPos[0][i];
                float yL = cbL[pL];
                combFilterStore[0][i] = yL * damp2 + combFilterStore[0][i] * damp1;
                cbL[pL] = input + combFilterStore[0][i] * room;
                if (++pL >= cbL.length) pL = 0;
                combPos[0][i] = pL;
                outL += yL;

                float[] cbR = combBuf[1][i];
                int pR = combPos[1][i];
                float yR = cbR[pR];
                combFilterStore[1][i] = yR * damp2 + combFilterStore[1][i] * damp1;
                cbR[pR] = input + combFilterStore[1][i] * room;
                if (++pR >= cbR.length) pR = 0;
                combPos[1][i] = pR;
                outR += yR;
            }

            for (int i = 0; i < ALLPASS_TUNING.length; i++) {
                float[] abL = allpassBuf[0][i];
                int pL = allpassPos[0][i];
                float bL = abL[pL];
                float nL = -outL + bL;
                abL[pL] = outL + bL * 0.5f;
                if (++pL >= abL.length) pL = 0;
                allpassPos[0][i] = pL;
                outL = nL;

                float[] abR = allpassBuf[1][i];
                int pR = allpassPos[1][i];
                float bR = abR[pR];
                float nR = -outR + bR;
                abR[pR] = outR + bR * 0.5f;
                if (++pR >= abR.length) pR = 0;
                allpassPos[1][i] = pR;
                outR = nR;
            }

            float wetL = (outL * w1 + outR * w2) * wet;
            float wetR = (outR * w1 + outL * w2) * wet;
            if (ch == 2) {
                buf[idx] = l * dry + wetL;
                buf[idx + 1] = r * dry + wetR;
            } else {
                // On mono, blend the stereo-spread right tank with width weight
                // so rv_width shapes mono reverb density and stereo phase decorrelation.
                buf[idx] = l * dry + (outL * (0.5f + 0.35f * w) + outR * (0.5f - 0.20f * w)) * wet;
            }
        }
    }
}
