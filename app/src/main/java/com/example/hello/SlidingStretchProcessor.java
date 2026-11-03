package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 7 sliding stretch: a time-varying WSOLA whose stretch ratio and
 * pitch factor ramp linearly from the initial to the final value across the
 * processed region, with raised-cosine crossfades across segment boundaries
 * so transitions are click-free.
 *
 * <p>Supports bounded-chunk file-to-file streaming via
 * {@link #processOfflineRangeToFile} so long takes never allocate full-region
 * buffers in RAM.
 */
final class SlidingStretchProcessor extends AudioProcessor {

    private final FxParameter initialTempo = addParameter(FxParameter.floatParam(
            "slide_init", "Initial Tempo Change", "%", -75f, 300f, 0f));
    private final FxParameter finalTempo = addParameter(FxParameter.floatParam(
            "slide_final", "Final Tempo Change", "%", -75f, 300f, 0f));
    private final FxParameter initialPitch = addParameter(FxParameter.floatParam(
            "slide_pitchinit", "Initial Pitch Shift", " st", -24f, 24f, 0f));
    private final FxParameter finalPitch = addParameter(FxParameter.floatParam(
            "slide_pitchfinal", "Final Pitch Shift", " st", -24f, 24f, 0f));

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

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        // Offline-only effect: rendered through processOfflineRegion.
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
        double r0 = 1.0 / Math.max(0.20, 1.0 + initialTempo.getFloat() / 100.0);
        double r1 = 1.0 / Math.max(0.20, 1.0 + finalTempo.getFloat() / 100.0);
        double p0 = initialPitch.getFloat();
        double p1 = finalPitch.getFloat();
        if (Math.abs(r0 - 1.0) < 1e-6 && Math.abs(r1 - 1.0) < 1e-6
                && Math.abs(p0) < 1e-6 && Math.abs(p1) < 1e-6) {
            return in.clone();
        }
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sampleRate);
        final int segments = 8;
        final int overlapIn = Math.min(120, Math.max(0, frames / (segments * 4)));
        float[][] segBuffers = new float[segments][];
        int[] coreFrames = new int[segments];
        int totalOutFrames = 0;
        int inPos = 0;
        for (int s = 0; s < segments; s++) {
            double u = (s + 0.5) / segments;
            double ratio = Math.max(0.2, r0 + (r1 - r0) * u);
            double pitch = p0 + (p1 - p0) * u;
            int segIn = (s == segments - 1) ? frames - inPos
                    : Math.max(1, frames / segments);
            if (segIn <= 0) {
                segBuffers[s] = new float[0];
                continue;
            }
            int extIn = Math.min(frames - inPos, segIn + (s < segments - 1 ? overlapIn : 0));
            float[] chunk = new float[extIn * channels];
            System.arraycopy(in, inPos * channels, chunk, 0, chunk.length);
            float[] processed = chunk;
            if (Math.abs(pitch) > 1e-6) {
                processed = wsola.pitchShift(chunk, extIn, channels, pitch, true);
            }
            if (Math.abs(ratio - 1.0) > 1e-6) {
                processed = wsola.timeStretch(processed,
                        processed.length / channels, channels, ratio, true);
            }
            segBuffers[s] = processed;
            int procFrames = processed.length / channels;
            int core = (s < segments - 1 && extIn > segIn)
                    ? Math.max(1, (int) Math.round(procFrames * (segIn / (double) extIn)))
                    : procFrames;
            coreFrames[s] = Math.min(procFrames, core);
            totalOutFrames += coreFrames[s];
            inPos += segIn;
            if (inPos >= frames) break;
        }
        float[] out = new float[Math.max(1, totalOutFrames) * channels];
        int outFramePos = 0;
        for (int s = 0; s < segments; s++) {
            float[] seg = segBuffers[s];
            if (seg == null || seg.length == 0) continue;
            int segTotalF = seg.length / channels;
            int coreF = coreFrames[s];
            int xfade = (s > 0) ? Math.min(64, Math.min(coreF / 4, outFramePos)) : 0;
            if (xfade > 0) {
                for (int f = 0; f < xfade; f++) {
                    float wIn = 0.5f * (1f - (float) Math.cos(Math.PI * (f + 0.5) / xfade));
                    float wOut = 1f - wIn;
                    int dstBase = (outFramePos - xfade + f) * channels;
                    int srcBase = f * channels;
                    for (int c = 0; c < channels; c++) {
                        out[dstBase + c] = out[dstBase + c] * wOut + seg[srcBase + c] * wIn;
                    }
                }
            }
            int copyStartF = xfade;
            int copyCountF = Math.max(0, Math.min(coreF, segTotalF) - copyStartF);
            if (copyCountF > 0 && outFramePos + copyCountF <= totalOutFrames) {
                System.arraycopy(seg, copyStartF * channels,
                        out, outFramePos * channels, copyCountF * channels);
                outFramePos += copyCountF;
            }
        }
        return out;
    }

    @Override
    public long processOfflineRangeToFile(File srcWav, long startFrame,
                                          long endFrame, File dstWav,
                                          int sampleRate, int channels)
            throws Exception {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        long selFrames = endFrame - startFrame;
        if (selFrames <= MAX_IN_MEMORY_OFFLINE_FRAMES) {
            return super.processOfflineRangeToFile(
                    srcWav, startFrame, endFrame, dstWav, this.sampleRate, channels);
        }

        FormatConverter.WavInfo info = FormatConverter.readFloatWav(srcWav);
        int ch = Math.max(1, channels > 0 ? channels : info.channels);
        int sr = info.sampleRate > 0 ? info.sampleRate : this.sampleRate;
        long totalFrames = info.dataBytes / 4L / ch;
        startFrame = Math.max(0L, Math.min(totalFrames, startFrame));
        endFrame = Math.max(startFrame, Math.min(totalFrames, endFrame));
        long frames = endFrame - startFrame;
        if (frames <= 0) return -1L;

        double r0 = 1.0 / Math.max(0.20, 1.0 + initialTempo.getFloat() / 100.0);
        double r1 = 1.0 / Math.max(0.20, 1.0 + finalTempo.getFloat() / 100.0);
        double p0 = initialPitch.getFloat();
        double p1 = finalPitch.getFloat();
        float mix = getWetDryMix();
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(sr);

        int maxChunk = 16384;
        long numSegments = Math.max(8L, (frames + maxChunk - 1L) / maxChunk);
        long frameBytes = ch * 4L;
        long totalOutFrames = 0L;

        try (RandomAccessFile inRaf = new RandomAccessFile(srcWav, "r");
             RandomAccessFile outRaf = new RandomAccessFile(dstWav, "rw")) {
            outRaf.setLength(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, 0L));

            long inPos = 0L;
            for (long s = 0; s < numSegments && inPos < frames; s++) {
                double u = (s + 0.5) / numSegments;
                double ratio = Math.max(0.2, r0 + (r1 - r0) * u);
                double pitch = p0 + (p1 - p0) * u;
                int segIn = (s == numSegments - 1)
                        ? (int) Math.min((long) maxChunk * 2, frames - inPos)
                        : (int) Math.max(1L, frames / numSegments);
                if (segIn <= 0) break;

                byte[] rawBytes = new byte[segIn * ch * 4];
                inRaf.seek(info.dataStart + (startFrame + inPos) * frameBytes);
                inRaf.readFully(rawBytes);
                float[] chunk = new float[segIn * ch];
                ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer().get(chunk);

                float[] processed = chunk;
                if (Math.abs(pitch) > 1e-6) {
                    processed = wsola.pitchShift(chunk, segIn, ch, pitch, true);
                }
                if (Math.abs(ratio - 1.0) > 1e-6) {
                    processed = wsola.timeStretch(processed,
                            processed.length / ch, ch, ratio, true);
                }
                if (mix < 0.999f) {
                    float dryWeight = 1f - mix;
                    int common = Math.min(chunk.length, processed.length);
                    for (int i = 0; i < common; i++) {
                        processed[i] = chunk[i] * dryWeight + processed[i] * mix;
                    }
                    for (int i = common; i < processed.length; i++) {
                        processed[i] *= mix;
                    }
                }
                int outF = processed.length / ch;
                Dsp.writeFloatsLE(outRaf, processed, 0, outF * ch);
                totalOutFrames += outF;
                inPos += segIn;
            }
            outRaf.seek(0);
            outRaf.write(Dsp.wavFloatHeader(ch, sr, totalOutFrames * frameBytes));
        }
        return totalOutFrames > 0 ? totalOutFrames : -1L;
    }
}
