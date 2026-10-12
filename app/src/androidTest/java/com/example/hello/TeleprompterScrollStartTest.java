package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Proves the prompter starts moving with the recording.
 *
 * <p>The complaint was that the script sat still for several seconds after
 * hitting record — the countdown was gating the scroll, so the take began
 * before the text did.
 */
@RunWith(AndroidJUnit4.class)
public class TeleprompterScrollStartTest {

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
        View fab = a.findViewById(R.id.fabWrap);
        if (fab != null) {
            a.runOnUiThread(fab::performClick);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        // A fresh install asks for a take name before showing the studio.
        View root = a.getWindow().getDecorView();
        TeleprompterView tele = (TeleprompterView) root.findViewById(R.id.teleView);
        if (tele == null) {
            View start = findByText(root, "Start");
            if (start != null) {
                a.runOnUiThread(start::performClick);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            tele = (TeleprompterView) a.findViewById(R.id.teleView);
        }
        return tele;
    }

    private static View findByText(View v, String needle) {
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
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
    public void prompterBeginsScrollingWithRecordingNotAfterACountdown() throws Exception {
        Activity a = launch();
        // Seed a script long enough that there is somewhere to scroll to.
        a.getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                .putString("tele_script", manyWords(400))
                .putBoolean("tele_auto", true)
                .apply();
        TeleSettings.invalidateCache();

        // Relaunch so the fragment picks the script up.
        Activity b = launch();
        TeleprompterView tele = gotoStudio(b);
        assertNotNull("teleprompter not on screen", tele);
        // Give layout a beat, then confirm there is scrollable content.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(400);
        assertTrue("no scrollable script loaded", tele.maxScroll() > 0f);

        View fab = b.findViewById(R.id.fabWrap);
        assertNotNull("no record button", fab);
        final boolean[] started = {false};
        b.runOnUiThread(() -> {
            fab.performClick();
            started[0] = true;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("record tap never ran", started[0]);

        // Sample the scroll position. The first movement must land within a
        // second of capture starting, not after a multi-second countdown.
        float first = tele.scrollOffset();
        long firstMoveAt = -1L;
        long t0 = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - t0 < 6000L) {
            SystemClock.sleep(150);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            float now = tele.scrollOffset();
            if (now > first + 1f) {
                firstMoveAt = SystemClock.elapsedRealtime() - t0;
                break;
            }
        }
        assertTrue("prompter never started scrolling", firstMoveAt >= 0L);
        assertTrue("prompter waited " + firstMoveAt
                + "ms before moving; it must move with the recording",
                firstMoveAt < 1500L);

        // Stop capture so the emulator is not left recording.
        View stop = b.findViewById(R.id.fabStopWrap);
        if (stop != null) {
            b.runOnUiThread(stop::performClick);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }
}