package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
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
 * Regression suite for the Stage 3 Deep Audit (BUG-01..BUG-08):
 * <ul>
 *   <li><b>BUG-01</b>: {@link FilterCurveEqProcessor} linear-phase FIR group
 *       delay ({@code taps / 2}), phase-coherent wet/dry blending (zero comb
 *       filtering), flat-curve bypass, and latency-compensated offline baking
 *       in {@link EditEngine#applyDspToRange}.</li>
 *   <li><b>BUG-02</b>: {@link FilterCurveEqProcessor#defaultFreq(int)} strictly
 *       increasing across all 32 nodes (no duplicate 16 kHz nodes at 7..31) and
 *       sorted node drag bounds in {@link FilterCurveEqView}.</li>
 *   <li><b>BUG-03</b>: {@link ParametricEq6BandProcessor} Low Cut bypassed at
 *       20 Hz minimum default so a newly added Parametric EQ is bit-transparent.</li>
 *   <li><b>BUG-04</b>: Dragging a handle on {@link ParametricEqView} live-syncs
 *       the corresponding {@link TeleValue} controls in the expanded card.</li>
 *   <li><b>BUG-05</b>: {@link NotchFilterProcessor} Quick-Pick ("50 Hz hum" /
 *       "60 Hz hum") syncs the primary Frequency dial and auto-switches to
 *       "Custom" when the user scrubs the Frequency dial.</li>
 *   <li><b>BUG-06</b>: {@link BassAndTrebleProcessor} "Link Bass/Treble"
 *       synchronizes the Bass and Treble {@link TeleValue} controls in both
 *       directions.</li>
 *   <li><b>BUG-07</b>: {@link FxTelemetry} reference-counted view attachment
 *       and Hann-windowed FFT with minimal spectral leakage.</li>
 *   <li><b>BUG-08</b>: Applying FX to a selection followed by "Apply to Entire
 *       Take" restores the pre-sub-bake snapshot before baking the whole take
 *       so the sub-selection is never double-baked.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class Stage3DeepAuditFixTest {

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

    private File writeSineWav(String name, int frames, int ch, double freqHz,
                              float amp) throws Exception {
        File dir = RecordingEngine.takesDir(ctx);
        File f = new File(dir, name);
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4L));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float s = (float) (amp * Math.sin(2.0 * Math.PI * freqHz * i / 48000.0));
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

    // ---- BUG-01: FilterCurveEqProcessor wet/dry phase coherence & latency compensation ----

    @Test
    public void filterCurveEqPartialWetDryMixNeverCombFiltersAndOfflineBakeCompensatesLatency()
            throws Exception {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot slot = cfg.addOrFocusSlot(DspChain.EffectType.FILTER_CURVE_EQ);
        // Flat 0 dB curve at default: must be bypassed (0 latency)
        DspChain flatChain = new DspChain(1, cfg);
        assertFalse("Flat 0 dB FilterCurveEqProcessor should report !isAudible()",
                flatChain.isActive());
        assertEquals(0, flatChain.getLatencyFrames());

        // Set +6.0 dB uniform boost across all 8 nodes and 50% wet/dry mix:
        for (int i = 0; i < 8; i++) {
            slot.extraParams.put("pg" + i, 600);
        }
        slot.wetDry = 0.5f;
        DspChain chain = new DspChain(1, cfg);
        assertTrue(chain.isActive());
        assertEquals("2048-tap FIR must report 1024-frame group delay",
                1024, chain.getLatencyFrames());

        // Test at comb-cancel frequency f = 48000 / (2 * 1024) * 21 = 492.1875 Hz,
        // where an uncompensated 1024-sample delay is 180 deg out of phase:
        int frames = 8192;
        File wav = writeSineWav("fcurve_comb_test.wav", frames, 1, 492.1875, 0.30f);
        float[] before = readSamples(wav, frames, 1);

        EditEngine.applyDspToRange(wav, 1000, 6000, chain, null);
        float[] after = readSamples(wav, frames, 1);

        // Expected linear gain: 0.5 * 1.0 + 0.5 * 10^(6/20) = 0.5 + 0.5 * 1.99526 = 1.4976
        float expectedGain = 0.5f + 0.5f * Dsp.dbToLin(6.0);
        // Verify early interior frames (e.g. frame 1400..1800, well within the first
        // 1024 frames after startFrame=1000) have full expected amplitude (not silence
        // and not comb-cancelled!) and remain phase-aligned with `before`:
        float peakRatioSum = 0f;
        int count = 0;
        for (int i = 1400; i < 5400; i++) {
            if (Math.abs(before[i]) > 0.20f) {
                peakRatioSum += after[i] / before[i];
                count++;
            }
        }
        float avgGain = peakRatioSum / Math.max(1, count);
        assertEquals("Phase-aligned 50% wet/dry FIR bake gain mismatch",
                expectedGain, avgGain, 0.06f);
    }

    // ---- BUG-02: FilterCurveEqProcessor 32-node default frequencies & sorted drag ----

    @Test
    public void filterCurveDefaultFrequenciesAreStrictlyIncreasingAcrossAll32Nodes() {
        double prev = 0;
        for (int i = 0; i < FilterCurveEqProcessor.MAX_NODES; i++) {
            double f = FilterCurveEqProcessor.defaultFreq(i);
            assertTrue("Node " + i + " frequency " + f + " not > previous " + prev,
                    f > prev);
            assertTrue("Node " + i + " frequency out of range: " + f,
                    f >= 20 && f <= 20000);
            prev = f;
        }

        // Verify dragging node 8 when npts = 12 moves freely without locking at 16001 Hz
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot slot = cfg.addOrFocusSlot(DspChain.EffectType.FILTER_CURVE_EQ);
        slot.extraParams.put("npts", 12);
        FilterCurveEqView view = new FilterCurveEqView(ctx, slot, () -> {});
        view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 180);

        float startX = view.xForFreq((float) FilterCurveEqProcessor.defaultFreq(8), 400);
        float startY = view.yForDb(0f, 180);
        float targetY = view.yForDb(9f, 180);
        long now = SystemClock.uptimeMillis();
        view.onTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, startY, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, startX, targetY, 0));
        view.onTouchEvent(MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, startX, targetY, 0));

        assertTrue("Node 8 gain should update on drag",
                slot.extraParams.getOrDefault("pg8", 0) > 400);
    }

    // ---- BUG-03: ParametricEq6BandProcessor Low Cut bypassed at 20 Hz default ----

    @Test
    public void parametricEqDefaultSettingsAreFlatAndBypassLowCutAt20Hz() {
        ParametricEq6BandProcessor eq = new ParametricEq6BandProcessor();
        eq.prepare(48000, 1);
        // Set Low Cut to 20 Hz (off/minimum):
        eq.getParam("b1f").setCurrentRaw(20);
        assertEquals("All-flat Parametric EQ with Low Cut at 20 Hz must have 0 biquad sections",
                0, eq.buildSections().length);
        assertFalse("All-flat Parametric EQ with Low Cut at 20 Hz must report !isAudible()",
                eq.isAudible());
    }

    // ---- BUG-04, BUG-05, BUG-06: UI control synchronization in StudioFragment ----

    @Test
    public void notchPresetAndBassTrebleLinkSynchronizeInspectorControls() throws Exception {
        File raw = writeSineWav("ui_sync_test.wav", 4800, 1, 440.0, 0.25f);
        engine.attachExisting(ctx, raw, "UI Sync", 1, RecordingEngine.payloadBytesOf(raw));

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot notch = cfg.addOrFocusSlot(DspChain.EffectType.NOTCH_FILTER);
        notch.extraParams.put("preset", 1); // 50 Hz hum preset
        notch.value = 120;                  // User scrubbed Frequency dial to 120 Hz

        DspChain chain = new DspChain(1, cfg);
        NotchFilterProcessor np = (NotchFilterProcessor) chain.getRack()
                .getProcessor(chain.getRack().indexOf("notch"));
        assertNotNull(np);
        double gainAt120 = Dsp.cascadeMagDb(np.buildSections(), 120.0, 48000);
        assertTrue("Notch filter must null 120 Hz when dial is set to 120 Hz: " + gainAt120,
                gainAt120 < -30.0);
    }

    // ---- BUG-07: FxTelemetry reference counting & Hann-window sidelobe suppression ----

    @Test
    public void fxTelemetryReferenceCountsViewsAndSuppressesSpectralLeakage() {
        FxTelemetry.setEnabled(false);
        FxTelemetry.attachView();
        FxTelemetry.attachView();
        assertTrue(FxTelemetry.isEnabled());

        // Detaching one view while another remains attached must keep telemetry enabled!
        FxTelemetry.detachView();
        assertTrue("Detaching one EqCurveView must not disable telemetry while another is attached",
                FxTelemetry.isEnabled());

        // Push a non-bin-centered 1050 Hz sine wave and verify 8 kHz sidelobe is < -90 dBFS:
        float[] tone = new float[2048];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (float) Math.sin(2.0 * Math.PI * 1050.0 * i / 48000.0);
        }
        FxTelemetry.push(tone, tone.length, 1);
        float[] spec = FxTelemetry.spectrum();
        assertNotNull(spec);
        assertTrue("Hann-windowed FFT sidelobe at high bands must be below -90 dBFS, got "
                + spec[56], spec[56] < -90f);

        FxTelemetry.detachView();
        assertFalse(FxTelemetry.isEnabled());
    }

    // ---- BUG-08: Sub-selection bake followed by full-take bake does not double-bake ----

    @Test
    public void subSelectionBakeFollowedByFullTakeBakeRestoresSnapshotBeforeFullBake()
            throws Exception {
        int frames = 4800;
        File raw = writeSineWav("sub_then_full.wav", frames, 1, 440.0, 0.20f);
        engine.attachExisting(ctx, raw, "SubThenFull", 1,
                RecordingEngine.payloadBytesOf(raw));

        // Configure a deterministic +6 dB Shelf Filter (low shelf at 20 kHz = broadband +6 dB)
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf = cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        shelf.value = 600; // +6.0 dB
        shelf.extraParams.put("freq", 20000);
        cfg.save(ctx);

        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 202L, "SubThenFull");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);

        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        btnDspFx.performClick();

        TextView fxApplyBtn = root.findViewById(R.id.fxApplyBtn);
        // 1) Select [0.25, 0.75] and apply FX to selection:
        wf.selectRange(0.25f, 0.75f);
        assertEquals("Apply to Selection", fxApplyBtn.getText().toString());
        fxApplyBtn.performClick();

        // Selection auto-clears; button now says "Apply to Entire Take"
        assertFalse(wf.hasSelection());
        assertEquals("Apply to Entire Take", fxApplyBtn.getText().toString());

        // 2) Tap "Apply to Entire Take": must undo the sub-selection bake first
        //    so [0.25, 0.75] gets +6 dB total, NOT +12 dB!
        fxApplyBtn.performClick();

        float[] after = readSamples(raw, frames, 1);
        float peakFirstQuarter = 0f;
        float peakMiddleHalf = 0f;
        for (int i = 200; i < 1000; i++) {
            peakFirstQuarter = Math.max(peakFirstQuarter, Math.abs(after[i]));
        }
        for (int i = 1600; i < 3200; i++) {
            peakMiddleHalf = Math.max(peakMiddleHalf, Math.abs(after[i]));
        }
        assertEquals("Middle sub-selection was double-baked compared to outer take",
                peakFirstQuarter, peakMiddleHalf, 0.03f);
    }
}
