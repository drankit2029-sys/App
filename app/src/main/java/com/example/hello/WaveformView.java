package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/**
 * Scrolling waveform: a centre line with recent raw-audio peaks drawn as
 * rounded bars, newest at the playhead, older bars fading to the left.
 *
 * <p>Slim by design — thin bars on a faint centre rule, no chunky grid — so
 * it reads as an instrument rather than a chart. Vertical drags zoom, which
 * is the gesture a producer expects from a timeline.
 */
public class WaveformView extends View {

    private static final int COLS = 150;

    /**
     * Hard ceiling on retained peak columns. When the history outgrows this it
     * is halved in resolution rather than truncated, so a long take still
     * shows its whole shape — just coarser.
     */
    private static final int MAX_HISTORY = 8192;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Growable peak history. Unlike a fixed ring buffer this keeps the entire
     * recording, which is what lets the minimum zoom be "whole timeline fits
     * left of the playhead" instead of an arbitrary constant.
     */
    private float[] history = new float[COLS];

    private int count;
    private boolean live;
    /** Horizontal zoom: time shown per unit length (1 = default). */
    private float zoom = 1f;

    private float lastTouchY = 0f;
    private float touchAccum = 0f;

    public static final float[] ZOOM_STEPS = {0.5f, 1f, 2f, 4f};

    /** Notified when a vertical drag crosses a zoom threshold. */
    public interface OnZoomGesture {
        void onZoomGesture(float direction, float totalDy);
    }

    private OnZoomGesture zoomListener;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0xFF3A414B);
        linePaint.setStrokeWidth(1.5f);
        barGlowPaint.setShader(new LinearGradient(0, 0, 1, 0,
                new int[]{0x33FF3B30, 0xFFFF3B30},
                null, Shader.TileMode.CLAMP));
        playPaint.setColor(0xFFFF3B30);
        playPaint.setStrokeWidth(2f);
        Arrays.fill(history, 0f);
    }

    public void setOnZoomGesture(OnZoomGesture l) {
        zoomListener = l;
    }

    /**
     * Smallest zoom allowed for the current history length: the level at which
     * every retained sample fits to the left of the red centre line.
     *
     * <p>Bars occupy {@code COLS / zoom} columns across the full width, and the
     * playhead sits at {@code w / 2}, so the whole timeline fits when
     * {@code count * gap <= w / 2}.
     */
    public synchronized float minimumZoom() {
        int n = Math.max(count, 1);
        return (float) Math.min(ZOOM_STEPS[0],
                Math.max(0.02f, COLS / (2f * n)));
    }

    public synchronized int historyCount() {
        return count;
    }

    /** Zoom step index into {@link #ZOOM_STEPS}; clamps + invalidates. */
    public void setZoom(float z) {
        float lo = minimumZoom();
        float hi = ZOOM_STEPS[ZOOM_STEPS.length - 1];
        float nz = Math.max(lo, Math.min(hi, z));
        if (Math.abs(nz - zoom) > 0.001f) {
            zoom = nz;
            invalidate();
        }
    }

    public float getZoom() {
        return zoom;
    }

    public void setLive(boolean live) {
        this.live = live;
        invalidate();
    }

    /** Push one 0..1 peak sample from the raw capture path. */
    public void setMicLevel(float level) {
        push(Math.max(0f, Math.min(1f, level)));
    }

    public synchronized void push(float v) {
        if (count == history.length) {
            if (history.length >= MAX_HISTORY) {
                halveHistory();
            } else {
                float[] bigger = new float[Math.min(MAX_HISTORY,
                        history.length * 2)];
                System.arraycopy(history, 0, bigger, 0, count);
                history = bigger;
            }
        }
        history[count++] = v;
        postInvalidateOnAnimation();
    }

    /** Drops resolution by one bit so an hour-long take still fits in memory. */
    private void halveHistory() {
        int half = count / 2;
        for (int i = 0; i < half; i++) {
            history[i] = Math.max(history[i * 2], history[i * 2 + 1]);
        }
        count = half;
    }

    public synchronized void clear() {
        Arrays.fill(history, 0f);
        count = 0;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchY = event.getY();
                touchAccum = 0f;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = event.getY();
                float dy = y - lastTouchY;
                lastTouchY = y;
                // Drag up (negative dy) = zoom in, drag down = zoom out.
                touchAccum += -dy;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (Math.abs(touchAccum) >= 6f && zoomListener != null) {
                    zoomListener.onZoomGesture(touchAccum > 0 ? 1 : -1,
                            touchAccum);
                }
                touchAccum = 0f;
                performClick();
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float centerY = h / 2f;
        float centerX = w / 2f;

        // Just the centre rule: one hairline, no grid clutter.
        canvas.drawLine(0, centerY, w, centerY, linePaint);

        float gap = w / (float) COLS * zoom;
        float barW = Math.max(1.5f, gap * 0.45f);
        barPaint.setStrokeWidth(barW);
        float maxH = h * 0.44f;

        int n;
        synchronized (this) {
            n = count;
        }
        int colorLive = 0xFFFF3B30;
        int colorIdle = 0xFF4A5361;
        for (int i = 0; i < n; i++) {
            float v;
            synchronized (this) {
                v = history[i];
            }
            float x = centerX - (n - 1 - i) * gap + gap / 2f;
            if (x < -barW || x > w + barW) continue;
            float bh = Math.max(2f, v * maxH * 2f);
            // Older bars recede toward the left edge, newest are brightest.
            float vis = Math.min(1f, Math.max(0f, x / Math.max(1f, centerX)));
            float age = 0.30f + 0.70f * vis;
            barPaint.setColor(live ? colorLive : colorIdle);
            barPaint.setAlpha(Math.round(255 * age));
            canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, barPaint);
        }
        barPaint.setAlpha(255);

        // Playhead: a short tick at the centre, brighter while recording.
        playPaint.setAlpha(live ? 255 : 90);
        canvas.drawLine(centerX, centerY - maxH - 4f, centerX, centerY + maxH + 4f,
                playPaint);
    }
}