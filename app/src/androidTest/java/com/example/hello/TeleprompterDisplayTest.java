package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * On-device checks for the prompter rework: centring, stable wrapping, the
 * windowed render, and the inline four-way controls.
 */
@RunWith(AndroidJUnit4.class)
public class TeleprompterDisplayTest {

    private Activity launch() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    private static String manyWords(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append("word").append(i);
        }
        return sb.toString();
    }

    @Test
    public void splitsScriptIntoSegmentsThatCoverEveryWord() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);

        int total = 400;
        v.setScript(manyWords(total));
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);

        assertTrue("no segments produced", v.segmentCount() > 0);
        // Every word must survive the split, in order.
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < v.segmentCount(); i++) {
            String s = v.segmentAt(i);
            if (s == null || s.isEmpty()) continue;
            if (joined.length() > 0) joined.append(' ');
            joined.append(s);
        }
        String[] got = joined.toString().split("\\s+");
        assertEquals("segment split lost or reordered words", total, got.length);
        for (int i = 0; i < total; i++) {
            assertEquals("word " + i + " changed", "word" + i, got[i]);
        }
    }

    @Test
    public void segmentsOutOfRangeAreEmptyNotCrashing() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.setScript(manyWords(60));

        // Before the start and past the end must both return "".
        assertEquals("", v.segmentAt(-1));
        assertEquals("", v.segmentAt(v.segmentCount()));
        assertEquals("", v.segmentAt(v.segmentCount() + 500));
    }

    @Test
    public void wrappingIsStableAcrossRedraws() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.setScript(manyWords(300));

        List<String> first = snapshot(v);
        // Scroll, force redraws, and confirm the split did not shift. A
        // character-count estimate re-wrapped between passes, which is what
        // made the words appear to flicker.
        for (int i = 0; i < 40; i++) {
            v.scrollBy(17f);
            v.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            v.layout(0, 0, w, h);
        }
        assertEquals("segments changed while scrolling", first, snapshot(v));
    }

    private static List<String> snapshot(TeleprompterView v) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < v.segmentCount(); i++) out.add(v.segmentAt(i));
        return out;
    }

    @Test
    public void lastLineCanReachTheCentreLine() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.setScript(manyWords(500));

        v.setScrollOffset(Float.MAX_VALUE);
        assertTrue("nothing scrollable", v.maxScroll() > 0f);
        float lh = v.lineHeight();
        // At full scroll the last segment must land on the centre line.
        float centreLine = h / 2f + lh * 0.36f;
        float lastBaseline = h / 2f - v.maxScroll()
                + (v.segmentCount() - 1) * lh;
        assertEquals("last line did not reach the centre line",
                centreLine, lastBaseline, 2f);
        // And it must be comfortably inside the viewport, not clipped.
        assertTrue("last line is below the viewport", lastBaseline < h);
        assertTrue("last line is above the viewport", lastBaseline > 0f);
    }

    @Test
    public void textIsHorizontallyCentred() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.setScript(manyWords(50));

        android.graphics.Bitmap bmp = android.graphics.Bitmap
                .createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        v.draw(c);

        // Ink in the first 40px would mean the text hugs the left padding.
        int farLeft = inked(bmp, 0, 40);
        assertEquals("text is flush to the left edge", 0, farLeft);
        int farRight = inked(bmp, w - 40, w);
        assertEquals("text is flush to the right edge", 0, farRight);
        // And there should be ink somewhere in the middle band.
        assertTrue("nothing rendered", inked(bmp, w / 4, w - w / 4) > 0);

        // Symmetry: ink left of centre should roughly mirror ink right of it.
        // A left-aligned prompter shows a large, stable imbalance.
        int left = inked(bmp, 0, w / 2);
        int right = inked(bmp, w / 2, w);
        float skew = Math.abs(left - right) / (float) Math.max(1, left + right);
        assertTrue("ink is not balanced about the centre (skew=" + skew
                + ", left=" + left + ", right=" + right + ")", skew < 0.18f);
    }

    private static int inked(android.graphics.Bitmap b, int x0, int x1) {
        int n = 0;
        for (int x = Math.max(0, x0); x < Math.min(b.getWidth(), x1); x++) {
            for (int y = 0; y < b.getHeight(); y += 2) {
                if ((b.getPixel(x, y) & 0xFF000000) != 0) n++;
            }
        }
        return n;
    }

    @Test
    public void studioShowsInlineControlsInsteadOfTuneMenu() {
        Activity a = launch();
        // Inflate the studio layout directly: this asserts the screen's
        // contract without depending on which fragment happens to be
        // on-screen after launch (a name modal or library can be showing).
        View root = android.view.LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        assertNotNullId(root, R.id.teleSizeValue);
        assertNotNullId(root, R.id.teleSpeedValue);
        assertNotNullId(root, R.id.teleWplValue);
        assertNotNullId(root, R.id.telePauseBtn);
        assertNotNullId(root, R.id.zoomValue);
        // The separate tuning entry point is gone from the studio screen.
        assertTrue("Tune button still present",
                findByText(root, "Tune") == null);
    }

    /** Depth-first search for a visible TextView with the given label. */
    private static View findByText(View v, String needle) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null
                    && needle.equals(cs.toString().trim())) {
                return v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), needle);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void assertNotNullId(View root, int id) {
        if (root.findViewById(id) == null) {
            throw new AssertionError("missing view id " + id);
        }
    }

    @Test
    public void teleValueStepsAndReports() {
        Activity a = launch();
        TeleValue tv = new TeleValue(a);
        tv.configure(10, 20, 1, 15, "");
        final int[] seen = {-1};
        tv.setOnValueChanged(v -> seen[0] = v);
        assertEquals(15, tv.getValue());
        tv.setValue(16, true);
        assertEquals(16, tv.getValue());
        assertEquals(16, seen[0]);
        // Clamped to the configured range.
        tv.setValue(999, true);
        assertEquals(20, tv.getValue());
        tv.setValue(-999, true);
        assertEquals(10, tv.getValue());
    }

    @Test
    public void zoomMinimumFollowsTimelineLength() {
        Activity a = launch();
        WaveformView wv = new WaveformView(a);
        int minShort = Math.round(wv.minimumZoom() * 100f);
        for (int i = 0; i < 4000; i++) wv.push(0.5f);
        int minLong = Math.round(wv.minimumZoom() * 100f);
        assertEquals("history did not grow", 4000, wv.historyCount());
        assertTrue("minimum zoom did not drop for a longer timeline",
                minLong < minShort);
        // And the floor must never be above the old fixed 0.5.
        assertTrue("minimum zoom exceeds the old cap",
                wv.minimumZoom() <= 0.5f);
    }
}