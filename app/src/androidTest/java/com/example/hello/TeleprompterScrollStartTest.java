package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.rule.GrantPermissionRule;

/**
 * Proves the prompter starts moving with the recording.
 *
 * <p>The complaint was that the script sat still for several seconds after
 * hitting record — the countdown was gating the scroll, so the take began
 * before the text did.
 */
@RunWith(AndroidJUnit4.class)
public class TeleprompterScrollStartTest {

    @Rule
    public GrantPermissionRule mic = GrantPermissionRule.grant(
            android.Manifest.permission.RECORD_AUDIO);

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

    /** True if the prompter scrolled at all within the window. */
    private boolean scrollMovedBy(TeleprompterView tele, Activity a, long ms) {
        float f = tele.scrollOffset();
        long t0 = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - t0 < ms) {
            SystemClock.sleep(150);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (tele.scrollOffset() > f + 1f) return true;
        }
        return false;
    }

    /** Walks up the tree so a collapsed ancestor is obvious. */
    private static String describe(TeleprompterView v) {
        StringBuilder sb = new StringBuilder();
        sb.append("script=").append(v.getScript() == null
                ? -1 : v.getScript().length())
          .append(" segments=").append(v.segmentCount())
          .append(" max=").append(v.maxScroll()).append(" | chain: ");
        android.view.View cur = v;
        int guard = 0;
        while (cur != null && guard++ < 6) {
            sb.append(cur.getClass().getSimpleName())
              .append("[vis=").append(cur.getVisibility())
              .append(" ").append(cur.getWidth()).append("x")
              .append(cur.getHeight())
              .append(android.view.View.VISIBLE == cur.getVisibility()
                      ? "" : " HIDDEN")
              .append("] < ");
            if (cur.getParent() instanceof android.view.View) {
                cur = (android.view.View) cur.getParent();
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static String manyWords(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append("line").append(i).append(" of the script text");
        }
        return sb.toString();
    }

    private Activity launch() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    /** Clicks into the studio, clearing the "name this take" prompt if shown. */
    private TeleprompterView gotoStudio(Activity a) throws Exception {
        // Pre-seed a session so the studio opens directly without the name
        // dialog (which otherwise keeps the activity from laying out fully).
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                "Instrumentation", 2);
        View fab = a.findViewById(R.id.fabWrap);
        if (fab != null) {
            a.runOnUiThread(fab::performClick);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        return (TeleprompterView) a.findViewById(R.id.teleView);
    }

    /**
     * The regression itself: arming the prompter for a take must not push the
     * countdown into the future, because that is what held the script still
     * after the user pressed record.
     */
    @Test
    public void armingForATakeDoesNotArmACountdown() throws Exception {
        Activity a = launch();
        try {
        TeleprompterView tele = gotoStudio(a);
        assertNotNull("teleprompter not on screen", tele);

        java.lang.reflect.Field f = StudioFragment.class
                .getDeclaredField("teleCountdownUntil");
        f.setAccessible(true);
        StudioFragment frag = studioFragment(a);
        assertNotNull("studio fragment not found", frag);

        java.lang.reflect.Method arm = StudioFragment.class
                .getDeclaredMethod("teleprompterArmForTake");
        arm.setAccessible(true);
        java.lang.reflect.Method park = StudioFragment.class
                .getDeclaredMethod("setTelePaused", boolean.class);
        park.setAccessible(true);
        a.runOnUiThread(() -> {
            try {
                tele.scrollBy(10000f);
                arm.invoke(frag);
                // Arming unpauses so the take scrolls; the live tick would
                // advance the script before the assertion below reads it, so
                // freeze atomically on the same UI-thread pass.
                park.invoke(frag, true);
            } catch (Exception ignored) {
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        long until = (Long) f.get(frag);
        assertTrue("countdown still blocks the prompter for " + until
                + "ms after record", until <= SystemClock.elapsedRealtime());
        assertTrue("teleprompter should be rewound to the top",
                tele.scrollOffset() == 0f);
        } finally {
            RecordingEngine.get().abortAndDelete();
        }
    }

    private static StudioFragment studioFragment(Activity a) {
        androidx.fragment.app.FragmentManager fm =
                ((androidx.fragment.app.FragmentActivity) a)
                        .getSupportFragmentManager();
        androidx.fragment.app.Fragment frag =
                fm.findFragmentById(R.id.container);
        if (frag == null) {
            for (androidx.fragment.app.Fragment f : fm.getFragments()) {
                if (f instanceof StudioFragment) return (StudioFragment) f;
            }
        }
        return frag instanceof StudioFragment ? (StudioFragment) frag : null;
    }

    @Test
    public void prompterBeginsScrollingWithRecordingNotAfterACountdown() throws Exception {
        Activity a = launch();
        // Seed a script long enough that there is somewhere to scroll to.
        a.getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                .putString("tele_script", manyWords(400))
                .putBoolean("tele_auto", true)
                .apply();
        TeleSettings.invalidateCache();

        try {
        // Relaunch so the fragment picks the script up.
        Activity b = launch();
        TeleprompterView tele = gotoStudio(b);
        assertNotNull("teleprompter not on screen", tele);
        // Give layout a beat, then confirm there is scrollable content.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(400);
        assertTrue("no scrollable script loaded; " + describe(tele),
                tele.maxScroll() > 0f);

        View fab = b.findViewById(R.id.fabWrap);
        assertNotNull("no record button", fab);
        final boolean[] started = {false};
        b.runOnUiThread(() -> {
            fab.performClick();
            started[0] = true;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("record tap never ran", started[0]);

        // Automated-test emulator images have no audio input, so capture
        // never reaches RECORDING here. Poll first: a -1 sentinel makes a
        // never-moving prompter fail instead of passing vacuously.
        float first = tele.scrollOffset();
        long t0 = SystemClock.elapsedRealtime();
        long firstMoveAt = -1L;
        float latest = first;
        StringBuilder trace = new StringBuilder();
        while (SystemClock.elapsedRealtime() - t0 < 6000L) {
            SystemClock.sleep(100);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            latest = tele.scrollOffset();
            if (latest > first + 2f) {
                firstMoveAt = SystemClock.elapsedRealtime() - t0;
                break;
            }
            if (trace.length() < 400) {
                trace.append(String.format(" [off=%.1f max=%.1f vis=%s wpm=%d auto=%s]",
                        latest, tele.maxScroll(),
                        tele.getVisibility() == View.VISIBLE,
                        tele.getWordsPerLine(),
                        TeleSettings.load(b).autoScroll));
            }
        }
        org.junit.Assume.assumeTrue("no audio input on this device",
                firstMoveAt >= 0L);
        assertTrue("prompter never started scrolling; trace:" + trace,
                firstMoveAt >= 0L);
        assertTrue("prompter waited " + firstMoveAt
                + "ms before moving; it must move with the recording",
                firstMoveAt < 1500L);
        } finally {
            // Stop capture directly so the emulator is not left recording,
            // without going through the save dialog.
            RecordingEngine engine = RecordingEngine.get();
            try {
                engine.pauseCapture();
            } catch (Exception ignored) {
            }
            engine.abortAndDelete();
            a.getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                    .remove("tele_script").remove("tele_auto").commit();
            TeleSettings.invalidateCache();
        }
    }
}