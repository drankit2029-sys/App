package com.takeone.studio;

import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.uiautomator.By;
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
 * Marketing screenshots for the Play Store listing. Seeds a realistic,
 * populated library (varied durations and favourites) and captures a fixed
 * set of composed screens on this device. Ads are suppressed by the
 * automated-test/entitlement paths so no ad ever appears in a listing shot.
 */
@RunWith(AndroidJUnit4.class)
public class PlayStoreScreenshotTest {

    private static final long SETTLE_MS = 800L;

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

    private static final class TakeSpec {
        final String name;
        final int seconds;
        final boolean favorite;

        TakeSpec(String name, int seconds, boolean favorite) {
            this.name = name;
            this.seconds = seconds;
            this.favorite = favorite;
        }
    }

    private static final TakeSpec[] TAKES = {
            new TakeSpec("Interview with Sarah", 162, true),

            new TakeSpec("Script Read v2", 57, false),
            new TakeSpec("Field Recording", 118, false),
            new TakeSpec("Meeting Notes", 187, false),
            new TakeSpec("Guitar Riff Draft", 44, true),
            new TakeSpec("Podcast Intro", 75, false),
            new TakeSpec("Voice Memo", 18, false),
            new TakeSpec("Morning Warmup", 32, false),
    };

    @Test(timeout = 300000L)
    public void captureListingScreens() throws Exception {
        InstrumentationRegistry.getInstrumentation();
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        UiDevice device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        String deviceId = arg("storeScreensDevice");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = (Build.MANUFACTURER + "_" + Build.MODEL + "_API"
                    + Build.VERSION.SDK_INT + "_" + device.getDisplayWidth()
                    + "x" + device.getDisplayHeight())
                    .replaceAll("[^A-Za-z0-9_.-]", "_");
        }
        File outRoot = new File(ctx.getExternalFilesDir(null),
                "store-screens/" + deviceId);
        deleteRecursively(outRoot);
        //noinspection ResultOfMethodCallIgnored
        outRoot.mkdirs();

        JSONObject report = new JSONObject();
        JSONArray shots = new JSONArray();
        JSONArray issues = new JSONArray();
        report.put("deviceId", deviceId);
        report.put("api", Build.VERSION.SDK_INT);
        report.put("displayWidth", device.getDisplayWidth());
        report.put("displayHeight", device.getDisplayHeight());

        android.app.Instrumentation instrumentation =
                InstrumentationRegistry.getInstrumentation();
        Activity activity = null;
        RecordingEngine engine = RecordingEngine.get();
        try {
            // Ads are suppressed under automated test and with a Pro
            // override; do the same here so listing shots never carry an ad.
            TrialAndBillingManager.setTestOverridePro(Boolean.TRUE);
            engine.abortAndDelete();

            File takesDir = RecordingEngine.takesDir(ctx);
            //noinspection ResultOfMethodCallIgnored
            takesDir.mkdirs();

            // Newest first: addRecording inserts at 0, so seed oldest first.
            // Give the opening take its display name so the studio top bar
            // reads well in screenshots.
            for (int i = TAKES.length - 1; i >= 0; i--) {
                TakeSpec spec = TAKES[i];
                File raw = new File(takesDir,
                        i == 0 ? "Interview with Sarah.f32.wav"
                               : "store_seed_" + i + ".f32.wav");
                writeTake(raw, spec.seconds, 2);
                Take t = TakesRepository.addRecording(raw,
                        spec.seconds * 1000L, spec.name, "WAV32");
                if (spec.favorite && t != null) {
                    TakesRepository.toggleFavorite(t.id);
                }
            }

            // Preset rack with four Pro effects so FX shots look composed.
            DspChain.Config cfg = DspChain.Config.load(ctx);
            cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
            cfg.save(ctx);

            String script = "TakeOne is small-studio magic.\n"
                    + "Today we're recording the final master for the\n"
                    + "VoxFi brand film.\n"
                    + "Ninety seconds, one perfect take,\n"
                    + "and a brand voice that took two years to find.\n"
                    + "\n"
                    + "Take one. Action.";
            try {
                ctx.getSharedPreferences("takeone_script", Context.MODE_PRIVATE)
                        .edit().putString("tele_script", script).apply();
            } catch (Exception ignored) {
            }

            activity = launch(instrumentation);
            waitForTakes(8000);
            sleep(SETTLE_MS);
            shoot(device, outRoot, "listing", "01_library", shots, issues);

            // Open a polished take in the studio.
            File seed = new File(takesDir, "Interview with Sarah.f32.wav");
            final Activity act = activity;
            act.runOnUiThread(() ->
                    ((MainActivity) act).openStudioWithEdit(
                            seed, 991L, "Interview with Sarah"));
            StudioFragment studio = awaitStudio(activity);
            if (studio == null) {
                issues.put("studio never appeared");
                write(new File(outRoot, "report.json"), report.toString(2));
                return;
            }
            View root = null;
            for (int i = 0; i < 50 && root == null; i++) {
                root = studio.getView();
                sleep(100);
            }
            if (root == null) {
                issues.put("studio view never created");
                write(new File(outRoot, "report.json"), report.toString(2));
                return;
            }
            WaveformView waveform = root.findViewById(R.id.waveform);
            for (int i = 0; i < 100 && waveform.historyCount() <= 1; i++) {
                sleep(100);
            }
            sleep(SETTLE_MS);
            selectWholeTake(act, studio, waveform);
            shoot(device, outRoot, "listing", "02_studio", shots, issues);

            click(activity, R.id.btnDspFx);
            for (int i = 0; i < 40 && !studio.isFxPanelOpen(); i++) {
                sleep(100);
            }
            sleep(SETTLE_MS);
            shoot(device, outRoot, "listing", "03_fx_rack", shots, issues);

            // Back to the teleprompter (studio main).
            act.runOnUiThread(() -> {
                if (studio.isFxPanelOpen()) {
                    View btn = ((MainActivity) act).findViewById(
                            R.id.fxCloseBtn);
                    if (btn != null) btn.performClick();
                }
            });
            sleep(SETTLE_MS);
            shoot(device, outRoot, "listing", "04_teleprompter", shots,
                    issues);

            report.put("screenshots", shots);
            report.put("issues", issues);
        } catch (Throwable t) {
            issues.put("fatal: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            report.put("screenshots", shots);
            report.put("issues", issues);
            deleteTakesDir(takesDirSafe(ctx));
            throw t;
        } finally {
            try {
                write(new File(outRoot, "report.json"), report.toString(2));
            } catch (Throwable ignored) {
            }
            try {
                deleteTakesDir(takesDirSafe(ctx));
            } catch (Throwable ignored) {
            }
            engine.abortAndDelete();
        }
    }

    // ---- helpers -------------------------------------------------------

    private File takesDirSafe(Context ctx) {
        try {
            return RecordingEngine.takesDir(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteTakesDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("store_seed_")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    private static void selectWholeTake(final Activity act,
                                        final StudioFragment studio,
                                        final WaveformView waveform) {
        act.runOnUiThread(() -> {
            waveform.selectRange(0f, 1f);
            if (!waveform.hasSelection()) {
                waveform.toggleSelectionAtPlayhead();
                waveform.selectRange(0f, 1f);
            }
        });
        sleep(250);
    }

    private static Activity launch(android.app.Instrumentation instr) {
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

    private static void click(Activity activity, int viewId) {
        final View[] v = new View[1];
        for (int i = 0; i < 40 && v[0] == null; i++) {
            activity.runOnUiThread(() -> v[0] =
                    ((MainActivity) activity).findViewById(viewId));
            sleep(50);
        }
        if (v[0] != null) {
            final View btn = v[0];
            activity.runOnUiThread(btn::performClick);
        }
    }

    private static void waitForTakes(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (TakesRepository.all().size() >= TAKES.length) {
                return;
            }
            sleep(150);
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
            w.write(Dsp.wavFloatHeader(channels,
                    RecordingEngine.SAMPLE_RATE, bytes));
            float[] buf = new float[frames * channels];
            for (int f = 0; f < frames; f++) {
                float v = (float) (0.18 * Math.sin(
                        2.0 * Math.PI * 220.0 * f
                                / RecordingEngine.SAMPLE_RATE)
                        + 0.07 * Math.sin(
                        2.0 * Math.PI * 660.0 * f
                                / RecordingEngine.SAMPLE_RATE));
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
