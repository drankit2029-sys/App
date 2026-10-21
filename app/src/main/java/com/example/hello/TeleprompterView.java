package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Teleprompter surface.
 *
 * <p>Renders the script itself rather than an EditText so it can wrap strictly
 * to what actually fits, centre every line, and fade the copy out linearly
 * towards the top and bottom so the performer's eye is pulled to the opaque
 * centre line.
 *
 * <p>The script is split once into <b>segments</b> — one segment per display
 * line. Rendering then draws only the {@link #visibleLineCount()} segments that
 * fall inside the viewport, padded with empty segments at both ends, so the
 * last line of the script still reaches the centre line and never scrolls off
 * past the end.
 */
public class TeleprompterView extends View {

    /** Line height as a fraction of the rendered text size. */
    private static final float LINE_HEIGHT_EM = 1.5f;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** One entry per display line. Never null; blank lines are "". */
    private final List<String> segments = new ArrayList<>();

    private String script = "";
    private float textSizePx = 44f;
    private int wordsPerLine = TeleMath.MIN_WORDS_PER_LINE;
    private int textColor = 0xFFF2F4F7;
    private boolean mirror;

    private float fadePx = 0f;

    /**
     * Downward shift of the reading centre, in pixels. The control chrome
     * overlays the top of the panel, so the brightest line must sit at the
     * middle of the *visible* area — not the middle of the view.
     */
    private float centerShiftPx = 0f;

    private float scrollOffset = 0f;
    private float maxScroll = 0f;

    /** Cache key for the wrap, so scrolling never re-wraps the script. */
    private int wrapWidth = -1;
    private int wrapHeight = -1;
    private float wrapSizePx = -1f;
    private int wrapWpl = -1;

    public TeleprompterView(Context context) {
        super(context);
        init();
    }

    public TeleprompterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);
        setWillNotDraw(false);
        // Without this the view never receives touch events for dragging.
        setClickable(true);
        setFocusable(true);
    }

    public void setTextColor(int color) {
        textColor = color;
        invalidate();
    }

    public void setMirror(boolean m) {
        mirror = m;
        invalidate();
    }

    public void setScript(String text) {
        script = text == null ? "" : text;
        // A new script always starts at the top; preserving an old fractional
        // line across unrelated content would show an arbitrary position.
        scrollOffset = 0f;
        wrapWidth = -1; // force a re-split
        rewrap();
        invalidate();
    }

    public String getScript() {
        return script;
    }

    public void setTextSizePx(float px) {
        if (px > 0f && Math.abs(px - textSizePx) > 0.5f) {
            float linePosition = linePosition();
            textSizePx = px;
            rewrap();
            restoreLinePosition(linePosition);
            invalidate();
        }
    }

    public float getTextSizePx() {
        return textSizePx;
    }

    /** Size actually being drawn. Kept while live scrubbing commits go through
     * {@link #setTextSizePx(float)} on every move. */
    public float renderedTextSizePx() {
        return textSizePx;
    }

    /**
     * Shifts the reading centre down so the focused line lands in the middle
     * of the visible area when chrome overlays the top of the panel.
     */
    public void setCenterShiftPx(float px) {
        float clamped = Math.max(0f, px);
        if (Math.abs(clamped - centerShiftPx) > 0.5f) {
            centerShiftPx = clamped;
            invalidate();
        }
    }

    public void setWordsPerLine(int wpl) {
        int w = Math.max(TeleMath.MIN_WORDS_PER_LINE, wpl);
        if (w != wordsPerLine) {
            float linePosition = linePosition();
            wordsPerLine = w;
            rewrap();
            restoreLinePosition(linePosition);
            invalidate();
        }
    }

    public int getWordsPerLine() {
        return wordsPerLine;
    }

    /**
     * Words actually shown on a wrapped line. The requested count is clamped
     * to the measured width during {@link #rewrap()}, so callers must use this
     * value—not the requested value—when converting reading speed into pixels.
     */
    public int effectiveWordsPerLine() {
        int width = getWidth();
        if (width <= 0) {
            return Math.max(TeleMath.MIN_WORDS_PER_LINE,
                    Math.min(TeleMath.MAX_WORDS_PER_LINE, wordsPerLine));
        }
        float pad = getPaddingLeft() + getPaddingRight();
        return TeleMath.wordsPerLine(Math.round(Math.max(1f, width - pad)),
                textSizePx, wordsPerLine);
    }

    public float lineHeight() {
        return renderedTextSizePx() * LINE_HEIGHT_EM;
    }

    /** Current scroll position measured in lines, independent of text size. */
    private float linePosition() {
        float lh = lineHeight();
        return lh > 0f ? scrollOffset / lh : 0f;
    }

    private void restoreLinePosition(float linePosition) {
        setScrollOffset(linePosition * lineHeight());
    }

    /** How many display lines fit in the viewport right now. */
    public int visibleLineCount() {
        int h = getHeight();
        if (h <= 0) return 0;
        return (int) Math.ceil(h / lineHeight());
    }

    /** Number of segments the script was split into. */
    public int segmentCount() {
        return segments.size();
    }

    public String segmentAt(int i) {
        return (i < 0 || i >= segments.size()) ? "" : segments.get(i);
    }

    public float contentHeight() {
        return Math.max(0, segments.size()) * lineHeight();
    }

    public float maxScroll() {
        return maxScroll;
    }

    public float scrollOffset() {
        return scrollOffset;
    }

    public void setScrollOffset(float px) {
        float clamped = Math.max(0f, Math.min(maxScroll, px));
        if (Math.abs(clamped - scrollOffset) > 0.0005f) {
            scrollOffset = clamped;
            invalidate();
        }
    }

    public void scrollBy(float dy) {
        setScrollOffset(scrollOffset + dy);
    }

    public void rewind() {
        setScrollOffset(0f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fadePx = h * 0.44f;
        // Height feeds the scroll bounds, so the cached split is only reusable
        // when the height is unchanged too.
        rewrap();
    }

    /**
     * Splits the script into display-line segments.
     *
     * <p>Widths are measured with {@link Paint#measureText} rather than
     * estimated from a character count. That matters: an estimate makes the
     * same words wrap differently between layout passes, which is what makes
     * the prompter look like it is flickering when you scroll.
     */
    private void rewrap() {
        int width = getWidth();
        if (width <= 0 || script.trim().isEmpty()) {
            if (segments.size() != 0 || !script.trim().isEmpty()) {
                segments.clear();
            }
            wrapWidth = width;
            wrapHeight = getHeight();
            wrapSizePx = textSizePx;
            wrapWpl = wordsPerLine;
            recomputeMaxScroll();
            return;
        }
        // Nothing changed — keep the existing split so scrolling is stable.
        // The bounds are still recomputed below because they depend on height,
        // which is not part of the split itself.
        if (width == wrapWidth && getHeight() == wrapHeight
                && textSizePx == wrapSizePx && wordsPerLine == wrapWpl
                && !segments.isEmpty()) {
            recomputeMaxScroll();
            return;
        }

        textPaint.setTextSize(textSizePx);
        float padL = getPaddingLeft();
        float padR = getPaddingRight();
        float avail = Math.max(1f, width - padL - padR);

        int wpl = TeleMath.wordsPerLine(Math.round(avail), textSizePx,
                wordsPerLine);

        segments.clear();
        String space = " ";
        float spaceW = textPaint.measureText(space);
        for (String paragraph : script.split("\\n", -1)) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                segments.add("");
                continue;
            }
            String[] words = trimmed.split("\\s+");
            int i = 0;
            while (i < words.length) {
                StringBuilder line = new StringBuilder(words[i]);
                float lineW = textPaint.measureText(words[i]);
                int take = 1;
                while (i + take < words.length && take < wpl) {
                    float nextW = lineW + spaceW
                            + textPaint.measureText(words[i + take]);
                    if (nextW > avail) break;
                    line.append(space).append(words[i + take]);
                    lineW = nextW;
                    take++;
                }
                segments.add(line.toString());
                i += take;
            }
        }

        wrapWidth = width;
        wrapHeight = getHeight();
        wrapSizePx = textSizePx;
        wrapWpl = wordsPerLine;
        recomputeMaxScroll();
    }

    /**
     * Scroll bounds. The last segment must be able to reach the centre line,
     * otherwise the tail of the script scrolls past the bottom and the panel
     * goes blank.
     *
     * <p>First baseline is {@code h/2 - scrollOffset} and segment k sits at
     * {@code h/2 - scrollOffset + k*lh}. Requiring the final segment
     * (k = n-1) to land on the centre line gives
     * {@code scrollOffset = (n-1)*lh - centreOffset}.
     */
    private void recomputeMaxScroll() {
        int h = getHeight();
        if (h <= 0 || segments.isEmpty()) {
            maxScroll = 0f;
            return;
        }
        float lh = lineHeight();
        float centreOffset = lh * 0.36f;
        float total = contentHeight();
        maxScroll = Math.max(0f, total - lh - centreOffset);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    /**
     * Baseline for the beginning of the visible window.
     *
     * <p>The absolute baseline of segment {@code absoluteIndex} is always
     * {@code centreY - scrollOffset + absoluteIndex * lineHeight}. When
     * only the visible window is drawn, its first segment is therefore not at
     * the nominal first baseline: the window start must retain
     * {@code firstSegment * lineHeight}. Omitting that term is what used to
     * make every line snap upward by one full line each time scrolling crossed
     * a line boundary.
     */
    static float windowStartBaseline(float centreY, float scrollOffset,
                                     int firstSegment, float lineHeight) {
        return centreY - scrollOffset + firstSegment * lineHeight;
    }

    /**
     * First segment to draw for a scroll position: one line above the top
     * edge, so the uppermost line slides smoothly out of the panel instead
     * of popping out of existence while still well inside it.
     */
    static int drawStartForScroll(float centreY, float scrollOffset,
                                  float lineHeight) {
        return (int) Math.floor((scrollOffset - centreY) / lineHeight) - 1;
    }

    /**
     * How many segments to draw starting at {@code drawFrom} (whose baseline
     * is {@code drawBaseline}) so the window reaches past the bottom edge
     * with one line of margin.
     */
    static int drawCountForScroll(float drawBaseline, float lineHeight,
                                  int viewHeight) {
        int n = (int) Math.ceil(
                (viewHeight + lineHeight - drawBaseline) / lineHeight) + 1;
        return Math.max(0, n);
    }

    /**
     * Returns exactly {@code visibleLines} segments starting at
     * {@code firstSegment}, substituting {@code ""} for any index outside the
     * script.
     */
    static String[] windowForScroll(List<String> segments, int visibleLines,
                                    int firstSegment) {
        if (visibleLines <= 0) return new String[0];
        String[] out = new String[visibleLines];
        for (int i = 0; i < visibleLines; i++) {
            int idx = firstSegment + i;
            // Empty segments at both ends instead of crashing.
            out[i] = (idx < 0 || idx >= segments.size()) ? "" : segments.get(idx);
        }
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float lh = lineHeight();
        int count = visibleLineCount();

        // Reading centre: the middle of the visible area, pushed down when
        // chrome overlays the top of the panel.
        float centreY = h / 2f + centerShiftPx;
        int drawFrom;
        float drawBaseline;
        int drawCount;
        if (segments.isEmpty()) {
            return;
        }
        if (totalLinesFits(count)) {
            drawFrom = 0;
            drawBaseline = centreY - (segments.size() - 1) * lh / 2f
                    + lh * 0.36f;
            drawCount = segments.size();
        } else {
            // The window starts at the top edge — not one line above the
            // reading centre. Starting at the scroll position only drew a
            // single line above the centre, so every line vanished abruptly
            // a couple of lines above the middle instead of travelling up
            // to the top of the panel.
            drawFrom = drawStartForScroll(centreY, scrollOffset, lh);
            drawBaseline = windowStartBaseline(centreY, scrollOffset,
                    drawFrom, lh);
            drawCount = drawCountForScroll(drawBaseline, lh, h);
        }
        String[] window = windowForScroll(segments, drawCount, drawFrom);

        int save = canvas.save();
        if (mirror) {
            canvas.translate(w, 0);
            canvas.scale(-1f, 1f);
        }
        int layer = canvas.saveLayer(0, 0, w, h, null);

        textPaint.setColor(textColor);
        textPaint.setTextSize(renderedTextSizePx());
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShader(null);
        textPaint.setXfermode(null);

        float centreX = w / 2f;
        float baseline = drawBaseline;
        float baselineAlphaFloor = 0.30f;
        for (String line : window) {
            // Fade is symmetric about the reading centre, not the view
            // centre, so the brightest line is always the middle one.
            float a = TeleMath.fadeAlpha(baseline - centerShiftPx, h, fadePx);
            if (!line.isEmpty() && a > 0.01f) {
                textPaint.setAlpha(Math.round(255f
                        * Math.max(baselineAlphaFloor, a)));
                canvas.drawText(line, centreX, baseline, textPaint);
            }
            baseline += lh;
        }
        textPaint.setAlpha(255);

        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        // Opaque band follows the reading centre: 30% of the height each
        // side, clamped into the view.
        float opaqueTop = Math.max(0f, (centreY - h * 0.30f) / h);
        float opaqueBottom = Math.min(1f, (centreY + h * 0.30f) / h);
        maskPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, opaqueTop, opaqueBottom, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, maskPaint);
        maskPaint.setShader(null);
        maskPaint.setXfermode(null);
        canvas.restoreToCount(layer);
        canvas.restoreToCount(save);
    }

    /**
     * True when the whole script is shorter than the viewport.
     */
    private boolean totalLinesFits(int count) {
        return segments.size() <= 1 && scrollOffset <= 0.01f;
    }

    // ---- dragging the script by hand ----

    private float downX;
    private float downY;
    private float lastTouchY;
    private int activePointer = -1;
    private boolean dragging;

    /** True only while the user's finger is actively dragging the script. */
    public boolean isDragging() {
        return dragging;
    }

    @Override
    protected void onDetachedFromWindow() {
        dragging = false;
        activePointer = -1;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (maxScroll() <= 0f) {
            // Nothing to scroll: let taps through, but do not claim drags.
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                lastTouchY = event.getY();
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                int idx = event.findPointerIndex(activePointer);
                if (idx < 0) return true;
                float y = event.getY(idx);
                float dy = y - lastTouchY;
                // A4-11: the horizontal term used event.getX() - event.getX(0),
                // which is always 0, so any horizontal swipe with a little
                // vertical movement started a scroll. Compare against the
                // recorded downX instead.
                if (!dragging && Math.abs(y - downY) > touchSlopPx()
                        && Math.abs(y - downY)
                        > Math.abs(event.getX(idx) - downX)) {
                    dragging = true;
                    // Re-anchor so the first frames of travel do not jump by
                    // the slop that was just crossed.
                    lastTouchY = y;
                }
                if (dragging) {
                    // Drag down moves the text down, i.e. back through the script.
                    scrollBy(-dy);
                    lastTouchY = y;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = -1;
                if (!dragging) performClick();
                dragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return true;
        }
    }

    private float touchSlopPx() {
        return getResources().getDisplayMetrics().density * 8f;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private String[] windowOf(int count, int from) {
        return windowForScroll(segments, count, from);
    }
}
