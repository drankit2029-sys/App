package com.example.hello;

/**
 * Stage 5 plosive and breath suppressor: a dynamic low-shelf filter that
 * only engages when the sub-140 Hz energy exceeds the mid-band vowel
 * energy, so chest voice stays intact while 'P' and 'B' mic blasts are
 * tamed.
 */
final class PlosiveBreathSuppressorProcessor extends AudioProcessor {

    private final FxParameter sensitivity = addParameter(FxParameter.intParam(
            "plosive_sens", "Plosive Cut Sensitivity", "%", 0, 100, 1, 50));
    private final FxParameter breath = addParameter(FxParameter.dbParam(
            "plosive_breath", "Breath Attenuation", 0f, 24f, 8f));
    private final FxParameter lowFreq = addParameter(FxParameter.intParam(
            "plosive_freq", "Plosive Band", " Hz", 60, 200, 10, 120));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter lowBand = new BiquadFilter();
    private BiquadFilter midBand = new BiquadFilter();
    private float gainDb = 0f;

    PlosiveBreathSuppressorProcessor() {
    }

    @Override
    public String getId() {
        return "plosive";
    }

    @Override
    public String getTitle() {
        return "Plosive & Breath Suppressor";
    }

    @Override
    public String getDescription() {
        return "Dynamic sub-140 Hz plosive tamer with breath attenuation";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        lowBand.reset();
        midBand.reset();
        gainDb = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (sensitivity.getCurrentRaw() > 0
                || breath.getFloat() > 0.01f);
    }

    private void rebuild() {
        float f = lowFreq.getCurrentRaw();
        lowBand.setSections(new float[][]{Dsp.rbjLowPass(sampleRate, f, 0.7071)},
                channels);
        midBand.setSections(new float[][]{Dsp.rbjBandPass(sampleRate, 1000, 1.0f)},
                channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        float sens = sensitivity.getCurrentRaw() / 100f;
        float breathDb = breath.getFloat();

        final int block = 64;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] low = new float[n * ch];
            float[] mid = new float[n * ch];
            System.arraycopy(buf, offset * ch, low, 0, low.length);
            System.arraycopy(buf, offset * ch, mid, 0, mid.length);
            lowBand.process(low, n, ch);
            midBand.process(mid, n, ch);
            double lowE = 0;
            double midE = 0;
            for (int i = 0; i < n * ch; i++) {
                lowE += (double) low[i] * low[i];
                midE += (double) mid[i] * mid[i];
            }
            float lowDb = Dsp.linToDb((float) Math.sqrt(lowE / (n * ch)));
            float midDb = Dsp.linToDb((float) Math.sqrt(midE / (n * ch)));

            float target = 0f;
            float excess = lowDb - midDb - 8f;
            if (excess > 0f) {
                target = -Math.min(24f, excess) * sens;
            }
            if (breathDb > 0.01f && midDb < -50f) {
                target = Math.min(target, -breathDb);
            }
            float step = (target - gainDb) / n;
            for (int f = 0; f < n; f++) {
                gainDb += step;
                float g = Dsp.dbToLin(gainDb);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    int local = f * ch + c;
                    buf[idx + c] = buf[idx + c] * (1f - sens)
                            + low[local] * sens * g;
                }
            }
            offset += n;
        }
    }
}
