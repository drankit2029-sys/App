package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

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
    public void appSurvivesOpeningEditorTwice() {
        Activity a = launch();
        a.runOnUiThread(() -> {
            TeleDialogs.showEditor(a, "script", 120, text -> { }, null);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        a.runOnUiThread(() -> {
            TeleDialogs.showEditor(a, "script two", 120, text -> { }, null);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("activity died while dialogs were open",
                !a.isFinishing() && !a.isDestroyed());
    }
}