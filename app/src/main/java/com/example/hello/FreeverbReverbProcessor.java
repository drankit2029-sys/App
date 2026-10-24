package com.example.hello;

/**
 * Stage 6 studio reverb: a Schroeder-Moorer / Freeverb-style network with
 * eight parallel low-pass feedback comb filters, four series all-pass
 * diffusers, a pre-delay line, damping and a mid/side width mixer.
 *
 * <p>The comb delays are mutually prime so the tail does not build obvious
 * resonances, and the right channel is offset by 23 samples to widen the
 * stereo image.
 */
final class FreeverbReverbProcessor extends AudioProcessor {

    private static final int COMBS = 8;
    private static final int ALLPASS = 4;
    private static final int STEREO_SPREAD = 23;
    private static final int[] COMB_TUNING = {
            1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS_TUNING = {556, 441, 341, 225};

    private final FxParameter roomSize = addParameter(FxParameter.intParam(
            "rv_size", "Room Size", "%", 0, 100, 1, 55));
    private final FxParameter preDelay = addParameter(FxParameter.intParam(
            "rv_predelay", "Pre-Delay", " ms", 0, 200, 1, 15));
    private final FxParameter decay = addParameter(FxParameter.intParam(
            "rv_decay", "Reverberance", "%", 0, 100, 1, 50));
    private final FxParameter damping = addParameter(FxParameter.intParam(
            "rv_damping", "Damping", "%", 0, 100, 1, 50));
    private final FxParameter toneLow = addParameter(FxParameter.intParam(
            "rv_tonelo", "Tone Low", "%", -100, 100, 1, 0));
    private final FxParameter toneHigh = addParameter(FxParameter.intParam(
            "rv_tonehi", "Tone High", "%", -100, 100, 1, 0));
    private final FxParameter wetGain = addParameter(FxParameter.dbParam(
            "rv_wet", "Wet Gain", -36f, 6f, -8f));
    private final FxParameter dryGain = addParameter(FxParameter.dbParam(
            "rv_dry", "Dry Gain", -36f, 6f, 0f));
    private final FxParameter width = addParameter(FxParameter.intParam(
            "rv_width", "Stereo Width", "%", 0, 100, 1, 100));
    private final FxParameter wetOnly = addParameter(FxParameter.boolParam(
            "rv_wetonly", "Wet Only", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private Comb[] combL = new Comb[COMBS];
    private Comb[] combR = new Comb[COMBS];
    private Allpass[] apL = new Allpass[ALLPASS];
    private Allpass[] apR = new Allpass[ALLPASS];
    private float[] preBuffer = new float[0];
    private int prePos = 0;
    private int preFrames = 0;
    private BiquadFilter toneLo = new BiquadFilter();
    private BiquadFilter toneHi = new BiquadFilter();
    private final float[] toneScratch = new float[2];

    FreeverbReverbProcessor() {
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
        return "Freeverb-style stereo reverb with damping and width";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.SPATIAL;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        for (int i = 0; i < COMBS; i++) {
            combL[i].reset();
            combR[i].reset();
        }
        for (int i = 0; i < ALLPASS; i++) {
            apL[i].reset();
            apR[i].reset();
        }
        java.util.Arrays.fill(preBuffer, 0f);
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && wetGain.getFloat() > -35.9f;
    }

    @Override
    public int getTailFrames() {
        // 4 seconds of tail at 48 kHz is enough for the longest decay.
        return (int) (sampleRate * 4.0);
    }

    private void rebuild() {
        float size = roomSize.getCurrentRaw() / 100f;
        float scale = 0.7f + size * 1.6f;
        int spread = Math.max(1, Math.round(STEREO_SPREAD * scale));
        for (int i = 0; i < COMBS; i++) {
            int lenL = Math.max(8, (int) (COMB_TUNING[i] * scale));
            int lenR = lenL + spread;
            if (combL[i] == null) {
                combL[i] = new Comb(lenL);
                combR[i] = new Comb(lenR);
            } else {
                combL[i].resize(lenL);
                combR[i].resize(lenR);
            }
        }
        for (int i = 0; i < ALLPASS; i++) {
            int lenL = Math.max(8, (int) (ALLPASS_TUNING[i] * scale));
            int lenR = lenL + spread;
            if (apL[i] == null) {
                apL[i] = new Allpass(lenL);
                apR[i] = new Allpass(lenR);
            } else {
                apL[i].resize(lenL);
                apR[i].resize(lenR);
            }
        }
        preFrames = Math.max(0, (int) (preDelay.getCurrentRaw() * 0.001f * sampleRate));
        if (preBuffer.length < preFrames * 2) {
            preBuffer = new float[Math.max(1, preFrames * 2)];
        }
        float lo = toneLow.getCurrentRaw() / 100f;
        float hi = toneHigh.getCurrentRaw() / 100f;
        if (lo != 0f) {
            toneLo.setSections(new float[][]{
                    Dsp.rbjLowShelf(sampleRate, 250f, 0.7071, lo * 12f)}, 2);
        } else {
            toneLo.setSections(new float[0][], channels);
        }
        if (hi != 0f) {
            toneHi.setSections(new float[][]{
                    Dsp.rbjHighShelf(sampleRate, 4000f, 0.7071, hi * 12f)}, 2);
        } else {
            toneHi.setSections(new float[0][], channels);
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();

        float damp = damping.getCurrentRaw() / 100f;
        float decayPct = decay.getCurrentRaw() / 100f;
        float feedback = 0.7f + decayPct * 0.28f;
        float wet = Dsp.dbToLin(wetGain.getFloat());
        float dry = wetOnly.getBoolean() ? 0f : Dsp.dbToLin(dryGain.getFloat());
        float widthPct = width.getCurrentRaw() / 100f;

        for (int f = 0; f < frames; f++) {
            float inL = buf[f * ch];
            float inR = ch > 1 ? buf[f * ch + 1] : inL;

            // Pre-delay.
            float pL = inL;
            float pR = inR;
            if (preFrames > 0 && preBuffer.length >= preFrames * 2) {
                pL = preBuffer[prePos * 2];
                pR = preBuffer[prePos * 2 + 1];
                preBuffer[prePos * 2] = inL;
                preBuffer[prePos * 2 + 1] = inR;
                prePos = (prePos + 1) % preFrames;
            }

            float wetL = 0f;
            float wetR = 0f;
            for (int i = 0; i < COMBS; i++) {
                wetL += combL[i].process(pL, feedback, damp);
                wetR += combR[i].process(pR, feedback, damp);
            }
            wetL /= COMBS;
            wetR /= COMBS;
            for (int i = 0; i < ALLPASS; i++) {
                wetL = apL[i].process(wetL);
                wetR = apR[i].process(wetR);
            }

            // Mid/side width control.
            float mid = (wetL + wetR) * 0.5f;
            float side = (wetL - wetR) * 0.5f * widthPct;
            wetL = mid + side;
            wetR = mid - side;

            if (toneLo.sectionCount() > 0 || toneHi.sectionCount() > 0) {
                toneScratch[0] = wetL;
                toneScratch[1] = wetR;
                toneLo.process(toneScratch, 1, 2);
                toneHi.process(toneScratch, 1, 2);
                wetL = toneScratch[0];
                wetR = toneScratch[1];
            }

            buf[f * ch] = dry * inL + wet * wetL;
            if (ch > 1) buf[f * ch + 1] = dry * inR + wet * wetR;
        }
    }

    /** Low-pass feedback comb filter. */
    private static final class Comb {
        private float[] buffer = new float[0];
        private int pos = 0;
        private float store = 0f;

        Comb(int len) {
            resize(len);
        }

        void resize(int len) {
            if (buffer.length != len) {
                buffer = new float[len];
                pos = 0;
                store = 0f;
            }
        }

        void reset() {
            java.util.Arrays.fill(buffer, 0f);
            pos = 0;
            store = 0f;
        }

        float process(float input, float feedback, float damp) {
            float output = buffer[pos];
            store = output * (1f - damp) + store * damp;
            buffer[pos] = input + store * feedback;
            pos = (pos + 1) % buffer.length;
            return output;
        }
    }

    /** Series all-pass diffuser. */
    private static final class Allpass {
        private float[] buffer = new float[0];
        private int pos = 0;

        Allpass(int len) {
            resize(len);
        }

        void resize(int len) {
            if (buffer.length != len) {
                buffer = new float[len];
                pos = 0;
            }
        }

        void reset() {
            java.util.Arrays.fill(buffer, 0f);
            pos = 0;
        }

        float process(float input) {
            float buffered = buffer[pos];
            float output = -input + buffered;
            buffer[pos] = input + buffered * 0.5f;
            pos = (pos + 1) % buffer.length;
            return output;
        }
    }
}
