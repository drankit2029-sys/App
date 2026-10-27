package com.example.hello;

/**
 * Stage 4 split-band de-esser: a band-pass sidechain extracts sibilance
 * energy, compares it (in dB) against the broadband energy and attenuates
 * the sibilant band when the relative ratio crosses the threshold.
 *
 * <p>Because the detection is relative, quiet whispers and loud shouts are
 * de-essed with the same strength.
 */
final class DeEsserProcessor extends AudioProcessor {

    private static final String[] MODE_LABELS = {"Split-Band", "Wideband"};

    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "deess_thresh", "Threshold", -48f, 0f, -18f));
    private final FxParameter frequency = addParameter(FxParameter.intParam(
            "deess_freq", "Center Frequency", " Hz", 3500, 12000, 100, 6800));
    private final FxParameter q = addParameter(FxParameter.floatParam(
            "deess_q", "Bandwidth / Q", "", 0.5f, 6f, 1.6f));
    private final FxParameter maxCut = addParameter(FxParameter.dbParam(
            "deess_max", "Max Reduction", 0f, 24f, 10f));
    private final FxParameter mode = addParameter(FxParameter.enumParam(
            "deess_mode", "Mode", MODE_LABELS, 0));
    private final FxParameter listen = addParameter(FxParameter.boolParam(
            "deess_listen", "Listen to Sidechain", false));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private BiquadFilter band = new BiquadFilter();
    private BiquadFilter splitLow = new BiquadFilter();
    private float reductionDb = 0f;

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
        return "Relative sibilance detection across the 3.5-12 kHz band";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        band.reset();
        splitLow.reset();
        reductionDb = 0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    private void rebuild() {
        float f = frequency.getCurrentRaw();
        float qq = Math.max(0.3f, q.getFloat());
        band.setSections(new float[][]{Dsp.rbjBandPass(sampleRate, f, qq)},
                channels);
        splitLow.setSections(new float[][]{Dsp.rbjLowPass(sampleRate, f, 0.7071)},
                channels);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        boolean wideband = mode.getEnumIndex() == 1;
        float thresh = threshold.getFloat();
        float maxCutDb = maxCut.getFloat();

        final int block = 64;
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(block, frames - offset);
            float[] side = new float[n * ch];
            System.arraycopy(buf, offset * ch, side, 0, side.length);
            band.process(side, n, ch);

            float[] lowBand = new float[n * ch];
            System.arraycopy(buf, offset * ch, lowBand, 0, lowBand.length);
            splitLow.process(lowBand, n, ch);

            double fullEnergy = 0;
            double bandEnergy = 0;
            for (int i = 0; i < n * ch; i++) {
                float orig = buf[offset * ch + i];
                fullEnergy += (double) orig * orig;
                bandEnergy += (double) side[i] * side[i];
            }
            float fullDb = Dsp.linToDb((float) Math.sqrt(fullEnergy / (n * ch)));
            float bandDb = Dsp.linToDb((float) Math.sqrt(bandEnergy / (n * ch)));
            float relative = bandDb - fullDb;

            float target = 0f;
            if (relative > thresh) {
                float over = relative - thresh;
                float amount = Math.min(maxCutDb, over * 0.5f);
                target = -amount;
            }
            float step = (target - reductionDb) / n;
            for (int f = 0; f < n; f++) {
                reductionDb += step;
                float g = Dsp.dbToLin(reductionDb);
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    if (listen.getBoolean()) {
                        buf[idx + c] = side[f * ch + c];
                    } else if (wideband) {
                        buf[idx + c] *= g;
                    } else {
                        float low = lowBand[f * ch + c];
                        float high = buf[idx + c] - low;
                        buf[idx + c] = low + high * g;
                    }
                }
            }
            offset += n;
        }
    }
}
