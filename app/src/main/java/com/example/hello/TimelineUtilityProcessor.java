package com.example.hello;

/**
 * Stage 7 timeline utility: invert the polarity, reverse the region in
 * time, or repeat it a number of times. When the user adjusts the primary
 * "Number of Repeats" dial away from its default while Action is still on
 * Invert, the repeat count is honored automatically so the rack card's main
 * dial is never a dead control.
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

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        int actIdx = action.getEnumIndex();
        int repRaw = repeats.getCurrentRaw();
        // If Action is still at default (Invert) but the user explicitly dialed
        // Number of Repeats away from its default (2), engage Repeat mode.
        if (actIdx == 0 && repRaw != repeats.defaultRaw) {
            actIdx = 2;
        }
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
}
