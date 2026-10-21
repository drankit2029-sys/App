package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
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
 * Regression suite for the Stage 3 FX application audit (BUG-01..BUG-07):
 * proves that library takes opened via "Edit" (which attach in State.IDLE)
 * have the editor strip, FX rack, Preview, and Apply enabled immediately;
 * that EqCurveView canvases sync with TeleValue edits, disallow parent scroll
 * interception, and commit only the dragged band/slider/node live; that
 * Stage 3 primary parameters cannot be clobbered by extraParams; and that
 * shared prototype processors are never mutated by UI formatting.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class Stage3FxAuditFixTest {

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
        File dir = RecordingEngine.takesDir(ctx);
        File f = new File(dir, name);
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4L));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float s = (float) (0.35 * Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0));
                for (int c = 0; c < ch; c++) {
                    buf[i * ch + c] = s;
                }
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        }
        return f;
    }

    private static float[] readSamples(File wav, int frames, int ch) throws Exception {
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

    // ---- BUG-01: Library "Edit" session in State.IDLE unlocks FX & applies ----

    @Test
    public void libraryEditSessionInIdleStateEnablesEditorStripAndAppliesFx()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("edit_idle_fx.wav", frames, 1);
        float[] before = readSamples(raw, frames, 1);

        // Attach exactly as LibraryFragment.startEditFromFile() does: state is IDLE.
        engine.attachExisting(ctx, raw, "Idle Edit Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        assertEquals(RecordingEngine.State.IDLE, engine.state);

        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 101L, "Idle Edit Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull("StudioFragment not attached", frag);
        View root = frag.getView();
        assertNotNull(root);

        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        TextView btnSelectRange = root.findViewById(R.id.btnSelectRange);
        TextView btnTrimCrop = root.findViewById(R.id.btnTrimCrop);
        assertTrue("FX button must be enabled on an IDLE attached edit session",
                btnDspFx.isEnabled());
        assertTrue("Select button must be enabled on an IDLE attached edit session",
                btnSelectRange.isEnabled());
        assertTrue("Trim button must be enabled on an IDLE attached edit session",
                btnTrimCrop.isEnabled());

        // Tap FX button to open the inline FX rack.
        btnDspFx.performClick();
        View fxPanel = root.findViewById(R.id.fxPanel);
        assertEquals("FX panel did not open on IDLE edit session",
                View.VISIBLE, fxPanel.getVisibility());

        TextView fxApplyBtn = root.findViewById(R.id.fxApplyBtn);
        TextView fxPreviewBtn = root.findViewById(R.id.fxPreviewBtn);
        assertTrue("FX Preview button must be enabled in IDLE edit session",
                fxPreviewBtn.isEnabled());
        assertTrue("FX Apply button must be enabled in IDLE edit session",
                fxApplyBtn.isEnabled());

        // Tap "Apply to Entire Take" and verify the audio samples on disk are modified!
        fxApplyBtn.performClick();
        float[] after = readSamples(raw, frames, 1);
        double diffEnergy = 0;
        for (int i = 0; i < frames; i++) {
            double d = after[i] - before[i];
            diffEnergy += d * d;
        }
        assertTrue("Applying FX in an IDLE Library-Edit session did not modify audio",
                diffEnergy > 1e-3);
    }

    // ---- BUG-02A & 02B: ParametricEqView syncs with TeleValue & commits only active band ----

    @Test
    public void parametricEqViewPreservesTeleValueEditsAndUpdatesLiveOnDrag() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot slot = cfg.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6);
        // Simulate adjusting Band 4 Gain via the TeleValue control below the canvas:
        slot.extraParams.put("b4g", 900); // +9.0 dB on Band 4 (2500 Hz)

        final int[] mutations = {0};
        ParametricEqView view = new ParametricEqView(ctx, slot, () -> mutations[0]++);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 180);

        // Response at 2500 Hz must reflect the +9.0 dB TeleValue edit after sync:
        view.syncFromSlot();
        assertEquals(9.0f, view.responseDb(2500f), 0.6f);

        // Drag Band 2 (350 Hz) vertically to boost it, and verify Band 4's +9.0 dB
        // is NOT clobbered and onMutated fires during ACTION_MOVE!
        float b2x = view.xForFreq(350f, 400);
        float b2y = view.yForDb(0f, 180);
        float targetY = view.yForDb(6f, 180);
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, b2x, b2y, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, b2x, targetY, 0));
        assertTrue("Dragging EQ handle must trigger live onMutated during ACTION_MOVE",
                mutations[0] >= 1);
        view.onTouchEvent(MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, b2x, targetY, 0));

        assertEquals("Dragging Band 2 clobbered Band 4's TeleValue setting",
                Integer.valueOf(900), slot.extraParams.get("b4g"));
        assertTrue("Band 2 gain was not committed to slot.extraParams",
                slot.extraParams.containsKey("b2g") && slot.extraParams.get("b2g") > 300);
    }

    @Test
    public void parametricEqViewExposesAndMovesAllSixBandHandles() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot slot = cfg.addOrFocusSlot(
                DspChain.EffectType.PARAMETRIC_EQ6);
        final int[] mutations = {0};
        ParametricEqView view = new ParametricEqView(ctx, slot,
                () -> mutations[0]++);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 180);
        assertEquals("6-band EQ must expose six band handles",
                6, ParametricEqView.bandCount());

        float zero = view.yForDb(0f, 180);
        long now = SystemClock.uptimeMillis();

        // Band 1 (low cut) has no gain handle; dragging horizontally must
        // move its corner frequency instead of selecting band 2.
        float lowStart = view.xForFreq(80f, 400);
        float lowEnd = view.xForFreq(140f, 400);
        view.onTouchEvent(MotionEvent.obtain(now, now,
                MotionEvent.ACTION_DOWN, lowStart, zero, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 16,
                MotionEvent.ACTION_MOVE, lowEnd, zero, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 32,
                MotionEvent.ACTION_UP, lowEnd, zero, 0));
        assertTrue("Low-cut handle must move band 1 frequency",
                slot.extraParams.getOrDefault("b1f", 80) >= 120);
        assertEquals("Low-cut drag must not move a bell band",
                350, slot.extraParams.getOrDefault("b2f", 350).intValue());

        // Band 6 (high cut) is the sixth handle at the right edge.
        float highStart = view.xForFreq(20000f, 400);
        float highEnd = view.xForFreq(12000f, 400);
        view.onTouchEvent(MotionEvent.obtain(now, now + 48,
                MotionEvent.ACTION_DOWN, highStart, zero, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 64,
                MotionEvent.ACTION_MOVE, highEnd, zero, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 80,
                MotionEvent.ACTION_UP, highEnd, zero, 0));
        assertTrue("High-cut handle must move band 6 frequency",
                slot.extraParams.getOrDefault("b6f", 20000) <= 13000);
        assertTrue("All six band drags must fire onMutated", mutations[0] >= 2);
    }

    @Test
    public void graphicEqViewAndFilterCurveEqViewCommitLiveOnDrag() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot geqSlot = cfg.addOrFocusSlot(DspChain.EffectType.GRAPHIC_EQ);
        geqSlot.enabled = false;
        final int[] geqMut = {0};
        GraphicEqView geqView = new GraphicEqView(ctx, geqSlot, () -> geqMut[0]++);
        geqView.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        geqView.layout(0, 0, 400, 180);

        float x1k = geqView.xForFreq(1000f, 400);
        float y0 = geqView.yForDb(0f, 180);
        float yBoost = geqView.yForDb(8f, 180);
        long now = SystemClock.uptimeMillis();
        geqView.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1k, y0, 0));
        geqView.onTouchEvent(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, x1k, yBoost, 0));
        geqView.onTouchEvent(MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, x1k, yBoost, 0));

        assertTrue("Dragging Graphic EQ slider must auto-enable bypassed slot",
                geqSlot.enabled);
        assertTrue("Graphic EQ drag must fire onMutated", geqMut[0] >= 1);
        assertTrue("Band 5 (1 kHz) gain not stored in extraParams",
                geqSlot.extraParams.getOrDefault("g5", 0) > 400);

        DspChain.EffectSlot fcSlot = cfg.addOrFocusSlot(DspChain.EffectType.FILTER_CURVE_EQ);
        fcSlot.enabled = false;
        final int[] fcMut = {0};
        FilterCurveEqView fcView = new FilterCurveEqView(ctx, fcSlot, () -> fcMut[0]++);
        fcView.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        fcView.layout(0, 0, 400, 180);

        float nx = fcView.xForFreq((float) FilterCurveEqProcessor.defaultFreq(2), 400);
        float ny = fcView.yForDb(0f, 180);
        float nyBoost = fcView.yForDb(10f, 180);
        fcView.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, nx, ny, 0));
        fcView.onTouchEvent(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, nx, nyBoost, 0));
        fcView.onTouchEvent(MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, nx, nyBoost, 0));

        assertTrue("Dragging Filter Curve node must auto-enable bypassed slot",
                fcSlot.enabled);
        assertTrue("Filter Curve drag must fire onMutated during move", fcMut[0] >= 1);
        assertTrue("Filter Curve node 2 gain not stored in extraParams",
                fcSlot.extraParams.getOrDefault("pg2", 0) > 500);
    }

    // ---- BUG-02C: Stage 3 primary parameter cannot be clobbered by extraParams ----

    @Test
    public void stage3PrimarySlotValueIsNotClobberedByStaleExtraParams() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf = cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        shelf.value = 900; // +9.0 dB on primary "gain" dial
        shelf.extraParams.put("gain", 0); // stale extraParam entry

        DspChain chain = new DspChain(1, cfg);
        AudioProcessor proc = chain.getRack().getProcessor(
                chain.getRack().indexOf("shelf"));
        assertNotNull(proc);
        assertEquals("Primary slot.value must win over stale extraParams entry",
                900, proc.getParam("gain").getCurrentRaw());
    }

    // ---- BUG-03: Shared prototype processor is not mutated by UI formatting ----

    @Test
    public void formatSlotSubtitleDoesNotMutateSharedPrototypeProcessor() {
        AudioProcessor proto = FxRackChain.getPrototypeProcessor("eq6");
        assertNotNull(proto);
        FxParameter outParam = proto.getParam("out");
        FxParameter b2gParam = proto.getParam("b2g");
        assertNotNull(outParam);
        assertNotNull(b2gParam);
        assertEquals(0, outParam.getCurrentRaw());
        assertEquals(0, b2gParam.getCurrentRaw());

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot slot = cfg.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6);
        slot.value = -650;
        slot.extraParams.put("b2g", 1200);

        String sub = StudioFragment.formatSlotSubtitle(slot, cfg);
        assertTrue("Subtitle should show -6.5 dB: " + sub, sub.contains("-6.5 dB"));
        assertEquals("Prototype 'out' raw value was mutated by formatSlotSubtitle",
                0, outParam.getCurrentRaw());
        assertEquals("Prototype 'b2g' raw value was mutated by formatSlotSubtitle",
                0, b2gParam.getCurrentRaw());
    }

    // ---- BUG-06: Stage 3 slots with negative dB value still split Warmth & Presence ----

    @Test
    public void stage3EffectWithZeroOrNegativeValueStillSplitsWarmthAndPresenceOrder() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot w = cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        DspChain.EffectSlot eq = cfg.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6);
        DspChain.EffectSlot p = cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        w.value = 80;
        eq.value = -600; // -6.0 dB output trim (negative slot.value!)
        eq.extraParams.put("b4g", -1200);
        p.value = 80;

        DspChain chain = new DspChain(1, cfg);
        float[] sig = new float[1024];
        for (int i = 0; i < sig.length; i++) {
            sig[i] = (float) (0.5 * Math.sin(i * 0.08) + 0.3 * Math.sin(i * 0.55));
        }
        float[] outSplit = sig.clone();
        chain.processInPlace(outSplit, 1024);

        // Compare against adjacent Warmth + Presence before Parametric EQ:
        DspChain.Config cfgAdj = new DspChain.Config();
        cfgAdj.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot w2 = cfgAdj.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        DspChain.EffectSlot p2 = cfgAdj.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        DspChain.EffectSlot eq2 = cfgAdj.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6);
        w2.value = 80;
        p2.value = 80;
        eq2.value = -600;
        eq2.extraParams.put("b4g", -1200);

        float[] outAdj = sig.clone();
        new DspChain(1, cfgAdj).processInPlace(outAdj, 1024);

        boolean diff = false;
        for (int i = 0; i < outSplit.length; i++) {
            if (Math.abs(outSplit[i] - outAdj[i]) > 1e-4f) {
                diff = true;
                break;
            }
        }
        assertTrue("Stage 3 processor with negative slot.value failed to split Warmth & Presence",
                diff);
    }

    // ---- Empty rack must not offer a dry "preview" that looks broken ----

    @Test
    public void emptyFxRackDisablesPreviewAndApplyUntilAnEffectIsEnabled()
            throws Exception {
        File raw = createFloatWav("empty_rack_preview.wav", 2400, 1);
        DspChain.Config empty = new DspChain.Config();
        empty.clearRack();
        empty.save(ctx);

        engine.attachExisting(ctx, raw, "Empty Rack Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 102L, "Empty Rack Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull("StudioFragment not attached", frag);
        View root = frag.getView();
        assertNotNull(root);

        root.findViewById(R.id.btnDspFx).performClick();
        assertEquals(View.VISIBLE,
                root.findViewById(R.id.fxPanel).getVisibility());

        TextView preview = root.findViewById(R.id.fxPreviewBtn);
        TextView apply = root.findViewById(R.id.fxApplyBtn);
        assertFalse("Preview with no active effects would just play dry audio",
                preview.isEnabled());
        assertFalse("Apply must stay disabled with an empty rack",
                apply.isEnabled());
    }

    @Test
    public void effectExpandButtonOpensDedicatedModalOverlay()
            throws Exception {
        File raw = createFloatWav("effect_modal.wav", 2400, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.clearRack();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.PARAMETRIC_EQ6, true,
                DspChain.EffectType.PARAMETRIC_EQ6.defaultVal, true));
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Modal Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 103L, "Modal Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        root.findViewById(R.id.btnDspFx).performClick();

        View expand = findViewByContentDescription(root, "Expand effect");
        assertNotNull("Every effect pill must expose an expand button", expand);
        expand.performClick();

        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog
                .getLatestDialog();
        assertNotNull("Expand must open a modal", dialog);
        assertTrue(dialog.isShowing());
        View decor = dialog.getWindow() == null
                ? null : dialog.getWindow().getDecorView();
        assertNotNull(decor);
        assertNotNull("Dedicated inspector missing its close action",
                findTextStartingWith(decor, "Close"));
        assertNotNull("Dedicated inspector missing its parameter section",
                findTextStartingWith(decor, "All parameters"));
        dialog.dismiss();
    }

    @Test
    public void changingGraphicEqLayoutKeepsAdvancedControlsExpanded()
            throws Exception {
        File raw = createFloatWav("graphic_layout.wav", 2400, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.clearRack();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.GRAPHIC_EQ, true,
                DspChain.EffectType.GRAPHIC_EQ.defaultVal, true));
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Graphic Layout Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 104L, "Graphic Layout Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);
        root.findViewById(R.id.btnDspFx).performClick();

        TextView showAdvanced = findTextStartingWith(root,
                "Show advanced controls");
        assertNotNull(showAdvanced);
        showAdvanced.performClick();

        TextView layout = findTextStartingWith(root, "Layout: 10 bands");
        assertNotNull(layout);
        layout.performClick();

        assertNotNull("Changing the layout must not collapse the advanced editor",
                findTextStartingWith(root, "Hide advanced controls"));
        assertNotNull("Layout control must still be visible after the edit",
                findTextStartingWith(root, "Layout: 31 bands"));
    }

    private static View findViewByContentDescription(View root, String text) {
        if (root == null) return null;
        CharSequence desc = root.getContentDescription();
        if (desc != null && text.contentEquals(desc)) return root;
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findViewByContentDescription(
                    group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static TextView findTextStartingWith(View root, String prefix) {
        if (root == null) return null;
        if (root instanceof TextView) {
            CharSequence value = ((TextView) root).getText();
            if (value != null && value.toString().startsWith(prefix)) {
                return (TextView) root;
            }
        }
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findTextStartingWith(group.getChildAt(i), prefix);
            if (found != null) return found;
        }
        return null;
    }
}
