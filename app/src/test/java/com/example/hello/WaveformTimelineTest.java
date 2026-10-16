package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
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
 * Roadmap Phase 4 timeline API & touch gestures: playhead fractions,
 * selection brackets, handle dragging, range sweeping, and punch-in bucket
 * overwrites.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WaveformTimelineTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private static WaveformView loaded(Activity a, int peaks) {
        WaveformView wv = new WaveformView(a);
        wv.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, 400, 100);
        for (int i = 0; i < peaks; i++) wv.push(0.5f);
        return wv;
    }

    private static MotionEvent ev(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    @Test
    public void selectionTogglesAroundPlayhead() {
        WaveformView wv = loaded(activity(), 100);
        assertFalse(wv.hasSelection());
        wv.toggleSelectionAtPlayhead();
        assertTrue(wv.hasSelection());
        float start = wv.selectionStartFraction();
        float end = wv.selectionEndFraction();
        assertTrue("start=" + start, start >= 0f && start < end);
        assertTrue("end=" + end, end <= 1f);
        wv.toggleSelectionAtPlayhead();
        assertFalse(wv.hasSelection());
    }

    @Test
    public void selectionListenerFires() {
        WaveformView wv = loaded(activity(), 100);
        List<String> calls = new ArrayList<>();
        wv.setTimelineListener(new WaveformView.TimelineListener() {
            @Override
            public void onPlayheadScrubbed(int bucketIndex, float fraction) {
            }

            @Override
            public void onSelectionChanged(boolean active, float startFrac,
                                           float endFrac) {
                calls.add(active + ":" + startFrac + "-" + endFrac);
            }
        });
        wv.toggleSelectionAtPlayhead();
        assertEquals(1, calls.size());
        assertTrue(calls.get(0).startsWith("true:"));
        wv.clearSelection();
        assertEquals(2, calls.size());
        assertTrue(calls.get(1).startsWith("false:"));
    }

    @Test
    public void tinyHistoryCannotSelect() {
        WaveformView wv = loaded(activity(), 4);
        wv.toggleSelectionAtPlayhead();
        assertFalse(wv.hasSelection());
    }

    @Test
    public void playheadFractionRoundTrips() {
        WaveformView wv = loaded(activity(), 100);
        wv.setPlayheadFraction(0.5f);
        assertEquals(0.5f, wv.playheadFraction(), 0.02f);
        wv.setPlayheadFraction(2f);
        assertEquals(1f, wv.playheadFraction(), 0.001f);
    }

    @Test
    public void pushOrOverwriteUpdatesBucketInPlace() {
        WaveformView wv = loaded(activity(), 100);
        wv.pushOrOverwrite(0.9f, 0.5f, false);
        assertEquals(100, wv.historyCount());
        assertEquals(0.9f, wv.peakAtForTest(50), 0.001f);
        assertEquals(0.51f, wv.playheadFraction(), 0.02f);
        wv.pushOrOverwrite(0.7f, 0f, true);
        assertEquals(101, wv.historyCount());
    }

    @Test
    public void horizontalDragScrubsPlayheadWhenSelectionOff() {
        WaveformView wv = loaded(activity(), 200);
        final float[] lastFrac = {-1f};
        wv.setTimelineListener(new WaveformView.TimelineListener() {
            @Override
            public void onPlayheadScrubbed(int bucketIndex, float fraction) {
                lastFrac[0] = fraction;
            }

            @Override
            public void onSelectionChanged(boolean active, float s, float e) {
            }
        });
        wv.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 350f, 50f));
        for (float x = 330f; x >= 100f; x -= 20f) {
            wv.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x, 50f));
        }
        wv.onTouchEvent(ev(MotionEvent.ACTION_UP, 100f, 50f));
        assertTrue("scrub did not fire: " + lastFrac[0],
                lastFrac[0] >= 0.15f && lastFrac[0] <= 0.35f);
    }

    @Test
    public void horizontalDragSweepsSelectionWhenSelectionActive() {
        WaveformView wv = loaded(activity(), 200);
        wv.toggleSelectionAtPlayhead();
        assertTrue(wv.hasSelection());
        // Touch away from handles and drag horizontally from x=80 to x=280
        wv.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 80f, 50f));
        for (float x = 100f; x <= 280f; x += 20f) {
            wv.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x, 50f));
        }
        wv.onTouchEvent(ev(MotionEvent.ACTION_UP, 280f, 50f));
        assertTrue(wv.hasSelection());
        assertTrue("start=" + wv.selectionStartFraction(),
                wv.selectionStartFraction() > 0.10f
                        && wv.selectionStartFraction() < 0.30f);
        assertTrue("end=" + wv.selectionEndFraction(),
                wv.selectionEndFraction() > 0.60f
                        && wv.selectionEndFraction() < 0.80f);
    }
}
