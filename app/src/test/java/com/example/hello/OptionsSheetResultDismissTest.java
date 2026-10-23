package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * BUG-STU-02: dismissing a result dialog by tapping outside or pressing Back
 * must still run the callback, exactly once. The studio used this callback to
 * leave the screen, so a plain dismissal trapped the user there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OptionsSheetResultDismissTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void outsideDismissFiresCallbackOnce() {
        Activity a = activity();
        final int[] fired = {0};
        android.app.Dialog d = OptionsSheet.showResult(a, true,
                "Saved", "Msg", () -> fired[0]++);
        ShadowLooper.idleMainLooper();
        // Simulate a tap outside / Back press.
        d.dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals("dismiss must run the callback", 1, fired[0]);
    }

    @Test
    public void bannerTapThenDismissFiresOnlyOnce() {
        Activity a = activity();
        final int[] fired = {0};
        android.app.Dialog d = OptionsSheet.showResult(a, true,
                "Take saved", null, () -> fired[0]++);
        ShadowLooper.idleMainLooper();
        android.view.View ok = d.findViewById(R.id.resultOk);
        assertTrue("Got it button must be hidden",
                ok == null || ok.getVisibility() == android.view.View.GONE);
        android.view.View title = d.findViewById(R.id.resultTitle);
        a.runOnUiThread(((android.view.View) title.getParent().getParent())
                ::performClick);
        ShadowLooper.idleMainLooper();
        d.dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals("callback fired more than once", 1, fired[0]);
    }

    @Test
    public void callbackSurvivesRepeatedDismissals() {
        Activity a = activity();
        final int[] fired = {0};
        android.app.Dialog d = OptionsSheet.showResult(a, false,
                "Failed", "Msg", () -> fired[0]++);
        ShadowLooper.idleMainLooper();
        d.dismiss();
        d.dismiss();
        d.cancel();
        ShadowLooper.idleMainLooper();
        assertEquals(1, fired[0]);
    }
}