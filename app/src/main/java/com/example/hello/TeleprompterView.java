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
    private float solidBand = 0.20f;

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
        wrapWidth = -1; // force a re-split
        rewrap();
        invalidate();
    }

    public String getScript() {
        return script;
    }

    public void setTextSizePx(float px) {
        if (px > 0f && Math.abs(px - textSizePx) > 0.5f) {
            textSizePx = px;
            rewrap();
            invalidate();
        }
    }

    public float getTextSizePx() {
        return textSizePx;
    }

    public void setWordsPerLine(int wpl) {
        int w = Math.max(TeleMath.MIN_WORDS_PER_LINE, wpl);
        if (w != wordsPerLine) {
            wordsPerLine = w;
            rewrap();
            invalidate();
        }
    }

    public int getWordsPerLine() {
        return wordsPerLine;
    }

    public float lineHeight() {
        return textSizePx * 1.5f;
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
        if (Math.abs(clamped - scrollOffset) > 0.01f) {
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float lh = lineHeight();
        int count = visibleLineCount();

        // Short script: park it in the middle of the panel and do not scroll.
        float centreY = h / 2f;
        float firstBaseline;
        int fromIndex;
        if (segments.isEmpty()) {
            return;
        }
        if (totalLinesFits(count)) {
            firstBaseline = centreY
                    - (segments.size() - 1) * lh / 2f + lh * 0.36f;
            fromIndex = 0;
        } else {
            firstBaseline = centreY - scrollOffset;
            fromIndex = (int) Math.floor(scrollOffset / lh);
        }

        // Window of exactly `count` segments, padded with empty segments so a
        // partially-scrolled script never indexes out of bounds.
        String[] window = windowOf(count, fromIndex);

        int save = canvas.save();
        if (mirror) {
            canvas.translate(w, 0);
            canvas.scale(-1f, 1f);
        }
        int layer = canvas.saveLayer(0, 0, w, h, null);

        textPaint.setColor(textColor);
        textPaint.setTextSize(textSizePx);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShader(null);
        textPaint.setXfermode(null);

        float centreX = w / 2f;
        float baseline = firstBaseline;
        float baselineAlphaFloor = 0.30f;
        for (String line : window) {
            float a = TeleMath.fadeAlpha(baseline, h, fadePx);
            if (!line.isEmpty() && a > 0.01f) {
                textPaint.setAlpha(Math.round(255f
                        * Math.max(baselineAlphaFloor, a)));
                canvas.drawText(line, centreX, baseline, textPaint);
            }
            baseline += lh;
        }
        textPaint.setAlpha(255);

        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        maskPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0f, solidBand, 1f - solidBand, 1f},
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
        return count > 0 && segments.size() <= count
                && scrollOffset <= 0.01f;
    }

    // ---- dragging the script by hand ----

    private float downY;
    private float lastTouchY;
    private int activePointer = -1;
    private boolean dragging;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (maxScroll() <= 0f) {
            // Nothing to scroll: let taps through, but do not claim drags.
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = event.getPointerId(0);
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
                if (!dragging && Math.abs(y - downY) > touchSlopPx()
                        && Math.abs(y - downY) > Math.abs(event.getX() - event.getX(0))) {
                    dragging = true;
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

    /**
     * Returns exactly {@code count} segments starting at {@code from},
     * substituting "" for any index outside the script.
     */
    private String[] windowOf(int count, int from) {
        if (count <= 0) return new String[0];
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            int idx = from + i;
            // Empty segments at both ends instead of crashing.
            out[i] = (idx < 0 || idx >= segments.size()) ? "" : segments.get(idx);
        }
        return out;
    }
}