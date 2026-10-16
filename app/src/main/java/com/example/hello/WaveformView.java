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
                Math.max(0.005f, COLS / (2f * n)));
    }

    public synchronized int historyCount() {
        return count;
    }

    /** Test hook: peak at an index, or -1 when out of range. */
    synchronized float peakAtForTest(int i) {
        if (i < 0 || i >= count) return -1f;
        return history[i];
    }

    private boolean followMinZoom = false;

    /** True when the user has zoomed all the way out to fit the whole take. */
    public synchronized boolean isFollowingMinZoom() {
        return followMinZoom;
    }

    /** Zoom step index into {@link #ZOOM_STEPS}; clamps + invalidates. */
    public void setZoom(float z) {
        float lo;
        float nz;
        boolean changed;
        synchronized (this) {
            lo = minimumZoom();
            float hi = ZOOM_STEPS[ZOOM_STEPS.length - 1];
            nz = Math.max(lo, Math.min(hi, z));
            followMinZoom = (count > COLS / 2) && (nz <= lo + 0.0005f);
            changed = Math.abs(nz - zoom) > 0.0001f;
            zoom = nz;
        }
        if (changed) {
            invalidate();
        }
    }

    public float getZoom() {
        return zoom;
    }

    public synchronized void setLive(boolean live) {
        this.live = live;
        invalidate();
    }

    /** Push one 0..1 peak sample from the raw capture path. */
    public void setMicLevel(float level) {
        push(Math.max(0f, Math.min(1f, level)));
    }

    public synchronized void push(float v) {
        float prevMin = minimumZoom();
        boolean wasAtMin = followMinZoom
                || (count > COLS / 2 && Math.abs(zoom - prevMin) <= 0.0005f);
        if (count == history.length) {
            if (history.length >= MAX_HISTORY) {
                halveHistory();
                if (!wasAtMin) {
                    zoom = Math.min(ZOOM_STEPS[ZOOM_STEPS.length - 1], zoom * 2f);
                }
            } else {
                float[] bigger = new float[Math.min(MAX_HISTORY,
                        history.length * 2)];
                System.arraycopy(history, 0, bigger, 0, count);
                history = bigger;
            }
        }
        history[count++] = v;
        if (wasAtMin) {
            followMinZoom = true;
            zoom = minimumZoom();
        }
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
        followMinZoom = false;
        invalidate();
    }

    /**
     * Populates the timeline from an existing 32-bit float WAV (edit punch-in
     * or crash recovery), so an attached session shows its waveform instead
     * of an empty strip.
     */
    public void loadFromFloatWav(java.io.File raw) {
        loadFromFloatWav(raw, null);
    }

    /**
     * Same, with an optional UI-thread callback fired once peaks are loaded
     * (NEW-05 fix: lets the studio refresh its zoom control floor for Edit
     * sessions, and preserves {@code live} + any newly pushed mic samples if
     * autostart recovery began recording while disk I/O was in flight).
     */
    public void loadFromFloatWav(java.io.File raw, Runnable onLoaded) {
        if (raw == null) return;
        new Thread(() -> {
            try {
                float[] peaks = Dsp.loadPeaks(raw, MAX_HISTORY);
                if (peaks.length == 0) return;
                synchronized (WaveformView.this) {
                    int n = Math.min(peaks.length, MAX_HISTORY);
                    int livePushed = count;
                    int totalNeeded = Math.min(MAX_HISTORY, n + livePushed);
                    while (history.length < totalNeeded
                            && history.length < MAX_HISTORY) {
                        float[] bigger = new float[Math.min(MAX_HISTORY,
                                history.length * 2)];
                        System.arraycopy(history, 0, bigger, 0, count);
                        history = bigger;
                    }
                    if (livePushed > 0) {
                        // Autostart capture pushed samples while we were
                        // reading disk: prepend the loaded peaks ahead of
                        // the live tail instead of clobbering it.
                        int keepLive = Math.min(livePushed,
                                MAX_HISTORY - n);
                        if (keepLive > 0) {
                            System.arraycopy(history, 0, history, n, keepLive);
                        }
                        System.arraycopy(peaks, 0, history, 0, n);
                        count = n + Math.max(0, keepLive);
                    } else {
                        System.arraycopy(peaks, 0, history, 0, n);
                        count = n;
                    }
                    zoom = Math.max(minimumZoom(), Math.min(zoom,
                            ZOOM_STEPS[ZOOM_STEPS.length - 1]));
                }
                postInvalidate();
                if (onLoaded != null) post(onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load").start();
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
                // Step live as the finger travels: waiting for release made
                // short or interrupted drags feel dead.
                float stepPx = 20f * getResources().getDisplayMetrics().density;
                while (touchAccum >= stepPx || touchAccum <= -stepPx) {
                    if (zoomListener != null) {
                        zoomListener.onZoomGesture(
                                touchAccum > 0 ? 1 : -1, touchAccum);
                    }
                    touchAccum += touchAccum > 0 ? -stepPx : stepPx;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
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
        boolean isLive;
        synchronized (this) {
            n = count;
            isLive = live;
        }
        int colorLive = 0xFFFF3B30;
        int colorIdle = 0xFF4A5361;
        for (int i = 0; i < n; i++) {
            float v;
            synchronized (this) {
                v = history[i];
            }
            // Align newest bar (i = n - 1) flush to the left of centerX so
            // bars never protrude to the right (in front) of the playhead.
            float x = centerX - (n - 1 - i) * gap - barW / 2f;
            if (x < -barW || x > centerX) continue;
            float bh = Math.max(2f, v * maxH * 2f);
            // Older bars recede toward the left edge, newest are brightest.
            float vis = Math.min(1f, Math.max(0f, x / Math.max(1f, centerX)));
            float age = 0.30f + 0.70f * vis;
            barPaint.setColor(isLive ? colorLive : colorIdle);
            barPaint.setAlpha(Math.round(255 * age));
            canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, barPaint);
        }
        barPaint.setAlpha(255);

        // Playhead: a short tick at the centre, brighter while recording.
        playPaint.setAlpha(isLive ? 255 : 90);
        canvas.drawLine(centerX, centerY - maxH - 4f, centerX, centerY + maxH + 4f,
                playPaint);
    }
}