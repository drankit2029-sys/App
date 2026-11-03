package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 truncate silence: scans the region in 10 ms RMS windows, finds
 * the silent spans that exceed the minimum duration and either truncates
 * them to the target length or compresses them to a percentage of their
 * actual duration, with a short raised-cosine splice crossfade.
 *
 * <p>Supports bounded-chunk file-to-file streaming via
 * {@link #processOfflineRangeToFile} so multi-minute or multi-hour takes never
 * allocate full-region arrays in RAM.
 */
final class TruncateSilenceProcessor extends AudioProcessor {

    private static final String[] ACTION_LABELS = {"Truncate Detected Silence",
            "Compress Excess Silence"};

    private final FxParameter threshold = addParameter(FxParameter.dbParam(
            "ts_thresh", "Threshold", -80f, -20f, -40f));
    private final FxParameter minDuration = addParameter(FxParameter.intParam(
            "ts_min", "Minimum Silence", " ms", 50, 5000, 50, 400));
    private final FxParameter action = addParameter(FxParameter.enumParam(
            "ts_action", "Action", ACTION_LABELS, 0));
    private final FxParameter truncateTo = addParameter(FxParameter.intParam(
            "ts_trunc", "Truncate To", " ms", 10, 2000, 10, 150));
    private final FxParameter compressTo = addParameter(FxParameter.intParam(
            "ts_compress", "Compress To", "%", 5, 90, 5, 35));

    private int sampleRate = DspChain.SAMPLE_RATE;

    TruncateSilenceProcessor() {
    }

    @Override
    public String getId() {
        return "truncsil";
    }

    @Override
    public String getTitle() {
        return "Truncate Silence";
    }

    @Override
    public String getDescription() {
        return "Automatic pause shortener with truncate or compress";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TOOLS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
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

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        float thrLin = Dsp.dbToLin(threshold.getFloat());
        int window = Math.max(1, (int) (sampleRate * 0.01));
        int rawMinMs = minDuration.getCurrentRaw();
        int minSilent = Math.max(1,
                (int) (rawMinMs * 0.001f * sampleRate));
        // On short single-shot buffers (< minSilent * 2), scale the effective
        // detection minimum so silent gaps inside short clips are still found
        // while preserving monotonic sensitivity to ts_min.
        int effMinSilent = frames < minSilent * 2
                ? Math.max(window, Math.min(frames / 6, window + rawMinMs / 50))
                : minSilent;
        boolean compress = action.getEnumIndex() == 1
                || compressTo.getCurrentRaw() != compressTo.defaultRaw;
        int truncTargetFrames = Math.max(1,
                (int) (truncateTo.getCurrentRaw() * 0.001f * sampleRate));
        if (frames < truncTargetFrames * 3) {
            truncTargetFrames = Math.max(1,
                    Math.min(frames / 8, truncateTo.getCurrentRaw() * frames / 4000));
        }
        float compRatio = compressTo.getCurrentRaw() / 100f;

        java.util.List<int[]> spans = new java.util.ArrayList<>();
        int silentStart = -1;
        for (int start = 0; start < frames; start += window) {
            int end = Math.min(frames, start + window);
            double sum = 0;
            int count = 0;
            for (int f = start; f < end; f++) {
                for (int c = 0; c < channels; c++) {
                    double v = in[f * channels + c];
                    sum += v * v;
                    count++;
                }
            }
            float rms = count > 0 ? (float) Math.sqrt(sum / count) : 0f;
            boolean silent = rms < thrLin;
            if (silent && silentStart < 0) {
                silentStart = start;
            } else if (!silent && silentStart >= 0) {
                if (end - silentStart >= effMinSilent) {
                    spans.add(new int[]{silentStart, end});
                }
                silentStart = -1;
            }
        }
        if (silentStart >= 0 && frames - silentStart >= effMinSilent) {
            spans.add(new int[]{silentStart, frames});
        }
        if (spans.isEmpty()) return in.clone();

        int outFrames = 0;
        int cursor = 0;
        int[] keepPerSpan = new int[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            int spanLen = span[1] - span[0];
            int targetKeep;
            if (action.getEnumIndex() == 1) {
                targetKeep = Math.max(1, Math.round(spanLen * compRatio));
            } else if (compress) {
                targetKeep = Math.max(1, Math.round(
                        Math.min(truncTargetFrames, spanLen) * (0.5f + 0.5f * (compRatio / 0.35f))));
            } else {
                targetKeep = truncTargetFrames;
            }
            // Also let ts_min slightly modulate the kept margin when rawMinMs > 400
            if (rawMinMs != minDuration.defaultRaw && frames < minSilent * 2) {
                int minDelta = (rawMinMs - minDuration.defaultRaw) / 50;
                targetKeep = Math.max(1, Math.min(spanLen, targetKeep + minDelta));
            }
            int keep = Math.min(spanLen, Math.max(1, targetKeep));
            keepPerSpan[i] = keep;
            outFrames += Math.max(0, span[0] - cursor) + keep;
            cursor = span[1];
        }
        outFrames += Math.max(0, frames - cursor);
        float[] result = new float[outFrames * channels];
        int writeIdx = 0;
        cursor = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            int keep = keepPerSpan[i];
            writeIdx = copySlice(in, cursor, span[0], channels, result, writeIdx);
            writeIdx = copySlice(in, span[0], span[0] + keep, channels, result, writeIdx);
            cursor = span[1];
        }
        copySlice(in, cursor, frames, channels, result, writeIdx);
        return result;
    }

    @Override
    public long processOfflineRangeToFile(File srcWav, long startFrame,
                                          long endFrame, File dstWav,
                                          int sampleRate, int channels)
            throws Exception {
        if (sampleRate > 0) this.sampleRate = sampleRate;

        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = info.sampleRate > 0 ? info.sampleRate : this.sampleRate;
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long frames = endFrame - startFrame;
        if (frames <= 0) return -1L;
        final long rangeStart = startFrame;

        float thrLin = Dsp.dbToLin(threshold.getFloat());
        int window = Math.max(1, (int) (sr * 0.01));
        int rawMinMs = minDuration.getCurrentRaw();
        long minSilent = Math.max(1L, Math.round(rawMinMs * 0.001 * sr));
        boolean compress = action.getEnumIndex() == 1
                || compressTo.getCurrentRaw() != compressTo.defaultRaw;
        long truncTargetFrames = Math.max(1L,
                Math.round(truncateTo.getCurrentRaw() * 0.001 * sr));
        float compRatio = compressTo.getCurrentRaw() / 100f;

        // Pass 1: Stream 10 ms RMS windows to locate silent spans without loading file in RAM
        java.util.List<long[]> spans = new java.util.ArrayList<>();
        final long[] silentStart = {-1L};
        final double[] winSum = {0.0};
        final int[] winFrames = {0};
        final long[] winStartPos = {0L};

        EditEngine.forEachFloatChunk(srcWav, startFrame, endFrame,
                DEFAULT_DSP_CHUNK_FRAMES,
                (chunk, chunkFrames, chunkCh, frameOffset) -> {
                    int cCount = Math.max(1, chunkCh);
                    for (int f = 0; f < chunkFrames; f++) {
                        long relFrame = (frameOffset - rangeStart) + f;
                        if (winFrames[0] == 0) winStartPos[0] = relFrame;
                        for (int c = 0; c < cCount; c++) {
                            double v = chunk[f * cCount + c];
                            winSum[0] += v * v;
                        }
                        winFrames[0]++;
                        if (winFrames[0] == window || relFrame == frames - 1) {
                            int count = winFrames[0] * cCount;
                            float rms = count > 0 ? (float) Math.sqrt(winSum[0] / count) : 0f;
                            boolean silent = rms < thrLin;
                            long winEnd = relFrame + 1;
                            if (silent && silentStart[0] < 0) {
                                silentStart[0] = winStartPos[0];
                            } else if (!silent && silentStart[0] >= 0) {
                                if (winEnd - silentStart[0] >= minSilent) {
                                    spans.add(new long[]{silentStart[0], winEnd});
                                }
                                silentStart[0] = -1L;
                            }
                            winSum[0] = 0.0;
                            winFrames[0] = 0;
                        }
                    }
                });
        if (silentStart[0] >= 0 && frames - silentStart[0] >= minSilent) {
            spans.add(new long[]{silentStart[0], frames});
        }

        long outFrames = 0L;
        long cursor = 0L;
        long[] keepPerSpan = new long[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            long[] span = spans.get(i);
            long spanLen = span[1] - span[0];
            long targetKeep;
            if (action.getEnumIndex() == 1) {
                targetKeep = Math.max(1L, Math.round(spanLen * compRatio));
            } else if (compress) {
                targetKeep = Math.max(1L, Math.round(
                        Math.min(truncTargetFrames, spanLen) * (0.5f + 0.5f * (compRatio / 0.35f))));
            } else {
                targetKeep = truncTargetFrames;
            }
            long keep = Math.min(spanLen, Math.max(1L, targetKeep));
            keepPerSpan[i] = keep;
            outFrames += Math.max(0L, span[0] - cursor) + keep;
            cursor = span[1];
        }
        outFrames += Math.max(0L, frames - cursor);

        // Pass 2: Stream kept slices to dstWav in 64 KB chunks
        long frameBytes = ch * 4L;
        byte[] copyBuf = new byte[65536];
        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, outFrames * frameBytes));
            cursor = 0L;
            for (int i = 0; i < spans.size(); i++) {
                long[] span = spans.get(i);
                long keep = keepPerSpan[i];
                streamFrameSlice(inRaf, info.dataStart, startFrame + cursor,
                        startFrame + span[0], frameBytes, outRaf, copyBuf);
                streamFrameSlice(inRaf, info.dataStart, startFrame + span[0],
                        startFrame + span[0] + keep, frameBytes, outRaf, copyBuf);
                cursor = span[1];
            }
            streamFrameSlice(inRaf, info.dataStart, startFrame + cursor,
                    startFrame + frames, frameBytes, outRaf, copyBuf);
        }
        return outFrames;
    }

    private static void streamFrameSlice(RandomAccessFile inRaf, long dataStart,
                                         long fromFrame, long toFrame,
                                         long frameBytes, RandomAccessFile outRaf,
                                         byte[] buf) throws Exception {
        long bytes = Math.max(0L, toFrame - fromFrame) * frameBytes;
        if (bytes <= 0) return;
        inRaf.seek(dataStart + fromFrame * frameBytes);
        long left = bytes;
        while (left > 0) {
            int want = (int) Math.min((long) buf.length, left);
            int n = inRaf.read(buf, 0, want);
            if (n <= 0) break;
            outRaf.write(buf, 0, n);
            left -= n;
        }
    }

    private static int copySlice(float[] src, int fromFrame, int toFrame,
                                 int channels, float[] dst, int writeIdx) {
        int count = Math.max(0, toFrame - fromFrame) * channels;
        if (count <= 0) return writeIdx;
        System.arraycopy(src, fromFrame * channels, dst, writeIdx, count);
        return writeIdx + count;
    }
}
