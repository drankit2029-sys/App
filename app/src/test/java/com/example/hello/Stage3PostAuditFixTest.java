package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Regression suite for the Post-Stage-3 Audit (BUG-01..BUG-08):
 * <ul>
 *   <li><b>BUG-01</b>: {@link EditEngine#applyDspToRange} primes a
 *       linear-phase FIR chain with the post-selection lookahead when the
 *       selection is shorter than the chain latency (no zero-padded gap).</li>
 *   <li><b>BUG-02</b>: {@link DspChain.Config#isActive()} ignores enabled but
 *       audibly flat Stage 3 slots, matching {@link DspChain#isActive()}.</li>
 *   <li><b>BUG-04</b>: {@link EditEngine.UndoJournal#recordSnapshot(File)}
 *       reports refusal for takes over the 30 MB undo budget.</li>
 *   <li><b>BUG-05</b>: flat {@link GraphicEqProcessor} / {@link
 *       ShelfFilterProcessor} slots report {@code !isAudible()}.</li>
 *   <li><b>BUG-07</b>: the "60 Hz hum" quick-pick engages even when the
 *       frequency dial is still parked at 50 Hz.</li>
 *   <li><b>BUG-08</b>: switching {@link ClassicFiltersProcessor} topology
 *       resets the biquad delay line instead of carrying stale state.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class Stage3PostAuditFixTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    private File writeSineWav(String name, int frames, int ch, double freqHz,
                              float amp) throws Exception {
        File dir = RecordingEngine.takesDir(ctx);
        File f = new File(dir, name);
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4L));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float s = (float) (amp
                        * Math.sin(2.0 * Math.PI * freqHz * i / 48000.0));
                for (int c = 0; c < ch; c++) {
                    buf[i * ch + c] = s;
                }
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        }
        return f;
    }

    private static float[] readSamples(File wav, int frames, int ch)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        byte[] raw = new byte[frames * ch * 4];
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            r.seek(info.dataStart);
            r.readFully(raw);
        }
        float[] out = new float[frames * ch];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(out);
        return out;
    }

    private static DspChain.Config curveEqConfig(int gainCentibels) {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot slot =
                cfg.addOrFocusSlot(DspChain.EffectType.FILTER_CURVE_EQ);
        for (int i = 0; i < 8; i++) {
            slot.extraParams.put("pg" + i, gainCentibels);
        }
        return cfg;
    }

    // ---- BUG-01 ----

    @Test
    public void shortSelectionFirBakeMatchesContiguousStreamNoZeroPaddedPrime()
            throws Exception {
        int frames = 4096;
        int startFrame = 1000;
        int endFrame = 1600; // 600 frames < 1024-frame group delay
        File wav = writeSineWav("post_audit_short_fir.wav", frames, 1,
                1000.0, 0.4f);
        float[] whole = readSamples(wav, frames, 1);

        DspChain.Config cfg = curveEqConfig(600);

        // Reference: the intended feed is one contiguous run covering
        // pre-roll [760,1000) + prime [1000,2024) + lookahead [2024,2624).
        DspChain refChain = new DspChain(1, cfg);
        refChain.reset();
        float[] stream = Arrays.copyOfRange(whole, 760, 2624);
        refChain.processInPlace(stream, stream.length);

        DspChain bakeChain = new DspChain(1, cfg);
        assertTrue(EditEngine.applyDspToRange(wav, startFrame, endFrame,
                bakeChain, null));
        float[] after = readSamples(wav, frames, 1);

        // [1240,1360) is fully wet (outside both 240-frame seam fades).
        for (int n = 1250; n < 1350; n++) {
            int refIdx = n + 264; // (n - 760) + latency 1024
            assertEquals("frame " + n + " does not match the contiguous "
                            + "stream (post-context was zero-padded)",
                    stream[refIdx], after[n], 1e-3f);
        }
    }

    // ---- BUG-02 / BUG-05 ----

    @Test
    public void flatStage3SlotsDoNotActivateTheChainUntilDialed() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);

        DspChain.EffectSlot geq =
                cfg.addOrFocusSlot(DspChain.EffectType.GRAPHIC_EQ);
        assertFalse("Flat Graphic EQ slot must not report the chain active",
                cfg.isActive());
        assertFalse(new DspChain(1, cfg).isActive());
        geq.extraParams.put("g5", 600);
        assertTrue(cfg.isActive());
        assertTrue(new DspChain(1, cfg).isActive());

        DspChain.Config fcurveCfg = new DspChain.Config();
        fcurveCfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot fcurve =
                fcurveCfg.addOrFocusSlot(DspChain.EffectType.FILTER_CURVE_EQ);
        assertFalse(fcurveCfg.isActive());
        fcurve.extraParams.put("pg3", 600);
        assertTrue(fcurveCfg.isActive());

        DspChain.Config btCfg = new DspChain.Config();
        btCfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot bt =
                btCfg.addOrFocusSlot(DspChain.EffectType.BASS_TREBLE);
        assertFalse(btCfg.isActive());
        bt.value = 600;
        assertTrue(btCfg.isActive());

        DspChain.Config shelfCfg = new DspChain.Config();
        shelfCfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf =
                shelfCfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        assertFalse(shelfCfg.isActive());
        shelf.value = 600;
        assertTrue(shelfCfg.isActive());
    }

    @Test
    public void flatGraphicEqAndShelfProcessorsReportInaudible() {
        GraphicEqProcessor geq = new GraphicEqProcessor();
        geq.prepare(48000, 1);
        assertTrue(geq.isEnabled());
        assertFalse(geq.isAudible());
        geq.getParam("g5").setCurrentRaw(600);
        assertTrue(geq.isAudible());

        ShelfFilterProcessor shelf = new ShelfFilterProcessor();
        shelf.prepare(48000, 1);
        assertTrue(shelf.isEnabled());
        assertFalse(shelf.isAudible());
        shelf.getParam("gain").setCurrentRaw(600);
        assertTrue(shelf.isAudible());
    }

    // ---- BUG-07 ----

    @Test
    public void notch60QuickPickEngagesWhenDialStillAt50() {
        NotchFilterProcessor notch = new NotchFilterProcessor();
        notch.prepare(48000, 1);
        notch.getParam("freq").setCurrentRaw(50);
        notch.getParam("preset").setEnumIndex(2); // "60 Hz hum"

        float[][] sections = notch.buildSections();
        double at60 = Dsp.cascadeMagDb(sections, 60.0, 48000);
        double at50 = Dsp.cascadeMagDb(sections, 50.0, 48000);
        assertTrue("60 Hz quick-pick must notch 60 Hz, got " + at60,
                at60 < -20.0);
        assertTrue("60 Hz quick-pick must not notch 50 Hz, got " + at50,
                at50 > -6.0);
    }

    // ---- BUG-08 ----

    @Test
    public void classicFilterTopologyChangeResetsBiquadState() {
        ClassicFiltersProcessor switchedOn = new ClassicFiltersProcessor();
        switchedOn.prepare(48000, 1);
        switchedOn.getParam("freq").setCurrentRaw(1000);
        switchedOn.getParam("type").setEnumIndex(0);
        switchedOn.getParam("mode").setEnumIndex(0);
        float[] warm = new float[4800];
        Arrays.fill(warm, 0.5f);
        switchedOn.processInPlace(warm, 4800, 1);

        switchedOn.getParam("mode").setEnumIndex(1);
        float[] switched = new float[512];
        Arrays.fill(switched, 0.5f);
        switchedOn.processInPlace(switched, 512, 1);

        ClassicFiltersProcessor reference = new ClassicFiltersProcessor();
        reference.prepare(48000, 1);
        reference.getParam("freq").setCurrentRaw(1000);
        reference.getParam("type").setEnumIndex(0);
        reference.getParam("mode").setEnumIndex(1);
        reference.reset();
        float[] expected = new float[512];
        Arrays.fill(expected, 0.5f);
        reference.processInPlace(expected, 512, 1);

        for (int i = 0; i < expected.length; i++) {
            assertEquals("Stale biquad state leaked across LP -> HP switch at "
                    + i, expected[i], switched[i], 1e-5f);
        }
    }

    // ---- BUG-04 ----

    @Test
    public void recordSnapshotRefusesTakesOverUndoBudget() throws Exception {
        File big = new File(ctx.getCacheDir(), "post_audit_big.wav");
        try (RandomAccessFile w = new RandomAccessFile(big, "rw")) {
            w.setLength(31L * 1024L * 1024L);
        }
        try {
            EditEngine.UndoJournal journal =
                    new EditEngine.UndoJournal(ctx.getCacheDir());
            assertFalse("31 MB take must not be snapshotted", journal.recordSnapshot(big));
            assertFalse(journal.canUndo());

            File small = new File(ctx.getCacheDir(), "post_audit_small.wav");
            try (RandomAccessFile w = new RandomAccessFile(small, "rw")) {
                w.setLength(1024);
            }
            try {
                assertTrue(journal.recordSnapshot(small));
                assertTrue(journal.canUndo());
            } finally {
                // noinspection ResultOfMethodCallIgnored
                small.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            big.delete();
        }
    }
}
