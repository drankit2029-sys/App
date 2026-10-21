package com.example.hello;

/**
 * Stage 4 noise gate / downward expander with a hysteresis state machine
 * (closed, attack, open, hold, release), a sidechain high-pass so low
 * rumble cannot false-trigger the gate, and a configurable level reduction
 * range.
 */
final class NoiseGateExpanderProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Gate", "Downward Expander"};

    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "gate_mode", "Mode", MODE_LABELS, 0));
    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "gate_thresh", "Threshold", -96f, -6f, -42f));
    private final FxParameter hysteresis = addParameter(FxParameter.dbParam(
            "gate_hyst", "Hysteresis", 0f, 12f, 4f));
    private final FxParameter range = addParameter(FxParameter.dbParam(
            "gate_range", "Level Reduction", -100f, 0f, -24f));
    private final FxParameter ratio = addParameter(FxParameter.floatParam(
            "gate_ratio", "Ratio", ":1", 1.2f, 10f, 2.5f));
    private final FxParameter attack = addParameter(FxParameter.floatParam(
            "gate_attack", "Attack", " ms", 0.5f, 250f, 8f));
    private final FxParameter hold = addParameter(FxParameter.floatParam(
            "gate_hold", "Hold", " ms", 0f, 1000f, 60f));
    private final FxParameter release = addParameter(FxParameter.floatParam(
            "gate_release", "Release", " ms", 10f, 3000f, 220f));
    private final FxParameter sidechainHpf = addParameter(FxParameter.intParam(
            "gate_hpf", "Sidechain HPF", " Hz", 20, 2000, 10, 120));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter scFilter = new BiquadFilter();
    private DynamicsDetector.PeakHold peakHold;
    private float gainDb = 0f;
    private boolean open = false;
    private int holdCounter = 0;

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
        return "Hysteresis gate and downward expander with sidechain HPF";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        peakHold = new DynamicsDetector.PeakHold();
        rebuildSidechain();
    }

    @Override
    public void reset() {
        scFilter.reset();
        if (peakHold != null) peakHold.reset();
        gainDb = 0f;
        open = false;
        holdCounter = 0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && range.getFloat() < -0.01f;
    }

    private void rebuildSidechain() {
        float f = Math.max(20f, sidechainHpf.getCurrentRaw());
        scFilter.setSections(new float[][]{Dsp.rbjHighPass(sampleRate, f, 0.7071)},
                channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuildSidechain();
        if (peakHold == null) peakHold = new DynamicsDetector.PeakHold();

        float thresh = threshold.getFloat();
        float hyst = hysteresis.getFloat();
        float rangeDb = range.getFloat();
        boolean expander = mode.getEnumIndex() == 1;
        float expRatio = ratio.getFloat();
        float attCoef = (float) (1.0 - Math.exp(-1.0
                / (Math.max(0.0005f, attack.getFloat() * 0.001f) * sampleRate)));
        float relCoef = (float) (1.0 - Math.exp(-1.0
                / (Math.max(0.001f, release.getFloat() * 0.001f) * sampleRate)));
        float holdMs = hold.getFloat();
        int holdFrames = Math.max(0,
                (int) (holdMs * 0.001f * sampleRate));

        final int block = 32;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] side = new float[n * ch];
            System.arraycopy(buf, offset * ch, side, 0, side.length);
            scFilter.process(side, n, ch);
            float levelDb = Dsp.linToDb(peakOf(side, n, ch));
            float detectDb = peakHold.update(levelDb, n,
                    holdMs, sampleRate);

            if (!open) {
                if (detectDb > thresh + hyst) {
                    open = true;
                    holdCounter = holdFrames;
                }
            } else {
                if (detectDb < thresh - hyst) {
                    if (holdCounter > 0) {
                        holdCounter -= n;
                    } else {
                        open = false;
                    }
                } else {
                    holdCounter = holdFrames;
                }
            }

            float target;
            if (expander) {
                target = open ? 0f : DynamicsDetector.expanderGainDb(
                        detectDb, thresh, rangeDb, expRatio);
            } else {
                target = open ? 0f : rangeDb;
            }
            float step = (target - gainDb) / n;
            for (int f = 0; f < n; f++) {
                gainDb += step;
                float g = Dsp.dbToLin(gainDb);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) buf[idx + c] *= g;
            }
            offset += n;
        }
    }

    private static float peakOf(float[] buf, int frames, int ch) {
        float peak = 0f;
        for (int i = 0; i < frames * ch; i++) {
            float a = Math.abs(buf[i]);
            if (a > peak) peak = a;
        }
        return peak;
    }
}
