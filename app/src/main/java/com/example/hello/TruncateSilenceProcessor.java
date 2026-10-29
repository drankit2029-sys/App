package com.example.hello;

/**
 * Stage 7 truncate silence: scans the region in 10 ms RMS windows, finds
 * the silent spans that exceed the minimum duration and either truncates
 * them to the target length or compresses them to a percentage of their
 * actual duration, with a short raised-cosine splice crossfade.
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

    private static int copySlice(float[] src, int fromFrame, int toFrame,
                                 int channels, float[] dst, int writeIdx) {
        int count = Math.max(0, toFrame - fromFrame) * channels;
        if (count <= 0) return writeIdx;
        System.arraycopy(src, fromFrame * channels, dst, writeIdx, count);
        return writeIdx + count;
    }
}
