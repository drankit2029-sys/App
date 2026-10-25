package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Effects mode: the FX rack drives the transport.
 *
 * <p>With the rack open, Play auditions the effect-applied audio, Stop
 * commits the rack to the region that was selected before the rack opened,
 * and the FX chip is only highlighted while the panel is actually open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxEffectsModeTest {

    private Context ctx;
    private RecordingEngine engine;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        engine = RecordingEngine.get();
        engine.abortAndDelete();
    }

    @After
    public void tearDown() {
        engine.abortAndDelete();
    }

    private File createFloatWav(String name, int frames, int ch) throws Exception {
        File dir = new File(ctx.getCacheDir(), "fxmode");
        dir.mkdirs();
        File f = new File(dir, name);
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float s = (float) (Math.sin(i * 2 * Math.PI * 440 / 48000) * 0.2);
                for (int c = 0; c < ch; c++) buf[i * ch + c] = s;
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
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    @Test
    public void theFxChipIsOnlyHighlightedWhileTheRackIsOpen() throws Exception {
        int frames = 4800;
        File raw = createFloatWav("chip_highlight.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        // A rack with an effect in it, but the panel is closed.
        DspChain.EffectSlot slot =
                cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        slot.value = 80;
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Chip Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 301L, "Chip Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);

        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        assertFalse("The rack must start closed", frag.isFxPanelOpen());
        // The studio paints the selected chip white and the idle one grey,
        // so the text colour is the chip's own selected/idle signal.
        int idleColour = btnDspFx.getCurrentTextColor();
        assertNotEquals("A pending rack must not highlight the FX chip",
                SELECTED_CHIP_TEXT, idleColour);

        // Effects mode needs a region before it can open.
        ((WaveformView) root.findViewById(R.id.waveform))
                .selectRange(0.25f, 0.75f);

        // Open the rack: now the chip is highlighted.
        btnDspFx.performClick();
        assertTrue("The rack must be open", frag.isFxPanelOpen());
        assertEquals("An open rack must highlight the FX chip",
                SELECTED_CHIP_TEXT, btnDspFx.getCurrentTextColor());
    }

    /** Text colour the studio uses for a selected (highlighted) chip. */
    private static final int SELECTED_CHIP_TEXT = 0xFFFFFFFF;

    /** The attached take loads its bars on a worker; wait for that handoff. */
    private static void awaitHistory(WaveformView wf) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (wf.historyCount() <= 1
                && System.currentTimeMillis() < deadline) {
            Robolectric.flushForegroundThreadScheduler();
            Thread.sleep(10L);
        }
        Robolectric.flushForegroundThreadScheduler();
    }

    @Test
    public void theRackCommitsToTheRegionSelectedBeforeItOpened()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("frozen_target.wav", frames, 1);
        float[] before = readSamples(raw, frames, 1);

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf =
                cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        shelf.value = 600;
        shelf.extraParams.put("freq", 20000);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Frozen Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 302L, "Frozen Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);

        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);

        // Select the second half BEFORE opening the rack.
        wf.selectRange(0.5f, 1f);
        btnDspFx.performClick();
        assertTrue("The rack must be open", frag.isFxPanelOpen());

        // Stop commits the rack to that region.
        frag.applyEffectsToSelection();

        float[] after = readSamples(raw, frames, 1);
        float peakFirstHalf = 0f;
        float peakSecondHalf = 0f;
        for (int i = 100; i < 2000; i++) {
            peakFirstHalf = Math.max(peakFirstHalf, Math.abs(after[i]));
        }
        for (int i = 2600; i < 4600; i++) {
            peakSecondHalf = Math.max(peakSecondHalf, Math.abs(after[i]));
        }
        assertEquals("The first half must stay dry",
                0.20f, peakFirstHalf, 0.03f);
        assertEquals("The selected half must carry the effect",
                0.40f, peakSecondHalf, 0.06f);
        // The untouched half must be bit-identical.
        for (int i = 100; i < 2000; i++) {
            assertEquals("The dry region must be untouched at " + i,
                    before[i], after[i], 1e-6f);
        }
    }

    @Test
    public void anEmptyRackDoesNotArmTheChain() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        assertFalse("An empty rack must stay inactive", cfg.isActive());
        DspChain chain = new DspChain(1, cfg);
        assertFalse("An empty rack must not process audio", chain.isActive());
    }

    @Test
    public void openingEffectsModeWithoutASelectionAsksForOne()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("needs_selection.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Needs Selection", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 303L, "Needs Selection");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);

        assertFalse("A fresh session has no selection", wf.hasSelection());
        btnDspFx.performClick();

        assertFalse("FX mode must not open without a region",
                frag.isFxPanelOpen());
        assertTrue("Tapping FX must enable selection like Trim does",
                wf.hasSelection());

        // With a region selected the same chip now enters the mode.
        btnDspFx.performClick();
        assertTrue("FX mode must open once a region exists",
                frag.isFxPanelOpen());
    }

    @Test
    public void everyOtherEditingActionIsLockedInsideEffectsMode()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("locked_mode.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Locked Mode", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 304L, "Locked Mode");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        TextView btnSelect = root.findViewById(R.id.btnSelectRange);
        TextView btnReplaceMode = root.findViewById(R.id.btnPreviewPlay);
        TextView btnReplace = root.findViewById(R.id.btnReplaceSeg);
        TextView btnTrim = root.findViewById(R.id.btnTrimCrop);
        TextView btnUndo = root.findViewById(R.id.btnEditUndo);

        assertTrue("Trim must be live outside effects mode",
                btnTrim.isEnabled());
        wf.selectRange(0.25f, 0.75f);
        btnDspFx.performClick();
        assertTrue(frag.isFxPanelOpen());

        assertTrue("Select must stay live to pick a noise-learning range",
                btnSelect.isEnabled());
        assertFalse("Replace must be locked inside effects mode",
                btnReplaceMode.isEnabled());
        assertFalse("Select & Replace must be locked inside effects mode",
                btnReplace.isEnabled());
        assertFalse("Trim must be locked inside effects mode",
                btnTrim.isEnabled());
        assertFalse("Undo must be locked inside effects mode",
                btnUndo.isEnabled());
        assertTrue("The FX chip must stay live so the mode can be closed",
                btnDspFx.isEnabled());

        btnDspFx.performClick();
        assertFalse(frag.isFxPanelOpen());
        assertTrue("Closing the rack must unlock Trim", btnTrim.isEnabled());
    }

    @Test
    public void closingTheRackRestoresTheDryWaveform()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("dry_restore.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf =
                cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        shelf.value = 600;
        shelf.extraParams.put("freq", 20000);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Dry Restore", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 305L, "Dry Restore");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);

        wf.selectRange(0.25f, 0.75f);
        btnDspFx.performClick();
        assertTrue(frag.isFxPanelOpen());

        // Splice a synthetic effect-applied region and verify the view knows
        // it is showing processed bars, then verify closing restores dry.
        float[] peaks = new float[wf.getHistoryCount()];
        java.util.Arrays.fill(peaks, 0.9f);
        wf.setFxRegionPeaks(peaks, 0.25f, 0.75f);
        assertTrue("The waveform must show the effect-applied region",
                wf.hasFxRegion());

        btnDspFx.performClick();
        assertFalse(frag.isFxPanelOpen());
        assertFalse("Closing FX mode must restore the dry waveform",
                wf.hasFxRegion());
    }

    @Test
    public void learningSelectionIsClampedToTheFrozenTarget()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("clamped_selection.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Clamped", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 306L, "Clamped");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        awaitHistory(wf);

        wf.selectRange(0.25f, 0.75f);
        btnDspFx.performClick();
        assertTrue(frag.isFxPanelOpen());

        // Even a request for the whole take is confined to the target, so a
        // learning selection can never move the region Stop applies to.
        wf.selectRange(0.05f, 0.95f);
        assertEquals("The left handle must stay inside the target",
                0.25f, wf.selectionStartFraction(), 0.02f);
        assertEquals("The right handle must stay inside the target",
                0.75f, wf.selectionEndFraction(), 0.02f);

        wf.clearSelection();
        assertTrue("The target stays shaded when the handles are hidden",
                wf.hasFxRegion());
    }

    @Test
    public void effectsModeKeepsThePlayheadInsideItsTarget() {
        WaveformView wf = new WaveformView(ctx);
        for (int i = 0; i < 100; i++) wf.push(0.5f);
        wf.setFxRegionBounds(0.25f, 0.75f);

        wf.setPlayheadFraction(0.1f);
        assertEquals("Playhead must clamp up to the target start",
                0.25f, wf.playheadFraction(), 0.01f);

        wf.setPlayheadFraction(0.9f);
        assertEquals("Playhead must clamp down to the target end",
                0.75f, wf.playheadFraction(), 0.01f);

        wf.setPlayheadFraction(0.5f);
        assertEquals("A playhead inside the target is untouched",
                0.5f, wf.playheadFraction(), 0.01f);

        wf.clearFxRegion();
        wf.setPlayheadFraction(0.9f);
        assertEquals("Leaving effects mode releases the clamp",
                0.9f, wf.playheadFraction(), 0.01f);
    }

    @Test
    public void rotationKeepsEffectsModeOpen() throws Exception {
        int frames = 4800;
        File raw = createFloatWav("rotate_fx.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Rotate FX", 1,
                RecordingEngine.payloadBytesOf(raw));
        org.robolectric.android.controller.ActivityController<MainActivity>
                controller = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible();
        MainActivity act = controller.get();
        act.openStudioWithEdit(raw, 307L, "Rotate FX");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        awaitHistory(wf);

        wf.selectRange(0.2f, 0.8f);
        btnDspFx.performClick();
        assertTrue(frag.isFxPanelOpen());

        controller.recreate();
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment restored = (StudioFragment) controller.get()
                .getSupportFragmentManager().findFragmentByTag("STU");
        assertNotNull("The studio must survive rotation", restored);
        assertNotNull(restored.getView());
        WaveformView restoredWf = restored.getView()
                .findViewById(R.id.waveform);
        awaitHistory(restoredWf);
        assertTrue("FX mode must survive rotation", restored.isFxPanelOpen());
        assertTrue("The restored mode must still shade its target",
                restoredWf.hasFxRegion());
    }

    @Test
    public void durationChangingEffectsUpdateTimelineInRealTimeAndPreserveFxScrollBounds()
            throws Exception {
        int frames = 48000; // 1.0 s take -> 20 buckets at 20 Hz
        File raw = createFloatWav("realtime_stretch.wav", frames, 1);

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        // 0.5x speed doubles the selected region's duration!
        DspChain.EffectSlot speed =
                cfg.addOrFocusSlot(DspChain.EffectType.CHANGE_SPEED_AND_PITCH);
        speed.value = 50; // 0.50x speed
        cfg.save(ctx);

        DspChain chain = new DspChain(1, cfg);
        File previewWav = new File(ctx.getCacheDir(), "live_preview_stretch.wav");
        FxPreviewPeaks.LiveRegionResult res = FxPreviewPeaks.renderRegionLive(
                raw, chain, 20, 48000, 0.25f, 0.75f, previewWav);
        assertNotNull("Live region result must not be null", res);
        assertTrue("Change Speed must report lengthChanged", res.lengthChanged);
        // Original take: 48,000 frames; selected [0.25, 0.75) = 24,000 frames
        // At 0.5x speed, selected region becomes 48,000 frames -> total 72,000 frames (30 buckets)!
        assertEquals(72000L, res.newTotalFrames);
        assertEquals(20, res.peaks.length);

        WaveformView wf = new WaveformView(ctx);
        for (int i = 0; i < 20; i++) wf.push(0.4f);
        wf.setLive(false);
        wf.setFxRegionBounds(0.25f, 0.75f);
        wf.setFxRegionStretchedPeaks(res.peaks, 0.25f, 0.75f, res.newTotalFrames);

        // Total bars updated in real time from 20 -> 30 (5 prefix + 20 stretched + 5 suffix)
        assertEquals(30, wf.getHistoryCount());
        assertEquals(20, wf.getBaseHistoryCount());
        assertTrue("FX region must remain active after stretch", wf.hasFxRegion());
        assertEquals(5f / 30f, wf.fxRegionStartFraction(), 0.02f);
        assertEquals(25f / 30f, wf.fxRegionEndFraction(), 0.02f);

        // Scrolling/clamping in FX mode must stay bounded to the stretched FX region!
        wf.setPlayheadFraction(0.01f);
        assertEquals("Playhead must clamp to stretched FX region start",
                5f / 30f, wf.playheadFraction(), 0.02f);
        wf.setPlayheadFraction(0.99f);
        assertEquals("Playhead must clamp to stretched FX region end",
                25f / 30f, wf.playheadFraction(), 0.02f);

        // Closing FX mode restores the original 20 dry bars and [0.25, 0.75] bounds
        wf.clearFxRegion();
        assertEquals(20, wf.getHistoryCount());
        assertFalse(wf.hasFxRegion());
        previewWav.delete();
    }

    @Test
    public void audioGeneratorDurationTimerResizesRegionAtAllValuesIncludingDefault() {
        AudioGeneratorTool gen = new AudioGeneratorTool();
        gen.prepare(48000, 1);
        // Even at default 5.0 s (500 raw) or custom 2.0 s (200 raw), outFrames follows gen_dur:
        float[] in = new float[24000]; // 0.5 s selection
        float[] outDefault5s = gen.processOfflineRegion(in, 24000, 1);
        assertEquals(48000 * 5, outDefault5s.length);

        gen.getParam("gen_dur").setFloat(2.0f);
        float[] out2s = gen.processOfflineRegion(in, 24000, 1);
        assertEquals(48000 * 2, out2s.length);
    }
}

