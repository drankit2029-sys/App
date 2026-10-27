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
import android.widget.ImageView;
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
import java.util.List;

/**
 * Tier 2 of the FX verification harness: one effect driven through the real UI
 * on a device.
 *
 * <p>Every parameter of the effect under test is walked through the control the
 * user actually touches (the dial on the rack card, or the chip/slider in the
 * expanded inspector), a screenshot is taken at each step, and a screen
 * recording covers the sweep. The dry and baked renders plus the app's own
 * waveform peaks are written next to the shots, so the waveform claim can be
 * checked numerically as well as visually.
 *
 * <p>Launching mirrors the repo's other device tests
 * ({@code Instrumentation.startActivitySync} with explicit flags) rather than
 * ActivityScenario, and recording starts only once the studio is up: this
 * project's notes record UiAutomator wedging while a recording is running.
 *
 * <p>Run with: {@code -e fxEffect <id>}.
 */
@RunWith(AndroidJUnit4.class)
public class FxUiSweepDeviceTest {

    private static final int SCREEN_BUCKETS = 600;
    private static final long SETTLE_MS = 320L;

    @Rule
    public GrantPermissionRule mic = GrantPermissionRule.grant(
            android.Manifest.permission.RECORD_AUDIO);

    @Test
    public void sweepEveryParameterThroughTheUi() throws Exception {
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

        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.attachExisting(ctx, input, "FX Verify " + effectId,
                info.channels, RecordingEngine.payloadBytesOf(input));

        // The rack sits behind the Pro gate. The manager exposes this hook for
        // instrumentation tests; without it the paywall sheet stands in front of
        // the panel and nothing can be driven.
        TrialAndBillingManager.setTestOverridePro(Boolean.TRUE);

        JSONObject result = new JSONObject();
        result.put("effect", effectId);
        result.put("title", type.title);
        result.put("inputFrames", info.frames);
        result.put("channels", info.channels);
        JSONArray shots = new JSONArray();
        JSONArray driven = new JSONArray();
        JSONArray problems = new JSONArray();
        JSONArray notes = new JSONArray();
        final boolean[] degraded = {false};

        // ---- bring the studio up with the take attached
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

        // The bars are loaded from the working file on a worker, and FX mode
        // needs a region first, so wait for the bars and then make sure a
        // selection actually took before asking for the panel.
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
        boolean selected = waveform.hasSelection();
        for (int attempt = 0; attempt < 3 && !frag.isFxPanelOpen(); attempt++) {
            ui(activity, fxChip::performClick);
            awaitFxPanel(frag);
        }
        capture(device, new File(work, "panel-open.jpg"));
        result.put("barsLoaded", waveform.historyCount());
        result.put("regionSelected", selected);
        result.put("paywallVisible", device.findObject(
                By.textContains("Pro Studio")) != null);
        assertTrue("The rack must open (bars=" + waveform.historyCount()
                + ", selection=" + selected + ")", frag.isFxPanelOpen());

        // ---- start recording only now that the UI is settled. Any recording
        // left by an earlier attempt is removed first, so the file on the device
        // always matches the offsets this attempt logs.
        String videoPath = "/data/local/tmp/fx-" + effectId + ".mp4";
        try {
            instr.getUiAutomation()
                    .executeShellCommand("rm -f " + videoPath).close();
        } catch (Throwable ignored) {
            // a stale file only affects the frame offsets, not the screenshots
        }
        ParcelFileDescriptor recorder = startScreenRecord(videoPath);
        long streamStart = 0L;
        if (recorder != null) {
            drain(recorder);
            Thread.sleep(1200);
            streamStart = SystemClock.elapsedRealtime();
            result.put("videoStartedElapsedMs", streamStart);
        } else {
            problems.put("screenrecord could not be started");
        }

        // ---- the rack may already hold the active preset's effects; clear it so
        // the sweep drives exactly the effect under test and its card is the only
        // one the inspector can expand.
        boolean clearedThroughUi = clearRack(device);
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
        result.put("rackClearedThroughUi", clearedThroughUi);

        // ---- add the effect through the real dialog, verify it landed, and fall
        // back to the production config only if the dialog could not be driven
        boolean addedThroughUi = addEffectThroughDialog(type.title, device)
                && rackHasEffect(frag, type);
        if (!addedThroughUi) {
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
                    if (!rackHasEffect(frag, fxType)) {
                        cfg.addOrFocusSlot(fxType);
                        cfg.save(activity);
                        rebuildRack(frag);
                    }
                } catch (Throwable t) {
                    System.out.println("programmatic fallback failed: " + t);
                }
            });
            Thread.sleep(SETTLE_MS);
        }
        result.put("addedThroughUi", addedThroughUi);

        // ---- open the inspector so every parameter has a control
        device.dumpWindowHierarchy(new File(work, "hierarchy-rack-open.xml"));
        if (!expandEffect(root, activity, device, type.title, work)) {
            problems.put("the effect inspector could not be opened; parameters were "
                    + "driven from the rack card where possible");
        }
        Thread.sleep(SETTLE_MS);
        device.dumpWindowHierarchy(new File(work, "hierarchy-expanded.xml"));

        AudioProcessor proto = FxRackChain.getPrototypeProcessor(effectId);
        assertNotNull("No prototype processor for " + effectId, proto);
        List<FxParameter> params = proto.getParameters();
        String primaryParam = FxVerifySupportDevice.primaryParamId(type);

        int shotIndex = 0;
        int controlsFound = 0;
        for (FxParameter param : params) {
            String noControl = uiOwnsNoControl(type, param.id);
            if (noControl != null) {
                JSONObject note = new JSONObject();
                note.put("param", param.id);
                note.put("paramName", param.name);
                note.put("reason", noControl);
                notes.put(note);
                continue;
            }
            int[] values = valuesFor(param);
            View control = findParamControl(root, param, type, primaryParam);
            String controlKind = control instanceof TeleValue ? "TeleValue"
                    : control instanceof TextView ? "chip" : "missing";
            if ("missing".equals(controlKind)) {
                degraded[0] = true;
                problems.put("no UI control found for parameter " + param.id
                        + " (" + param.name + ")");
            } else {
                controlsFound++;
            }
            for (int value : values) {
                String label = param.id + "=" + param.formatRaw(value);
                try {
                    if (control instanceof TeleValue) {
                        TeleValue tv = (TeleValue) control;
                        ui(activity, () -> tv.setValue(value, true));
                        // One real touch too, so the control's own touch path is
                        // exercised and not only the programmatic setter.
                        tapStepper(device, value >= param.defaultRaw);
                    } else if (control instanceof TextView) {
                        clickChipToState(activity, (TextView) control, param, value);
                    }
                    Thread.sleep(SETTLE_MS);
                } catch (Throwable t) {
                    problems.put(param.id + ": " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
                long offset = recorder == null ? 0
                        : SystemClock.elapsedRealtime() - streamStart;
                File shot = new File(work, String.format("shot-%03d-%s.jpg",
                        shotIndex, param.id));
                capture(device, shot);
                JSONObject entry = new JSONObject();
                entry.put("index", shotIndex);
                entry.put("param", param.id);
                entry.put("paramName", param.name);
                entry.put("type", param.type.name());
                entry.put("valueRaw", value);
                entry.put("valueDisplay", param.formatRaw(value));
                entry.put("isDefault", value == param.defaultRaw);
                entry.put("control", controlKind);
                entry.put("videoOffsetMs", offset);
                entry.put("shot", shot.getName());
                shots.put(entry);
                driven.put(label);
                shotIndex++;
            }
        }

        // ---- bake the rack and capture the rendered waveform evidence
        if (controlsFound == 0) {
            // Nothing was driven: the run must not read as a pass with no
            // evidence behind it, so leave the hierarchy for the next look.
            device.dumpWindowHierarchy(new File(work, "hierarchy-no-controls.xml"));
            result.put("shotCount", 0);
            result.put("controlsFound", 0);
            result.put("problems", problems);
            result.put("notes", notes);
            write(new File(work, "result.json"), result.toString(2));
            TrialAndBillingManager.setTestOverridePro(null);
            org.junit.Assert.fail("No parameter control was reachable for "
                    + effectId + "; see hierarchy-*.xml in the artifact");
        }
        result.put("controlsFound", controlsFound);
        ui(activity, frag::applyEffectsToSelection);
        Thread.sleep(800);
        File wet = new File(work, "wet.wav");
        copy(input, wet);
        writePeaks(dry, wet, work);
        result.put("bakedFrames", FxVerifySupportDevice.readInfo(wet).frames);

        JSONObject logcatErrors = collectLogcatErrors(ctx);
        if (logcatErrors.length() > 0) {
            result.put("logcatErrors", logcatErrors);
        }
        stopScreenRecord(recorder, videoPath);
        result.put("videoPath", videoPath);
        result.put("videoName", new File(videoPath).getName());
        result.put("recorded", recorder != null);
        result.put("degraded", degraded[0]);
        result.put("shotCount", shots.length());
        result.put("driven", driven);
        result.put("problems", problems);
        result.put("notes", notes);
        result.put("shots", shots);
        write(new File(work, "shots.json"), shots.toString(2));
        write(new File(work, "result.json"), result.toString(2));
        TrialAndBillingManager.setTestOverridePro(null);
        System.out.println("FX UI sweep " + effectId + ": " + shots.length()
                + " parameter states captured, degraded=" + degraded[0]);
    }

    // ------------------------------------------------------------------ plumbing

    /** The repo's device-test launch pattern. */
    private static Activity launch(Instrumentation instr) {
        Intent i = new Intent(instr.getTargetContext(), MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    private static void ui(Activity activity, Runnable action) {
        activity.runOnUiThread(action);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
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

    /** Taps "Delete All" and then the sheet's confirmation, if the rack has slots. */
    private static boolean clearRack(UiDevice device) throws Exception {
        try {
            UiObject2 delete = findText(device, "Delete All");
            if (delete == null) return false;
            if (!delete.isEnabled()) return true;
            delete.click();
            Thread.sleep(600);
            List<UiObject2> confirmations = device.findObjects(By.text("Delete All"));
            if (!confirmations.isEmpty()) {
                confirmations.get(confirmations.size() - 1).click();
                Thread.sleep(700);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Finds a text node, scrolling the rack once if it sits below the fold. */
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

    /**
     * Adds the effect through the real searchable dialog and reports whether it
     * actually landed: the dialog dismisses itself when a row is tapped, so a
     * closed dialog is the success signal. The soft keyboard has to be put away
     * first, otherwise the tap aimed at the row hits the keyboard instead.
     */
    private static boolean addEffectThroughDialog(String title, UiDevice device)
            throws Exception {
        try {
            if (!openAddDialog(device)) return false;
            UiObject2 box = device.findObject(By.clazz("android.widget.EditText"));
            if (box != null) {
                box.setText(title);
                Thread.sleep(600);
            }
            // 1) tap the filtered row (the keyboard may still be up, so this can miss)
            if (tapRow(title, device)) return true;
            // 2) put the keyboard away, then tap again
            device.pressBack();
            Thread.sleep(500);
            if (!dialogOpen(device) && !openAddDialog(device)) return false;
            if (tapRow(title, device)) return true;
            // 3) reopen clean and scroll the dialog's list to the row
            if (!dialogOpen(device) && !openAddDialog(device)) return false;
            UiObject2 list = device.findObject(By.clazz("android.widget.ScrollView"));
            for (int i = 0; i < 14; i++) {
                if (tapRow(title, device)) return true;
                if (list == null) break;
                list.scroll(Direction.DOWN, 0.8f);
                Thread.sleep(250);
            }
            return !dialogOpen(device);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean openAddDialog(UiDevice device) throws Exception {
        UiObject2 add = findText(device, "+ Add Effect");
        if (add == null) return false;
        add.click();
        Thread.sleep(600);
        return dialogOpen(device);
    }

    /** Taps the effect's row and reports whether the dialog dismissed itself. */
    private static boolean tapRow(String title, UiDevice device) throws Exception {
        UiObject2 row = device.findObject(By.text(title));
        if (row == null) return false;
        row.click();
        Thread.sleep(800);
        return !dialogOpen(device);
    }

    private static boolean dialogOpen(UiDevice device) {
        return device.findObject(By.text("Add Effect")) != null;
    }

    /** True when the rack already holds a slot for this effect. */
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

    /**
     * Opens the per-effect inspector. Three routes are tried, because the
     * expand affordance is an image button with only a content description:
     * the accessibility tree across windows, the in-process view tree, and
     * finally tapping the card itself.
     */
    private static boolean expandEffect(View root, Activity activity, UiDevice device,
                                        String title, File work) throws Exception {
        try {
            UiObject2 expand = device.findObject(By.desc("Expand effect"));
            if (expand != null) {
                expand.click();
                Thread.sleep(600);
                if (inspectorOpen(root)) return true;
            }
        } catch (Throwable ignored) {
            // fall through to the in-process routes
        }
        List<View> found = new ArrayList<>();
        for (View windowRoot : allWindowRoots(root)) {
            collectByDescription(windowRoot, "Expand effect", found);
        }
        if (!found.isEmpty()) {
            View target = found.get(0);
            ui(activity, target::performClick);
            Thread.sleep(600);
            if (inspectorOpen(root)) return true;
        }
        try {
            UiObject2 cardTitle = device.findObject(By.text(title));
            if (cardTitle != null) {
                cardTitle.click();
                Thread.sleep(600);
                if (inspectorOpen(root)) return true;
            }
        } catch (Throwable ignored) {
            // reported by the caller
        }
        device.dumpWindowHierarchy(new File(work, "hierarchy-no-inspector.xml"));
        return false;
    }

    /** The inspector's own header, used to tell whether expansion worked. */
    private static boolean inspectorOpen(View root) {
        for (View windowRoot : allWindowRoots(root)) {
            if (containsTextStarting(windowRoot, "All parameters")
                    || containsText(windowRoot, "Reset effect")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTextStarting(View v, String prefix) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().startsWith(prefix)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsTextStarting(g.getChildAt(i), prefix)) return true;
            }
        }
        return false;
    }

    private static void tapStepper(UiDevice device, boolean up) {
        try {
            List<UiObject2> steps = device.findObjects(
                    By.desc(up ? "increase" : "decrease"));
            if (!steps.isEmpty()) {
                steps.get(0).click();
                Thread.sleep(60);
            }
        } catch (Throwable ignored) {
            // A missed tap costs nothing: the value was already set directly.
        }
    }

    private static void clickChipToState(Activity activity, TextView chip,
                                         FxParameter param, int value)
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
            Thread.sleep(120);
        }
    }

    private static int[] valuesFor(FxParameter p) {
        switch (p.type) {
            case BOOLEAN:
                return new int[]{0, 1};
            case ENUM: {
                int n = p.enumLabels == null ? 1 : p.enumLabels.length;
                int[] out = new int[n];
                for (int i = 0; i < n; i++) out[i] = i;
                return out;
            }
            case ACTION:
                return new int[]{1};
            default:
                return new int[]{p.minRaw, p.defaultRaw, p.maxRaw};
        }
    }

    private static View findParamControl(View root, FxParameter param,
                                         DspChain.EffectType type, String primaryParam) {
        // The rack card labels its dial with the effect's own control name,
        // while the inspector uses the parameter name, so accept both.
        List<String> captions = new ArrayList<>();
        captions.add(param.name);
        if (param.id.equals(primaryParam) && type.paramLabel != null) {
            captions.add(type.paramLabel);
        }
        for (View windowRoot : allWindowRoots(root)) {
            List<View> teleValues = new ArrayList<>();
            collectTeleValues(windowRoot, teleValues);
            for (View v : teleValues) {
                for (String caption : captions) {
                    if (containsText(v, caption)) return v;
                }
            }
            List<View> chips = new ArrayList<>();
            collectChips(windowRoot, chips);
            for (View v : chips) {
                CharSequence text = ((TextView) v).getText();
                if (text == null) continue;
                String s = text.toString();
                for (String caption : captions) {
                    if (s.equals(caption) || s.startsWith(caption + ":")) return v;
                }
            }
        }
        return null;
    }

    /**
     * Parameters the rack deliberately has no control for: the curve editors
     * own their nodes on the canvas, and the two tone cards each edit only
     * their own corner frequency. Mirrors the skips in
     * {@code StudioFragment}'s inspector builder.
     */
    private static String uiOwnsNoControl(DspChain.EffectType type, String paramId) {
        if (type == DspChain.EffectType.WARMTH_BODY && "presence_freq".equals(paramId)) {
            return "edited by the Vocal Presence card";
        }
        if (type == DspChain.EffectType.VOCAL_PRESENCE && "warmth_freq".equals(paramId)) {
            return "edited by the Warmth card";
        }
        if (type == DspChain.EffectType.GRAPHIC_EQ && paramId.matches("g\\d+")) {
            return "dragged on the graphic EQ canvas";
        }
        if (type == DspChain.EffectType.FILTER_CURVE_EQ
                && (paramId.startsWith("pf") || paramId.startsWith("pg")
                || "npts".equals(paramId))) {
            return "dragged on the filter curve canvas";
        }
        return null;
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

    private static void collectTeleValues(View v, List<View> out) {
        if (v instanceof TeleValue) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTeleValues(g.getChildAt(i), out);
            }
        }
    }

    private static void collectChips(View v, List<View> out) {
        if (v instanceof TextView && v.isClickable()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectChips(g.getChildAt(i), out);
        }
    }

    private static void collectByDescription(View v, String desc, List<View> out) {
        CharSequence cd = v.getContentDescription();
        if (v instanceof ImageView && cd != null && desc.contentEquals(cd)) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectByDescription(g.getChildAt(i), desc, out);
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

    /** Keeps the command's pipe clear so the recording cannot block on it. */
    private static void drain(ParcelFileDescriptor pfd) {
        Thread t = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0) {
                    // screenrecord only chats when asked to; nothing to keep.
                }
            } catch (Throwable ignored) {
                // closed on stop
            }
        }, "screenrecord-drain");
        t.setDaemon(true);
        t.start();
    }

    private static void stopScreenRecord(ParcelFileDescriptor pfd, String path) {
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pkill -INT screenrecord").close();
            Thread.sleep(1800);
        } catch (Throwable ignored) {
            // the time limit will end it
        }
    }

    private static JSONObject collectLogcatErrors(Context ctx) throws Exception {
        JSONObject out = new JSONObject();
        Process p = new ProcessBuilder("logcat", "-d", "-v", "brief", "*:E")
                .redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 400) {
                if (line.contains("FATAL EXCEPTION") || line.contains("ANR in ")
                        || line.contains("AndroidRuntime")) {
                    sb.append(line).append('\n');
                }
            }
        }
        p.waitFor();
        String filtered = sb.toString().replace(ctx.getPackageName(), "<pkg>");
        if (filtered.isEmpty()) return out;
        out.put("raw", filtered.length() > 4000 ? filtered.substring(0, 4000) : filtered);
        return out;
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

    /** {@code StudioFragment.dspConfig} is private; the fallback needs it. */
    private static DspChain.Config dspConfigOf(StudioFragment frag) throws Exception {
        Field f = StudioFragment.class.getDeclaredField("dspConfig");
        f.setAccessible(true);
        return (DspChain.Config) f.get(frag);
    }

    /** {@code StudioFragment.rebuildFxRackList()} is private too. */
    private static void rebuildRack(StudioFragment frag) throws Exception {
        java.lang.reflect.Method m =
                StudioFragment.class.getDeclaredMethod("rebuildFxRackList");
        m.setAccessible(true);
        m.invoke(frag);
    }
}
