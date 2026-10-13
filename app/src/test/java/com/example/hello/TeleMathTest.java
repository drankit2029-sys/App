package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The teleprompter's layout and pacing rules. These are the ones that must
 * hold: text can never overflow its column, and the fade has to be linear
 * and symmetric about the centre.
 */
public class TeleMathTest {

    // ---- words per line ----

    @Test
    public void bigTextForcesFewerWords() {
        int width = 1000;
        float small = 12f * 3f; // 36px
        float big = 48f * 3f;   // 144px
        int want = 8;
        int atSmall = TeleMath.wordsPerLine(width, small, want);
        int atBig = TeleMath.wordsPerLine(width, big, want);
        assertEquals("small text should fit the request", want, atSmall);
        assertTrue("large text must clamp: " + atBig, atBig < want);
    }

    @Test
    public void neverExceedsTheRequest() {
        float[] sizes = {36f, 60f, 90f, 120f, 168f};
        for (float s : sizes) {
            for (int want = 2; want <= 14; want++) {
                int got = TeleMath.wordsPerLine(1200, s, want);
                assertTrue("s=" + s + " want=" + want + " got=" + got,
                        got <= want);
                assertTrue(got >= TeleMath.MIN_WORDS_PER_LINE);
                assertTrue(got <= TeleMath.MAX_WORDS_PER_LINE);
            }
        }
    }

    @Test
    public void clampIsReported() {
        // Big text in a narrow panel must be visibly clamped, not silently.
        assertTrue(TeleMath.wordsPerLineWasClamped(300, 120f, 8));
        assertFalse(TeleMath.wordsPerLineWasClamped(1400, 40f, 8));
    }

    @Test
    public void degenerateSpaceFallsBackSafely() {
        // No width or no text size (view not laid out yet): return the
        // floor rather than dividing by zero. This is reported as clamped,
        // which is honest - the request genuinely could not be honoured.
        assertEquals(TeleMath.MIN_WORDS_PER_LINE,
                TeleMath.wordsPerLine(0, 40f, 6));
        assertEquals(TeleMath.MIN_WORDS_PER_LINE,
                TeleMath.wordsPerLine(800, 0f, 6));
        assertEquals(TeleMath.MIN_WORDS_PER_LINE,
                TeleMath.wordsPerLine(0, 0f, 6));
        assertTrue(TeleMath.wordsPerLineWasClamped(0, 0f, 6));
    }

    // ---- pacing ----

    @Test
    public void fasterReadingScrollsFaster() {
        float slow = TeleMath.scrollPxPerSecond(90, 5, 48f);
        float fast = TeleMath.scrollPxPerSecond(200, 5, 48f);
        assertTrue("slow=" + slow + " fast=" + fast, fast > slow);
        assertTrue(slow > 0f);
    }

    @Test
    public void biggerTextScrollsFasterPerLine() {
        float small = TeleMath.scrollPxPerSecond(120, 5, 24f);
        float large = TeleMath.scrollPxPerSecond(120, 5, 72f);
        assertTrue("small=" + small + " large=" + large, large > small);
    }

    @Test
    public void zeroSpeedScrollsNowhere() {
        assertEquals(0f, TeleMath.scrollPxPerSecond(0, 5, 48f), 0.0001f);
        assertEquals(0f, TeleMath.scrollPxPerSecond(120, 0, 48f), 0.0001f);
    }

    @Test
    public void scrollSpeedMatchesRenderedLineMotion() {
        // 120 wpm is two words/second. On two-word lines, that is exactly one
        // rendered line-height per second.
        assertEquals(72f, TeleMath.scrollPxPerSecond(120, 2, 48f), 0.001f);
        // Halving the effective words per line doubles the required line rate.
        assertEquals(144f, TeleMath.scrollPxPerSecond(120, 1, 48f), 0.001f);
    }

    @Test
    public void charsPerSecondScalesWithLineLength() {
        assertTrue(TeleMath.charsPerSecond(8, 120)
                > TeleMath.charsPerSecond(4, 120));
    }

    // ---- script metrics ----

    @Test
    public void wordCounting() {
        assertEquals(0, TeleMath.wordCount(null));
        assertEquals(0, TeleMath.wordCount("   \n\n  "));
        assertEquals(3, TeleMath.wordCount("one two three"));
        assertEquals(3, TeleMath.wordCount("  one   two \n\n three  "));
    }

    @Test
    public void estimateGrowsWithLengthAndFallsWithSpeed() {
        String script = "a b c d e f g h i j";
        int slow = TeleMath.estimatedSeconds(script, 60);
        int fast = TeleMath.estimatedSeconds(script, 300);
        assertTrue("slow=" + slow + " fast=" + fast, slow > fast);
        assertEquals(10, TeleMath.wordCount(script));
        assertEquals(0, TeleMath.estimatedSeconds(script, 0));
    }

    // ---- fade ----

    @Test
    public void fadeIsStrongestAtTheCentre() {
        float h = 400f;
        float fade = h * 0.46f;
        float centre = TeleMath.fadeAlpha(h / 2f, h, fade);
        float upper = TeleMath.fadeAlpha(h * 0.25f, h, fade);
        float lower = TeleMath.fadeAlpha(h * 0.75f, h, fade);
        assertEquals(1f, centre, 0.0001f);
        assertTrue(centre > upper);
        assertTrue(centre > lower);
    }

    @Test
    public void fadeIsSymmetricAboutTheCentre() {
        float h = 600f;
        float fade = h * 0.46f;
        for (float y = 10f; y < h; y += 17f) {
            float a = TeleMath.fadeAlpha(y, h, fade);
            float b = TeleMath.fadeAlpha(h - y, h, fade);
            assertEquals("asymmetry at y=" + y, a, b, 0.0001f);
        }
    }

    @Test
    public void fadeDecreasesMonotonicallyOutward() {
        // Linear, not stepped: each step outward must not increase.
        float h = 500f;
        float fade = h * 0.46f;
        float prev = 2f;
        for (float y = h / 2f; y >= 0f; y -= 3f) {
            float a = TeleMath.fadeAlpha(y, h, fade);
            assertTrue("rise at y=" + y, a <= prev + 0.0001f);
            prev = a;
        }
    }

    @Test
    public void fadeEndsFullyTransparentAndNeverInverts() {
        float h = 300f;
        assertEquals(0f, TeleMath.fadeAlpha(0f, h, h * 0.46f), 0.0001f);
        assertEquals(0f, TeleMath.fadeAlpha(h, h, h * 0.46f), 0.0001f);
        assertEquals(1f, TeleMath.fadeAlpha(150f, h, 0f), 0.0001f);
        assertEquals(1f, TeleMath.fadeAlpha(150f, 0f, 10f), 0.0001f);
    }
}