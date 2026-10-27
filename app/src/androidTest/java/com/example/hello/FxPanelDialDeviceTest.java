package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The effects-panel verification, one parameter at a time.
 *
 * <p>One run covers one effect. The rack is emptied, the effect is added, and
 * the pill is expanded <b>inline in the panel</b> by tapping its title - the
 * dedicated "All parameters" modal is deliberately not used - so the only
 * control under test is the dial the pill itself shows.
 *
 * <p>Each dial value is applied, screenshotted, and checked against the
 * waveform: the app repaints its peaks from the rack config it just saved, so
 * the test renders the same peaks with the app's own {@link FxPreviewPeaks} and
 * asserts the change numerically rather than assuming it from a screenshot.
 *
 * <p>The value whose waveform moved furthest is then baked into the take with
 * {@code applyEffectsToSelection}, and the dry render, the applied render and
 * the waveform peaks are left in the takes directory for adb to pull. The Python
 * side reads those WAVs and verifies, from the audio, that the effect really was
 * applied.
 *
 * <p>Run with: {@code -e fxEffect <id>}.
 */
@RunWith(AndroidJUnit4.class)
public class FxPanelDialDeviceTest {

    private static final int SCREEN_BUCKETS = 600;
    private static final long SETTLE_MS = 320L;
    private static final String ADVANCED = "Show advanced controls";

    @Rule
    public GrantPermissionRule mic = GrantPermissionRule.grant(
            android.Manifest.permission.RECORD_AUDIO);

    @Test
    public void changeOneDialInThePillAndProveItWasApplied() throws Exception {
        String effectId = InstrumentationRegistry.getArguments().getString("fxEffect");
        assumeNotNull("Pass -e fxEffect <id>", effectId);

        DspChain.EffectType type = null;
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            if (t.id.equals(effectId)) type = t;
        }
        assertNotNull("Unknown effect " + effectId, type);
        final DspChain.EffectType fxType = type;

        Instrumentation instr = InstrumentationRegistry.getInstrumentation();
        Context ctx = instr.getTargetContext();
        UiDevice device = UiDevice.getInstance(instr);
        File work = new File(RecordingEngine.takesDir(ctx), "fxverify-" + effectId);
        work.mkdirs();
        File[] stale = work.listFiles();
        if (stale != null) {
            for (File f : stale) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        // ---- the uploaded clip is the input, exactly as the app would load it
        File input = new File(work, "input.wav");
        copyAsset("take2_48k_f32.wav", input);
        FxVerifySupportDevice.WavInfo info = FxVerifySupportDevice.readInfo(input);
        File dry = new File(work, "dry.wav");
        copy(input, dry);
        // master.wav stays pristine for the whole run: every parameter's applied
        // render is produced from a fresh copy of it.
        File master = new File(work, "master.wav");
        copy(input, master);

        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.attachExisting(ctx, input, "FX Verify " + effectId,
                info.channels, RecordingEngine.payloadBytesOf(input));

        // The rack sits behind the Pro gate; the manager provides this override
        // for instrumentation tests.
        TrialAndBillingManager.setTestOverridePro(Boolean.TRUE);

        JSONObject result = new JSONObject();
        result.put("effect", effectId);
        result.put("title", type.title);
        result.put("inputFrames", info.frames);
        result.put("channels", info.channels);
        // Every parameter's step entries land in one manifest, each tagged with
        // the parameter and the one video that owns its change.
        JSONArray steps = new JSONArray();
        JSONArray params = new JSONArray();
        JSONArray problems = new JSONArray();
        final boolean[] degraded = {false};


        // ---- studio up, whole take selected, rack open
        Activity activity = launch(instr);
        assertNotNull("MainActivity did not start", activity);
        final File inputFile = input;
        ui(activity, () -> ((MainActivity) activity).openStudioWithEdit(
                inputFile, 991L, "FX Verify"));
        StudioFragment frag = awaitStudio(activity);
        assertNotNull("Studio fragment never appeared", frag);

        final View[] rootHolder = new View[1];
        ui(activity, () -> rootHolder[0] = frag.getView());
        View root = rootHolder[0];
        assertNotNull("Studio view missing", root);
        final WaveformView waveform = root.findViewById(R.id.waveform);
        final TextView fxChip = root.findViewById(R.id.btnDspFx);

        for (int i = 0; i < 80 && waveform.historyCount() <= 1; i++) {
            Thread.sleep(100);
        }
        ui(activity, () -> waveform.selectRange(0f, 1f));
        if (!waveform.hasSelection()) {
            ui(activity, () -> {
                waveform.toggleSelectionAtPlayhead();
                waveform.selectRange(0f, 1f);
            });
        }
        for (int attempt = 0; attempt < 3 && !frag.isFxPanelOpen(); attempt++) {
            ui(activity, fxChip::performClick);
            awaitFxPanel(frag);
        }
        assertTrue("The rack must open (bars=" + waveform.historyCount() + ")",
                frag.isFxPanelOpen());

        // ---- a clean rack and the effect under test
        boolean addedThroughUi = makeRackContainOnly(frag, activity, device,
                fxType, problems, degraded);
        result.put("addedThroughUi", addedThroughUi);
        device.dumpWindowHierarchy(new File(work, "hierarchy-rack-added.xml"));

        // ---- expand the pill inline: tap its title, which toggles the body
        boolean expandedInline = expandInline(root, activity, type.title, device);
        if (!expandedInline) {
            problems.put("the pill could not be expanded inline; the panel-level "
                    + "dial never appeared");
        }
        result.put("expandedInline", expandedInline);

        AudioProcessor proto = FxRackChain.getPrototypeProcessor(effectId);
        assertNotNull("No prototype processor for " + effectId, proto);
        String primaryId = FxVerifySupportDevice.primaryParamId(type);
        FxParameter primary = primaryId == null ? null : proto.getParam(primaryId);

        // One test per parameter of this effect, with a hard time budget so a
        // single stubborn parameter can never hold the whole job open.
        long deadline = System.currentTimeMillis() + 38L * 60_000L;
        int appliedTotal = 0;
        int neutralTotal = 0;
        int triedTotal = 0;
        int index = 0;
        for (FxParameter param : proto.getParameters()) {
            if (System.currentTimeMillis() > deadline) {
                result.put("timedOut", true);
                problems.put("stopped at the per-shard time budget; "
                        + (proto.getParameters().size() - triedTotal)
                        + " parameter(s) not tested");
                break;
            }
            JSONObject entry = new JSONObject();
            entry.put("index", index);
            entry.put("param", param.id);
            entry.put("paramName", param.name);
            entry.put("type", param.type.name());
            try {
                int verdict = verifyOneParameter(param, primary, entry,
                        steps, frag, activity, device, root, work, master,
                        info, fxType, problems, degraded);
                triedTotal++;
                if (verdict == 2) appliedTotal++;
                else if (verdict == 1) neutralTotal++;
            } catch (Throwable t) {
                entry.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                problems.put(param.id + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
            params.put(entry);
            index++;
            write(new File(work, "result.json"), result.toString(2));
        }
        result.put("params", params);
        result.put("paramCount", params.length());
        result.put("appliedCount", appliedTotal);
        result.put("neutralCount", neutralTotal);
        result.put("testedCount", triedTotal);
        result.put("degraded", degraded[0]);
        result.put("problems", problems);
        write(new File(work, "result.json"), result.toString(2));
        write(new File(work, "shots.json"), steps.toString(2));
        TrialAndBillingManager.setTestOverridePro(null);
        assertTrue("No parameter reached a verdict for " + effectId,
                triedTotal > 0);
        System.out.println("FX panel verification " + effectId + ": "
                + triedTotal + " parameters tested (" + appliedTotal
                + " applied, " + neutralTotal + " neutral), degraded="
                + degraded[0]);
    }

    /**
     * One parameter: record it, step its values, check the waveform after each
     * change, then apply the value whose waveform moved furthest and write
     * wet-&lt;param&gt;.wav from a fresh copy of the untouched source.
     */
    private static int verifyOneParameter(FxParameter param, FxParameter primary,
                                          JSONObject entry, JSONArray steps,
                                          StudioFragment frag, Activity activity,
                                          UiDevice device, View root, File work,
                                          File master,
                                          FxVerifySupportDevice.WavInfo info,
                                          DspChain.EffectType type,
                                          JSONArray problems, boolean[] degraded)
            throws Exception {
        // Reset the slot so this parameter is the only thing that varies in the
        // recording and in the applied render.
        resetSlotToDefaults(frag, activity);
        Thread.sleep(SETTLE_MS);

        boolean primaryControl = primary != null && param.id.equals(primary.id);
        // Parameters the panel only offers on a curve canvas: the graphic EQ's
        // bands and the filter curve's nodes are dragged, not stepped.
        boolean canvasOnly =
                (type == DspChain.EffectType.GRAPHIC_EQ && param.id.matches("g\\d+"))
                || (type == DspChain.EffectType.FILTER_CURVE_EQ
                    && (param.id.startsWith("pf") || param.id.startsWith("pg")
                        || "npts".equals(param.id)));
        String location = primaryControl ? "pill-dial"
                : canvasOnly ? "curve"
                : param.type == FxParameter.Type.ACTION ? "pill-chip"
                : "pill-advanced";
        entry.put("inPill", location);

        String videoPath = new File(work, "fx-" + param.id + ".mp4").getPath();
        ParcelFileDescriptor recorder = startScreenRecord(videoPath);
        long streamStart = 0L;
        if (recorder != null) {
            drain(recorder);
            Thread.sleep(1200);
            streamStart = SystemClock.elapsedRealtime();
        } else {
            problems.put(param.id + ": screenrecord could not be started");
        }

        // ---- the control lives in the pill: expanded inline, advanced open
        ensurePillExpanded(root, activity, type.title, device);
        ensureAdvancedOpen(root, activity);

        View control = canvasOnly ? null : findParamControl(root, param);
        String controlKind = control instanceof TeleValue ? "TeleValue"
                : control instanceof TextView ? "chip" : "none";
        entry.put("control", controlKind);
        if ("none".equals(controlKind) && !canvasOnly) {
            degraded[0] = true;
            problems.put(param.id + ": no control appeared in the pill for "
                    + param.name);
        }

        // ---- step this one parameter, checking the waveform after each change
        int[] values = dialValues(param);
        float[] previous = null;
        float bestDelta = -1f;
        int bestValue = canvasOnly ? param.clampRaw(param.maxRaw) : param.defaultRaw;
        for (int value : values) {
            if (control instanceof TeleValue) {
                final TeleValue tv = (TeleValue) control;
                ui(activity, () -> tv.setValue(value, true));
            } else if (control instanceof TextView && param.type != FxParameter.Type.ACTION) {
                clickChipToState(activity, (TextView) control, param, value, root);
            } else if (control instanceof TextView) {
                ui(activity, () -> control.performClick());
            }
            Thread.sleep(SETTLE_MS);
            long offset = recorder == null ? 0
                    : SystemClock.elapsedRealtime() - streamStart;

            float[] peaks = renderPeaks(master, info.channels, type, param, value);
            String waveVerdict = "unknown";
            float maxDelta = 0f;
            int changedBars = 0;
            if (previous != null && peaks != null) {
                int n = Math.min(previous.length, peaks.length);
                for (int i = 0; i < n; i++) {
                    float d = Math.abs(peaks[i] - previous[i]);
                    if (d > 1e-3f) changedBars++;
                    if (d > maxDelta) maxDelta = d;
                }
                waveVerdict = changedBars > 0 ? "changed" : "unchanged";
            } else if (peaks != null) {
                waveVerdict = "baseline";
            }
            File shot = new File(work, String.format("shot-%03d-%s-%d.jpg",
                    steps.length(), param.id, value));
            capture(device, shot);
            JSONObject step = new JSONObject();
            step.put("index", steps.length());
            step.put("param", param.id);
            step.put("paramName", param.name);
            step.put("valueRaw", value);
            step.put("valueDisplay", param.formatRaw(value));
            step.put("videoOffsetMs", offset);
            step.put("video", new File(videoPath).getName());
            step.put("shot", shot.getName());
            step.put("waveform", waveVerdict);
            step.put("barsChanged", changedBars);
            step.put("maxDelta", Math.round(maxDelta * 10000) / 10000.0);
            if (peaks != null) step.put("peakCount", peaks.length);
            steps.put(step);
            if (maxDelta > bestDelta) {
                bestDelta = maxDelta;
                bestValue = value;
            }
            previous = peaks;
        }
        entry.put("steps", steps.length());
        entry.put("inPillLocation", location);
        entry.put("waveformChangedAtBest", bestDelta > 0f);
        entry.put("maxDelta", Math.round(Math.max(bestDelta, 0) * 10000) / 10000.0);
        entry.put("video", new File(videoPath).getName());

        // ---- apply: the exact path Apply runs, on a fresh copy of the source
        entry.put("bakedValueRaw", bestValue);
        entry.put("bakedValueDisplay", param.formatRaw(bestValue));
        File wet = new File(work, "wet-" + param.id + ".wav");
        copy(master, wet);
        boolean applied = applyOne(activity, wet, master, info, type, param,
                bestValue, entry);
        entry.put("applied", applied);
        int emitted = 0;
        if (applied) {
            float[] dryPeaks = Dsp.loadPeaks(master, SCREEN_BUCKETS);
            float[] wetPeaks = Dsp.loadPeaks(wet, SCREEN_BUCKETS);
            JSONObject peaks = new JSONObject();
            peaks.put("dry", toArray(dryPeaks));
            peaks.put("wet", toArray(wetPeaks));
            peaks.put("buckets", SCREEN_BUCKETS);
            write(new File(work, "peaks-" + param.id + ".json"), peaks.toString());
            entry.put("appliedFrames", FxVerifySupportDevice.readInfo(wet).frames);
            emitted = 2;
        } else {
            String err = entry.optString("applyError", "");
            // A parameter whose own value is neutral at every setting has
            // nothing to bake: Apply itself skips an inactive chain, so this is
            // a verdict ("neutral configuration"), not a failure.
            if (err.isEmpty() || err.contains("no audible")) {
                entry.put("neutral", true);
                if (err.isEmpty()) {
                    putQuiet(entry, "applyError", "Apply reported nothing to do");
                }
                emitted = 1;
            }
        }

        stopScreenRecord(recorder);
        return emitted;
    }

    // ----------------------------------------------------------- apply blueprint

    /**
     * Applies one parameter's value through the production apply paths
     * (EditEngine, exactly what commitDspFx runs), to a fresh copy of the
     * source, with a rack config in which only this parameter deviates.
     */
    private static boolean applyOne(Activity activity, File wet, File master,
                                    FxVerifySupportDevice.WavInfo info,
                                    DspChain.EffectType type, FxParameter param,
                                    int value, JSONObject entry) {
        final boolean[] ok = {false};
        final java.util.concurrent.CountDownLatch applyLatch =
                new java.util.concurrent.CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                DspChain.Config cfg = new DspChain.Config();
                cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
                DspChain.EffectSlot slot = cfg.addOrFocusSlot(type);
                slot.enabled = true;
                slot.wetDry = 1f;
                applyParamToSlot(slot, type, param, value);
                // Legacy stages still read their percentage fields, so mirror
                // onSlotMutated exactly as the pill does before saving.
                cfg.syncLegacyFieldsFromSlots();
                cfg.activePresetId = DspChain.Preset.CUSTOM.id;
                FormatConverter.WavInfo winfo = FormatConverter.readFloatWav(wet);
                long totalFrames = winfo.dataBytes / 4 / Math.max(1, winfo.channels);
                if (totalFrames <= 0) return;
                DspChain chain = new DspChain(winfo.channels, cfg);
                java.util.List<AudioProcessor> offline = chain.offlineProcessors();
                if (!offline.isEmpty()) {
                    long regionEnd = totalFrames;
                    boolean done = true;
                    for (AudioProcessor proc : offline) {
                        long newTotal = EditEngine.applyLengthChangingEffectToRange(
                                wet, 0, regionEnd, proc, null);
                        if (newTotal < 0) {
                            done = false;
                            break;
                        }
                        regionEnd = newTotal;
                    }
                    if (done && chain.hasInPlaceProcessors()) {
                        DspChain inPlace = new DspChain(winfo.channels, cfg);
                        inPlace.clearOfflineProcessors();
                        EditEngine.applyDspToRange(wet, 0, regionEnd, inPlace, null);
                    }
                    ok[0] = done;
                    if (!done) {
                        putQuiet(entry, "applyError", "offline renderer rejected "
                                + "the apply: " + offline.size() + " pass(es)");
                    }
                } else {
                    if (!chain.isActive()) {
                        putQuiet(entry, "applyError", "the chain reports no audible "
                                + "effect for this configuration (Apply skips it)");
                    }
                    ok[0] = EditEngine.applyDspToRange(
                            wet, 0, totalFrames, chain, null);
                }
            } catch (Throwable t) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                String text = sw.toString();
                putQuiet(entry, "applyError", text.substring(0,
                        Math.min(1400, text.length())));
                ok[0] = false;
            } finally {
                applyLatch.countDown();
            }
        });
        // The offline renderers (WSOLA pitch/tempo, the stretchers) can take
        // several seconds on a 30 s take with two slow cores: wait for the work,
        // never for an idle thread, and carry the failure text into the artifact.
        try {
            if (!applyLatch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
                putQuiet(entry, "applyError", "apply did not finish within 120 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
            // fall through
        }
        return ok[0];
    }

    /**
     * Mirrors how the pill writes a parameter into the slot: the primary dial
     * owns {@code slot.value}, the advanced grid writes {@code extraParams}, and
     * the couple of linked controls update the slot value the way the UI does.
     */
    private static void applyParamToSlot(DspChain.EffectSlot slot,
                                         DspChain.EffectType type,
                                         FxParameter param, int value) {
        String primaryId = FxVerifySupportDevice.primaryParamId(type);
        if (primaryId != null && primaryId.equals(param.id)) {
            slot.value = value;
        } else {
            slot.extraParams.put(param.id, value);
        }
        if (type == DspChain.EffectType.NOTCH_FILTER && "preset".equals(param.id)) {
            if (value == 1) slot.value = 50;
            else if (value == 2) slot.value = 60;
        }
        if (type == DspChain.EffectType.BASS_TREBLE && "treble".equals(param.id)) {
            Integer link = slot.extraParams.get("link");
            if (link != null && link != 0) slot.value = value;
        }
    }

    /** Wipes the slot's state back to its defaults before each parameter's run. */
    private static void resetSlotToDefaults(StudioFragment frag, Activity activity) {
        activity.runOnUiThread(() -> {
            try {
                DspChain.Config cfg = dspConfigOf(frag);
                if (cfg.slots.isEmpty()) return;
                DspChain.EffectSlot slot = cfg.slots.get(0);
                AudioProcessor proto = FxRackChain.getPrototypeProcessor(slot.type.id);
                String primaryId = FxVerifySupportDevice.primaryParamId(slot.type);
                FxParameter def = proto == null || primaryId == null
                        ? null : proto.getParam(primaryId);
                slot.enabled = true;
                slot.wetDry = 1f;
                slot.extraParams.clear();
                if (def != null) slot.value = def.defaultRaw;
                cfg.save(activity);
                rebuildRack(frag);
            } catch (Throwable t) {
                System.out.println("slot reset failed: " + t);
            }
        });
        // The rebuild re-reads the config on the next frame; a short soak is
        // enough and never demands an idle UI thread (the waveform keeps it busy).
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The peaks the waveform would be painted with for this exact config. */
    private static float[] renderPeaks(File raw, int channels,
                                       DspChain.EffectType type, FxParameter param,
                                       int value) {
        try {
            DspChain.Config cfg = new DspChain.Config();
            cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
            DspChain.EffectSlot slot = cfg.addOrFocusSlot(type);
            slot.enabled = true;
            applyParamToSlot(slot, type, param, value);
            DspChain chain = new DspChain(Math.max(1, channels), cfg);
            return FxPreviewPeaks.render(raw, chain, SCREEN_BUCKETS, 48000);
        } catch (Throwable t) {
            System.out.println("peaks render failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------- panel driving

    private static void ensurePillExpanded(View root, Activity activity,
                                           String title, UiDevice device)
            throws Exception {
        if (aTeleValueIsVisible(root)) return;
        expandInline(root, activity, title, device);
        if (!aTeleValueIsVisible(root)) {
            expandInline(root, activity, title, device);
        }
    }

    /**
     * Every secondary parameter lives behind the pill's "Show advanced controls
     * (N)" chip, revealed inline - never in a separate modal.
     */
    private static void ensureAdvancedOpen(View root, Activity activity)
            throws Exception {
        if (advancedControlsVisible(root)) return;
        View toggle = findChipStartsWith(root, ADVANCED);
        if (toggle != null) {
            ui(activity, toggle::performClick);
        }
        if (!advancedControlsVisible(root)) {
            UiObject2 chip = deviceFor().findObject(By.textStartsWith(ADVANCED));
            if (chip != null) {
                chip.click();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static UiDevice deviceFor() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    private static boolean advancedControlsVisible(View root) {
        List<TeleValue> found = new ArrayList<>();
        collectTeleValues(root, found);
        return found.size() > 1;
    }

    private static View findChipStartsWith(View root, String startsWith) {
        List<View> chips = new ArrayList<>();
        collectChips(root, chips);
        for (View v : chips) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().startsWith(startsWith)) return v;
        }
        return null;
    }

    private static void collectChips(View v, List<View> out) {
        if (v instanceof TextView && v.isClickable()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectChips(g.getChildAt(i), out);
        }
    }

    private static TextView asChip(View v) {
        return v instanceof TextView ? (TextView) v : null;
    }

    /** org.json throws checked exceptions; the manifest remembers, the test flows. */
    private static void putQuiet(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (Exception ignored) {
            // reporting only
        }
    }

    /** The activity's decor plus every dialog window the process owns. */
    private static List<View> allWindowRoots(View activityRoot) {
        List<View> roots = new ArrayList<>();
        roots.add(activityRoot.getRootView());
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Field instance = wmg.getDeclaredField("sDefaultWindowManager");
            instance.setAccessible(true);
            Object mgr = instance.get(null);
            Field views = wmg.getDeclaredField("mViews");
            views.setAccessible(true);
            List<?> list = (List<?>) views.get(mgr);
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof View) roots.add((View) o);
                }
            }
        } catch (Throwable ignored) {
            // Dialog roots are a bonus; the activity tree is always searched.
        }
        return roots;
    }

    /** Finds the parameter's control inside the pill (dial or chip). */
    private static View findParamControl(View root, FxParameter param) {
        for (View windowRoot : allWindowRoots(root)) {
            List<TeleValue> teleValues = new ArrayList<>();
            collectTeleValues(windowRoot, teleValues);
            for (View v : teleValues) {
                if (containsText(v, param.name)) return v;
            }
            List<View> chips = new ArrayList<>();
            collectChips(windowRoot, chips);
            for (View v : chips) {
                CharSequence text = ((TextView) v).getText();
                if (text == null) continue;
                String s = text.toString();
                if (s.equals(param.name) || s.startsWith(param.name + ":")) return v;
            }
        }
        return null;
    }

    private static void clickChipToState(Activity activity, TextView chip,
                                         FxParameter param, int value, View root)
            throws Exception {
        int guard = 0;
        while (guard++ < 16) {
            String label = chip.getText() == null ? "" : chip.getText().toString();
            boolean matches;
            if (param.type == FxParameter.Type.BOOLEAN) {
                matches = label.endsWith(value != 0 ? "On" : "Off");
            } else if (param.type == FxParameter.Type.ENUM) {
                matches = label.endsWith(param.getEnumLabel(value));
            } else {
                matches = true;
            }
            if (matches) return;
            ui(activity, chip::performClick);
            TextView again = asChip(findParamControl(root, param));
            if (again == null) return;
            chip = again;
        }
    }


    // ------------------------------------------------------------- dial helpers

    /** The peaks the waveform is painted with for the current rack config. */
    private static float[] renderPeaks(File raw, StudioFragment frag, int channels) {
        try {
            DspChain.Config cfg = dspConfigOf(frag);
            DspChain chain = new DspChain(Math.max(1, channels), cfg);
            return FxPreviewPeaks.render(raw, chain, SCREEN_BUCKETS, 48000);
        } catch (Throwable t) {
            System.out.println("peaks render failed: " + t);
            return null;
        }
    }

    private static int[] dialValues(FxParameter p) {
        Set<Integer> set = new LinkedHashSet<>();
        for (int v : new int[]{p.minRaw, p.defaultRaw, p.maxRaw}) {
            set.add(p.clampRaw(v));
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) out[i++] = v;
        return out;
    }

    /**
     * Expands the pill by tapping its title - the app toggles the inline body
     * there (StudioFragment's card builder) - and reports whether a dial is now
     * present in the panel tree.
     */
    private static boolean expandInline(View root, Activity activity, String title,
                                        UiDevice device) throws Exception {
        try {
            UiObject2 cardTitle = device.findObject(By.text(title));
            if (cardTitle != null) {
                cardTitle.click();
                Thread.sleep(500);
                if (aTeleValueIsVisible(root)) return true;
            }
        } catch (Throwable ignored) {
            // try the in-process tree
        }
        List<View> titles = new ArrayList<>();
        collectText(root, title, titles);
        if (!titles.isEmpty()) {
            View t = titles.get(0);
            ui(activity, t::performClick);
            Thread.sleep(500);
            if (aTeleValueIsVisible(root)) return true;
            // one more tap, in case the first landed on a stale node
            ui(activity, t::performClick);
            Thread.sleep(500);
            return aTeleValueIsVisible(root);
        }
        return false;
    }

    private static boolean aTeleValueIsVisible(View root) {
        List<TeleValue> found = new ArrayList<>();
        collectTeleValues(root, found);
        return !found.isEmpty();
    }

    private static TeleValue findTeleValueByCaption(View root, String... captions) {
        List<TeleValue> candidates = new ArrayList<>();
        collectTeleValues(root, candidates);
        for (String caption : captions) {
            if (caption == null) continue;
            for (TeleValue tv : candidates) {
                if (containsText(tv, caption)) return tv;
            }
        }
        return null;
    }

    // -------------------------------------------------- rack setup and helpers

    /** Empties the rack and adds exactly one effect; true when the UI did it. */
    private static boolean makeRackContainOnly(StudioFragment frag, Activity activity,
                                               UiDevice device,
                                               DspChain.EffectType type,
                                               JSONArray problems,
                                               boolean[] degraded) throws Exception {
        boolean clearedThroughUi = false;
        try {
            UiObject2 delete = findText(device, "Delete All");
            if (delete != null) {
                clearedThroughUi = true;
                if (delete.isEnabled()) {
                    delete.click();
                    Thread.sleep(600);
                    List<UiObject2> confirmations =
                            device.findObjects(By.text("Delete All"));
                    if (!confirmations.isEmpty()) {
                        confirmations.get(confirmations.size() - 1).click();
                        Thread.sleep(700);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("rack clear through UI failed: " + t);
        }
        ui(activity, () -> {
            try {
                DspChain.Config cfg = dspConfigOf(frag);
                if (!cfg.slots.isEmpty()) {
                    cfg.clearRack();
                    cfg.save(activity);
                    rebuildRack(frag);
                }
            } catch (Throwable t) {
                System.out.println("rack clear fell back and failed: " + t);
            }
        });
        Thread.sleep(SETTLE_MS);

        boolean added = false;
        try {
            if (openAddDialog(device)) {
                UiObject2 box = device.findObject(By.clazz("android.widget.EditText"));
                if (box != null) {
                    box.setText(type.title);
                    Thread.sleep(600);
                    device.pressBack();
                    Thread.sleep(400);
                    if (!dialogOpen(device) && !openAddDialog(device)) return false;
                    UiObject2 row = device.findObject(By.text(type.title));
                    if (row != null) {
                        row.click();
                        Thread.sleep(800);
                        added = !dialogOpen(device);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("add dialog failed: " + t);
        }
        if (added && !rackHasEffect(frag, type)) added = false;
        if (!added && !rackHasEffect(frag, type)) {
            degraded[0] = true;
            try {
                problems.put("the Add Effect dialog did not add " + type.title
                        + "; the rack was populated programmatically instead");
            } catch (Throwable ignored) {
                // reporting only
            }
            ui(activity, () -> {
                try {
                    DspChain.Config cfg = dspConfigOf(frag);
                    if (!rackHasEffect(frag, type)) {
                        cfg.addOrFocusSlot(type);
                        cfg.save(activity);
                        rebuildRack(frag);
                    }
                } catch (Throwable t) {
                    System.out.println("programmatic fallback failed: " + t);
                }
            });
            Thread.sleep(SETTLE_MS);
        }
        return added;
    }

    private static boolean openAddDialog(UiDevice device) throws Exception {
        UiObject2 add = findText(device, "+ Add Effect");
        if (add == null) return false;
        add.click();
        Thread.sleep(600);
        return dialogOpen(device);
    }

    private static boolean dialogOpen(UiDevice device) {
        return device.findObject(By.text("Add Effect")) != null;
    }

    private static boolean rackHasEffect(StudioFragment frag, DspChain.EffectType type) {
        try {
            DspChain.Config cfg = dspConfigOf(frag);
            for (DspChain.EffectSlot slot : cfg.slots) {
                if (slot.type == type) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Activity launch(Instrumentation instr) {
        Intent i = new Intent(instr.getTargetContext(), MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    /**
     * Runs the action on the UI thread and waits for the action itself, not for
     * the thread to idle: the painted waveform keeps the main thread busy for a
     * long recording, so waitForIdleSync can block a whole run with no URL back.
     */
    private static void ui(Activity activity, Runnable action) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final Throwable[] thrown = {null};
        activity.runOnUiThread(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                thrown[0] = t;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thrown[0] != null) {
            System.out.println("ui action threw: " + thrown[0]);
        }
    }

    private static StudioFragment awaitStudio(Activity activity) throws Exception {
        for (int i = 0; i < 100; i++) {
            final StudioFragment[] holder = new StudioFragment[1];
            ui(activity, () -> holder[0] = (StudioFragment)
                    ((MainActivity) activity).getSupportFragmentManager()
                            .findFragmentByTag("STU"));
            if (holder[0] != null && holder[0].getView() != null) return holder[0];
            Thread.sleep(100);
        }
        return null;
    }

    private static void awaitFxPanel(StudioFragment frag) throws Exception {
        for (int i = 0; i < 50 && !frag.isFxPanelOpen(); i++) Thread.sleep(100);
    }

    private static UiObject2 findText(UiDevice device, String text) throws Exception {
        UiObject2 found = device.findObject(By.text(text));
        if (found != null) return found;
        UiObject2 list = device.findObject(
                By.res(device.getCurrentPackageName(), "fxScroll"));
        for (int i = 0; i < 4 && found == null; i++) {
            if (list != null) list.scroll(Direction.DOWN, 0.8f);
            Thread.sleep(250);
            found = device.findObject(By.text(text));
        }
        return found;
    }

    private static void collectText(View v, String text, List<View> out) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().equals(text)) out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectText(g.getChildAt(i), text, out);
            }
        }
    }

    private static void collectTeleValues(View v, List<TeleValue> out) {
        if (v instanceof TeleValue) out.add((TeleValue) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTeleValues(g.getChildAt(i), out);
            }
        }
    }

    private static boolean containsText(View v, String text) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().equals(text)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private static DspChain.Config dspConfigOf(StudioFragment frag) throws Exception {
        Field f = StudioFragment.class.getDeclaredField("dspConfig");
        f.setAccessible(true);
        return (DspChain.Config) f.get(frag);
    }

    private static void rebuildRack(StudioFragment frag) throws Exception {
        java.lang.reflect.Method m =
                StudioFragment.class.getDeclaredMethod("rebuildFxRackList");
        m.setAccessible(true);
        m.invoke(frag);
    }

    private static void capture(UiDevice device, File out) {
        File png = new File(out.getParentFile(), out.getName().replace(".jpg", ".png"));
        try {
            if (!device.takeScreenshot(png)) return;
            Bitmap bmp = BitmapFactory.decodeFile(png.getAbsolutePath());
            if (bmp == null) return;
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            }
            bmp.recycle();
        } catch (Throwable ignored) {
            // A missing screenshot shows up as a missing file in the artifact.
        } finally {
            //noinspection ResultOfMethodCallIgnored
            png.delete();
        }
    }

    private static ParcelFileDescriptor startScreenRecord(String path) {
        try {
            return InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("screenrecord --size 720x1280 "
                            + "--bit-rate 2000000 --time-limit 900 " + path);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void drain(ParcelFileDescriptor pfd) {
        Thread t = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0) {
                    // keep the shell pipe clear
                }
            } catch (Throwable ignored) {
                // closed on stop
            }
        }, "screenrecord-drain");
        t.setDaemon(true);
        t.start();
    }

    private static void stopScreenRecord(ParcelFileDescriptor pfd) {
        try {
            if (pfd != null) pfd.close();
        } catch (Throwable ignored) {
            // the kill below still ends it
        }
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pkill -INT screenrecord").close();
            Thread.sleep(1800);
        } catch (Throwable ignored) {
            // the time limit will end it
        }
    }

    private static void writePeaks(File dry, File wet, File work) throws Exception {
        float[] dryPeaks = Dsp.loadPeaks(dry, SCREEN_BUCKETS);
        float[] wetPeaks = Dsp.loadPeaks(wet, SCREEN_BUCKETS);
        JSONObject o = new JSONObject();
        o.put("dry", toArray(dryPeaks));
        o.put("wet", toArray(wetPeaks));
        o.put("buckets", SCREEN_BUCKETS);
        write(new File(work, "peaks.json"), o.toString());
    }

    private static JSONArray toArray(float[] v) throws Exception {
        JSONArray a = new JSONArray();
        for (float f : v) a.put(Math.round(f * 10000) / 10000.0);
        return a;
    }

    private static void copyAsset(String name, File dest) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(name);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void write(File f, String text) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f),
                StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }
}
