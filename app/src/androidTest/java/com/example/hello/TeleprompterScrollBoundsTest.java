package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Regression cover for the prompter appearing "dead": no scrolling, and the
 * script stopping after the first screenful.
 */
@RunWith(AndroidJUnit4.class)
public class TeleprompterScrollBoundsTest {

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

    /**
     * The exact ordering the real screen uses: script is assigned before the
     * view has a size. The scroll bounds were computed at height 0 and then
     * never recomputed, leaving maxScroll at 0.
     */
    @Test
    public void scriptSetBeforeLayoutStillGetsScrollBounds() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);

        // No measure/layout yet: width and height are both 0.
        v.setScript(manyWords(400));
        assertEquals("precondition: unlaid view has no bounds",
                0f, v.maxScroll(), 0.01f);

        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);

        assertTrue("maxScroll stayed 0 after layout, so the script cannot "
                + "be scrolled past the first screenful", v.maxScroll() > 0f);

        // And it must actually be able to reach the end.
        v.setScrollOffset(Float.MAX_VALUE);
        assertEquals("did not reach the end", v.maxScroll(),
                v.scrollOffset(), 0.01f);
        assertTrue("end of script is at the top of the view",
                v.scrollOffset() > 0f);
    }

    @Test
    public void resizingKeepsScrollBoundsValid() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        v.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 900, 600);
        v.setScript(manyWords(400));
        float before = v.maxScroll();
        assertTrue("no scroll range when laid out", before > 0f);

        // Panel height does not change where the tail has to stop, so the
        // range is stable; what matters is that it stays reachable and that a
        // position from the old height is re-clamped into the new bounds.
        v.scrollBy(100000f);
        v.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 900, 300);
        assertTrue("scroll range vanished after resize", v.maxScroll() > 0f);
        v.scrollBy(100000f);
        assertTrue("offset exceeded bounds after resize",
                v.scrollOffset() <= v.maxScroll() + 0.5f);
        assertTrue("offset left the script", v.scrollOffset() > 0f);
    }

    @Test
    public void draggingTheScriptScrollsIt() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.setScript(manyWords(400));

        final float before = v.scrollOffset();
        // Drag upward: the text follows the finger, revealing later lines.
        v.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 300f, 300f));
        for (float y = 290f; y >= 110f; y -= 20f) {
            v.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 450f, y));
        }
        v.onTouchEvent(motion(MotionEvent.ACTION_UP, 450f, 110f));
        assertTrue("a drag did not scroll the script (" + before + " -> "
                + v.scrollOffset() + ")", v.scrollOffset() > before + 1f);
    }

    @Test
    public void draggingBackReturnsToTheTop() {
        Activity a = launch();
        TeleprompterView v = new TeleprompterView(a);
        int w = 900, h = 600;
        v.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        v.setScript(manyWords(400));

        v.onTouchEvent(motion(MotionEvent.ACTION_DOWN, 450f, 150f));
        for (float y = 450f; y <= 550f; y += 20f) {
            v.onTouchEvent(motion(MotionEvent.ACTION_MOVE, 450f, y));
        }
        v.onTouchEvent(motion(MotionEvent.ACTION_UP, 450f, 550f));
        assertEquals("dragging back did not return to the top",
                0f, v.scrollOffset(), 0.5f);
    }

    /**
     * The prompter must have real height on the actual studio screen. It once
     * collapsed to 0px because the fixed content below overran the layout, so
     * nothing was drawn and nothing could scroll.
     */
    @Test
    public void prompterHasUsableHeightOnTheRealScreen() {
        Activity a = launch();
        View fab = a.findViewById(R.id.fabWrap);
        if (fab != null) {
            a.runOnUiThread(fab::performClick);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        View root = a.getWindow().getDecorView();
        TeleprompterView tele = (TeleprompterView) root.findViewById(R.id.teleView);
        if (tele == null) {
            View start = findByText(root, "Start");
            if (start != null) {
                a.runOnUiThread(start::performClick);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            tele = (TeleprompterView) a.findViewById(R.id.teleView);
        }
        assertTrue("prompter not found", tele != null);
        a.runOnUiThread(() -> tele.setScript(manyWords(400)));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);

        assertTrue("prompter collapsed to " + tele.getHeight() + "px",
                tele.getHeight() >= 120);
        assertTrue("prompter cannot scroll (max=" + tele.maxScroll() + ")",
                tele.maxScroll() > 0f);
        // At the default text size it must show more than a line or two.
        int lines = tele.visibleLineCount();
        assertTrue("only " + lines + " line(s) fit", lines >= 4);
    }

    private static View findByText(View v, String needle) {
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null && needle.equals(cs.toString().trim())) return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), needle);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private MotionEvent motion(int action, float x, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    /**
     * The inline stepper must respond to a horizontal drag even though its
     * children are clickable and consume the initial touch.
     */
    @Test
    public void inlineStepperScrubsOnHorizontalDrag() {
        Activity a = launch();
        TeleValue tv = new TeleValue(a);
        tv.configure(10, 100, 1, 50, "");
        final int[] seen = {-1};
        tv.setOnValueChanged(v -> seen[0] = v);

        int w = 200, h = 40;
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, w, h);

        long t = android.os.SystemClock.uptimeMillis();
        tv.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 20f));
        for (float x = 104f; x <= 160f; x += 6f) {
            tv.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, x, 21f));
        }
        tv.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, 160f, 21f));

        assertTrue("horizontal drag did not change the value (still "
                + tv.getValue() + ")", tv.getValue() > 50);
        assertEquals("listener was not notified", tv.getValue(), seen[0]);
    }

    @Test
    public void inlineStepperIgnoresVerticalDrag() {
        Activity a = launch();
        TeleValue tv = new TeleValue(a);
        tv.configure(10, 100, 1, 50, "");
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, 200, 40);

        tv.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 100f, 10f));
        for (float y = 12f; y <= 38f; y += 6f) {
            tv.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, 101f, y));
        }
        tv.dispatchTouchEvent(motion(MotionEvent.ACTION_CANCEL, 101f, 38f));
        assertEquals("a vertical drag must not change the value",
                50, tv.getValue());
    }
}