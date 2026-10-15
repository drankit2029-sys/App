package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The four value-control behaviours must not leak into each other: a scrub
 * adjusts and finishes without opening the picker or keyboard, while taps and
 * holds retain their own entry points.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TeleValueGestureTest {

    private static class RecordingTeleValue extends TeleValue {
        int pickers;
        int keyboards;
        final List<Integer> live = new ArrayList<>();
        int finished = -1;
        int discrete = -1;

        RecordingTeleValue(Context context) {
            super(context);
            setOnScrubListener(new OnScrubListener() {
                @Override public void onScrubValueChanged(int value) {
                    live.add(value);
                }

                @Override public void onScrubFinished(int value) {
                    finished = value;
                }
            });
            setOnValueChanged(value -> discrete = value);
        }

        @Override protected void showValuePicker() {
            pickers++;
        }

        @Override protected void showKeyboardInput() {
            keyboards++;
        }
    }

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private RecordingTeleValue control() {
        Activity a = activity();
        RecordingTeleValue tv = new RecordingTeleValue(a);
        a.setContentView(tv);
        tv.configure(10, 100, 1, 50, "");
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, 300, 80);
        return tv;
    }

    private static View findValueLabel(View root) {
        CharSequence description = root.getContentDescription();
        if (root instanceof android.widget.TextView
                && description != null
                && "value".contentEquals(description)) {
            return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findValueLabel(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static float[] centerOf(View root, View v) {
        float x = v.getLeft() + v.getWidth() / 2f;
        float y = v.getTop() + v.getHeight() / 2f;
        android.view.ViewParent parent = v.getParent();
        while (parent != null && parent != root && parent instanceof View) {
            View parentView = (View) parent;
            x += parentView.getLeft();
            y += parentView.getTop();
            parent = parentView.getParent();
        }
        return new float[]{x, y};
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    @Test
    public void scrubReportsLiveAndFinishWithoutDialogs() {
        RecordingTeleValue tv = control();
        View label = findValueLabel(tv);
        assertTrue("value label not found", label != null);
        float[] c = centerOf(tv, label);

        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        for (float x = c[0] + 8f; x <= c[0] + 130f; x += 8f) {
            tv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x, c[1]));
        }
        assertTrue("scrub did not report live values: " + tv.live,
                !tv.live.isEmpty());
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, c[0] + 130f, c[1]));

        assertEquals("scrub did not finish", tv.getValue(), tv.finished);
        assertEquals("scrub leaked into the discrete listener", -1, tv.discrete);
        assertEquals("scrub opened the picker", 0, tv.pickers);
        assertEquals("scrub opened the keyboard", 0, tv.keyboards);
    }

    @Test
    public void verticalScrubDrivesValueWhenEnabled() {
        RecordingTeleValue tv = control();
        tv.setVerticalScrub(true);
        View label = findValueLabel(tv);
        float[] c = centerOf(tv, label);

        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        // Drag upward: the value must climb, like the waveform's own gesture.
        for (float y = c[1] - 8f; y >= c[1] - 80f; y -= 8f) {
            tv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, c[0], y));
        }
        assertTrue("vertical scrub did not report live values: " + tv.live,
                !tv.live.isEmpty());
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, c[0], c[1] - 80f));

        assertEquals(tv.getValue(), tv.finished);
        assertTrue("vertical scrub moved the value down", tv.getValue() > 50);
        assertEquals(0, tv.pickers);
        assertEquals(0, tv.keyboards);
    }

    @Test
    public void verticalMoveIsConsumedWithoutValueOrDialogs() {        RecordingTeleValue tv = control();
        View label = findValueLabel(tv);
        float[] c = centerOf(tv, label);

        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        for (float y = c[1] + 8f; y <= c[1] + 80f; y += 8f) {
            tv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, c[0], y));
        }
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, c[0], c[1] + 80f));

        assertEquals(50, tv.getValue());
        assertTrue("vertical move reported live values", tv.live.isEmpty());
        assertEquals(-1, tv.finished);
        assertEquals(0, tv.pickers);
        assertEquals(0, tv.keyboards);
    }

    @Test
    public void secondTouchCancelsPendingPicker() {
        RecordingTeleValue tv = control();
        View label = findValueLabel(tv);
        float[] c = centerOf(tv, label);

        // A clean tap schedules the picker through the label callback; the
        // next touch must cancel it before it can appear during a gesture.
        ((android.widget.TextView) label).performClick();
        assertTrue("clean tap did not schedule the picker",
                tv.hasPendingPickerForTest());

        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        assertFalse("second touch left the picker scheduled",
                tv.hasPendingPickerForTest());
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, c[0], c[1]));
        assertEquals(0, tv.pickers);
        assertEquals(0, tv.keyboards);
    }

    @Test
    public void doubleTapOpensKeyboardDirectly() {
        RecordingTeleValue tv = control();
        View label = findValueLabel(tv);

        ((android.widget.TextView) label).performClick();
        ((android.widget.TextView) label).performClick();

        assertEquals(1, tv.keyboards);
        assertEquals(0, tv.pickers);
    }

    @Test
    public void movementCancelsPendingHold() {
        RecordingTeleValue tv = control();
        View label = findValueLabel(tv);
        float[] c = centerOf(tv, label);

        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        for (float x = c[0] + 8f; x <= c[0] + 80f; x += 8f) {
            tv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x, c[1]));
        }
        tv.runPendingLongPressForTest();
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, c[0] + 80f, c[1]));

        assertEquals("a moved hold still summoned the keyboard",
                0, tv.keyboards);
        assertEquals(0, tv.pickers);
    }

    @Test
    public void stationaryHoldOpensKeyboard() {
        RecordingTeleValue tv = control();
        View label = findValueLabel(tv);
        float[] c = centerOf(tv, label);

        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        tv.runPendingLongPressForTest();
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, c[0], c[1]));

        assertEquals(1, tv.keyboards);
        assertEquals(0, tv.pickers);
    }

    @Test
    public void stackedZoomKeepsScrubBehaviour() {
        RecordingTeleValue tv = control();
        tv.setStacked(true);
        assertTrue("stacked mode was not retained", tv.isStacked());
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, 80, 180);

        View label = findValueLabel(tv);
        float[] c = centerOf(tv, label);
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, c[0], c[1]));
        for (float x = c[0] + 8f; x <= c[0] + 60f; x += 8f) {
            tv.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x, c[1]));
        }
        tv.dispatchTouchEvent(event(MotionEvent.ACTION_UP, c[0] + 60f, c[1]));

        assertTrue("stacked scrub did not report live values",
                !tv.live.isEmpty());
        assertEquals(tv.getValue(), tv.finished);
        assertEquals(0, tv.pickers);
        assertEquals(0, tv.keyboards);
    }

    @Test
    public void configureClampsInitialValue() {
        RecordingTeleValue tv = control();
        tv.configure(10, 20, 1, 999, "");
        assertEquals(20, tv.getValue());
        tv.configure(10, 20, 1, -999, "");
        assertEquals(10, tv.getValue());
        assertEquals(10, tv.getMin());
    }

    @Test
    public void captionShowsAndHides() {
        RecordingTeleValue tv = control();
        tv.setCaption("Size");
        android.view.View caption = null;
        android.view.ViewGroup root = tv;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View c = root.getChildAt(i);
            if (c instanceof android.widget.TextView
                    && "Size".contentEquals(
                            ((android.widget.TextView) c).getText())) {
                caption = c;
            }
        }
        assertTrue(caption != null
                && caption.getVisibility() == android.view.View.VISIBLE);
        tv.setCaption("");
        assertTrue(caption.getVisibility() == android.view.View.GONE);
        tv.setCaption(null);
        assertTrue(caption.getVisibility() == android.view.View.GONE);
    }

    @Test
    public void plusMinusStepDiscretely() {
        RecordingTeleValue tv = control();
        android.view.View plus = null;
        android.view.View minus = null;
        android.view.ViewGroup row =
                (android.view.ViewGroup) tv.getChildAt(1);
        for (int i = 0; i < row.getChildCount(); i++) {
            android.view.View c = row.getChildAt(i);
            CharSequence d = c.getContentDescription();
            if (d != null && "increase".contentEquals(d)) plus = c;
            if (d != null && "decrease".contentEquals(d)) minus = c;
        }
        assertTrue(plus != null && minus != null);
        plus.performClick();
        assertEquals(51, tv.getValue());
        assertEquals(51, tv.discrete);
        minus.performClick();
        minus.performClick();
        assertEquals(49, tv.getValue());
    }

    @Test
    public void unchangedValueDoesNotNotify() {
        RecordingTeleValue tv = control();
        tv.setValue(50, true);
        assertEquals(-1, tv.discrete);
        tv.setValue(51, true);
        assertEquals(51, tv.discrete);
    }

    @Test
    public void stackedDefaultsOff() {
        RecordingTeleValue tv = control();
        assertTrue(!tv.isStacked());
    }
}
