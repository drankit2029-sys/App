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

import java.util.Arrays;
import java.util.List;

/**
 * Rendering math for the prompter: absolute line positions must stay
 * continuous when scrolling crosses a segment boundary, and size edits must
 * preserve the current line instead of jumping.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TeleprompterRenderTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private TeleprompterView laidOutView(int width, int height) {
        TeleprompterView v = new TeleprompterView(activity());
        v.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, width, height);
        return v;
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
    public void windowBaselineIsContinuousAcrossSegmentBoundary() {
        float centreY = 300f;
        float lineHeight = 66f;

        // Check the same absolute segment immediately before and after
        // scrolling across several line boundaries.
        for (int k = 1; k <= 5; k++) {
            float before = k * lineHeight - 0.1f;
            int fromBefore = (int) Math.floor(before / lineHeight);
            float beforeBaseline = TeleprompterView.windowStartBaseline(
                    centreY, before, fromBefore, lineHeight)
                    + (k - fromBefore) * lineHeight;

            float after = k * lineHeight + 0.1f;
            int fromAfter = (int) Math.floor(after / lineHeight);
            float afterBaseline = TeleprompterView.windowStartBaseline(
                    centreY, after, fromAfter, lineHeight)
                    + (k - fromAfter) * lineHeight;

            assertEquals("segment " + k + " jumped at its boundary",
                    beforeBaseline, afterBaseline, 0.5f);
        }
    }

    @Test
    public void windowForScrollPadsBothEnds() {
        List<String> segments = Arrays.asList("one", "two", "three");
        String[] window = TeleprompterView.windowForScroll(segments, 5, -1);
        assertEquals(5, window.length);
        assertEquals("", window[0]);
        assertEquals("one", window[1]);
        assertEquals("two", window[2]);
        assertEquals("three", window[3]);
        assertEquals("", window[4]);
    }

    @Test
    public void effectiveWordsPerLineNeverExceedsRequested() {
        TeleprompterView v = laidOutView(900, 600);
        v.setWordsPerLine(10);
        int effective = v.effectiveWordsPerLine();
        assertTrue("effective=" + effective, effective >= TeleMath.MIN_WORDS_PER_LINE);
        assertTrue("effective=" + effective,
                effective <= Math.min(10, TeleMath.MAX_WORDS_PER_LINE));
    }

    @Test
    public void changingSizePreservesCurrentLine() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(300));
        float oldLineHeight = v.lineHeight();
        assertTrue("no scrollable fixture", v.maxScroll() > 0f);

        float linePosition = 7.25f;
        v.setScrollOffset(linePosition * oldLineHeight);
        v.setTextSizePx(v.getTextSizePx() + 12f);

        float restored = v.scrollOffset() / v.lineHeight();
        assertEquals("size edit moved to line " + restored,
                linePosition, restored, 0.01f);
    }

    @Test
    public void repeatedLiveEditsKeepLinePosition() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(200));
        float linePosition = 4.5f;
        v.setScrollOffset(linePosition * v.lineHeight());

        // Simulate a scrub: several committed size changes in a row must not
        // drift the reading position.
        for (int i = 0; i < 5; i++) {
            v.setTextSizePx(v.getTextSizePx() + 2f);
        }
        float restored = v.scrollOffset() / v.lineHeight();
        assertEquals("live edits drifted to line " + restored,
                linePosition, restored, 0.05f);
    }

    @Test
    public void drawWindowAlwaysCoversViewport() {
        float lineHeight = 66f;
        int height = 227;
        // Reading centre pushed down as if chrome overlays the top.
        float centreY = height / 2f + 20f;
        for (float scroll = 0f; scroll <= 40 * lineHeight;
             scroll += lineHeight / 3f) {
            int from = TeleprompterView.drawStartForScroll(
                    centreY, scroll, lineHeight);
            float first = TeleprompterView.windowStartBaseline(
                    centreY, scroll, from, lineHeight);
            int n = TeleprompterView.drawCountForScroll(
                    first, lineHeight, height);
            assertTrue("top gap at scroll " + scroll + ": first=" + first,
                    first <= 0f && first > -2f * lineHeight);
            float last = first + (n - 1) * lineHeight;
            assertTrue("bottom gap at scroll " + scroll + ": last=" + last,
                    last >= height);
            // The segment sitting at the top edge must be drawn, not skipped.
            int topIndex = (int) Math.floor((scroll - centreY) / lineHeight);
            assertTrue("top line " + topIndex + " omitted at scroll " + scroll,
                    topIndex >= from && topIndex < from + n);
        }
    }

    @Test
    public void changingWordsPerLineKeepsLinePosition() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(300));
        float linePosition = 5.0f;
        v.setScrollOffset(linePosition * v.lineHeight());
        // Simulates one live scrub tick on the WPL control: the view must
        // re-segment immediately without losing the reading position.
        v.setWordsPerLine(TeleMath.MAX_WORDS_PER_LINE);
        assertTrue(v.segmentCount() > 0);
        assertEquals("wpl edit moved to line "
                        + v.scrollOffset() / v.lineHeight(),
                linePosition, v.scrollOffset() / v.lineHeight(), 0.05f);
        assertTrue(v.effectiveWordsPerLine() <= TeleMath.MAX_WORDS_PER_LINE);
    }

    @Test
    public void nullAndEmptyScriptsClear() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(50));
        assertTrue(v.segmentCount() > 0);
        v.setScript(null);
        assertEquals(0, v.segmentCount());
        assertEquals(0f, v.maxScroll(), 0.01f);
        v.setScript("   \n  ");
        assertEquals(0f, v.maxScroll(), 0.01f);
    }

    @Test
    public void rewindAndClampBehave() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(300));
        v.setScrollOffset(Float.MAX_VALUE);
        assertEquals(v.maxScroll(), v.scrollOffset(), 0.01f);
        v.rewind();
        assertEquals(0f, v.scrollOffset(), 0.01f);
        v.setScrollOffset(-50f);
        assertEquals(0f, v.scrollOffset(), 0.01f);
        v.scrollBy(10f);
        assertTrue(v.scrollOffset() > 0f);
    }

    @Test
    public void contentHeightMatchesSegments() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(100));
        assertEquals(v.segmentCount() * v.lineHeight(), v.contentHeight(),
                0.01f);
    }

    @Test
    public void centerShiftClampsNegative() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(100));
        // Must not throw, and must settle at the floor.
        v.setCenterShiftPx(-40f);
        v.setCenterShiftPx(30f);
        assertTrue(v.contentHeight() > 0f);
    }
}
