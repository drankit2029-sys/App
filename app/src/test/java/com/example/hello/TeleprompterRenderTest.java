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

import java.util.ArrayList;
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

    private static List<String> snapshot(TeleprompterView v) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < v.segmentCount(); i++) {
            out.add(v.segmentAt(i));
        }
        return out;
    }

    @Test
    public void windowBaselineIsContinuousAcrossSegmentBoundary() {
        float height = 600f;
        float lineHeight = 66f;

        // Check the same absolute segment immediately before and after
        // scrolling across several line boundaries.
        for (int k = 1; k <= 5; k++) {
            float before = k * lineHeight - 0.1f;
            int fromBefore = (int) Math.floor(before / lineHeight);
            float beforeBaseline = TeleprompterView.windowStartBaseline(
                    height, before, fromBefore, lineHeight)
                    + (k - fromBefore) * lineHeight;

            float after = k * lineHeight + 0.1f;
            int fromAfter = (int) Math.floor(after / lineHeight);
            float afterBaseline = TeleprompterView.windowStartBaseline(
                    height, after, fromAfter, lineHeight)
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
    public void sizePreviewDoesNotResplitScript() {
        TeleprompterView v = laidOutView(900, 600);
        v.setScript(manyWords(200));
        List<String> before = snapshot(v);
        float committed = v.getTextSizePx();

        v.previewTextSizePx(committed + 10f);
        assertEquals(committed + 10f, v.renderedTextSizePx(), 0.01f);
        assertEquals("preview re-split the script", before, snapshot(v));

        v.setTextSizePx(committed + 10f);
        assertEquals(committed + 10f, v.renderedTextSizePx(), 0.01f);
    }
}
