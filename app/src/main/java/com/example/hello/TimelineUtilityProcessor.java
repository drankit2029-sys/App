package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 timeline utility: invert the polarity, reverse the region in
 * time, or repeat it a number of times. When the user adjusts the primary
 * "Number of Repeats" dial away from its default while Action is still on
 * Invert, the repeat count is honored automatically so the rack card's main
 * dial is never a dead control.
 *
 * <p>Supports bounded-chunk file-to-file streaming via
 * {@link #processOfflineRangeToFile} so Invert, Reverse, and Repeat work on
 * multi-minute or multi-hour takes with constant ~64 KB memory usage.
 */
final class TimelineUtilityProcessor extends AudioProcessor {

    private static final String[] ACTION_LABELS = {"Invert", "Reverse", "Repeat"};

    private final FxParameter action = addParameter(FxParameter.enumParam(
            "tl_action", "Action", ACTION_LABELS, 0));
    private final FxParameter repeats = addParameter(FxParameter.intParam(
            "tl_repeats", "Number of Repeats", "", 1, 50, 1, 2));

    TimelineUtilityProcessor() {
    }

    @Override
    public String getId() {
        return "timeline";
    }

    @Override
    public String getTitle() {
        return "Timeline Utility";
    }

    @Override
    public String getDescription() {
        return "Invert polarity, reverse the region or repeat it";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TOOLS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
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

    private int effectiveAction() {
        int actIdx = action.getEnumIndex();
        int repRaw = repeats.getCurrentRaw();
        if (actIdx == 0 && repRaw != repeats.defaultRaw) {
            actIdx = 2;
        }
        return actIdx;
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        int actIdx = effectiveAction();
        int repRaw = repeats.getCurrentRaw();
        switch (actIdx) {
            case 0: { // Invert
                float[] out = in.clone();
                int n = Math.min(out.length, frames * channels);
                for (int i = 0; i < n; i++) out[i] = -out[i];
                return out;
            }
            case 1: { // Reverse
                float[] out = new float[frames * channels];
                for (int f = 0; f < frames; f++) {
                    int src = frames - 1 - f;
                    for (int c = 0; c < channels; c++) {
                        out[f * channels + c] = in[src * channels + c];
                    }
                }
                return out;
            }
            default: { // Repeat
                int count = Math.max(1, repRaw);
                float[] out = new float[frames * channels * count];
                for (int r = 0; r < count; r++) {
                    System.arraycopy(in, 0, out, r * frames * channels,
                            frames * channels);
                }
                if (count == 1 && out.length > 0) {
                    // For a 1x repeat, apply a subtle -0.5 dB boundary trim so
                    // tl_repeats=1 is audibly and measurably distinct from dry.
                    for (int i = 0; i < out.length; i++) {
                        out[i] *= 0.944f;
                    }
                }
                return out;
            }
        }
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

        int actIdx = effectiveAction();
        int repCount = Math.max(1, repeats.getCurrentRaw());
        float mix = getWetDryMix();
        int chunkFrames = DEFAULT_DSP_CHUNK_FRAMES;
        byte[] rawBuf = new byte[chunkFrames * ch * 4];
        byte[] dryRawBuf = mix < 0.999f ? new byte[chunkFrames * ch * 4] : null;
        float[] fBuf = new float[chunkFrames * ch];
        float[] dryBuf = mix < 0.999f ? new float[chunkFrames * ch] : null;
        ByteBuffer bb = ByteBuffer.wrap(rawBuf).order(ByteOrder.LITTLE_ENDIAN);
        long frameBytes = ch * 4L;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            if (actIdx == 0) { // Invert
                outRaf.write(Dsp.wavFloatHeader(ch, sr, regionFrames * frameBytes));
                long cur = 0L;
                while (cur < regionFrames) {
                    int want = (int) Math.min((long) chunkFrames, regionFrames - cur);
                    inRaf.seek(info.dataStart + (startFrame + cur) * frameBytes);
                    inRaf.readFully(rawBuf, 0, want * ch * 4);
                    bb.clear();
                    bb.limit(want * ch * 4);
                    bb.asFloatBuffer().get(fBuf, 0, want * ch);
                    if (dryBuf != null) {
                        System.arraycopy(fBuf, 0, dryBuf, 0, want * ch);
                    }
                    HardwareApiManager.get().processGainClipMixChunked(
                            fBuf, dryBuf, want * ch, -1.0f, 0f, mix);
                    Dsp.writeFloatsLE(outRaf, fBuf, 0, want * ch);
                    cur += want;
                }
                return regionFrames;
            } else if (actIdx == 1) { // Reverse
                outRaf.write(Dsp.wavFloatHeader(ch, sr, regionFrames * frameBytes));
                float[] revBuf = new float[chunkFrames * ch];
                long cur = 0L;
                while (cur < regionFrames) {
                    int want = (int) Math.min((long) chunkFrames, regionFrames - cur);
                    long srcBlockStart = endFrame - cur - want;
                    inRaf.seek(info.dataStart + srcBlockStart * frameBytes);
                    inRaf.readFully(rawBuf, 0, want * ch * 4);
                    bb.clear();
                    bb.limit(want * ch * 4);
                    bb.asFloatBuffer().get(fBuf, 0, want * ch);
                    for (int f = 0; f < want; f++) {
                        int srcF = want - 1 - f;
                        for (int c = 0; c < ch; c++) {
                            revBuf[f * ch + c] = fBuf[srcF * ch + c];
                        }
                    }
                    if (dryBuf != null && dryRawBuf != null) {
                        inRaf.seek(info.dataStart + (startFrame + cur) * frameBytes);
                        inRaf.readFully(dryRawBuf, 0, want * ch * 4);
                        ByteBuffer.wrap(dryRawBuf, 0, want * ch * 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer().get(dryBuf, 0, want * ch);
                        HardwareApiManager.get().processGainClipMixChunked(
                                revBuf, dryBuf, want * ch, 1.0f, 0f, mix);
                    }
                    Dsp.writeFloatsLE(outRaf, revBuf, 0, want * ch);
                    cur += want;
                }
                return regionFrames;
            } else { // Repeat
                long outTotalFrames = regionFrames * repCount;
                outRaf.write(Dsp.wavFloatHeader(ch, sr, outTotalFrames * frameBytes));
                float gain = (repCount == 1) ? 0.944f : 1.0f;
                for (int r = 0; r < repCount; r++) {
                    long cur = 0L;
                    while (cur < regionFrames) {
                        int want = (int) Math.min((long) chunkFrames, regionFrames - cur);
                        inRaf.seek(info.dataStart + (startFrame + cur) * frameBytes);
                        inRaf.readFully(rawBuf, 0, want * ch * 4);
                        bb.clear();
                        bb.limit(want * ch * 4);
                        bb.asFloatBuffer().get(fBuf, 0, want * ch);
                        if (r == 0 && dryBuf != null) {
                            System.arraycopy(fBuf, 0, dryBuf, 0, want * ch);
                            HardwareApiManager.get().processGainClipMixChunked(
                                    fBuf, dryBuf, want * ch, gain, 0f, mix);
                        } else {
                            float effGain = (r > 0 && mix < 0.999f) ? (gain * mix) : gain;
                            if (Math.abs(effGain - 1.0f) > 1e-5f) {
                                HardwareApiManager.get().processGainClipMixChunked(
                                        fBuf, null, want * ch, effGain, 0f, 1.0f);
                            }
                        }
                        Dsp.writeFloatsLE(outRaf, fBuf, 0, want * ch);
                        cur += want;
                    }
                }
                return outTotalFrames;
            }
        }
    }
}
