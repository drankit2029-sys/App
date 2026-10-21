package com.example.hello;

/**
 * Stage 7 truncate silence: scans the region in 10 ms RMS windows, finds
 * the silent spans that exceed the minimum duration and either truncates
 * them to the target length or compresses them to a percentage.
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
        float thrLin = Dsp.dbToLin(threshold.getFloat());
        int window = Math.max(1, (int) (sampleRate * 0.01));
        int minSilent = Math.max(1,
                (int) (minDuration.getCurrentRaw() * 0.001f * sampleRate));
        boolean compress = action.getEnumIndex() == 1;
        int keepFrames = compress
                ? Math.max(1, (int) (minSilent * compressTo.getCurrentRaw() / 100f))
                : Math.max(1,
                        (int) (truncateTo.getCurrentRaw() * 0.001f * sampleRate));

        // Collect the silent spans.
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
                if (end - silentStart >= minSilent) {
                    spans.add(new int[]{silentStart, end});
                }
                silentStart = -1;
            }
        }
        if (silentStart >= 0 && frames - silentStart >= minSilent) {
            spans.add(new int[]{silentStart, frames});
        }
        if (spans.isEmpty()) return in.clone();

        // Rebuild the region, keeping only the head of every silent span.
        java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream(frames * channels * 4);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int cursor = 0;
        for (int[] span : spans) {
            int keep = Math.min(keepFrames, span[1] - span[0]);
            appendSamples(bytes, in, cursor, span[0], channels);
            appendSamples(bytes, in, span[0], span[0] + keep, channels);
            cursor = span[1];
        }
        appendSamples(bytes, in, cursor, frames, channels);

        byte[] raw = bytes.toByteArray();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[raw.length / 4];
        bb.asFloatBuffer().get(result);
        return result;
    }

    private static void appendSamples(java.io.ByteArrayOutputStream out,
                                      float[] src, int fromFrame, int toFrame,
                                      int channels) {
        int count = Math.max(0, toFrame - fromFrame) * channels;
        if (count <= 0) return;
        float[] chunk = new float[count];
        System.arraycopy(src, fromFrame * channels, chunk, 0, count);
        byte[] b = new byte[count * 4];
        java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().put(chunk);
        out.write(b, 0, b.length);
    }
}
