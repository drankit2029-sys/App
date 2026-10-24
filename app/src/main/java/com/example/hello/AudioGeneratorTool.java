package com.example.hello;

/**
 * Stage 7 audio generator: renders tone, chirp, white / pink / brown noise,
 * silence, a rhythm track, a plucked string or DTMF tones into the region.
 *
 * <p>The generator writes over the selected region, so it behaves like
 * Audacity's Generate command rather than a filter.
 */
final class AudioGeneratorTool extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Tone & Chirp", "Noise",
            "Silence", "Rhythm Track", "DTMF"};
    private static final String[] WAVE_LABELS = {"Sine", "Square", "Sawtooth",
            "Triangle", "Square No-Alias"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "gen_type", "Generator", TYPE_LABELS, 0));
    private final FxParameter wave = addParameter(FxParameter.enumParam(
            "gen_wave", "Waveform", WAVE_LABELS, 0));
    private final FxParameter startFreq = addParameter(FxParameter.intParam(
            "gen_start", "Start Frequency", " Hz", 20, 20000, 10, 1000));
    private final FxParameter endFreq = addParameter(FxParameter.intParam(
            "gen_end", "End Frequency", " Hz", 20, 20000, 10, 1000));
    private final FxParameter amplitude = addParameter(FxParameter.intParam(
            "gen_amp", "Amplitude", "%", 0, 100, 1, 50));
    private final FxParameter duration = addParameter(FxParameter.floatParam(
            "gen_dur", "Duration", " s", 0.1f, 600f, 5f));
    private final FxParameter tempo = addParameter(FxParameter.intParam(
            "gen_bpm", "Tempo", " BPM", 30, 300, 1, 120));
    private final FxParameter sequence = addParameter(FxParameter.intParam(
            "gen_seq", "DTMF Sequence", "", 0, 15, 1, 5));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private long rng = 0xDEADBEEFCAFEBABEL;

    AudioGeneratorTool() {
    }

    @Override
    public String getId() {
        return "generator";
    }

    @Override
    public String getTitle() {
        return "Audio Generator";
    }

    @Override
    public String getDescription() {
        return "Tone, chirp, noise, rhythm or DTMF generator";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.ANALYZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        rng = 0xDEADBEEFCAFEBABEL;
    }

    @Override
    public boolean isOfflineOnly() {
        return true;
    }

    /**
     * Offline-only effect: it is rendered through
     * {@link #processOfflineRegion} instead of the realtime chain.
     */
    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // No realtime path: the realtime rack skips offline-only processors.
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        float[] out = new float[frames * channels];
        int typeIdx = type.getEnumIndex();
        float amp = amplitude.getCurrentRaw() / 100f;
        switch (typeIdx) {
            case 0: // Tone & chirp
                renderTone(out, frames, channels, amp);
                break;
            case 1: // Noise
                renderNoise(out, frames, channels, amp);
                break;
            case 2: // Silence
                break;
            case 3: // Rhythm track
                renderRhythm(out, frames, channels, amp);
                break;
            default: // DTMF
                renderDtmf(out, frames, channels, amp);
                break;
        }
        return out;
    }

    private void renderTone(float[] out, int frames, int ch, float amp) {
        double phase = 0;
        double f0 = startFreq.getCurrentRaw();
        double f1 = endFreq.getCurrentRaw();
        for (int f = 0; f < frames; f++) {
            double u = frames <= 1 ? 0 : f / (double) (frames - 1);
            double freq = f0 + (f1 - f0) * u;
            phase += 2 * Math.PI * freq / sampleRate;
            float s = waveSample(wave.getEnumIndex(), phase);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s * amp;
        }
    }

    private void renderNoise(float[] out, int frames, int ch, float amp) {
        // The noise type is taken from the waveform selector: square stands
        // for white, triangle for pink and sawtooth for brown.
        int waveIdx = wave.getEnumIndex();
        float pinkB0 = 0f;
        float pinkB1 = 0f;
        float brown = 0f;
        for (int f = 0; f < frames; f++) {
            float white = nextNoise();
            float s;
            if (waveIdx == 0 || waveIdx == 1) {
                s = white;
            } else if (waveIdx == 2) {
                // Pink (Paul Kellet's economy filter).
                pinkB0 = 0.99765f * pinkB0 + white * 0.0990460f;
                pinkB1 = 0.96300f * pinkB1 + white * 0.2965164f;
                s = (pinkB0 + pinkB1 + white * 0.1848f) * 0.4f;
            } else {
                // Brown (leaky integrator).
                brown = (brown + white * 0.02f) * 0.998f;
                s = brown * 3.5f;
            }
            for (int c = 0; c < ch; c++) out[f * ch + c] = s * amp;
        }
    }

    private void renderRhythm(float[] out, int frames, int ch, float amp) {
        int bpm = tempo.getCurrentRaw();
        int framesPerBeat = Math.max(1, (int) (60.0 * sampleRate / bpm));
        int index = 0;
        for (int f = 0; f < frames; f++) {
            int pos = f % framesPerBeat;
            float s = 0f;
            if (pos < framesPerBeat / 8) {
                // A short damped sine "ping".
                double t = pos / (double) sampleRate;
                s = (float) (Math.sin(2 * Math.PI * 880 * t)
                        * Math.exp(-t * 40.0));
            }
            for (int c = 0; c < ch; c++) out[f * ch + c] = s * amp * 0.8f;
            index++;
        }
    }

    private void renderDtmf(float[] out, int frames, int ch, float amp) {
        int digit = sequence.getCurrentRaw();
        int[] rows = {697, 770, 852, 941};
        int[] cols = {1209, 1336, 1477, 1633};
        int row = digit / 4;
        int col = digit % 4;
        if (row > 3) row = 3;
        if (col > 3) col = 3;
        double f1 = rows[row];
        double f2 = cols[col];
        for (int f = 0; f < frames; f++) {
            double t = f / (double) sampleRate;
            double s = Math.sin(2 * Math.PI * f1 * t) + Math.sin(2 * Math.PI * f2 * t);
            s *= 0.5;
            for (int c = 0; c < ch; c++) out[f * ch + c] = (float) (s * amp);
        }
    }

    static float waveSample(int waveIdx, double phase) {
        double p = phase / (2 * Math.PI);
        p = p - Math.floor(p);
        switch (waveIdx) {
            case 1: // Square
                return p < 0.5 ? 1f : -1f;
            case 2: // Sawtooth
                return (float) (2 * p - 1);
            case 3: // Triangle
                return (float) (p < 0.5 ? 4 * p - 1 : 3 - 4 * p);
            case 4: { // Square no-alias: band-limited via additive synthesis
                double s = 0;
                for (int k = 1; k <= 12; k++) {
                    s += Math.sin(2 * Math.PI * k * phase) / k;
                }
                return (float) (s * 4 / Math.PI);
            }
            case 0:
            default:
                return (float) Math.sin(phase);
        }
    }

    private float nextNoise() {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        int bits = (int) ((rng >>> 33) & 0x7FFFFFFF);
        return (bits / (float) 0x7FFFFFFF) * 2f - 1f;
    }
}
