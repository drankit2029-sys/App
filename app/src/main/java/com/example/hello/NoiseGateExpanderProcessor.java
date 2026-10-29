package com.example.hello;

/**
 * Stage 4 Noise Gate / Downward Expander with Open Threshold, Close
 * Hysteresis, Ratio (or hard Gate), Range/Floor dB, Attack, Hold, Release,
 * and a Sidechain High-Pass filter so low-frequency rumble cannot false-open
 * the gate.
 */
final class NoiseGateExpanderProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Gate", "Expander"};

    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "gate_mode", "Mode", MODE_LABELS, 1));
    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "gate_thresh", "Threshold", -96f, -6f, -42f));
    private final FxParameter hysteresis = addParameter(FxParameter.dbParam(
            "gate_hyst", "Hysteresis", 0f, 12f, 4f));
    private final FxParameter ratio = addParameter(FxParameter.floatParam(
            "gate_ratio", "Ratio", ":1", 1.5f, 20f, 4.0f));
    private final FxParameter range = addParameter(FxParameter.dbParam(
            "gate_range", "Range / Floor", -90f, 0f, -24f));
    private final FxParameter attack = addParameter(FxParameter.intParam(
            "gate_att", "Attack", " ms", 1, 100, 1, 3));
    private final FxParameter hold = addParameter(FxParameter.intParam(
            "gate_hold", "Hold", " ms", 0, 1000, 10, 40));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "gate_rel", "Release", " ms", 10, 2000, 10, 180));
    private final FxParameter scHpf = addParameter(FxParameter.intParam(
            "gate_schpf", "Sidechain HPF", " Hz", 20, 600, 10, 120));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private final BiquadFilter scFilter = new BiquadFilter();
    private float env = 0f;
    private float currentGain = 1.0f;
    private int holdLeft = 0;
    private boolean open = false;

    NoiseGateExpanderProcessor() {
    }

    @Override
    public String getId() {
        return "gateexp";
    }

    @Override
    public String getTitle() {
        return "Gate / Expander";
    }

    @Override
    public String getDescription() {
        return "Hysteresis noise gate & downward expander with sidechain HPF";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        rebuildSidechain(channels);
    }

    private void rebuildSidechain(int ch) {
        int fc = scHpf.getCurrentRaw();
        if (fc > 20) {
            scFilter.setSections(new float[][]{
                    Dsp.rbjHighPass(sampleRate, Math.min(fc, sampleRate * 0.45), 0.7071)
            }, Math.max(1, Math.min(2, ch)));
        } else {
            scFilter.setSections(new float[0][], 1);
        }
    }

    @Override
    public void reset() {
        scFilter.reset();
        env = 0f;
        currentGain = 1.0f;
        holdLeft = 0;
        open = false;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        rebuildSidechain(ch);

        float[] sc = buf.clone();
        if (scFilter.sectionCount() > 0) {
            scFilter.process(sc, frames, ch);
        }

        float openDb = threshold.getFloat();
        float closeDb = openDb - Math.max(0f, hysteresis.getFloat());
        float openLin = Dsp.dbToLin(openDb);
        float closeLin = Dsp.dbToLin(closeDb);
        float floorLin = Dsp.dbToLin(range.getFloat());
        float r = mode.getEnumIndex() == 0 ? 20f : Math.max(1.2f, ratio.getFloat());
        int holdSamples = (int) (sr * (hold.getCurrentRaw() / 1000.0f));
        float attAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * Math.max(0.0005f, attack.getCurrentRaw() / 1000.0f)));
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * Math.max(0.005f, release.getCurrentRaw() / 1000.0f)));

        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float peak = Math.abs(sc[idx]);
            if (ch == 2) peak = Math.max(peak, Math.abs(sc[idx + 1]));
            env += (peak > env ? 0.05f : 0.002f) * (peak - env);

            if (peak >= openLin) {
                open = true;
                holdLeft = holdSamples;
            } else if (open && env < closeLin) {
                if (holdLeft > 0) holdLeft--;
                else open = false;
            }

            float want;
            if (open) {
                // An open gate is unity gain; never partially attenuate speech
                // that is above the user's Threshold.
                want = 1.0f;
            } else {
                float envDb = DspChain.fastLinToDb(env + 1e-7f);
                float under = Math.max(0f, openDb - envDb);
                float attenDb = -under * (r - 1.0f);
                want = Math.max(floorLin, DspChain.fastDbToLin(attenDb));
            }
            currentGain += (want > currentGain ? attAlpha : relAlpha) * (want - currentGain);
            for (int c = 0; c < ch; c++) buf[idx + c] *= currentGain;
        }
    }
}
