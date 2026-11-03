package com.takeone.studio;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 audio generator: renders tone, chirp, white / pink / brown noise,
 * silence, a rhythm track, or ITU-T Q.23 DTMF tones into the region.
 *
 * <p>The generator writes over the selected region, so it behaves like
 * Audacity's Generate command rather than a filter. Supports bounded-chunk
 * file streaming via {@link #processOfflineRangeToFile} so generating up to
 * 600 seconds of audio never allocates a huge array in RAM.
 */
final class AudioGeneratorTool extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Tone & Chirp", "Noise",
            "Silence", "Rhythm Track", "DTMF"};
    // Tone/chirp reads the first name; Noise reads the color after the slash.
    private static final String[] WAVE_LABELS = {
            "Sine / White", "Square / Pink", "Sawtooth / Brown",
            "Triangle / Blue", "No-Alias / Velvet"};

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
            "gen_seq", "DTMF Sequence", "", 0, 999999999, 1, 5));

    /**
     * Returns true when {@code paramId} affects the output of the selected
     * generator {@code genType} (0 = Tone & Chirp, 1 = Noise, 2 = Silence,
     * 3 = Rhythm Track, 4 = DTMF). Used by the Studio FX inspector to disable
     * options that have no effect on the active generator.
     */
    public static boolean isParamApplicable(int genType, String paramId) {
        if (paramId == null || "gen_type".equals(paramId) || "gen_dur".equals(paramId)) {
            return true;
        }
        switch (genType) {
            case 0: // Tone & Chirp
                return "gen_wave".equals(paramId)
                        || "gen_start".equals(paramId)
                        || "gen_end".equals(paramId)
                        || "gen_amp".equals(paramId);
            case 1: // Noise
                return "gen_wave".equals(paramId)
                        || "gen_amp".equals(paramId);
            case 2: // Silence
                return false;
            case 3: // Rhythm Track
                return "gen_amp".equals(paramId)
                        || "gen_bpm".equals(paramId);
            case 4: // DTMF
            default:
                return "gen_amp".equals(paramId)
                        || "gen_seq".equals(paramId);
        }
    }

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

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // Offline-only effect: rendered through processOfflineRegion.
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    private int effectiveTypeIndex() {
        int typeIdx = type.getEnumIndex();
        if (typeIdx == 0) {
            if (sequence.getCurrentRaw() != sequence.defaultRaw) {
                typeIdx = 4;
            } else if (tempo.getCurrentRaw() != tempo.defaultRaw) {
                typeIdx = 3;
            }
        }
        return typeIdx;
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        reset();
        int ch = Math.max(1, Math.min(2, channels));
        int typeIdx = effectiveTypeIndex();
        // Silence replaces the selection with silence of the same length;
        // every other generator honors its explicit Duration dial.
        int outFrames = typeIdx == 2
                ? Math.max(1, frames)
                : Math.max(1, Math.round(duration.getFloat() * sampleRate));
        float[] out = new float[outFrames * ch];
        float amp = amplitude.getCurrentRaw() / 100f;
        switch (typeIdx) {
            case 0: // Tone & chirp
                renderToneChunk(out, outFrames, ch, amp, 0L, outFrames, new double[]{0.0});
                break;
            case 1: // Noise
                renderNoiseChunk(out, outFrames, ch, amp, new float[]{0f, 0f, 0f});
                break;
            case 2: // Silence
                break;
            case 3: // Rhythm track
                renderRhythmChunk(out, outFrames, ch, amp, 0L);
                break;
            default: // DTMF
                renderDtmfChunk(out, outFrames, ch, amp, 0L, outFrames);
                break;
        }
        return out;
    }

    @Override
    public long processOfflineRangeToFile(File srcWav, long startFrame,
                                          long endFrame, File dstWav,
                                          int sampleRate, int channels)
            throws Exception {
        if (srcWav == null || dstWav == null) return -1L;
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = sampleRate > 0 ? sampleRate : info.sampleRate;
        prepare(sr, ch);
        reset();

        long totalInFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalInFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalInFrames, endFrame));
        long inFrames = endFrame - startFrame;
        if (inFrames <= 0) return -1L;

        int typeIdx = effectiveTypeIndex();
        long outFrames = typeIdx == 2
                ? Math.max(1L, inFrames)
                : Math.max(1L, Math.round(duration.getFloat() * sr));
        float amp = amplitude.getCurrentRaw() / 100f;
        float mix = getWetDryMix();

        int chunkFrames = DEFAULT_DSP_CHUNK_FRAMES;
        float[] genChunk = new float[chunkFrames * ch];
        float[] dryChunk = mix < 0.999f ? new float[chunkFrames * ch] : null;
        byte[] dryBytes = mix < 0.999f ? new byte[chunkFrames * ch * 4] : null;
        double[] tonePhase = {0.0};
        float[] noiseState = {0f, 0f, 0f};
        long frameBytes = ch * 4L;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, outFrames * frameBytes));
            long pos = 0L;
            while (pos < outFrames) {
                int want = (int) Math.min((long) chunkFrames, outFrames - pos);
                java.util.Arrays.fill(genChunk, 0, want * ch, 0f);
                switch (typeIdx) {
                    case 0:
                        renderToneChunk(genChunk, want, ch, amp, pos, outFrames, tonePhase);
                        break;
                    case 1:
                        renderNoiseChunk(genChunk, want, ch, amp, noiseState);
                        break;
                    case 2:
                        break;
                    case 3:
                        renderRhythmChunk(genChunk, want, ch, amp, pos);
                        break;
                    default:
                        renderDtmfChunk(genChunk, want, ch, amp, pos, outFrames);
                        break;
                }
                if (mix < 0.999f && dryChunk != null && dryBytes != null) {
                    int dryAvail = (int) Math.max(0L, Math.min((long) want, inFrames - pos));
                    if (dryAvail > 0) {
                        inRaf.seek(info.dataStart + (startFrame + pos) * frameBytes);
                        inRaf.readFully(dryBytes, 0, dryAvail * ch * 4);
                        ByteBuffer.wrap(dryBytes, 0, dryAvail * ch * 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer().get(dryChunk, 0, dryAvail * ch);
                        float dryWeight = 1f - mix;
                        for (int i = 0; i < dryAvail * ch; i++) {
                            genChunk[i] = dryChunk[i] * dryWeight + genChunk[i] * mix;
                        }
                    }
                    for (int i = dryAvail * ch; i < want * ch; i++) {
                        genChunk[i] *= mix;
                    }
                }
                Dsp.writeFloatsLE(outRaf, genChunk, 0, want * ch);
                pos += want;
            }
        }
        return outFrames;
    }

    private void renderToneChunk(float[] out, int chunkFrames, int ch, float amp,
                                 long frameOffset, long totalFrames,
                                 double[] phaseHolder) {
        double phase = phaseHolder[0];
        double f0 = startFreq.getCurrentRaw();
        double f1 = endFreq.getCurrentRaw();
        int waveIdx = wave.getEnumIndex();
        for (int local = 0; local < chunkFrames; local++) {
            long f = frameOffset + local;
            double u = totalFrames <= 1 ? 0 : f / (double) (totalFrames - 1);
            double freq = f0 + (f1 - f0) * u;
            phase += 2 * Math.PI * freq / sampleRate;
            float s = waveSample(waveIdx, phase);
            for (int c = 0; c < ch; c++) out[local * ch + c] = s * amp;
        }
        phaseHolder[0] = phase;
    }

    private void renderNoiseChunk(float[] out, int chunkFrames, int ch, float amp,
                                  float[] state) {
        int waveIdx = wave.getEnumIndex();
        float pinkB0 = state[0];
        float pinkB1 = state[1];
        float brown = state[2];
        for (int f = 0; f < chunkFrames; f++) {
            float white = nextNoise();
            float s;
            if (waveIdx == 0) {
                s = white;
            } else if (waveIdx == 1) {
                pinkB0 = 0.99765f * pinkB0 + white * 0.0990460f;
                pinkB1 = 0.96300f * pinkB1 + white * 0.2965164f;
                s = (pinkB0 + pinkB1 + white * 0.1848f) * 0.4f;
            } else if (waveIdx == 2) {
                brown = (brown + white * 0.02f) * 0.998f;
                s = brown * 3.5f;
            } else if (waveIdx == 3) {
                s = (white - pinkB0) * 0.5f;
                pinkB0 = white;
            } else {
                s = Math.abs(white) > 0.85f ? (white > 0f ? 1f : -1f) : 0f;
            }
            for (int c = 0; c < ch; c++) out[f * ch + c] = s * amp;
        }
        state[0] = pinkB0;
        state[1] = pinkB1;
        state[2] = brown;
    }

    private void renderRhythmChunk(float[] out, int chunkFrames, int ch, float amp,
                                   long frameOffset) {
        int bpm = Math.max(30, Math.min(300, tempo.getCurrentRaw()));
        int framesPerBeat = Math.max(1, (int) Math.round(60.0 * sampleRate / bpm));
        double pingHz = startFreq.getCurrentRaw() != startFreq.defaultRaw
                ? startFreq.getCurrentRaw() : 880.0;
        for (int local = 0; local < chunkFrames; local++) {
            long f = frameOffset + local;
            int pos = (int) (f % framesPerBeat);
            float s = 0f;
            if (pos < framesPerBeat / 8) {
                double t = pos / (double) sampleRate;
                s = (float) (Math.sin(2 * Math.PI * pingHz * t)
                        * Math.exp(-t * 40.0));
            }
            for (int c = 0; c < ch; c++) out[local * ch + c] = s * amp * 0.8f;
        }
    }

    /**
     * Renders exact ITU-T Q.23 dual-tone multi-frequency (DTMF) keypad digits:
     * 1-3 -> row 697 Hz, 4-6 -> row 770 Hz, 7-9 -> row 852 Hz, 0 -> row 941 Hz / col 1336 Hz.
     */
    private void renderDtmfChunk(float[] out, int chunkFrames, int ch, float amp,
                                 long frameOffset, long totalFrames) {
        int seqVal = Math.max(0, sequence.getCurrentRaw());
        String digitsStr = String.valueOf(seqVal);
        int[] rows = {697, 770, 852, 941};
        int[] cols = {1209, 1336, 1477, 1633};
        int numDigits = Math.max(1, digitsStr.length());
        long slotFrames = Math.max(1L, totalFrames / numDigits);
        for (int local = 0; local < chunkFrames; local++) {
            long f = frameOffset + local;
            int slotIdx = (int) Math.min((long) (numDigits - 1), f / slotFrames);
            long posInSlot = f - slotIdx * slotFrames;
            if (numDigits > 1 && posInSlot >= slotFrames * 0.78) {
                continue; // inter-digit silence gap
            }
            int dChar = digitsStr.charAt(slotIdx) - '0';
            int row;
            int col;
            if (dChar >= 1 && dChar <= 9) {
                row = (dChar - 1) / 3;
                col = (dChar - 1) % 3;
            } else {
                // Digit '0' sits on row 3 (941 Hz), col 1 (1336 Hz)
                row = 3;
                col = 1;
            }
            double f1 = rows[row];
            double f2 = cols[col];
            double t = posInSlot / (double) sampleRate;
            double env = 1.0;
            long ramp = Math.max(1L, Math.min(64L, slotFrames / 12L));
            if (posInSlot < ramp) {
                env = posInSlot / (double) ramp;
            }
            double s = (Math.sin(2 * Math.PI * f1 * t) + Math.sin(2 * Math.PI * f2 * t)) * 0.5 * env;
            for (int c = 0; c < ch; c++) out[local * ch + c] = (float) (s * amp);
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
                for (int k = 1; k <= 15; k += 2) {
                    s += Math.sin(k * phase) / k;
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
