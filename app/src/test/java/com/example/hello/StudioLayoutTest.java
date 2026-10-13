package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The studio must keep its recording strip above the floating record button
 * while keeping the preset/teleprompter pills equal, compact, and aligned.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StudioLayoutTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void recordingStripStaysAboveFloatingRecordControls() {
        Activity a = activity();
        DisplayMetrics metrics = a.getResources().getDisplayMetrics();

        View activityRoot = LayoutInflater.from(a)
                .inflate(R.layout.activity_main, null, false);
        View fab = activityRoot.findViewById(R.id.fabWrap);
        assertTrue("record button missing", fab != null);
        ViewGroup recordBar = (ViewGroup) fab.getParent();
        int fabHeightPx = fab.getLayoutParams().height;
        int barMarginPx = ((ViewGroup.MarginLayoutParams) recordBar
                .getLayoutParams()).bottomMargin;
        int clearancePx = Math.round(8f * metrics.density);

        View studioRoot = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        assertTrue("studio root lost its record-button clearance (padding="
                        + studioRoot.getPaddingBottom() + ", need>="
                        + (fabHeightPx + barMarginPx + clearancePx) + ")",
                studioRoot.getPaddingBottom()
                        >= fabHeightPx + barMarginPx + clearancePx);
    }

    @Test
    public void recordingStripUsesRestoredMeterAndTimerSizes() {
        Activity a = activity();
        DisplayMetrics metrics = a.getResources().getDisplayMetrics();
        View root = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);

        View meter = root.findViewById(R.id.dbMeter);
        assertEquals("dB meter height changed",
                Math.round(168f * metrics.density),
                meter.getLayoutParams().height);
        TextView timer = root.findViewById(R.id.timerText);
        assertEquals("timer size changed",
                38f * metrics.scaledDensity, timer.getTextSize(), 0.5f);
    }

    @Test
    public void presetAndTeleprompterPillsAreEqualCompactAndAligned() {
        Activity a = activity();
        DisplayMetrics metrics = a.getResources().getDisplayMetrics();
        View root = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        LinearLayout preset = root.findViewById(R.id.settingsRow);
        LinearLayout toggle = root.findViewById(R.id.teleToggle);

        int pill = Math.round(40f * metrics.density);
        assertEquals("preset pill height changed", pill,
                preset.getLayoutParams().height);
        assertEquals("teleprompter pill height changed", pill,
                toggle.getLayoutParams().height);
        assertEquals("pills are not aligned", Gravity.CENTER_VERTICAL,
                preset.getGravity() & Gravity.VERTICAL_GRAVITY_MASK);
        assertEquals("pills are not aligned", Gravity.CENTER_VERTICAL,
                toggle.getGravity() & Gravity.VERTICAL_GRAVITY_MASK);
        assertEquals("pills have different widths",
                preset.getLayoutParams().width, toggle.getLayoutParams().width);
    }

    @Test
    public void zoomControlStacksVertically() {
        Activity a = activity();
        View root = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        TeleValue zoom = root.findViewById(R.id.zoomValue);
        assertTrue("zoom control missing", zoom != null);

        zoom.setStacked(true);
        assertTrue("zoom control is not stacked", zoom.isStacked());
        ViewGroup row = (ViewGroup) zoom.getChildAt(1);
        assertEquals("zoom controls are not vertical",
                LinearLayout.VERTICAL, ((LinearLayout) row).getOrientation());
        assertEquals("stacked row is still locked to the horizontal height",
                ViewGroup.LayoutParams.WRAP_CONTENT,
                row.getLayoutParams().height);
        assertTrue("zoom control is too wide for the waveform rail",
                zoom.getLayoutParams().width
                        <= Math.round(48f * a.getResources()
                                .getDisplayMetrics().density));
    }

    @Test
    public void prompterChromeOverlaysScript() {
        Activity a = activity();
        View root = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        FrameLayout panel = root.findViewById(R.id.telePanel);
        assertTrue("prompter panel missing", panel != null);
        assertTrue("script is not the bottom overlay layer",
                panel.getChildAt(0) instanceof TeleprompterView);
        assertTrue("controls do not overlay the script",
                panel.getChildCount() >= 2);
    }
}
