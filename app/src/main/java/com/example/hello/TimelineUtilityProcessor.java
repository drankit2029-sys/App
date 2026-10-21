package com.example.hello;

/**
 * Stage 7 timeline utility: invert the polarity, reverse the region in
 * time, or repeat it a number of times.
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
        switch (action.getEnumIndex()) {
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
                int count = Math.max(1, repeats.getCurrentRaw());
                float[] out = new float[frames * channels * count];
                for (int r = 0; r < count; r++) {
                    System.arraycopy(in, 0, out, r * frames * channels,
                            frames * channels);
                }
                return out;
            }
        }
    }
}
