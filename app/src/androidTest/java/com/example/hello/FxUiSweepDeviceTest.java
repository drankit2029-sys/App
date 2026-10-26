package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier 2 of the FX verification harness: the effect driven through the real UI
 * on a device, one effect per job.
 *
 * <p>Every parameter of the effect under test is walked through the control the
 * user actually touches (the dial on the card, or the chip/slider in the
 * expanded inspector), a screenshot is taken at each step, and a screen
 * recording covers the whole sweep. The dry and baked renders plus the app's
 * own waveform peaks are written next to the shots so the waveform claim can be
 * checked numerically as well as visually.
 *
 * <p>Run with: {@code -e fxEffect <id>}.
 */
@RunWith(AndroidJUnit4.class)
public class FxUiSweepDeviceTest {

    private static final int SCREEN_BUCKETS = 600;
    private static final long SETTLE_MS = 320L;

    @Test
    public void sweepEveryParameterThroughTheUi() throws Exception {
        String effectId = InstrumentationRegistry.getArguments().getString("fxEffect");
        assertNotNull("Pass -e fxEffect <id>", effectId);

        DspChain.EffectType type = null;
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            if (t.id.equals(effectId)) type = t;
        }
        assertNotNull("Unknown effect " + effectId, type);
        final DspChain.EffectType fxType = type;

        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File work = new File(RecordingEngine.takesDir(ctx), "fxverify-" + effectId);
        work.mkdirs();
        for (File f : work.listFiles()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
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

        JSONObject result = new JSONObject();
        result.put("effect", effectId);
        result.put("title", type.title);
        result.put("inputFrames", info.frames);
        result.put("channels", info.channels);
        JSONArray shots = new JSONArray();
        JSONArray driven = new JSONArray();
        JSONArray problems = new JSONArray();
        JSONArray notes = new JSONArray();
        boolean degraded = false;

        // ---- start recording before the UI moves
        String videoPath = "/data/local/tmp/fx-" + effectId + ".mp4";
        ParcelFileDescriptor recorder = startScreenRecord(videoPath);
        long streamStart = 0L;
        if (recorder != null) {
            Thread.sleep(1200);
            streamStart = SystemClock.elapsedRealtime();
        } else {
            problems.put("screenrecord could not be started");
        }

        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        final File inputFile = input;
        scenario.onActivity(a -> a.openStudioWithEdit(inputFile, 991L, "FX Verify"));
        StudioFragment frag = awaitStudio(scenario);
        assertNotNull("Studio fragment never appeared", frag);

        final View[] rootHolder = new View[1];
        scenario.onActivity(a -> rootHolder[0] = frag.getView());
        View root = rootHolder[0];
        assertNotNull("Studio view missing", root);

        final WaveformView waveform = root.findViewById(R.id.waveform);
        final TextView fxChip = root.findViewById(R.id.btnDspFx);
        scenario.onActivity(a -> {
            waveform.selectRange(0f, 1f);
            fxChip.performClick();
        });
        awaitFxPanel(frag);
        assertTrue("The rack must open", frag.isFxPanelOpen());

        // ---- add the effect through the real dialog, with a programmatic fallback
        if (!addEffectThroughDialog(effectId, type.title, work, device)) {
            degraded = true;
            try {
                problems.put("the Add Effect dialog could not be driven; the rack "
                        + "was populated programmatically for this effect");
            } catch (Throwable ignored) {
                // reporting only
            }
            scenario.onActivity(a -> {
                try {
                    DspChain.Config cfg = dspConfigOf(frag);
                    cfg.addOrFocusSlot(fxType);
                    cfg.save(a);
                    rebuildRack(frag);
                } catch (Throwable t) {
                    System.out.println("programmatic fallback failed: " + t);
                }
            });
            Thread.sleep(SETTLE_MS);
        }

        // ---- expand the inspector so every parameter has a control
        boolean expanded = expandEffect(root, scenario);
        if (!expanded) {
            problems.put("no Expand effect control was found; parameters were "
                    + "driven from the rack card where possible");
        }
        Thread.sleep(SETTLE_MS);

        AudioProcessor proto = FxRackChain.getPrototypeProcessor(effectId);
        assertNotNull("No prototype processor for " + effectId, proto);
        List<FxParameter> params = proto.getParameters();
        String primaryParam = FxVerifySupportDevice.primaryParamId(type);

        int shotIndex = 0;
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
            String controlKind;
            View control = findParamControl(root, param, type, primaryParam);
            if (control instanceof TeleValue) {
                controlKind = "TeleValue";
            } else if (control instanceof TextView) {
                controlKind = "chip";
            } else {
                controlKind = "missing";
            }
            if ("missing".equals(controlKind)) {
                degraded = true;
                problems.put("no UI control found for parameter " + param.id
                        + " (" + param.name + ")");
            }
            for (int value : values) {
                String label = param.id + "=" + param.formatRaw(value);
                try {
                    if (control instanceof TeleValue) {
                        TeleValue tv = (TeleValue) control;
                        scenario.onActivity(a -> tv.setValue(value, true));
                        // One real touch on the control, so the touch path is
                        // exercised too and not just the programmatic setter.
                        tapStepper(device, param.name, value >= param.defaultRaw);
                    } else if (control instanceof TextView) {
                        TextView chip = (TextView) control;
                        clickChipToState(scenario, chip, param, value);
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
        scenario.onActivity(a -> frag.applyEffectsToSelection());
        Thread.sleep(800);
        File wet = new File(work, "wet.wav");
        copy(input, wet);
        writePeaks(dry, wet, work);
        result.put("bakedFrames", FxVerifySupportDevice.readInfo(wet).frames);

        JSONObject logcatErrors = collectLogcatErrors(ctx);
        if (logcatErrors.length() > 0) {
            result.put("logcatErrors", logcatErrors);
        }
        stopScreenRecord(recorder, device, videoPath);
        result.put("videoPath", videoPath);
        result.put("videoName", new File(videoPath).getName());
        result.put("recorded", recorder != null);
        result.put("degraded", degraded);
        result.put("shotCount", shots.length());
        result.put("driven", driven);
        result.put("problems", problems);
        result.put("notes", notes);
        result.put("shots", shots);
        write(new File(work, "shots.json"), shots.toString(2));
        write(new File(work, "result.json"), result.toString(2));
        System.out.println("FX UI sweep " + effectId + ": " + shots.length()
                + " parameter states captured, degraded=" + degraded);
    }

    // ------------------------------------------------------------------ plumbing

    private static StudioFragment awaitStudio(ActivityScenario<MainActivity> scenario)
            throws Exception {
        for (int i = 0; i < 100; i++) {
            final StudioFragment[] holder = new StudioFragment[1];
            scenario.onActivity(a -> holder[0] = (StudioFragment)
                    a.getSupportFragmentManager().findFragmentByTag("STU"));
            if (holder[0] != null && holder[0].getView() != null) return holder[0];
            Thread.sleep(100);
        }
        return null;
    }

    private static void awaitFxPanel(StudioFragment frag) throws Exception {
        for (int i = 0; i < 50 && !frag.isFxPanelOpen(); i++) Thread.sleep(100);
    }

    /** Uses the real search dialog; returns false when it cannot be driven. */
    private static boolean addEffectThroughDialog(String effectId, String title,
                                                  File work, UiDevice device)
            throws Exception {
        try {
            UiObject2 add = device.findObject(By.text("+ Add Effect"));
            if (add == null) return false;
            add.click();
            Thread.sleep(500);
            UiObject2 box = device.findObject(By.clazz("android.widget.EditText"));
            if (box == null) return false;
            box.setText(title);
            Thread.sleep(500);
            UiObject2 row = device.findObject(By.text(title));
            if (row == null) return false;
            row.click();
            Thread.sleep(600);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean expandEffect(View root, ActivityScenario<MainActivity> scenario)
            throws Exception {
        List<View> found = new ArrayList<>();
        collectByDescription(root, "Expand effect", found);
        if (found.isEmpty()) return false;
        View target = found.get(0);
        scenario.onActivity(a -> target.performClick());
        Thread.sleep(400);
        return true;
    }

    private static void tapStepper(UiDevice device, String paramName, boolean up) {
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

    private static void clickChipToState(ActivityScenario<MainActivity> scenario,
                                         TextView chip, FxParameter param, int value)
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
            scenario.onActivity(a -> chip.performClick());
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
        if (v instanceof ImageView && desc.contentEquals(
                v.getContentDescription() == null ? "" : v.getContentDescription())) {
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
        File png = new File(out.getParentFile(), out.getName()
                .replace(".jpg", ".png"));
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

    private static void stopScreenRecord(ParcelFileDescriptor pfd, UiDevice device,
                                         String path) {
        try {
            if (pfd != null) pfd.close();
        } catch (Throwable ignored) {
            // closed below anyway
        }
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pkill -INT screenrecord").close();
            Thread.sleep(1500);
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
        String pkg = ctx.getPackageName();
        String filtered = sb.toString().replace(pkg, "<pkg>");
        out.put("raw", filtered.length() > 4000
                ? filtered.substring(0, 4000) : filtered);
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
}
