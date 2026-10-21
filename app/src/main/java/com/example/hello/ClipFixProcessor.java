package com.example.hello;

/**
 * Stage 5 de-clipper: finds contiguous runs of samples that hit the
 * clipping threshold and reconstructs the missing crest with a cubic
 * Hermite spline between the unclipped shoulders.
 */
final class ClipFixProcessor extends AudioProcessor {

    private final FxParameter threshold = addParameter(FxParameter.intParam(
            "clip_thresh", "Clipping Threshold", "%", 80, 100, 1, 95));
    private final FxParameter headroom = addParameter(FxParameter.dbParam(
            "clip_headroom", "Headroom", -18f, 0f, -6f));

    ClipFixProcessor() {
    }

    @Override
    public String getId() {
        return "clipfix";
    }

    @Override
    public String getTitle() {
        return "Clip Fix";
    }

    @Override
    public String getDescription() {
        return "Cubic-spline de-clipper with headroom attenuation";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        int ch = Math.max(1, Math.min(2, channels));
        float clipLevel = threshold.getCurrentRaw() / 100f;

        // Pass 1: find the clipped runs on the original signal.
        for (int c = 0; c < ch; c++) {
            int f = 0;
            while (f < frames) {
                float v = buf[f * ch + c];
                if (Math.abs(v) < clipLevel) {
                    f++;
                    continue;
                }
                int start = f;
                int sign = v >= 0 ? 1 : -1;
                while (f < frames && Math.abs(buf[f * ch + c]) >= clipLevel
                        && ((buf[f * ch + c] >= 0) == (sign > 0))) {
                    f++;
                }
                int end = f; // exclusive
                if (start < 1 || end >= frames - 1) continue;
                float y0 = buf[(start - 1) * ch + c];
                float y1 = buf[end * ch + c];
                // The slopes must be taken from OUTSIDE the clipped run:
                // inside it the signal is flat, so an inner difference would
                // report a zero slope and the spline would rebuild the very
                // plateau it is meant to replace.
                float d0 = (buf[(start - 1) * ch + c]
                        - buf[(start - 3) * ch + c]) * 0.5f;
                float d1 = (buf[(end + 1) * ch + c]
                        - buf[end * ch + c]) * 0.5f;
                int width = end - start;
                for (int i = start; i < end; i++) {
                    float t = (i - start + 1f) / (width + 1f);
                    float t2 = t * t;
                    float t3 = t2 * t;
                    float h00 = 2 * t3 - 3 * t2 + 1;
                    float h10 = t3 - 2 * t2 + t;
                    float h01 = -2 * t3 + 3 * t2;
                    float h11 = t3 - t2;
                    buf[i * ch + c] = h00 * y0 + h10 * d0 * (width + 1)
                            + h01 * y1 + h11 * d1 * (width + 1);
                }
            }
        }

        // Pass 2: the headroom is applied to the whole signal afterwards so
        // the restored crest ends up below the clipping level.
        float headroomGain = Dsp.dbToLin(headroom.getFloat());
        if (headroomGain != 1f) {
            int n = Math.min(buf.length, frames * ch);
            for (int i = 0; i < n; i++) buf[i] *= headroomGain;
        }
    }
}
