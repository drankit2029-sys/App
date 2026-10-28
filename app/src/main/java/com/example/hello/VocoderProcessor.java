package com.example.hello;

/**
 * Stage 6 channel vocoder: a logarithmically spaced band-pass filterbank
 * extracts the modulator's amplitude envelope per band and multiplies the
 * matching carrier band.
 */
final class VocoderProcessor extends AudioProcessor {

    private static final String[] CARRIER_LABELS = {"Internal Synth Saw",
            "Internal White Noise", "Right Channel"};
    private static final int[] BAND_OPTIONS = {8, 16, 20, 24, 32, 40};

    private final FxParameter carrier = addParameter(FxParameter.enumParam(
            "vc_carrier", "Carrier Source", CARRIER_LABELS, 0));
    private final FxParameter pitch = addParameter(FxParameter.intParam(
            "vc_pitch", "Carrier Pitch", " Hz", 50, 400, 1, 110));
    private final FxParameter bands = addParameter(FxParameter.enumParam(
            "vc_bands", "Number of Bands",
            new String[]{"8", "16", "20", "24", "32", "40"}, 2));
    private final FxParameter release = addParameter(FxParameter.intParam(
            "vc_release", "Envelope Release", "", 1, 120, 1, 20));
    private final FxParameter originalMix = addParameter(FxParameter.intParam(
            "vc_orig", "Original Audio Mix", "%", 0, 100, 1, 0));
    private final FxParameter noiseMix = addParameter(FxParameter.intParam(
            "vc_noise", "Noise Mix", "%", 0, 100, 1, 15));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int lastBuiltSampleRate = 0;
    private int channels = 2;
    private BiquadFilter[] bandFilters = new BiquadFilter[0];
    private BiquadFilter[] carrierFilters = new BiquadFilter[0];
    private float[] bandCenters = new float[0];
    private float[] envelopes = new float[0];
    private double sawPhase = 0;
    private long noiseState = 0x2545F4914F6CDD1DL;

    VocoderProcessor() {
    }

    @Override
    public String getId() {
        return "vocoder";
    }

    @Override
    public String getTitle() {
        return "Vocoder";
    }

    @Override
    public String getDescription() {
        return "Band-filterbank vocoder with an internal carrier";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        rebuild();
    }

    @Override
    public void reset() {
        java.util.Arrays.fill(envelopes, 0f);
        for (BiquadFilter f : bandFilters) {
            if (f != null) f.reset();
        }
        for (BiquadFilter f : carrierFilters) {
            if (f != null) f.reset();
        }
        sawPhase = 0;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && originalMix.getCurrentRaw() < 100;
    }

    private void rebuild() {
        int count = BAND_OPTIONS[Math.max(0, Math.min(BAND_OPTIONS.length - 1,
                bands.getEnumIndex()))];
        if (bandFilters.length == count && lastBuiltSampleRate == sampleRate) return;
        lastBuiltSampleRate = sampleRate;
        bandFilters = new BiquadFilter[count];
        carrierFilters = new BiquadFilter[count];
        bandCenters = new float[count];
        envelopes = new float[count];
        float lo = 80f;
        float hi = Math.min(12000f, sampleRate * 0.45f);
        for (int i = 0; i < count; i++) {
            float f = (float) (lo * Math.pow(hi / lo, i / (double) (count - 1)));
            bandCenters[i] = f;
            bandFilters[i] = new BiquadFilter();
            carrierFilters[i] = new BiquadFilter();
            float[][] sec = new float[][]{
                    Dsp.rbjBandPass(sampleRate, f, 4f)};
            bandFilters[i].setSections(sec, 1);
            carrierFilters[i].setSections(sec, 1);
        }
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        rebuild();
        int count = bandFilters.length;
        float origMix = originalMix.getCurrentRaw() / 100f;
        float noiseMixPct = noiseMix.getCurrentRaw() / 100f;
        float relCoef = 1f / Math.max(1, release.getCurrentRaw());
        double sawInc = (double) pitch.getCurrentRaw() / sampleRate;
        int carrierIdx = carrier.getEnumIndex();
        float[] probe = new float[1];
        float[] cProbe = new float[1];
        for (int f = 0; f < frames; f++) {
            float modulator = 0f;
            for (int c = 0; c < ch; c++) modulator += buf[f * ch + c];
            modulator /= ch;

            float baseCarrier;
            float white = nextNoise();
            if (carrierIdx == 1) {
                baseCarrier = white;
            } else if (carrierIdx == 2) {
                float rightOrMod = ch > 1 ? buf[f * ch + 1]
                        : (float) (buf[f * ch] * Math.cos(2.0 * Math.PI * sawPhase));
                baseCarrier = rightOrMod * (1f - noiseMixPct)
                        + white * noiseMixPct;
            } else {
                float saw = (float) (2.0 * (sawPhase - Math.floor(sawPhase)) - 1.0);
                baseCarrier = saw * (1f - noiseMixPct * 0.5f)
                        + white * (noiseMixPct * 0.5f);
            }

            float wet = 0f;
            for (int b = 0; b < count; b++) {
                probe[0] = modulator;
                bandFilters[b].process(probe, 1, 1);
                float e = Math.abs(probe[0]);
                envelopes[b] += (e - envelopes[b]) * relCoef;
                cProbe[0] = baseCarrier;
                carrierFilters[b].process(cProbe, 1, 1);
                wet += cProbe[0] * envelopes[b] * 4.0f;
            }
            wet /= Math.max(1f, (float) Math.sqrt(count));
            sawPhase += sawInc;
            if (sawPhase > 1.0) sawPhase -= 1.0;

            for (int c = 0; c < ch; c++) {
                int idx = f * ch + c;
                float original = buf[idx];
                float mixed = wet * (1f - origMix) + original * origMix;
                buf[idx] = mixed;
            }
        }
    }

    private float nextNoise() {
        noiseState = noiseState * 6364136223846793005L + 1442695040888963407L;
        int bits = (int) ((noiseState >>> 33) & 0x7FFFFFFF);
        return (bits / (float) 0x7FFFFFFF) * 2f - 1f;
    }
}
