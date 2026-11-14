package com.takeone.studio;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 fades: fade in, fade out, studio fade out (equal-power S-curve
 * plus a closing low-pass) and a fully customisable fade.
 *
 * <p>Supports bounded-chunk file-to-file streaming via
 * {@link #processOfflineRangeToFile} so applying fades to multi-minute takes
 * never allocates full-region arrays in RAM.
 */
final class FadeProcessor extends AudioProcessor {

    private static final String[] TYPE_LABELS = {"Fade In", "Fade Out",
            "Studio Fade Out", "Adjustable Fade"};
    private static final String[] CURVE_LABELS = {"Linear", "Logarithmic",
            "Exponential", "Cosine", "S-Curve"};

    private final FxParameter type = addParameter(FxParameter.enumParam(
            "fade_type", "Type", TYPE_LABELS, 0));
    private final FxParameter curve = addParameter(FxParameter.enumParam(
            "fade_curve", "Curve Shape", CURVE_LABELS, 0));
    private final FxParameter midBoost = addParameter(FxParameter.intParam(
            "fade_mid", "Mid-Fade Boost", "%", -100, 100, 1, 0));
    private final FxParameter startGain = addParameter(FxParameter.intParam(
            "fade_start", "Start Gain", "%", 0, 100, 1, 0));
    private final FxParameter endGain = addParameter(FxParameter.intParam(
            "fade_end", "End Gain", "%", 0, 100, 1, 100));
    private final FxParameter length = addParameter(FxParameter.intParam(
            "fade_len", "Length", "%", 1, 100, 1, 20));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int channels = 2;
    private final BiquadFilter closingLp = new BiquadFilter();

    FadeProcessor() {
    }

    @Override
    public String getId() {
        return "fade";
    }

    @Override
    public String getTitle() {
        return "Fade";
    }

    @Override
    public String getDescription() {
        return "Fade in, fade out, studio fade or custom curve";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TOOLS;
    }

    @Override
    public boolean isParamApplicable(String paramId) {
        return applicable(type.getEnumIndex(), paramId);
    }

    @Override
    public boolean isParamApplicable(String paramId, Map<String, Integer> values) {
        int rawType = values != null && values.containsKey("fade_type")
                ? type.clampRaw(values.get("fade_type"))
                : type.defaultRaw;
        return applicable(rawType, paramId);
    }

    private static boolean applicable(int typeIndex, String paramId) {
        return (!"fade_start".equals(paramId) && !"fade_end".equals(paramId))
                || typeIndex == 3;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        this.channels = Math.max(1, Math.min(2, channels));
        closingLp.setSections(new float[][]{Dsp.rbjLowPass(this.sampleRate, 800f, 0.7071)},
                this.channels);
    }

    @Override
    public void reset() {
        closingLp.reset();
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

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        float[] out = in.clone();
        int typeIdx = type.getEnumIndex();
        int fadeFrames = Math.max(1,
                (int) Math.round(frames * length.getCurrentRaw() / 100.0));
        fadeFrames = Math.min(fadeFrames, frames);

        switch (typeIdx) {
            case 0: { // Fade In
                applyRamp(out, frames, channels, fadeFrames, true, 0L, frames);
                break;
            }
            case 1: { // Fade Out
                applyRamp(out, frames, channels, fadeFrames, false, 0L, frames);
                break;
            }
            case 2: { // Studio Fade Out
                closingLp.reset();
                applyStudioFade(out, frames, channels, fadeFrames, 0L, frames);
                break;
            }
            default: { // Adjustable Fade
                applyAdjustable(out, frames, channels, fadeFrames, 0L);
                break;
            }
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
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long regionFrames = endFrame - startFrame;
        if (regionFrames <= 0) return -1L;

        prepare(sr, ch);
        closingLp.reset();
        int typeIdx = type.getEnumIndex();
        long fadeFrames = Math.max(1L,
                Math.round(regionFrames * length.getCurrentRaw() / 100.0));
        fadeFrames = Math.min(fadeFrames, regionFrames);
        float mix = getWetDryMix();

        int chunkFrames = DEFAULT_DSP_CHUNK_FRAMES;
        byte[] rawBuf = new byte[chunkFrames * ch * 4];
        float[] fBuf = new float[chunkFrames * ch];
        float[] dryBuf = mix < 0.999f ? new float[chunkFrames * ch] : null;
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);
        long frameBytes = ch * 4L;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, regionFrames * frameBytes));
            long offset = 0L;
            while (offset < regionFrames) {
                int want = (int) Math.min((long) chunkFrames, regionFrames - offset);
                inRaf.seek(info.dataStart + (startFrame + offset) * frameBytes);
                inRaf.readFully(rawBuf, 0, want * ch * 4);
                bb.clear();
                bb.limit(want * ch * 4);
                bb.asFloatBuffer().get(fBuf, 0, want * ch);
                if (dryBuf != null) {
                    System.arraycopy(fBuf, 0, dryBuf, 0, want * ch);
                }

                switch (typeIdx) {
                    case 0:
                        applyRamp(fBuf, want, ch, fadeFrames, true, offset, regionFrames);
                        break;
                    case 1:
                        applyRamp(fBuf, want, ch, fadeFrames, false, offset, regionFrames);
                        break;
                    case 2:
                        applyStudioFade(fBuf, want, ch, fadeFrames, offset, regionFrames);
                        break;
                    default:
                        applyAdjustable(fBuf, want, ch, fadeFrames, offset);
                        break;
                }

                if (dryBuf != null) {
                    HardwareApiManager.get().processGainClipMixChunked(
                            fBuf, dryBuf, want * ch, 1.0f, 0f, mix);
                }
                Dsp.writeFloatsLE(outRaf, fBuf, 0, want * ch);
                offset += want;
            }
        }
        return regionFrames;
    }

    private float shapeCurve(float u) {
        u = Math.max(0f, Math.min(1f, u));
        int curveIdx = curve.getEnumIndex();
        float s;
        switch (curveIdx) {
            case 1: // Logarithmic
                s = (float) Math.pow(u, 0.5);
                break;
            case 2: // Exponential
                s = (float) Math.pow(u, 2.0);
                break;
            case 3: // Cosine
                s = (float) (0.5 - 0.5 * Math.cos(Math.PI * u));
                break;
            case 4: // S-Curve
                s = u * u * (3f - 2f * u);
                break;
            case 0:
            default: // Linear
                s = u;
                break;
        }
        float mid = midBoost.getCurrentRaw() / 100f;
        if (mid != 0f) {
            s *= Math.max(0f, 1f + mid * 2.5f * u * (1f - u));
        }
        return Math.max(0f, Math.min(1.5f, s));
    }

    /** Shaped ramp in or out over the first/last {@code fadeFrames}. */
    private void applyRamp(float[] buf, int chunkFrames, int ch, long fadeFrames,
                           boolean fadeIn, long globalFrameOffset, long totalFrames) {
        // Fade In and Fade Out have fixed directions. Start/End Gain are only
        // editable in Adjustable Fade, so they must never turn a fade-out into
        // a fade-in when touched.
        float gStart = fadeIn ? 0f : 1f;
        float gEnd = fadeIn ? 1f : 0f;
        long startOut = totalFrames - fadeFrames;
        for (int local = 0; local < chunkFrames; local++) {
            long f = globalFrameOffset + local;
            float g;
            if (fadeIn) {
                if (f < fadeFrames) {
                    float u = fadeFrames <= 1 ? 0f : f / (float) (fadeFrames - 1);
                    float shaped = shapeCurve(u);
                    g = gStart + (gEnd - gStart) * shaped;
                } else {
                    g = gEnd;
                }
            } else {
                if (f >= startOut) {
                    long i = f - startOut;
                    float u = fadeFrames <= 1 ? 1f : i / (float) (fadeFrames - 1);
                    float shaped = shapeCurve(u);
                    g = gStart + (gEnd - gStart) * shaped;
                } else {
                    g = gStart;
                }
            }
            int idx = local * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= g;
        }
    }

    /** Equal-power S-curve with a low-pass that closes during the fade. */
    private void applyStudioFade(float[] buf, int chunkFrames, int ch,
                                 long fadeFrames, long globalFrameOffset,
                                 long totalFrames) {
        // Studio Fade Out is always a fade to silence.
        float gStart = 1f;
        float gEnd = 0f;
        long startOut = totalFrames - fadeFrames;
        for (int local = 0; local < chunkFrames; local++) {
            long f = globalFrameOffset + local;
            float u = 0f;
            if (f >= startOut) {
                long i = f - startOut;
                u = fadeFrames <= 1 ? 1f : i / (float) (fadeFrames - 1);
            }
            float cutoff = 20000f * (float) Math.pow(800f / 20000f, u);
            closingLp.setSections(new float[][]{
                    Dsp.rbjLowPass(sampleRate, cutoff, 0.7071)}, ch);
            closingLp.process(buf, 1, ch, local);
            float baseCos = (float) (0.5 * (1.0 + Math.cos(Math.PI * u)));
            float shaped = 0.5f * baseCos + 0.5f * shapeCurve(1f - u);
            float g = gEnd + (gStart - gEnd) * shaped;
            int idx = local * ch;
            for (int c = 0; c < ch; c++) {
                buf[idx + c] *= g;
            }
        }
    }

    /** Custom curve between an arbitrary start and end gain. */
    private void applyAdjustable(float[] buf, int chunkFrames, int ch,
                                 long fadeFrames, long globalFrameOffset) {
        float g0 = startGain.getCurrentRaw() / 100f;
        float g1 = endGain.getCurrentRaw() / 100f;
        for (int local = 0; local < chunkFrames; local++) {
            long f = globalFrameOffset + local;
            float u = fadeFrames <= 1 ? 1f
                    : Math.min(1f, f / (float) (fadeFrames - 1));
            float shape = shapeCurve(u);
            float g = g0 + (g1 - g0) * shape;
            int idx = local * ch;
            for (int c = 0; c < ch; c++) buf[idx + c] *= g;
        }
    }
}
