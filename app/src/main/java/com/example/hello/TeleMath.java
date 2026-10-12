package com.example.hello;

/**
 * Pure layout/rate maths for the teleprompter. No Android imports, so the
 * rules that must hold — mainly "the text can never overflow its column" —
 * are unit-testable off-device.
 */
public final class TeleMath {

    private TeleMath() {}

    /**
     * Average glyph width as a fraction of em, measured across the UI and
     * Roboto at the sizes we allow. Proportional-ish faces run wider than
     * 0.5; 0.52 leaves a little safety margin.
     */
    private static final float AVG_CHAR_EM = 0.52f;
    /** Average word length including the space, in characters. */
    private static final float AVG_WORD_CHARS = 5.8f;

    public static final int MIN_WORDS_PER_LINE = 2;
    public static final int MAX_WORDS_PER_LINE = 14;
    public static final int MIN_TEXT_SP = 12;
    public static final int MAX_TEXT_SP = 56;

    /**
     * How many words actually fit on one line.
     *
     * <p>This is the guard that keeps the script readable: the user asks for
     * N words per line, but big text in a narrow column physically cannot
     * hold them, so the request is reduced until it fits rather than
     * overflowing the panel. It never returns more than {@code requested}.
     */
    public static int wordsPerLine(int availableWidthPx, float textSizePx,
                                   int requested) {
        if (availableWidthPx <= 0 || textSizePx <= 0f) {
            return MIN_WORDS_PER_LINE;
        }
        float charWidthPx = textSizePx * AVG_CHAR_EM;
        if (charWidthPx < 1f) charWidthPx = 1f;
        int charsPerLine = (int) (availableWidthPx / charWidthPx);
        int fit = (int) (charsPerLine / AVG_WORD_CHARS);
        int want = Math.max(MIN_WORDS_PER_LINE, requested);
        int out = Math.min(want, fit);
        return Math.max(MIN_WORDS_PER_LINE, Math.min(MAX_WORDS_PER_LINE, out));
    }

    /**
     * True when the requested words-per-line had to be reduced, so the UI can
     * explain itself instead of silently ignoring the user's choice.
     */
    public static boolean wordsPerLineWasClamped(int availableWidthPx,
                                                 float textSizePx,
                                                 int requested) {
        return wordsPerLine(availableWidthPx, textSizePx, requested) < requested;
    }

    /**
     * Chars-per-second needed to read {@code wordsPerLine} words at
     * {@code wpm} words per minute. Drives the auto-scroll rate.
     */
    public static float charsPerSecond(int wordsPerLine, int wpm) {
        return (wordsPerLine * AVG_WORD_CHARS) * (wpm / 60f);
    }

    /**
     * Auto-scroll speed in pixels per second for a given reading rate.
     *
     * @param wpm            target reading speed, words per minute
     * @param wordsPerLine   effective (possibly clamped) words per line
     * @param textSizePx     rendered text size, which sets line height
     */
    public static float scrollPxPerSecond(int wpm, int wordsPerLine,
                                          float textSizePx) {
        if (wpm <= 0 || wordsPerLine <= 0 || textSizePx <= 0f) return 0f;
        float lineHeightPx = textSizePx * 1.55f;
        float linesPerSecond = charsPerSecond(wordsPerLine, wpm)
                / (wordsPerLine * AVG_WORD_CHARS);
        return linesPerSecond * lineHeightPx;
    }

    /**
     * Word count of the script, ignoring blank runs. Shown in the editor and
     * used for the "will this fit the take?" estimate.
     */
    public static int wordCount(String text) {
        if (text == null) return 0;
        int n = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean sep = Character.isWhitespace(c);
            if (sep) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                n++;
            }
        }
        return n;
    }

    /**
     * Seconds needed to read the whole script at {@code wpm}, so the studio
     * can warn before the performer runs out of tape.
     */
    public static int estimatedSeconds(String text, int wpm) {
        if (wpm <= 0) return 0;
        return Math.round(wordCount(text) * 60f / wpm);
    }

    /**
     * Opacity (0..1) for a vertical position in a panel that is fully opaque
     * at its centre and fades to nothing at the top and bottom edges.
     *
     * <p>Linear, not stepped, and symmetric — the performer's eye rests on
     * the middle line and everything above/below it recedes.
     *
     * @param y    pixel position
     * @param height panel height
     * @param fadePx height of the fade at each end
     */
    public static float fadeAlpha(float y, float height, float fadePx) {
        if (height <= 0f) return 1f;
        float half = height / 2f;
        if (fadePx <= 0f) fadePx = half;
        float fade = Math.min(fadePx, half);
        float d;
        if (y <= half) {
            d = half - y;
        } else {
            d = y - half;
        }
        if (d <= 0f) return 1f;
        float t = 1f - (d / fade);
        if (t < 0f) return 0f;
        if (t > 1f) return 1f;
        return t;
    }
}