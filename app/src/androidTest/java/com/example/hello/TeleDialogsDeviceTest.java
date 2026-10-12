package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.widget.SeekBar;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Opens the teleprompter dialogs against a real Activity on a real Android
 * image.
 *
 * <p>The reported crash was a framework rejection at show() time, which only
 * reproduces with a genuine window — Robolectric covers the ordering, this
 * covers the real thing.
 */
@RunWith(AndroidJUnit4.class)
public class TeleDialogsDeviceTest {

    private Activity launch() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        Intent i = new Intent(instr.getTargetContext(), MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    @Test
    public void tunerOpensOnRealDevice() {
        final Activity a = launch();
        final Dialog[] held = new Dialog[1];
        final boolean[] tuned = {false};
        a.runOnUiThread(() -> {
            held[0] = TeleDialogs.showTuner(a, TeleSettings.load(a), 900,
                    60f, () -> tuned[0] = true);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNotNull("tuner never built a dialog", held[0]);
        assertTrue("tuner never reported a tune", tuned[0]);

        a.runOnUiThread(() -> {
            // Every control must be present and live, not just inflated.
            Dialog d = held[0];
            assertNotNull(d.findViewById(R.id.tuneSpeed));
            assertNotNull(d.findViewById(R.id.tuneWpl));
            assertNotNull(d.findViewById(R.id.tuneSize));
            SeekBar sp = d.findViewById(R.id.tuneSpeed);
            sp.setProgress(sp.getMax());
            d.findViewById(R.id.tuneAuto).performClick();
            d.findViewById(R.id.tuneMirror).performClick();
            d.findViewById(R.id.tuneDone).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void editorOpensOnRealDevice() {
        final Activity a = launch();
        a.runOnUiThread(() -> {
            TeleDialogs.showEditor(a, "line one\nline two", 120,
                    text -> { }, null);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // Reaching here without an exception is the assertion: the old code
        // threw requestFeature() must be called before adding content.
    }

    @Test
    public void appSurvivesOpeningBothDialogs() {
        Activity a = launch();
        a.runOnUiThread(() -> {
            TeleSettings s = TeleSettings.load(a);
            TeleDialogs.showTuner(a, s, 800, 48f, () -> { });
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        a.runOnUiThread(() -> {
            TeleDialogs.showEditor(a, "script", 120, text -> { }, null);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("activity died while dialogs were open",
                !a.isFinishing() && !a.isDestroyed());
    }
}