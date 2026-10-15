package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
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
 * The waveform's vertical-drag zoom must step live as the finger travels:
 * firing only on release made short or interrupted drags feel dead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WaveformZoomTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private WaveformView laidOut() {
        WaveformView wv = new WaveformView(activity());
        wv.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, 300, 120);
        return wv;
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    @Test
    public void dragStepsLivePerThreshold() {
        WaveformView wv = new WaveformView(activity());
        wv.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, 300, 120);
        final List<Float> dirs = new ArrayList<>();
        wv.setOnZoomGesture((dir, dy) -> dirs.add(dir));

        wv.onTouchEvent(event(MotionEvent.ACTION_DOWN, 150f, 60f));
        // A long upward drag must produce several zoom-in steps on the way,
        // not a single event on release. Travel far past the view bounds so
        // the assertion holds at any screen density.
        for (float y = 55f; y >= -245f; y -= 5f) {
            wv.onTouchEvent(event(MotionEvent.ACTION_MOVE, 150f, y));
        }
        assertTrue("no live zoom steps during the drag (dirs=" + dirs + ")",
                dirs.size() >= 2);
        for (float d : dirs) {
            assertEquals("wrong direction", 1f, d, 0.001f);
        }
        int duringDrag = dirs.size();
        wv.onTouchEvent(event(MotionEvent.ACTION_UP, 150f, -245f));
        assertEquals("release must not add another step",
                duringDrag, dirs.size());
    }

    @Test
    public void shortDragDoesNothing() {
        WaveformView wv = new WaveformView(activity());
        wv.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        wv.layout(0, 0, 300, 120);
        final int[] calls = {0};
        wv.setOnZoomGesture((dir, dy) -> calls[0]++);

        wv.onTouchEvent(event(MotionEvent.ACTION_DOWN, 150f, 60f));
        wv.onTouchEvent(event(MotionEvent.ACTION_MOVE, 150f, 58f));
        wv.onTouchEvent(event(MotionEvent.ACTION_UP, 150f, 58f));
        assertEquals("a 2px nudge must not zoom", 0, calls[0]);
    }

    @Test
    public void emptyHistoryFloorIsHalf() {
        assertEquals(0.5f, laidOut().minimumZoom(), 0.001f);
    }

    @Test
    public void setZoomClampsBothEnds() {
        WaveformView wv = laidOut();
        wv.setZoom(99f);
        assertEquals(4f, wv.getZoom(), 0.001f);
        wv.setZoom(-5f);
        assertEquals(wv.minimumZoom(), wv.getZoom(), 0.001f);
    }

    @Test
    public void clearResetsHistory() {
        WaveformView wv = laidOut();
        for (int i = 0; i < 300; i++) wv.push(0.5f);
        assertEquals(300, wv.historyCount());
        wv.clear();
        assertEquals(0, wv.historyCount());
        assertEquals(0.5f, wv.minimumZoom(), 0.001f);
    }

    @Test
    public void historyCapsAtMax() {
        WaveformView wv = laidOut();
        for (int i = 0; i < 9000; i++) wv.push(0.5f);
        assertTrue("count=" + wv.historyCount(),
                wv.historyCount() <= 8192);
        assertTrue(wv.minimumZoom() < 0.5f);
        assertTrue(wv.minimumZoom() >= 0.02f);
    }
}
