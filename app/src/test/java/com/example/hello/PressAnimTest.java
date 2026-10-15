package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Press feedback must never break taps or layouts: it only animates scale
 * (and alpha for the plain variant), always returning views home.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PressAnimTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void applyKeepsClicksWorking() {
        Activity a = activity();
        View v = new View(a);
        final boolean[] clicked = {false};
        v.setOnClickListener(view -> clicked[0] = true);
        PressAnim.apply(v);
        v.performClick();
        assertTrue("click listener lost", clicked[0]);
    }

    @Test
    public void applySoftKeepsClicksWorking() {
        Activity a = activity();
        View v = new View(a);
        final boolean[] clicked = {false};
        v.setOnClickListener(view -> clicked[0] = true);
        PressAnim.applySoft(v, 0.95f);
        v.performClick();
        assertTrue("click listener lost", clicked[0]);
    }

    @Test
    public void nudgeAndPopDoNotThrow() {
        Activity a = activity();
        View v = new View(a);
        a.setContentView(v);
        PressAnim.nudge(v, 1.1f);
        PressAnim.pop(v);
        assertTrue("view detached", v.getParent() != null);
    }
}
