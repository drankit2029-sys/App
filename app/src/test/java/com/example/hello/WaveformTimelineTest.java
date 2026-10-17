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
    public void tinyHistorySelectsWhenAtLeastTwoBars() {
        WaveformView wv = loaded(activity(), 1);
        wv.toggleSelectionAtPlayhead();
        assertFalse(wv.hasSelection());

        WaveformView wv4 = loaded(activity(), 4);
        wv4.toggleSelectionAtPlayhead();
        assertTrue(wv4.hasSelection());
    }

    @Test
    public void inPlaceCropReplaceAndUndoPreserveBarDensity() {
        WaveformView wv = loaded(activity(), 60); // 3.0s at 20 bars/sec
        wv.setLive(false);
        wv.selectRange(0.25f, 0.75f); // buckets 15..45 (30 bars)
        assertTrue(wv.hasSelection());

        wv.recordUndoPeaks();
        wv.cropToSelectionBuckets();
        assertEquals(30, wv.historyCount());
        assertFalse(wv.hasSelection());

        assertTrue(wv.popUndoPeaks());
        assertEquals(60, wv.historyCount());
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
    public void horizontalDragScrollsRedWaveformWhenSelectionOff() {
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
        // Drag right moves earlier audio to the center playhead (200 -> ~100).
        wv.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 100f, 50f));
        for (float x = 120f; x <= 300f; x += 20f) {
            wv.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x, 50f));
        }
        wv.onTouchEvent(ev(MotionEvent.ACTION_UP, 300f, 50f));
        assertTrue("scroll did not fire: " + lastFrac[0],
                lastFrac[0] >= 0.4f && lastFrac[0] <= 0.6f);
        assertEquals(lastFrac[0], wv.playheadFraction(), 0.001f);
    }

    @Test
    public void handleDragMovesSelectionStartOnRedWaveform() {
        WaveformView wv = loaded(activity(), 200);
        wv.toggleSelectionAtPlayhead();
        assertTrue(wv.hasSelection());
        float before = wv.selectionStartFraction();
        // Grab the '[' handle (near x=103 for a 400px view) and drag it right.
        wv.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 103f, 50f));
        for (float x = 120f; x <= 200f; x += 20f) {
            wv.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x, 50f));
        }
        wv.onTouchEvent(ev(MotionEvent.ACTION_UP, 200f, 50f));
        assertTrue(wv.hasSelection());
        assertTrue("start did not move right: " + wv.selectionStartFraction(),
                wv.selectionStartFraction() > before + 0.05f);
        assertEquals(1.0f, wv.selectionEndFraction(), 0.001f);
    }

    @Test
    public void redWaveformScrollsAndTruncatesToPlayhead() {
        WaveformView wv = loaded(activity(), 200);
        wv.setLive(false);
        wv.setPlayheadFraction(0.5f);
        assertEquals(0.5f, wv.playheadFraction(), 0.02f);
        wv.truncateToPlayhead();
        assertEquals(100, wv.historyCount());
        assertEquals(1.0f, wv.playheadFraction(), 0.01f);
    }
}
