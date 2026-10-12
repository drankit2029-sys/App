package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Teleprompter surface.
 *
 * <p>Renders the script itself rather than an EditText so it can do the two
 * things a prompter needs and a text view cannot: wrap strictly to the
 * effective words-per-line, and fade the copy out linearly towards the top
 * and bottom so the performer's eye is pulled to the opaque centre line.
 *
 * <p>The fade is a real alpha mask applied to the finished text layer — not
 * a stack of banded overlays — so it is smooth at every pixel.
 */
public class TeleprompterView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<String> lines = new ArrayList<>();
    private String script = "";

    private float textSizePx = 44f;
    private int wordsPerLine = TeleMath.MIN_WORDS_PER_LINE;
    private int textColor = 0xFFF2F4F7;
    private boolean mirror;

    /** Distance from the centre line to fully transparent, in pixels. */
    private float fadePx = 0f;
    /** Fraction of the panel height held at full opacity. */
    private float solidBand = 0.22f;

    private float scrollOffset = 0f;
    private float maxScroll = 0f;

    public TeleprompterView(Context context) {
        super(context);
        init();
    }

    public TeleprompterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setSubpixelText(true);
        edgePaint.setColor(0x33000000);
        setWillNotDraw(false);
    }

    public void setTextColor(int color) {
        textColor = color;
        invalidate();
    }

    public void setMirror(boolean m) {
        mirror = m;
        invalidate();
    }

    /**
     * Sets the script and re-wraps it. Wrapping depends on the current
     * width and size, so this is also called whenever either changes.
     */
    public void setScript(String text) {
        script = text == null ? "" : text;
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

    /** Total pixel height of the wrapped script, for scroll bounds. */
    public float contentHeight() {
        return Math.max(0, lines.size()) * lineHeight();
    }

    public float maxScroll() {
        return maxScroll;
    }

    public float scrollOffset() {
        return scrollOffset;
    }

    /** Sets the scroll position in pixels, clamped to the script bounds. */
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

    /** Jump back to the top, used when a take starts. */
    public void rewind() {
        setScrollOffset(0f);
    }

    private float lineHeight() {
        return textSizePx * 1.55f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fadePx = h * 0.46f;
        rewrap();
    }

    /**
     * Re-flows the script to the current width, honouring the requested words
     * per line but never more than physically fit (see TeleMath).
     */
    private void rewrap() {
        lines.clear();
        int width = getWidth();
        if (width <= 0 || script.trim().isEmpty()) {
            recomputeMaxScroll();
            return;
        }
        float pad = getPaddingLeft() + getPaddingRight();
        int avail = width - Math.round(pad);
        // The clamp is the important part: a large text size in a narrow
        // panel silently reduces words-per-line instead of overflowing.
        int wpl = TeleMath.wordsPerLine(avail, textSizePx, wordsPerLine);

        float charW = Math.max(1f, textSizePx * 0.52f);
        int maxChars = Math.max(6, (int) (avail / charW));

        for (String paragraph : script.split("\\n", -1)) {
            String[] words = paragraph.trim().split("\\s+");
            int i = 0;
            if (words.length == 0 || (words.length == 1 && words[0].isEmpty())) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            while (i < words.length) {
                // Take up to wpl words, and never exceed the pixel width.
                int take = 0;
                StringBuilder probe = new StringBuilder();
                while (i + take < words.length && take < wpl) {
                    String cand = take == 0 ? words[i + take]
                            : probe + " " + words[i + take];
                    if (cand.length() > maxChars && take > 0) break;
                    probe.setLength(0);
                    probe.append(cand);
                    take++;
                }
                if (take == 0) take = 1; // a single unbreakable long word
                lines.add(probe.toString());
                i += take;
            }
        }
        recomputeMaxScroll();
    }

    private void recomputeMaxScroll() {
        float total = contentHeight();
        int h = getHeight();
        maxScroll = Math.max(0f, total - h + lineHeight());
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || lines.isEmpty()) return;

        float lh = lineHeight();
        // Centre line: the opaque reference the performer reads from.
        float centerY = h / 2f;
        float firstY = centerY - scrollOffset;

        int save = canvas.save();
        if (mirror) {
            // Camera-ready mirroring: scale about the centre so text reads
            // correctly in a beam splitter or flipped monitor.
            canvas.translate(w, 0);
            canvas.scale(-1f, 1f);
        }

        // Draw into an offscreen layer, then mask it with the vertical fade.
        int layer = canvas.saveLayer(0, 0, w, h, null);
        textPaint.setColor(textColor);
        textPaint.setTextSize(textSizePx);
        textPaint.setShader(null);
        textPaint.setXfermode(null);

        float padL = getPaddingLeft();
        float x;
        float alpha = 0.34f;
        if (mirror) {
            x = padL;
            textPaint.setTextAlign(Paint.Align.LEFT);
        } else {
            x = padL;
            textPaint.setTextAlign(Paint.Align.LEFT);
        }

        float baseline = firstY + lh * 0.72f;
        // Only draw lines that touch the viewport.
        int from = Math.max(0, (int) ((scrollOffset - lh) / lh));
        int to = Math.min(lines.size(), from + (int) (h / lh) + 4);
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (line == null || line.isEmpty()) continue;
            float a = TeleMath.fadeAlpha(baseline, h, fadePx);
            if (a <= 0.01f) {
                baseline += lh;
                continue;
            }
            textPaint.setAlpha(Math.round(255f * Math.max(alpha, a)));
            canvas.drawText(line, x, baseline, textPaint);
            baseline += lh;
        }
        textPaint.setAlpha(255);

        // Linear alpha mask: transparent -> opaque -> opaque -> transparent.
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
}