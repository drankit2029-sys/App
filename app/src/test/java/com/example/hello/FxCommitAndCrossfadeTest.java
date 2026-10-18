package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 1 FX-commit audit fixes: the seam crossfades must use GLOBAL frame
 * offsets so a multi-chunk range neither repeats the head fade on every 4096-
 * frame chunk nor drops a tail fade that straddles a chunk boundary, and they
 * must be skipped entirely at the file edges (a whole-take bake has nothing to
 * crossfade toward). Also covers dispatchSave() baking an unbaked chain when
 * the save arrives from the notification path.
 *
 * <p>The brickwall limiter at drive 100 is a deterministic per-sample gain
 * (fastDbToLin(10.5) ≈ 3.35x) with no clamping below the 0.82 ceiling, so the
 * tests can assert exact dry/wet blends instead of heuristics.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxCommitAndCrossfadeTest {

    /** Session rate the engine writes WAV headers at (48 kHz). */
    private static final int SR = 48000;

    /** Deterministic wet gain of the brickwall limiter at drive 100. */
    private static final float LIMITER_GAIN = DspChain.fastDbToLin(10.5f);

    private static File writeSineWav(File dir, String name, int frames,
                                     int channels, float amp) throws Exception {
        File f = new File(dir, name);
        long dataBytes = (long) frames * channels * 4L;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.write(Dsp.wavFloatHeader(channels, SR, dataBytes));
            float[] buf = new float[frames * channels];
            for (int i = 0; i < frames; i++) {
                float s = (float) (amp
                        * Math.sin(2.0 * Math.PI * 440.0 * i / SR));
                for (int c = 0; c < channels; c++) {
                    buf[i * channels + c] = s;
                }
            }
            Dsp.writeFloatsLE(raf, buf, 0, buf.length);
        }
        return f;
    }

    private static float[] readAllSamples(File wav, int frames, int channels)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        byte[] raw = new byte[frames * channels * 4];
        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            raf.seek(info.dataStart);
            raf.readFully(raw);
        }
        float[] out = new float[frames * channels];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(out);
        return out;
    }

    /** Limiter-only chain: a pure per-sample gain, no filter or AGC state. */
    private static DspChain limiterChain() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.BRICKWALL_LIMITER, true, 100, false));
        cfg.syncLegacyFieldsFromSlots();
        return new DspChain(1, cfg);
    }

    @Test
    public void fullRangeApplyProcessesEntireFileWithoutBoundaryFadeArtifacts()
            throws Exception {
        File dir = RuntimeEnvironment.getApplication().getCacheDir();
        int totalFrames = 2400;
        File wav = writeSineWav(dir, "fx_full.wav", totalFrames, 1, 0.25f);
        float[] before = readAllSamples(wav, totalFrames, 1);

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        DspChain chain = new DspChain(1, cfg);

        EditEngine.applyDspToRange(wav, 0, totalFrames, chain, null);
        float[] after = readAllSamples(wav, totalFrames, 1);

        double diffEnergy = 0;
        for (int i = 0; i < totalFrames; i++) {
            double d = after[i] - before[i];
            diffEnergy += d * d;
        }
        assertTrue("Full-take FX bake should modify audio samples",
                diffEnergy > 1e-3);
    }

    @Test
    public void subRangeApplyCrossfadesCleanlyAtBothBoundaries() throws Exception {
        File dir = RuntimeEnvironment.getApplication().getCacheDir();
        int totalFrames = 4000;
        File wav = writeSineWav(dir, "fx_sub.wav", totalFrames, 1, 0.20f);
        float[] before = readAllSamples(wav, totalFrames, 1);

        DspChain chain = limiterChain();

        int start = 1000;
        int end = 3000;
        EditEngine.applyDspToRange(wav, start, end, chain, null);
        float[] after = readAllSamples(wav, totalFrames, 1);

        for (int i = 0; i < start; i++) {
            assertEquals("Pre-selection sample mutated at " + i,
                    before[i], after[i], 1e-6f);
        }
        for (int i = end; i < totalFrames; i++) {
            assertEquals("Post-selection sample mutated at " + i,
                    before[i], after[i], 1e-6f);
        }
        // The first/last frames of an interior range sit at the dry end of
        // their raised-cosine fades (u=0 and u=1), so they must be untouched.
        assertEquals("Start seam frame must start at dry level",
                before[start], after[start], 1e-5f);
        assertEquals("End seam frame must land at dry level",
                before[end - 1], after[end - 1], 1e-5f);

        float stepStart = Math.abs(after[start] - after[start - 1]);
        float stepEnd = Math.abs(after[end] - after[end - 1]);
        assertTrue("Start boundary step too large: " + stepStart,
                stepStart < 0.05f);
        assertTrue("End boundary step too large: " + stepEnd,
                stepEnd < 0.05f);
    }

    @Test
    public void multiChunkSelectionDoesNotRepeatHeadFadeOrDropStraddledTailFade()
            throws Exception {
        File dir = RuntimeEnvironment.getApplication().getCacheDir();
        // Selection length 8292 = 4096 * 2 + 100: spans three chunks, and the
        // final chunk holds only 100 frames so the 240-frame tail fade
        // straddles chunk 1 (140 frames) and chunk 2 (100 frames).
        int totalFrames = 12000;
        int start = 1000;
        int end = start + 8292;
        File wav = writeSineWav(dir, "fx_multichunk.wav", totalFrames, 1, 0.20f);
        float[] before = readAllSamples(wav, totalFrames, 1);

        EditEngine.applyDspToRange(wav, start, end, limiterChain(), null);
        float[] after = readAllSamples(wav, totalFrames, 1);

        // 1. Chunk 1's head must be pure wet. The old local-index loop re-ran
        //    the head fade on every chunk, stamping origHead[0] (= before[start])
        //    over chunk 1's first 240 frames; the fixed global-offset code
        //    touches only [start, start + 240).
        int chunk1Start = start + 4096;
        assertEquals("Chunk 1 head must be pure wet (no repeated head fade)",
                LIMITER_GAIN * before[chunk1Start], after[chunk1Start], 2e-3f);
        assertTrue("Head fade must not stamp the original head onto chunk 1",
                Math.abs(after[chunk1Start] - before[start]) > 0.2f);

        // 2. The straddled tail fade must keep blending on the final chunk
        //    instead of being dropped: frame end-120 sits at u = 120/239 of the
        //    fade, so it must be an exact dry/wet blend, not pure wet.
        int fMid = 120;
        float uMid = fMid / 239f;
        float gDryMid = 0.5f * (1f - (float) Math.cos(Math.PI * uMid));
        float expectedMid = before[end - 240 + fMid] * gDryMid
                + LIMITER_GAIN * before[end - 240 + fMid] * (1f - gDryMid);
        assertEquals("Straddled tail fade must blend mid-window, not drop",
                expectedMid, after[end - 240 + fMid], 2e-3f);

        // 3. The very last frame of the range lands at the dry end of the fade.
        assertEquals("Straddled tail seam must land at dry level at end - 1",
                before[end - 1], after[end - 1], 1e-5f);
        float stepEnd = Math.abs(after[end] - after[end - 1]);
        assertTrue("End boundary step across straddled tail fade too large: "
                + stepEnd, stepEnd < 0.05f);

        // 4. Everything outside the selection is bit-identical.
        for (int i = 0; i < start; i++) {
            assertEquals("Pre-selection sample mutated at " + i,
                    before[i], after[i], 1e-6f);
        }
        for (int i = end; i < totalFrames; i++) {
            assertEquals("Post-selection sample mutated at " + i,
                    before[i], after[i], 1e-6f);
        }
    }

    @Test
    public void veryShortSelectionShrinksFadesAndKeepsGeometry() throws Exception {
        File dir = RuntimeEnvironment.getApplication().getCacheDir();
        int totalFrames = 6000;
        File wav = writeSineWav(dir, "fx_short.wav", totalFrames, 1, 0.20f);
        float[] before = readAllSamples(wav, totalFrames, 1);

        // 100 frames is below one crossfade window: the fades must shrink to
        // half the range instead of reading past it or writing outside it.
        int start = 2000;
        int end = 2100;
        EditEngine.applyDspToRange(wav, start, end, limiterChain(), null);
        float[] after = readAllSamples(wav, totalFrames, 1);

        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        assertEquals("Geometry must be untouched",
                (long) totalFrames * 4L, info.dataBytes);
        assertEquals("Audio before a short range must not move",
                before[start - 1], after[start - 1], 1e-6f);
        assertEquals("Audio after a short range must not move",
                before[end], after[end], 1e-6f);
        // A 50-frame fade still lands dry at the range edges.
        assertEquals("Short-range head edge stays dry",
                before[start], after[start], 1e-5f);
        assertEquals("Short-range tail edge stays dry",
                before[end - 1], after[end - 1], 1e-5f);
    }

    @Test
    public void fxBakeRequiredFlagInvalidatesStreamingAdoptionAndBakesRawOnSave()
            throws Exception {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();

        // Persist an active chain exactly as the studio would, so
        // dispatchSave()'s notification-path bake sees it via Config.load().
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.BRICKWALL_LIMITER, true, 100, false));
        cfg.syncLegacyFieldsFromSlots();
        cfg.save(ctx);

        engine.startNewSession(ctx, "FxBakeTest", 1);
        assertFalse("fresh session has no pending FX bake",
                engine.isFxBakeRequired());

        int frames = 2400;
        float[] rawSamples = new float[frames];
        for (int i = 0; i < frames; i++) {
            rawSamples[i] = (float) (0.15
                    * Math.sin(2.0 * Math.PI * 440.0 * i / SR));
        }
        try (RandomAccessFile raf = new RandomAccessFile(engine.rawFile, "rw")) {
            raf.setLength(0);
            raf.write(Dsp.wavFloatHeader(1, SR, (long) frames * 4L));
            Dsp.writeFloatsLE(raf, rawSamples, 0, rawSamples.length);
        }
        engine.dataBytes = (long) frames * 4L;
        float[] before = readAllSamples(engine.rawFile, frames, 1);

        engine.setFxBakeRequired(true);
        assertTrue(engine.isFxBakeRequired());

        ConversionJobs.SaveResult res = ConversionJobs.dispatchSave(
                ctx, RecordPreset.MEMO, SaveFormat.WAV24, null);
        assertFalse("Unbaked FX must not adopt a dry streaming .part",
                res.adopted);
        assertFalse("dispatchSave must clear fxBakeRequired after baking raw",
                engine.isFxBakeRequired());

        float[] after = readAllSamples(engine.rawFile, frames, 1);
        double diffEnergy = 0;
        for (int i = 0; i < frames; i++) {
            double d = after[i] - before[i];
            diffEnergy += d * d;
        }
        assertTrue("dispatchSave must bake the active chain into raw when "
                + "fxBakeRequired (notification-save path)", diffEnergy > 1e-2);

        if (res.job != null) {
            ConversionJobs.cancel(res.job.jobId, true);
        }
        engine.abortAndDelete();
    }
}
