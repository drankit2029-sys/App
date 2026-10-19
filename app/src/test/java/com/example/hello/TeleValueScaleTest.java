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
 * The high-resolution (basis-point) scale for the waveform zoom rail must
 * format sub-percent values with decimals while every integer-scale control
 * keeps behaving exactly as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TeleValueScaleTest {

    private TeleValue control() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        TeleValue tv = new TeleValue(a);
        a.setContentView(tv);
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, 300, 80);
        return tv;
    }

    private static String labelText(TeleValue tv) {
        return findValueLabel(tv).toString();
    }

    private static String findValueLabel(View root) {
        CharSequence description = root.getContentDescription();
        if (root instanceof android.widget.TextView
                && description != null
                && "value".contentEquals(description)) {
            return ((android.widget.TextView) root).getText().toString();
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = findValueLabel(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findButton(TeleValue tv, String desc) {
        android.view.ViewGroup row = (android.view.ViewGroup) tv.getChildAt(1);
        for (int i = 0; i < row.getChildCount(); i++) {
            android.view.View c = row.getChildAt(i);
            CharSequence d = c.getContentDescription();
            if (d != null && desc.contentEquals(d)) return c;
        }
        return null;
    }

    @Test
    public void decimalScaleFormatsZoomLabels() {
        TeleValue tv = control();
        tv.configure(50, 40000, 10, 5000, "%");
        tv.setValueScale(100);
        assertEquals("50%", labelText(tv));
        tv.setValue(10000, false);
        assertEquals("100%", labelText(tv));
        tv.setValue(250, false);
        assertEquals("2.5%", labelText(tv));
        tv.setValue(188, false);
        assertEquals("1.88%", labelText(tv));
        tv.setValue(92, false);
        assertEquals("0.92%", labelText(tv));
        tv.setValue(100, false);
        assertEquals("1%", labelText(tv));
    }

    @Test
    public void stepRuleDrivesPlusMinus() {
        TeleValue tv = control();
        tv.configure(0, 40000, 5, 100, "");
        tv.setStepRule((cur, dir) -> 10);
        findButton(tv, "increase").performClick();
        assertEquals(110, tv.getValue());
        findButton(tv, "decrease").performClick();
        findButton(tv, "decrease").performClick();
        assertEquals(90, tv.getValue());
    }

    @Test
    public void steppedLabelsHonorStepRule() {
        TeleValue tv = control();
        tv.configure(0, 100, 1, 0, "");
        tv.setStepRule((cur, dir) -> 25);
        String[] labels = tv.steppedLabelsForTest();
        assertEquals(5, labels.length);
        assertEquals("0", labels[0]);
        assertEquals("25", labels[1]);
        assertEquals("100", labels[4]);
    }

    @Test
    public void defaultScaleKeepsIntegerBehavior() {
        TeleValue tv = control();
        tv.configure(40, 400, 5, 120, "");
        assertEquals("120", labelText(tv));
        assertEquals(1, tv.getValueScale());
        findButton(tv, "increase").performClick();
        assertEquals(125, tv.getValue());
        assertEquals("125", labelText(tv));
        String[] labels = tv.steppedLabelsForTest();
        assertTrue(labels.length > 10);
        assertEquals("40", labels[0]);
        assertEquals("45", labels[1]);
    }

    @Test
    public void negativeScaledValuesFormatAndStepCorrectly() {
        // BUG-08 regression: negative scaled values <= -10*scale and negative
        // stepped wheel values must include min and format symmetrically.
        TeleValue tv = control();
        tv.setValueScale(100);
        tv.configure(-2400, 2400, 600, -1250, " dB");
        assertEquals("-12.5 dB", labelText(tv));
        tv.setValue(-125, false);
        assertEquals("-1.25 dB", labelText(tv));

        String[] labels = tv.steppedLabelsForTest();
        assertTrue(labels.length >= 9);
        assertEquals("-24 dB", labels[0]);
        assertEquals("24 dB", labels[labels.length - 1]);
    }

    @Test
    public void unalignedMaxIsIncludedInSteppedPickerLabels() {
        TeleValue tv = control();
        tv.configure(0, 12, 5, 0, "");
        String[] labels = tv.steppedLabelsForTest();
        assertEquals(4, labels.length);
        assertEquals("0", labels[0]);
        assertEquals("5", labels[1]);
        assertEquals("10", labels[2]);
        assertEquals("12", labels[3]);
    }
}
