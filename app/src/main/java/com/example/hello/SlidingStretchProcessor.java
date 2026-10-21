package com.example.hello;

/**
 * Stage 7 sliding stretch: a time-varying WSOLA whose stretch ratio and
 * pitch factor ramp linearly from the initial to the final value across the
 * processed region.
 */
final class SlidingStretchProcessor extends AudioProcessor {

    private final FxParameter initialTempo = addParameter(FxParameter.floatParam(
            "slide_init", "Initial Tempo Change", "%", -75f, 300f, 0f));
    private final FxParameter finalTempo = addParameter(FxParameter.floatParam(
            "slide_final", "Final Tempo Change", "%", -75f, 300f, 0f));
    private final FxParameter initialPitch = addParameter(FxParameter.floatParam(
            "slide_pitchinit", "Initial Pitch Shift", "", -24f, 24f, 0f));
    private final FxParameter finalPitch = addParameter(FxParameter.floatParam(
            "slide_pitchfinal", "Final Pitch Shift", "", -24f, 24f, 0f));

    private int sampleRate = DspChain.SAMPLE_RATE;

    SlidingStretchProcessor() {
    }

    @Override
    public String getId() {
        return "slidestretch";
    }

    @Override
    public String getTitle() {
        return "Sliding Stretch";
    }

    @Override
    public String getDescription() {
        return "Continuous tempo and pitch ramp across the region";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.TIME;
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
        return isEnabled() && (initialTempo.getFloat() != 0f
                || finalTempo.getFloat() != 0f
                || initialPitch.getFloat() != 0f
                || finalPitch.getFloat() != 0f);
    }

    @Override
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        if (in == null || frames <= 0) return in;
        double r0 = 1.0 + initialTempo.getFloat() / 100.0;
        double r1 = 1.0 + finalTempo.getFloat() / 100.0;
        double p0 = initialPitch.getFloat();
        double p1 = finalPitch.getFloat();
        if (Math.abs(r0 - 1.0) < 1e-6 && Math.abs(r1 - 1.0) < 1e-6
                && Math.abs(p0) < 1e-6 && Math.abs(p1) < 1e-6) {
            return in.clone();
        }
        // Estimate the output length from the average stretch factor.
        double avg = (r0 + r1) * 0.5;
        int outFrames = Math.max(1, (int) Math.round(frames * avg));
        float[] out = new float[outFrames * channels];
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sampleRate);

        // Process in segments so the ramp is audible.
        final int segments = 8;
        int inPos = 0;
        int outPos = 0;
        for (int s = 0; s < segments; s++) {
            double u = (s + 0.5) / segments;
            double ratio = r0 + (r1 - r0) * u;
            double pitch = p0 + (p1 - p0) * u;
            int segIn = (s == segments - 1) ? frames - inPos
                    : Math.max(1, frames / segments);
            int segOut = Math.max(1, (int) Math.round(segIn * ratio));
            float[] chunk = new float[segIn * channels];
            System.arraycopy(in, inPos * channels, chunk, 0, chunk.length);
            float[] processed = chunk;
            if (Math.abs(pitch) > 1e-6) {
                processed = wsola.pitchShift(chunk, segIn, channels, pitch);
            }
            processed = wsola.timeStretch(processed, segIn, channels, ratio);
            int copyFrames = Math.min(segOut, outFrames - outPos);
            int copySamples = Math.min(copyFrames * channels,
                    processed.length);
            if (copySamples > 0) {
                System.arraycopy(processed, 0, out, outPos * channels, copySamples);
            }
            outPos += copyFrames;
            inPos += segIn;
            if (inPos >= frames) break;
        }
        return out;
    }
}
