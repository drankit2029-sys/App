package com.takeone.studio;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * UI screenshot tour used by the emulator matrix.
 *
 * <p>The generic adb driver in {@code tools/ui-screens/ui_driver.py} can drive
 * any tap/key/screenshot sequence from the outside. This test seeds a real
 * take and visits the app's own dialogs and menus, which cannot be reached
 * from a cold launch without a session. Screenshots are written to
 * {@code files/ui-screens/<device>/<category>/} so the workflow can pull one
 * dedicated folder per emulator.
 *
 * <p>Run with:
 * {@code -e class com.takeone.studio.UiScreenshotTourTest -e uiScreensDevice <id>}.
 */
@RunWith(AndroidJUnit4.class)
public class UiScreenshotTourTest {

    private static final long SETTLE_MS = 650L;

    @Rule
    public GrantPermissionRule mic = GrantPermissionRule.grant(
            android.Manifest.permission.RECORD_AUDIO);

    @BeforeClass
    public static void grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            Context ctx = InstrumentationRegistry.getInstrumentation()
                    .getTargetContext();
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("pm grant " + ctx.getPackageName()
                            + " android.permission.POST_NOTIFICATIONS").close();
        } catch (Throwable ignored) {
        }
    }

    @Test(timeout = 300000L)
    public void captureEveryReachableUiSurface() throws Exception {
        Instrumentation instr = InstrumentationRegistry.getInstrumentation();
        Context ctx = instr.getTargetContext();
        UiDevice device = UiDevice.getInstance(instr);
        String deviceId = arg("uiScreensDevice");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = (Build.MANUFACTURER + "_" + Build.MODEL + "_API"
                    + Build.VERSION.SDK_INT + "_" + device.getDisplayWidth()
                    + "x" + device.getDisplayHeight())
                    .replaceAll("[^A-Za-z0-9_.-]", "_");
        }
        File outRoot = new File(ctx.getExternalFilesDir(null),
                "ui-screens/" + deviceId);
        deleteRecursively(outRoot);
        //noinspection ResultOfMethodCallIgnored
        outRoot.mkdirs();

        JSONObject report = new JSONObject();
        JSONArray screenshots = new JSONArray();
        JSONArray issues = new JSONArray();
        report.put("deviceId", deviceId);
        report.put("manufacturer", Build.MANUFACTURER);
        report.put("model", Build.MODEL);
        report.put("sdk", Build.VERSION.SDK_INT);
        report.put("displayWidth", device.getDisplayWidth());
        report.put("displayHeight", device.getDisplayHeight());
        report.put("displayRotation", device.getDisplayRotation());
        report.put("densityDpi", ctx.getResources().getDisplayMetrics().densityDpi);
        Configuration cfg = ctx.getResources().getConfiguration();
        report.put("screenWidthDp", cfg.screenWidthDp);
        report.put("screenHeightDp", cfg.screenHeightDp);
        report.put("smallestScreenWidthDp", cfg.smallestScreenWidthDp);
        report.put("orientation", cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? "landscape" : "portrait");
        report.put("screenshots", screenshots);
        report.put("issues", issues);

        Activity activity = null;
        RecordingEngine engine = RecordingEngine.get();
        try {
            TrialAndBillingManager.setTestOverridePro(Boolean.TRUE);

            File takes = RecordingEngine.takesDir(ctx);
            //noinspection ResultOfMethodCallIgnored
            takes.mkdirs();
            File raw = new File(takes, "ui_screenshot_" + System.nanoTime() + ".wav");
            writeTake(raw, 3, 2);
            engine.abortAndDelete();
            engine.attachExisting(ctx, raw, "UI Screenshot Take", 2,
                    RecordingEngine.payloadBytesOf(raw));

            activity = launch(instr);
            assertNotNull("MainActivity did not start", activity);
            sleep(1200);
            shoot(device, outRoot, "library", "01_library", screenshots, issues);

            click(activity, R.id.sortBtn);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "library", "02_sort_menu", screenshots, issues);
            UiObject2 newest = find(device, By.textContains("Newest first"));
            if (newest != null) {
                try {
                    newest.click();
                } catch (Throwable stale) {
                    device.pressBack();
                }
            } else {
                device.pressBack();
            }
            sleep(300);

            click(activity, R.id.searchToggle);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "library", "03_search", screenshots, issues);
            // Close search through its own toggle instead of a system BACK;
            // BACK can close the activity when the keyboard is already gone.
            click(activity, R.id.searchToggle);
            sleep(300);

            shoot(device, outRoot, "library", "04_before_studio",
                    screenshots, issues);
            if (activity.isFinishing()) {
                issues.put("library activity finished before studio launch");
            }
            final Activity act = activity;
            act.runOnUiThread(() -> ((MainActivity) act).openStudioWithEdit(
                    raw, 991L, "UI Screenshot Take"));
            StudioFragment studio = awaitStudio(activity);
            assertNotNull("Studio fragment never appeared", studio);
            StudioFragment frag = studio;
            View root = awaitRoot(frag);
            assertNotNull("Studio root missing", root);

            // Wait for the waveform bars loaded off the working file.
            WaveformView waveform = root.findViewById(R.id.waveform);
            for (int i = 0; i < 80 && waveform.historyCount() <= 1; i++) {
                sleep(100);
            }
            sleep(SETTLE_MS);
            shoot(device, outRoot, "studio", "01_main", screenshots, issues);

            // Display cutout/status/navigation safe-area evidence.
            recordInsets(activity, report, issues);
            assertBackButtonClearsCutout(activity, issues);

            click(activity, R.id.settingsRow);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "studio", "02_recording_settings",
                    screenshots, issues);
            device.pressBack();
            sleep(300);

            click(activity, R.id.teleEditBtn);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "studio", "03_teleprompter_editor",
                    screenshots, issues);
            device.pressBack();
            sleep(300);

            // Finish-take confirmation, dismissed without mutating the take.
            act.runOnUiThread(frag::onStopPressed);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "studio", "04_finish_take_modal",
                    screenshots, issues);
            if (device.hasObject(By.textContains("Finish take"))) {
                device.pressBack();
                sleep(300);
            }

            // Select the whole take, then open the FX rack.
            act.runOnUiThread(() -> {
                waveform.selectRange(0f, 1f);
                if (!waveform.hasSelection()) {
                    waveform.toggleSelectionAtPlayhead();
                    waveform.selectRange(0f, 1f);
                }
            });
            sleep(300);
            click(activity, R.id.btnDspFx);
            for (int i = 0; i < 30 && !frag.isFxPanelOpen(); i++) sleep(100);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "fx", "01_rack", screenshots, issues);

            click(activity, R.id.fxPresetDropdown);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "fx", "02_presets_menu",
                    screenshots, issues);
            device.pressBack();
            sleep(250);

            click(activity, R.id.fxToolsBtn);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "fx", "03_tools_menu", screenshots, issues);
            device.pressBack();
            sleep(250);

            // Open the production Add Effect dialog directly: on very short
            // screens the rack action row is clipped, and UiAutomator's
            // scroll/idle handshake can wedge inside the scroll view.
            boolean addDialogOpened = invokeAddEffectDialog(act, frag);
            if (addDialogOpened) {
                sleep(SETTLE_MS);
                shoot(device, outRoot, "fx", "04_add_effect_dialog",
                        screenshots, issues);
                device.pressBack();
                sleep(300);
            } else {
                issues.put("Add Effect dialog could not be opened");
            }

            // Add the effect through the app's own config/rebuild path so the
            // rack/inspector images exist even when the list row is below the
            // fold or the dialog itself is clipped on a 320x480 screen.
            if (addAmplifyProgrammatically(act, frag)) {
                sleep(SETTLE_MS);
                shoot(device, outRoot, "fx", "05_effect_added",
                        screenshots, issues);
                if (invokeEffectDetailDialog(act, frag)) {
                    sleep(SETTLE_MS);
                    shoot(device, outRoot, "fx", "06_effect_detail",
                            screenshots, issues);
                    UiObject2 close = find(device, By.text("Close"));
                    if (close != null) close.click();
                    else device.pressBack();
                    sleep(300);
                } else {
                    issues.put("inspector could not open");
                }
            } else {
                issues.put("effect could not be added programmatically");
            }

            // Notification shade over the live activity.
            try {
                device.openNotification();
                sleep(1000);
                shoot(device, outRoot, "system", "01_notification_overlay",
                        screenshots, issues);
                device.pressBack();
                sleep(500);
                report.put("notificationShadeOpened", true);
                assertTrue("activity finished after notification shade",
                        !activity.isFinishing());
            } catch (Throwable t) {
                issues.put("notification overlay: " + t.getClass().getSimpleName());
            }

            // Back navigation from the rack/studio.
            if (frag.isFxPanelOpen()) {
                click(activity, R.id.fxCloseBtn);
                sleep(300);
            }
            device.pressBack();
            sleep(800);
            shoot(device, outRoot, "system", "02_navigation_back",
                    screenshots, issues);
            report.put("passedBackNavigation", true);
        } catch (Throwable t) {
            issues.put("fatal: " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
            throw t;
        } finally {
            try {
                write(new File(outRoot, "report.json"), report.toString(2));
            } catch (Throwable ignored) {
            }
            try {
                engine.abortAndDelete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Activity launch(Instrumentation instr) {
        Context ctx = instr.getTargetContext();
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instr.startActivitySync(i);
        instr.waitForIdleSync();
        return activity;
    }

    private static StudioFragment awaitStudio(Activity activity) throws Exception {
        for (int i = 0; i < 80; i++) {
            StudioFragment f = (StudioFragment) ((MainActivity) activity)
                    .getSupportFragmentManager().findFragmentByTag("STU");
            if (f != null && f.isAdded()) return f;
            sleep(100);
        }
        return null;
    }

    private static View awaitRoot(StudioFragment frag) throws Exception {
        for (int i = 0; i < 50; i++) {
            final View[] holder = new View[1];
            Activity host = frag.getActivity();
            if (host == null) {
                sleep(100);
                continue;
            }
            host.runOnUiThread(() -> {
                try {
                    ((MainActivity) host).getSupportFragmentManager()
                            .executePendingTransactions();
                } catch (Throwable ignored) {
                }
                holder[0] = frag.getView();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (holder[0] != null) return holder[0];
            sleep(100);
        }
        return null;
    }

    private static void click(Activity activity, int id) {
        View v = activity.findViewById(id);
        if (v == null) return;
        activity.runOnUiThread(v::performClick);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }





    private static boolean addAmplifyProgrammatically(Activity activity,
                                                       StudioFragment fragment) {
        final boolean[] added = {false};
        activity.runOnUiThread(() -> {
            try {
                java.lang.reflect.Field configField = StudioFragment.class
                        .getDeclaredField("dspConfig");
                configField.setAccessible(true);
                DspChain.Config config =
                        (DspChain.Config) configField.get(fragment);
                if (config == null) return;
                config.addOrFocusSlot(DspChain.EffectType.AMPLIFY);
                config.save(fragment.requireContext());
                java.lang.reflect.Method rebuild = StudioFragment.class
                        .getDeclaredMethod("rebuildFxRackList");
                rebuild.setAccessible(true);
                rebuild.invoke(fragment);
                added[0] = true;
            } catch (Throwable ignored) {
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        return added[0];
    }

    private static boolean invokeEffectDetailDialog(Activity activity,
                                                     StudioFragment fragment) {
        final boolean[] opened = {false};
        activity.runOnUiThread(() -> {
            try {
                java.lang.reflect.Field configField = StudioFragment.class
                        .getDeclaredField("dspConfig");
                configField.setAccessible(true);
                DspChain.Config config =
                        (DspChain.Config) configField.get(fragment);
                if (config == null || config.slots.isEmpty()) return;
                DspChain.EffectSlot slot =
                        config.slots.get(config.slots.size() - 1);
                java.lang.reflect.Method m = StudioFragment.class
                        .getDeclaredMethod("showEffectDetailModal",
                                DspChain.EffectSlot.class);
                m.setAccessible(true);
                m.invoke(fragment, slot);
                opened[0] = true;
            } catch (Throwable ignored) {
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        return opened[0];
    }

    private static boolean invokeAddEffectDialog(Activity activity,
                                                  StudioFragment fragment) {
        final boolean[] opened = {false};
        activity.runOnUiThread(() -> {
            try {
                java.lang.reflect.Method m = StudioFragment.class
                        .getDeclaredMethod("showAddEffectSearchDialog");
                m.setAccessible(true);
                m.invoke(fragment);
                opened[0] = true;
            } catch (Throwable ignored) {
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        return opened[0];
    }

    private static UiObject2 findScrolling(UiDevice device,
                                           androidx.test.uiautomator.BySelector by) {
        for (int i = 0; i < 10; i++) {
            UiObject2 found = find(device, by);
            if (found != null) return found;
            boolean scrolled = false;
            java.util.List<UiObject2> scrollables =
                    device.findObjects(By.scrollable(true));
            for (UiObject2 scrollable : scrollables) {
                try {
                    scrollable.scroll(Direction.DOWN, 0.7f);
                    scrolled = true;
                } catch (Throwable ignored) {
                }
            }
            if (!scrolled) {
                int w = device.getDisplayWidth();
                int h = device.getDisplayHeight();
                device.swipe(w / 2, (int) (h * 0.72), w / 2,
                        (int) (h * 0.32), 20);
            }
            sleep(220);
        }
        return find(device, by);
    }

    private static UiObject2 find(UiDevice device, androidx.test.uiautomator.BySelector by) {
        return device.findObject(by);
    }

    private static void recordInsets(Activity activity, JSONObject report,
                                     JSONArray issues) {
        try {
            View decor = activity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets == null) {
                issues.put("window insets unavailable");
                return;
            }
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    Rect safe = new Rect(cutout.getSafeInsetLeft(),
                            cutout.getSafeInsetTop(),
                            cutout.getSafeInsetRight(),
                            cutout.getSafeInsetBottom());
                    report.put("cutoutSafeInsets", safe.toShortString());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    right = Math.max(right, cutout.getSafeInsetRight());
                }
            }
            report.put("systemWindowInsets",
                    new JSONObject().put("left", left).put("top", top)
                            .put("right", right).put("bottom", bottom));
        } catch (Throwable t) {
            issues.put("insets: " + t.getClass().getSimpleName());
        }
    }

    private static void assertBackButtonClearsCutout(Activity activity,
                                                      JSONArray issues) {
        try {
            View back = activity.findViewById(R.id.backBtn);
            if (back == null) {
                issues.put("back button not found");
                return;
            }
            int[] loc = new int[2];
            back.getLocationOnScreen(loc);
            WindowInsets insets = back.getRootWindowInsets();
            int safeTop = insets == null ? 0 : insets.getSystemWindowInsetTop();
            if (Build.VERSION.SDK_INT >= 28 && insets != null
                    && insets.getDisplayCutout() != null) {
                safeTop = Math.max(safeTop,
                        insets.getDisplayCutout().getSafeInsetTop());
            }
            if (loc[1] < safeTop) {
                issues.put("back button starts under the cutout/status area: y="
                        + loc[1] + " safeTop=" + safeTop);
            }
        } catch (Throwable t) {
            issues.put("back button inset check: "
                    + t.getClass().getSimpleName());
        }
    }

    private static void shoot(UiDevice device, File root, String folder,
                              String name, JSONArray screenshots,
                              JSONArray issues) {
        File dir = new File(root, folder);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File file = new File(dir, name + ".png");
        try {
            if (device.takeScreenshot(file) && file.length() > 0) {
                screenshots.put(folder + "/" + name + ".png");
            } else {
                issues.put("screenshot failed: " + folder + "/" + name);
            }
        } catch (Throwable t) {
            issues.put("screenshot " + folder + "/" + name + ": "
                    + t.getClass().getSimpleName());
        }
    }

    private static void writeTake(File raw, int seconds, int channels)
            throws Exception {
        int frames = RecordingEngine.SAMPLE_RATE * seconds;
        long bytes = (long) frames * channels * 4L;
        try (RandomAccessFile w = new RandomAccessFile(raw, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(channels, RecordingEngine.SAMPLE_RATE,
                    bytes));
            float[] buf = new float[frames * channels];
            for (int f = 0; f < frames; f++) {
                float v = (float) (0.18 * Math.sin(
                        2.0 * Math.PI * 220.0 * f / RecordingEngine.SAMPLE_RATE)
                        + 0.07 * Math.sin(
                        2.0 * Math.PI * 660.0 * f / RecordingEngine.SAMPLE_RATE));
                for (int c = 0; c < channels; c++) {
                    buf[f * channels + c] = v;
                }
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        }
    }

    private static String arg(String key) {
        return InstrumentationRegistry.getArguments().getString(key);
    }

    private static void sleep(long ms) {
        SystemClock.sleep(ms);
    }

    private static void write(File file, String text) throws Exception {
        try (java.io.FileWriter w = new java.io.FileWriter(file)) {
            w.write(text);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
