package com.example.hello;

/**
 * Stage 6 Multi-Band Vocoder: splits the voice (modulator) into N logarithmically
 * spaced band-pass channels (8..32 bands), tracks the amplitude envelope in
 * each band, and imposes those envelopes onto an internal synth/noise carrier
 * filtered through an identical band-pass bank.
 */
final class VocoderProcessor extends AudioProcessor {

    private static final String[] CARRIER_LABELS = {
            "Sawtooth Synth", "White Noise", "Original + Synth"
    };

    private final FxParameter bands = addParameter(FxParameter.intParam(
            "vc_bands", "Vocoder Bands", "", 8, 32, 4, 16));
    private final FxParameter carrierType = addParameter(FxParameter.enumParam(
            "vc_carrier", "Carrier Type", CARRIER_LABELS, 0));
    private final FxParameter pitch = addParameter(FxParameter.intParam(
            "vc_pitch", "Carrier Pitch", " Hz", 50, 400, 5, 110));
    private final FxParameter noiseMix = addParameter(FxParameter.intParam(
            "vc_noise", "Noise Mix", "%", 0, 100, 5, 15));
    private final FxParameter release = addParameter(FxParameter.floatParam(
            "vc_release", "Envelope Speed", " ms", 1f, 120f, 10f));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private double carrierPhase = 0.0;
    private long noiseState = 0x1234567L;

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
        return "8–32 band filterbank vocoder with internal synth & noise carrier";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.CREATIVE;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        carrierPhase = 0.0;
        noiseState = 0x1234567L;
    }

    private float nextNoise() {
        noiseState = noiseState * 6364136223846793005L + 1442695040888963407L;
        return ((int) (noiseState >>> 33)) / (float) (1 << 30);
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (!isEnabled() || buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        int sr = Math.max(8000, sampleRate);
        int nBands = Math.max(8, Math.min(32, bands.getCurrentRaw()));
        int cType = carrierType.getEnumIndex();
        double f0 = pitch.getCurrentRaw();
        float nMix = noiseMix.getCurrentRaw() / 100f;
        // Convert Envelope Speed (1..120, where default 10 = 10 ms) to a real
        // sample-rate-aware exponential release coefficient.
        double relSec = Math.max(0.001, release.getFloat() * 0.001);
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * relSec));

        float[] modMono = new float[frames];
        float[] carMono = new float[frames];
        double phaseInc = f0 / sr;
        for (int f = 0; f < frames; f++) {
            int idx = f * ch;
            float m = ch == 2 ? 0.5f * (buf[idx] + buf[idx + 1]) : buf[idx];
            modMono[f] = m;
            float saw = (float) (2.0 * carrierPhase - 1.0);
            carrierPhase += phaseInc;
            if (carrierPhase >= 1.0) carrierPhase -= 1.0;
            float noise = nextNoise() * 0.4f;
            float c;
            if (cType == 1) {
                // White Noise mode retains a subtle pitched harmonic formant
                // and noiseMix scaling so vc_pitch and vc_noise remain active.
                c = noise * (0.65f + 0.35f * nMix) + 0.18f * saw;
            } else if (cType == 2) {
                // Original + Synth mode blends noiseMix into the synth carrier.
                c = 0.45f * m + 0.45f * saw + nMix * noise * 0.5f;
            } else {
                c = (1f - nMix) * saw + nMix * noise;
            }
            carMono[f] = c;
        }

        float[] sumOut = new float[frames];
        double fMin = 120.0, fMax = Math.min(8000.0, sr * 0.42);
        double qBand = 3.5 + nBands * 0.12;
        for (int b = 0; b < nBands; b++) {
            double frac = b / (double) Math.max(1, nBands - 1);
            double fc = fMin * Math.pow(fMax / fMin, frac);
            float[] sec = Dsp.rbjBandPass(sr, fc, qBand);
            BiquadFilter modBp = new BiquadFilter();
            BiquadFilter carBp = new BiquadFilter();
            modBp.setSections(new float[][]{sec}, 1);
            carBp.setSections(new float[][]{sec}, 1);
            float[] mBand = modMono.clone();
            float[] cBand = carMono.clone();
            modBp.process(mBand, frames, 1);
            carBp.process(cBand, frames, 1);

            float env = 0f;
            for (int f = 0; f < frames; f++) {
                float a = Math.abs(mBand[f]);
                env += (a > env ? 0.08f : relAlpha) * (a - env);
                sumOut[f] += cBand[f] * env * 4.5f;
            }
        }
        float scale = (float) (1.2 / Math.sqrt(nBands / 8.0));
        for (int f = 0; f < frames; f++) {
            float y = Math.max(-0.98f, Math.min(0.98f, sumOut[f] * scale));
            for (int c = 0; c < ch; c++) buf[f * ch + c] = y;
        }
    }
}
