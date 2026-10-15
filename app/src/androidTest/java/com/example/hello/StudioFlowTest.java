package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Full studio screen flows against the real UI: navigation, pause/mirror
 * controls, and record-stop-save landing in the library.
 */
@RunWith(AndroidJUnit4.class)
public class StudioFlowTest {

    /**
     * The studio capture path also gates on POST_NOTIFICATIONS (SDK 33+):
     * without it the fab opens a rationale instead of recording. Grant it
     * once per class so these tests verify recording instead of skipping.
     */
    @org.junit.BeforeClass
    public static void grantNotifForUiRecording() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().getUiAutomation()
                        .executeShellCommand("pm grant "
                                + androidx.test.platform.app
                                        .InstrumentationRegistry
                                        .getInstrumentation()
                                        .getTargetContext().getPackageName()
                                + " android.permission.POST_NOTIFICATIONS");
            } catch (Exception ignored) {}
        }
    }

    @Rule
    public androidx.test.rule.GrantPermissionRule mic =
            androidx.test.rule.GrantPermissionRule.grant(
                    android.Manifest.permission.RECORD_AUDIO);

    private Activity launch() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    /** Opens the studio with a pre-seeded session (no name dialog). */
    private Activity openStudio() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine.get().abortAndDelete();
        RecordingEngine.get().startNewSession(ctx, "Flow Take", 2);
        Activity a = launch();
        View fab = a.findViewById(R.id.fabWrap);
        assertNotNull("no record button", fab);
        a.runOnUiThread(fab::performClick);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNotNull("studio did not open",
                a.findViewById(R.id.teleView));
        return a;
    }

    private static void uiTap(Activity a, View v) {
        a.runOnUiThread(v::performClick);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void studioOpensFromLibrary() throws Exception {
        Activity a = openStudio();
        try {
            assertNotNull(a.findViewById(R.id.telePanel));
            assertNotNull(a.findViewById(R.id.waveform));
            assertNotNull(a.findViewById(R.id.timerText));
            assertNotNull(a.findViewById(R.id.zoomValue));
            assertEquals("00:00.00",
                    ((TextView) a.findViewById(R.id.timerText)).getText()
                            .toString());
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }

    @Test
    public void pauseButtonFlipsLabelBothWays() throws Exception {
        Activity a = openStudio();
        try {
            TextView pause = a.findViewById(R.id.telePauseBtn);
            assertEquals("Pause", pause.getText().toString());
            uiTap(a, pause);
            assertEquals("Play", pause.getText().toString());
            uiTap(a, pause);
            assertEquals("Pause", pause.getText().toString());
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }

    @Test
    public void mirrorTogglePersistsSetting() throws Exception {
        Activity a = openStudio();
        try {
            TeleSettings.invalidateCache();
            boolean before = TeleSettings.load(a).mirror;
            uiTap(a, a.findViewById(R.id.teleMirrorBtn));
            TeleSettings.invalidateCache();
            assertEquals(!before, TeleSettings.load(a).mirror);
            uiTap(a, a.findViewById(R.id.teleMirrorBtn));
            TeleSettings.invalidateCache();
            assertEquals(before, TeleSettings.load(a).mirror);
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }

    @Test
    public void presetLockedWhileAttachedSessionHasAudio() throws Exception {
        android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        RecordPreset before = RecordPreset.load(ctx);
        try {
            RecordPreset.MEMO.save(ctx);
            // Attach a synthetic session with real audio, IDLE (edit shape).
            RecordingEngine.get().abortAndDelete();
            java.io.File takes = RecordingEngine.takesDir(ctx);
            java.io.File raw = new java.io.File(takes,
                    "lock_" + System.nanoTime() + ".wav");
            int frames = 48000;
            java.io.RandomAccessFile w =
                    new java.io.RandomAccessFile(raw, "rw");
            try {
                w.setLength(0);
                w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
                float[] buf = new float[frames * 2];
                for (int i = 0; i < buf.length; i++) {
                    buf[i] = (float) Math.sin(i * 0.05) * 0.3f;
                }
                Dsp.writeFloatsLE(w, buf, 0, buf.length);
            } finally {
                w.close();
            }
            RecordingEngine.get().attachExisting(ctx, raw, "Lock Take", 2,
                    RecordingEngine.payloadBytesOf(raw));
            Activity a = launch();
            try {
                View fab = a.findViewById(R.id.fabWrap);
                assertNotNull("no record button", fab);
                a.runOnUiThread(fab::performClick);
                InstrumentationRegistry.getInstrumentation()
                        .waitForIdleSync();
                assertNotNull("studio did not open",
                        a.findViewById(R.id.teleView));
                Thread.sleep(600);
                uiTap(a, a.findViewById(R.id.settingsRow));
                Thread.sleep(600);
                androidx.test.uiautomator.UiDevice d = UiDevice.getInstance(
                        InstrumentationRegistry.getInstrumentation());
                String podcast = ctx.getString(RecordPreset.PODCAST.titleRes);
                androidx.test.uiautomator.UiObject pill = d.findObject(
                        new UiSelector().text(podcast));
                assertTrue("no podcast pill", pill.waitForExists(5000));
                pill.click();
                Thread.sleep(600);
                assertEquals("preset switched on a fixed-geometry session",
                        RecordPreset.MEMO.id, RecordPreset.load(ctx).id);
                a.runOnUiThread(a::onBackPressed);
                InstrumentationRegistry.getInstrumentation()
                        .waitForIdleSync();
            } finally {
                RecordingEngine.get().abortAndDelete();
            }
        } finally {
            before.save(ctx);
        }
    }

    @Test
    public void backButtonLeavesStudio() throws Exception {
        Activity a = openStudio();
        try {
            // With no session attached, back exits instead of offering save.
            RecordingEngine.get().abortAndDelete();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            uiTap(a, a.findViewById(R.id.backBtn));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            // Library header returns.
            assertNotNull("did not return to library",
                    findByText(a.getWindow().getDecorView(), "Library"));
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }

    @Test
    public void recordStartsCapture() throws Exception {
        Activity a = openStudio();
        try {
            uiTap(a, a.findViewById(R.id.fabWrap));
            long t0 = android.os.SystemClock.elapsedRealtime();
            boolean recording = false;
            while (android.os.SystemClock.elapsedRealtime() - t0 < 4000) {
                if (RecordingEngine.get().state
                        == RecordingEngine.State.RECORDING) {
                    recording = true;
                    break;
                }
                Thread.sleep(200);
            }
            // Devices without audio input cannot capture; the scroll path is
            // covered by tests that do not need a live mic.
            org.junit.Assume.assumeTrue("no audio input on this device",
                    recording);
            assertTrue(RecordingEngine.get().durationMs() >= 0);
        } finally {
            try {
                RecordingEngine.get().pauseCapture();
            } catch (Exception ignored) {}
            RecordingEngine.get().abortAndDelete();
        }
    }

    @Test
    public void recordStopSaveLandsAdoptedInLibrary() throws Exception {
        Activity a = openStudio();
        UiDevice device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        try {
            uiTap(a, a.findViewById(R.id.fabWrap));
            long t0 = android.os.SystemClock.elapsedRealtime();
            boolean recording = false;
            while (android.os.SystemClock.elapsedRealtime() - t0 < 4000) {
                if (RecordingEngine.get().state
                        == RecordingEngine.State.RECORDING) {
                    recording = true;
                    break;
                }
                Thread.sleep(200);
            }
            org.junit.Assume.assumeTrue("no audio input on this device",
                    recording);
            Thread.sleep(2500);
            // Stop -> save dialog -> Save -> adopted save -> library.
            uiTap(a, a.findViewById(R.id.fabStopWrap));
            UiObject save = device.findObject(new UiSelector().text("Save"));
            assertTrue("save dialog never appeared", save.waitForExists(5000));
            save.click();
            // Adopted save exits the studio quickly; the library row with
            // our take name must exist.
            boolean landed = false;
            t0 = android.os.SystemClock.elapsedRealtime();
            while (android.os.SystemClock.elapsedRealtime() - t0 < 15000) {
                if (findByText(a.getWindow().getDecorView(), "Flow Take")
                        != null) {
                    landed = true;
                    break;
                }
                Thread.sleep(500);
            }
            assertTrue("saved take never landed in the library", landed);
        } finally {
            try {
                for (Take t : TakesRepository.all()) {
                    if ("Flow Take".equals(t.title)) {
                        TakesRepository.delete(t.id);
                    }
                }
            } catch (Exception ignored) {}
            RecordingEngine.get().abortAndDelete();
        }
    }

    private static View findByText(View v, String needle) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && needle.equals(cs.toString().trim())) return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), needle);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    @Test
    public void backDuringRecordingOffersBackground() throws Exception {
        Activity a = openStudio();
        UiDevice device = UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
        try {
            uiTap(a, a.findViewById(R.id.fabWrap));
            long t0 = android.os.SystemClock.elapsedRealtime();
            boolean recording = false;
            while (android.os.SystemClock.elapsedRealtime() - t0 < 4000) {
                if (RecordingEngine.get().state
                        == RecordingEngine.State.RECORDING) {
                    recording = true;
                    break;
                }
                Thread.sleep(200);
            }
            org.junit.Assume.assumeTrue("no audio input on this device",
                    recording);
            uiTap(a, a.findViewById(R.id.backBtn));
            UiObject title = device.findObject(
                    new UiSelector().text("End this take?"));
            assertTrue("no background choice", title.waitForExists(5000));
            assertTrue("no keep-recording option",
                    device.findObject(new UiSelector()
                            .text("Keep recording in background"))
                            .waitForExists(3000));
            device.findObject(new UiSelector()
                    .text("Keep recording in background")).click();
            Thread.sleep(1000);
            // Still recording, back in the library.
            assertTrue(RecordingEngine.get().state
                    == RecordingEngine.State.RECORDING);
            assertNotNull(findByText(a.getWindow().getDecorView(),
                    "Library"));
            // Reopen and discard to leave no trace.
            uiTap(a, a.findViewById(R.id.fabWrap));
            Thread.sleep(1000);
            uiTap(a, a.findViewById(R.id.fabStopWrap));
            UiObject discard = device.findObject(
                    new UiSelector().text("Discard"));
            assertTrue("no discard option", discard.waitForExists(5000));
            discard.click();
            Thread.sleep(1500);
            assertTrue(!RecordingEngine.get().hasSession());
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }

    @Test
    public void audioFocusLossPausesAndNeverResumes() throws Exception {
        // A phone call manifests to the app as audio-focus loss; the
        // contract is pause now, never resume on your own.
        Activity a = openStudio();
        try {
            uiTap(a, a.findViewById(R.id.fabWrap));
            long t0 = android.os.SystemClock.elapsedRealtime();
            boolean recording = false;
            while (android.os.SystemClock.elapsedRealtime() - t0 < 4000) {
                if (RecordingEngine.get().state
                        == RecordingEngine.State.RECORDING) {
                    recording = true;
                    break;
                }
                Thread.sleep(200);
            }
            org.junit.Assume.assumeTrue("no audio input on this device",
                    recording);
            // Outlive the focus-loss grace window after capture start.
            Thread.sleep(1500);
            android.media.AudioManager am =
                    (android.media.AudioManager) a.getSystemService(
                            android.content.Context.AUDIO_SERVICE);
            android.media.AudioManager.OnAudioFocusChangeListener probe =
                    focus -> { };
            int res = am.requestAudioFocus(probe,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    res);
            try {
                long t1 = android.os.SystemClock.elapsedRealtime();
                boolean paused = false;
                while (android.os.SystemClock.elapsedRealtime() - t1 < 5000) {
                    if (RecordingEngine.get().state
                            == RecordingEngine.State.PAUSED) {
                        paused = true;
                        break;
                    }
                    Thread.sleep(200);
                }
                assertTrue("focus loss did not pause capture", paused);
                // Hand focus back: the app must NOT resume by itself.
                am.abandonAudioFocus(probe);
                Thread.sleep(2500);
                assertTrue("capture auto-resumed after focus returned",
                        RecordingEngine.get().state
                                == RecordingEngine.State.PAUSED);
            } finally {
                am.abandonAudioFocus(probe);
            }
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }
}
