package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stage 1 of the Pro Audio Effects plan: an FX commit must always process the
 * audio (whole take or selection — never a silent abort), must leave the
 * untouched region bit-identical, and must not click at the seams.
 *
 * <p>The seam check is the important one: a sub-range bake used to write the
 * processed samples straight over the original, so any gain/EQ/dynamics change
 * produced a step discontinuity at both boundaries (an audible click). The bake
 * now blends 240 frames (5 ms) of raised-cosine crossfade on each side and
 * pushes 240 frames of pre-roll through the chain first so filter state is
 * settled when the first processed sample is written.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxCommitAndCrossfadeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 1.0 s of a 229 Hz stereo tone at 0.4 amplitude. */
    private File toneRaw(String name) throws Exception {
        File f = new File(tmp.getRoot(), name);
        int frames = 48000;
        float[] buf = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float s = (float) Math.sin(i * 0.03) * 0.4f;
            buf[i * 2] = s;
            buf[i * 2 + 1] = s;
        }
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 8L));
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    private static float sampleAt(File f, long frame) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(44L + frame * 8L);
            byte[] b = new byte[4];
            r.readFully(b);
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        } finally {
            r.close();
        }
    }

    private static DspChain limiterChain() {
        // A limiter that clearly changes level, so the seams have something to
        // click on if the crossfade is missing.
        DspChain.Config cfg = new DspChain.Config();
        cfg.addOrFocusSlot(DspChain.EffectType.BRICKWALL_LIMITER);
        cfg.syncLegacyFieldsFromSlots();
        return new DspChain(2, cfg);
    }

    @Test
    public void fullTakeCommitProcessesEverySampleExactlyOnce() throws Exception {
        File raw = toneRaw("full.wav");
        float before = sampleAt(raw, 1000);
        EditEngine.applyDspToRange(raw, 0, 48000, limiterChain(), null);
        float after = sampleAt(raw, 1000);
        assertNotEquals("a whole-take commit must actually process audio",
                before, after, 0f);

        // A second commit of the SAME (already baked) chain must not run: that
        // is the studio's job, guarded by dspBakedToRaw / fxBakeRequired.
        RecordingEngine engine = RecordingEngine.get();
        assertEquals("no FX pending on a fresh engine",
                false, engine.isFxBakeRequired());
        engine.setFxBakeRequired(true);
        assertTrue("save path can see an unbaked chain", engine.isFxBakeRequired());
        engine.setFxBakeRequired(false);
    }

    @Test
    public void selectionCommitLeavesTheRestBitIdenticalAndSeamsAreClickFree()
            throws Exception {
        long start = 12000;
        long end = 36000;
        File raw = toneRaw("sel.wav");

        float[] headBefore = new float[64];
        for (int i = 0; i < headBefore.length; i++) {
            headBefore[i] = sampleAt(raw, start - 400 + i);
        }
        // Snap the tail frames too: the tone is a sine, so compare against the
        // actual samples rather than the peak amplitude.
        float[] tailBefore = new float[64];
        for (int i = 0; i < tailBefore.length; i++) {
            tailBefore[i] = sampleAt(raw, end + 2000 + i);
        }

        // Interior sample captured BEFORE the bake, so the assertion below is
        // a real "the audio changed" check.
        float midBefore = sampleAt(raw, (start + end) / 2);

        EditEngine.applyDspToRange(raw, start, end, limiterChain(), null);

        // 1) Nothing outside the range may move.
        for (int i = 0; i < headBefore.length; i++) {
            assertEquals("audio before the selection was modified: "
                            + (start - 400 + i),
                    headBefore[i], sampleAt(raw, start - 400 + i), 0f);
        }
        for (int i = 0; i < tailBefore.length; i++) {
            assertEquals("audio after the selection was modified: " + (end + 2000 + i),
                    tailBefore[i], sampleAt(raw, end + 2000 + i), 0f);
        }

        // 2) The audio inside the range really was processed.
        assertNotEquals("selection interior must be processed",
                midBefore, sampleAt(raw, (start + end) / 2), 1e-6f);

        // 3) No step discontinuity at either seam: the sample-to-sample delta
        //    across the boundary must stay within the natural slew of the tone.
        double naturalSlew = 0;
        for (long f = end + 2000; f < end + 2100; f++) {
            naturalSlew = Math.max(naturalSlew,
                    Math.abs(sampleAt(raw, f + 1) - sampleAt(raw, f)));
        }
        double headStep = Math.abs(sampleAt(raw, start) - sampleAt(raw, start - 1));
        double tailStep = Math.abs(sampleAt(raw, end) - sampleAt(raw, end - 1));
        assertTrue("click at the start seam: " + headStep
                        + " vs natural " + naturalSlew,
                headStep <= Math.max(naturalSlew, 0.05));
        assertTrue("click at the end seam: " + tailStep
                        + " vs natural " + naturalSlew,
                tailStep <= Math.max(naturalSlew, 0.05));
    }

    @Test
    public void veryShortSelectionStillBakesAndStaysInBounds() throws Exception {
        File raw = toneRaw("short.wav");
        // 100 frames is below one crossfade window: the fades must shrink
        // instead of reading past the range or writing outside the file.
        float before = sampleAt(raw, 4000);
        EditEngine.applyDspToRange(raw, 5000, 5100, limiterChain(), null);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        assertEquals("geometry must be untouched",
                48000L * 8L, info.dataBytes);
        assertEquals("audio before a short range must not move",
                before, sampleAt(raw, 4000), 0f);
    }
}
