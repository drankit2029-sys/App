package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/**
 * Interactive recording & non-linear editing waveform.
 *
 * <ul>
 *   <li><b>Live recording ({@code live == true})</b>: scrolls peaks leftward
 *       from the centre playhead (and shows upcoming existing bars to the
 *       right at dimmed alpha when punching in mid-take).</li>
 *   <li><b>Paused / Editor mode ({@code live == false})</b>: lays out the take
 *       across the full width (with up to 4x zoom around the playhead), draws
 *       the playhead cursor and draggable {@code [} / {@code ]} selection
 *       handles, and supports tap-to-seek, horizontal playhead scrubbing,
 *       handle dragging, horizontal range-sweep selection, double-tap
 *       selection toggle, and vertical drag zoom.</li>
 * </ul>
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
    private final Paint selFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleGripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] vpTmp = new float[4];

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

    private float downX = 0f;
    private float downY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private float touchAccum = 0f;
    private long lastTapUpMs = 0L;
    public static final float[] ZOOM_STEPS = {0.5f, 1f, 2f, 4f};

    /** Notified when a vertical drag crosses a zoom threshold. */
    public interface OnZoomGesture {
        void onZoomGesture(float direction, float totalDy);
    }

    private OnZoomGesture zoomListener;

    public interface TimelineListener {
        void onPlayheadScrubbed(int bucketIndex, float fraction);
        void onSelectionChanged(boolean active, float startFrac, float endFrac);
    }

    private TimelineListener timelineListener;
    /** Playhead bucket index (0 .. count). Defaults to count (end of take). */
    private int playheadIndex = 0;
    private boolean selectionActive = false;
    private int selStartIdx = 0;
    private int selEndIdx = 0;
    private int sweepAnchorIdx = 0;

    private static final int DRAG_NONE = 0;
    private static final int DRAG_ZOOM = 1;
    private static final int DRAG_SCRUB = 2;
    private static final int DRAG_SEL_START = 3;
    private static final int DRAG_SEL_END = 4;
    private static final int DRAG_SEL_SWEEP = 5;
    private int dragMode = DRAG_NONE;

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
        selFillPaint.setStyle(Paint.Style.FILL);
        selFillPaint.setColor(0x2EFF3B30);
        selBorderPaint.setStyle(Paint.Style.STROKE);
        selBorderPaint.setColor(0xFFFF3B30);
        selBorderPaint.setStrokeWidth(2f);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(0xFFFF3B30);
        handleGripPaint.setStyle(Paint.Style.STROKE);
        handleGripPaint.setStrokeCap(Paint.Cap.ROUND);
        handleGripPaint.setColor(0xFFF2F4F7);
        handleGripPaint.setStrokeWidth(1.5f);
        setClickable(true);
        Arrays.fill(history, 0f);
    }

    public void setOnZoomGesture(OnZoomGesture l) {
        zoomListener = l;
    }

    public void setTimelineListener(TimelineListener l) {
        this.timelineListener = l;
    }

    public synchronized float playheadFraction() {
        if (count <= 0) return 1f;
        return Math.max(0f, Math.min(1f, playheadIndex / (float) count));
    }

    public synchronized void setPlayheadFraction(float frac) {
        playheadIndex = Math.round(Math.max(0f, Math.min(1f, frac)) * count);
        postInvalidateOnAnimation();
    }

    public synchronized boolean hasSelection() {
        return selectionActive && selEndIdx > selStartIdx && count > 0;
    }

    public synchronized float selectionStartFraction() {
        return count <= 0 ? 0f : Math.max(0f, Math.min(1f, selStartIdx / (float) count));
    }

    public synchronized float selectionEndFraction() {
        return count <= 0 ? 1f : Math.max(0f, Math.min(1f, selEndIdx / (float) count));
    }

    /** Programmatically selects `[startFrac, endFrac]` (0..1) and notifies listeners. */
    public void selectRange(float startFrac, float endFrac) {
        boolean active;
        float sf;
        float ef;
        synchronized (this) {
            if (count <= 4) return;
            int s = Math.round(Math.max(0f, Math.min(1f, startFrac)) * count);
            int e = Math.round(Math.max(0f, Math.min(1f, endFrac)) * count);
            if (e < s) {
                int tmp = s;
                s = e;
                e = tmp;
            }
            if (e - s < 2) {
                if (e < count) e = Math.min(count, s + 2);
                else s = Math.max(0, e - 2);
            }
            selStartIdx = s;
            selEndIdx = e;
            selectionActive = (selEndIdx > selStartIdx);
            active = selectionActive;
            sf = selectionStartFraction();
            ef = selectionEndFraction();
        }
        invalidate();
        if (timelineListener != null) {
            timelineListener.onSelectionChanged(active, sf, ef);
        }
    }

    public synchronized void toggleSelectionAtPlayhead() {
        if (count <= 4) return;
        selectionActive = !selectionActive;
        if (selectionActive) {
            int span = Math.max(4, count / 4);
            int center = Math.min(count, Math.max(0, playheadIndex));
            if (center >= count - 2) {
                selEndIdx = count;
                selStartIdx = Math.max(0, count - span);
            } else if (center <= 2) {
                selStartIdx = 0;
                selEndIdx = Math.min(count, span);
            } else {
                selStartIdx = Math.max(0, center - span / 2);
                selEndIdx = Math.min(count, selStartIdx + span);
                if (selEndIdx - selStartIdx < span) {
                    selStartIdx = Math.max(0, selEndIdx - span);
                }
            }
            if (selEndIdx - selStartIdx < 2) {
                selStartIdx = 0;
                selEndIdx = count;
            }
        }
        invalidate();
        if (timelineListener != null) {
            timelineListener.onSelectionChanged(selectionActive,
                    selectionStartFraction(), selectionEndFraction());
        }
    }

    public synchronized void clearSelection() {
        selectionActive = false;
        invalidate();
        if (timelineListener != null) {
            timelineListener.onSelectionChanged(false, 0f, 1f);
        }
    }

    /** Updates an existing bucket in-place during a mid-file punch-in overwrite. */
    public synchronized void pushOrOverwrite(float v, float cursorFrac, boolean appending) {
        if (appending || count == 0) {
            push(v);
            playheadIndex = count;
        } else {
            int idx = Math.min(count - 1, Math.max(0, Math.round(cursorFrac * count)));
            history[idx] = Math.max(0f, Math.min(1f, v));
            playheadIndex = idx + 1;
            postInvalidateOnAnimation();
        }
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
        playheadIndex = count;
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
        playheadIndex = Math.min(half, (playheadIndex + 1) / 2);
        selStartIdx = Math.min(half, selStartIdx / 2);
        selEndIdx = Math.min(half, (selEndIdx + 1) / 2);
    }

    public synchronized void clear() {
        Arrays.fill(history, 0f);
        count = 0;
        playheadIndex = 0;
        selectionActive = false;
        selStartIdx = 0;
        selEndIdx = 0;
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
                    if (playheadIndex <= 0 || playheadIndex > count) {
                        playheadIndex = count;
                    }
                    zoom = Math.max(minimumZoom(), Math.min(zoom,
                            ZOOM_STEPS[ZOOM_STEPS.length - 1]));
                }
                postInvalidate();
                if (onLoaded != null) post(onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load").start();
    }

    // ---- Editor Viewport Mapping (when live == false && count > 0) ----

    private void computeEditorViewportLocked(int w, float[] out) {
        float density = getResources().getDisplayMetrics().density;
        float pad = Math.min(w * 0.04f, 12f * density);
        float usableW = Math.max(1f, w - 2f * pad);
        // At default zoom (<= 1.0), fit 100% of the take across usableW;
        // zooming above 1.0 magnifies up to 4x centered around playheadIndex.
        float zoomRatio = (!followMinZoom && zoom > 1.02f)
                ? Math.min(4f, zoom) : 1f;
        float totalW = usableW * zoomRatio;
        float playFrac = count <= 0 ? 1f
                : Math.max(0f, Math.min(1f, playheadIndex / (float) count));
        float scrollX = Math.max(0f, Math.min(totalW - usableW,
                playFrac * totalW - usableW * 0.5f));
        out[0] = pad;
        out[1] = usableW;
        out[2] = totalW;
        out[3] = scrollX;
    }

    private float screenXForBucketLocked(int bucketIdx, int w) {
        if (count <= 0 || w <= 0) return w / 2f;
        computeEditorViewportLocked(w, vpTmp);
        float frac = Math.max(0f, Math.min(1f, bucketIdx / (float) count));
        return vpTmp[0] + frac * vpTmp[2] - vpTmp[3];
    }

    private int bucketForScreenXLocked(float sx, int w) {
        if (count <= 0 || w <= 0) return 0;
        computeEditorViewportLocked(w, vpTmp);
        float frac = (sx - vpTmp[0] + vpTmp[3]) / Math.max(1f, vpTmp[2]);
        frac = Math.max(0f, Math.min(1f, frac));
        return Math.round(frac * count);
    }

    private void notifySelectionAndPlayhead() {
        if (timelineListener == null) return;
        boolean active;
        float sf, ef, pf;
        int pIdx;
        synchronized (this) {
            active = hasSelection();
            sf = selectionStartFraction();
            ef = selectionEndFraction();
            pf = playheadFraction();
            pIdx = playheadIndex;
        }
        timelineListener.onSelectionChanged(active, sf, ef);
        timelineListener.onPlayheadScrubbed(pIdx, pf);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int w = getWidth();
        float x = event.getX();
        float y = event.getY();
        float density = getResources().getDisplayMetrics().density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = lastTouchX = x;
                downY = lastTouchY = y;
                touchAccum = 0f;
                dragMode = DRAG_NONE;
                synchronized (this) {
                    if (!live && count > 0 && w > 0 && hasSelection()) {
                        float xStart = screenXForBucketLocked(selStartIdx, w);
                        float xEnd = screenXForBucketLocked(selEndIdx, w);
                        float hitRadius = 26f * density;
                        float dStart = Math.abs(x - xStart);
                        float dEnd = Math.abs(x - xEnd);
                        if (dStart <= hitRadius && dStart <= dEnd) {
                            dragMode = DRAG_SEL_START;
                        } else if (dEnd <= hitRadius) {
                            dragMode = DRAG_SEL_END;
                        }
                    }
                }
                if (dragMode != DRAG_NONE && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX;
                float dy = y - lastTouchY;
                float totalDy = y - downY;
                lastTouchX = x;
                lastTouchY = y;

                boolean isLive;
                int n;
                synchronized (this) {
                    isLive = live;
                    n = count;
                }

                if (dragMode == DRAG_NONE) {
                    float slop = 6f * density;
                    if (!isLive && n > 0 && w > 0
                            && Math.abs(dx) > slop
                            && Math.abs(dx) > Math.abs(totalDy) * 1.15f) {
                        boolean selMode;
                        synchronized (this) {
                            selMode = selectionActive;
                            if (selMode) {
                                dragMode = DRAG_SEL_SWEEP;
                                sweepAnchorIdx = bucketForScreenXLocked(downX, w);
                                int curIdx = bucketForScreenXLocked(x, w);
                                selStartIdx = Math.min(sweepAnchorIdx, curIdx);
                                selEndIdx = Math.max(sweepAnchorIdx, curIdx);
                                if (selEndIdx - selStartIdx < 2) {
                                    if (selEndIdx < n) {
                                        selEndIdx = Math.min(n, selStartIdx + 2);
                                    } else {
                                        selStartIdx = Math.max(0, selEndIdx - 2);
                                    }
                                }
                                playheadIndex = curIdx;
                            } else {
                                dragMode = DRAG_SCRUB;
                                playheadIndex = bucketForScreenXLocked(x, w);
                            }
                        }
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        invalidate();
                        if (selMode) {
                            notifySelectionAndPlayhead();
                        } else if (timelineListener != null) {
                            timelineListener.onPlayheadScrubbed(
                                    playheadIndex, playheadFraction());
                        }
                        return true;
                    } else if (Math.abs(totalDy) > slop) {
                        dragMode = DRAG_ZOOM;
                    }
                }

                if (dragMode == DRAG_SEL_START) {
                    synchronized (this) {
                        int idx = bucketForScreenXLocked(x, w);
                        selStartIdx = Math.max(0, Math.min(selEndIdx - 2, idx));
                        playheadIndex = selStartIdx;
                    }
                    invalidate();
                    notifySelectionAndPlayhead();
                    return true;
                }
                if (dragMode == DRAG_SEL_END) {
                    synchronized (this) {
                        int idx = bucketForScreenXLocked(x, w);
                        selEndIdx = Math.min(n, Math.max(selStartIdx + 2, idx));
                        playheadIndex = selEndIdx;
                    }
                    invalidate();
                    notifySelectionAndPlayhead();
                    return true;
                }
                if (dragMode == DRAG_SEL_SWEEP) {
                    synchronized (this) {
                        int curIdx = bucketForScreenXLocked(x, w);
                        selStartIdx = Math.min(sweepAnchorIdx, curIdx);
                        selEndIdx = Math.max(sweepAnchorIdx, curIdx);
                        if (selEndIdx - selStartIdx < 2) {
                            if (selEndIdx < n) {
                                selEndIdx = Math.min(n, selStartIdx + 2);
                            } else {
                                selStartIdx = Math.max(0, selEndIdx - 2);
                            }
                        }
                        playheadIndex = curIdx;
                    }
                    invalidate();
                    notifySelectionAndPlayhead();
                    return true;
                }
                if (dragMode == DRAG_SCRUB) {
                    int pIdx;
                    float pFrac;
                    synchronized (this) {
                        playheadIndex = bucketForScreenXLocked(x, w);
                        pIdx = playheadIndex;
                        pFrac = playheadFraction();
                    }
                    invalidate();
                    if (timelineListener != null) {
                        timelineListener.onPlayheadScrubbed(pIdx, pFrac);
                    }
                    return true;
                }

                // Drag up (negative dy) = zoom in, drag down = zoom out.
                touchAccum += -dy;
                float stepPx = 20f * density;
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
                float totalMove = Math.max(Math.abs(x - downX), Math.abs(y - downY));
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && dragMode == DRAG_NONE
                        && totalMove <= 8f * density) {
                    boolean tappedEditor = false;
                    boolean toggledSel = false;
                    synchronized (this) {
                        if (!live && count > 0 && w > 0) {
                            int tappedIdx = bucketForScreenXLocked(x, w);
                            long now = SystemClock.uptimeMillis();
                            playheadIndex = tappedIdx;
                            tappedEditor = true;
                            if (now - lastTapUpMs < 280L && count > 4) {
                                lastTapUpMs = 0L;
                                toggledSel = true;
                            } else {
                                lastTapUpMs = now;
                            }
                        }
                    }
                    if (toggledSel) {
                        toggleSelectionAtPlayhead();
                    }
                    if (tappedEditor) {
                        invalidate();
                        if (timelineListener != null) {
                            timelineListener.onPlayheadScrubbed(
                                    playheadIndex, playheadFraction());
                        }
                    }
                }
                dragMode = DRAG_NONE;
                touchAccum = 0f;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
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

        float density = getResources().getDisplayMetrics().density;
        float centerY = h / 2f;
        float centerX = w / 2f;
        float maxH = h * 0.44f;

        // Centre rule hairline
        canvas.drawLine(0, centerY, w, centerY, linePaint);

        int n;
        boolean isLive;
        int pIdx;
        boolean selOn;
        int sIdx;
        int eIdx;
        synchronized (this) {
            n = count;
            isLive = live;
            pIdx = playheadIndex;
            selOn = selectionActive && selEndIdx > selStartIdx && count > 0;
            sIdx = selStartIdx;
            eIdx = selEndIdx;
        }

        if (isLive || n <= 0) {
            // Live recording mode (or empty idle strip): scroll from centerX
            float gap = w / (float) COLS * zoom;
            float barW = Math.max(1.5f, gap * 0.45f);
            barPaint.setStrokeWidth(barW);
            int head = (pIdx > 0 && pIdx <= n) ? pIdx : n;
            for (int i = 0; i < n; i++) {
                float v;
                synchronized (this) {
                    v = history[i];
                }
                float x = centerX - (head - 1 - i) * gap - barW / 2f;
                if (x < -barW || x > w + barW) continue;
                float bh = Math.max(2f, v * maxH * 2f);
                if (i < head) {
                    float vis = Math.min(1f, Math.max(0f, x / Math.max(1f, centerX)));
                    float age = 0.30f + 0.70f * vis;
                    barPaint.setColor(0xFFFF3B30);
                    barPaint.setAlpha(Math.round(255 * age));
                } else {
                    // Upcoming audio to the right of playhead during mid-take punch-in
                    barPaint.setColor(0xFF4A5361);
                    barPaint.setAlpha(115);
                }
                canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, barPaint);
            }
            barPaint.setAlpha(255);
            playPaint.setColor(0xFFFF3B30);
            playPaint.setAlpha(isLive ? 255 : 90);
            canvas.drawLine(centerX, centerY - maxH - 4f, centerX, centerY + maxH + 4f,
                    playPaint);
            return;
        }

        // Paused / Editor mode: map full take across [pad, w - pad]
        float pad;
        float usableW;
        float totalW;
        float scrollX;
        synchronized (this) {
            computeEditorViewportLocked(w, vpTmp);
            pad = vpTmp[0];
            usableW = vpTmp[1];
            totalW = vpTmp[2];
            scrollX = vpTmp[3];
        }

        float xSelStart = pad + (sIdx / (float) n) * totalW - scrollX;
        float xSelEnd = pad + (eIdx / (float) n) * totalW - scrollX;
        float xPlayhead = pad + (Math.max(0, Math.min(n, pIdx)) / (float) n) * totalW - scrollX;

        // 1) Selection highlight region background
        if (selOn) {
            float left = Math.max(pad * 0.5f, xSelStart);
            float right = Math.min(w - pad * 0.5f, xSelEnd);
            if (right > left) {
                float boxTop = Math.max(2f, centerY - maxH - 5f);
                float boxBot = Math.min(h - 2f, centerY + maxH + 5f);
                rect.set(left, boxTop, right, boxBot);
                canvas.drawRoundRect(rect, 6f * density, 6f * density, selFillPaint);
            }
        }

        // 2) Downsampled waveform bars across visible viewport (<= 180 columns for 60fps)
        int maxCols = Math.max(60, Math.min(180, Math.round(usableW / (3.2f * density))));
        int cols = Math.min(n, maxCols);
        float colStep = totalW / cols;
        float barW = Math.max(1.8f, Math.min(4.5f * density, colStep * 0.58f));
        barPaint.setStrokeWidth(barW);

        for (int c = 0; c < cols; c++) {
            float x = pad + (c + 0.5f) * colStep - scrollX;
            if (x < pad - barW || x > w - pad + barW) continue;
            int b0 = (int) ((long) c * n / cols);
            int b1 = Math.max(b0 + 1, (int) ((long) (c + 1) * n / cols));
            float peak = 0f;
            synchronized (this) {
                int lim = Math.min(n, b1);
                for (int b = b0; b < lim; b++) {
                    if (history[b] > peak) peak = history[b];
                }
            }
            float bh = Math.max(2.5f, peak * maxH * 2f);
            int bMid = (b0 + b1) >>> 1;
            if (selOn) {
                if (bMid >= sIdx && bMid < eIdx) {
                    barPaint.setColor(0xFFFF453A);
                    barPaint.setAlpha(245);
                } else {
                    barPaint.setColor(0xFF4A5361);
                    barPaint.setAlpha(105);
                }
            } else {
                if (bMid <= pIdx) {
                    barPaint.setColor(0xFFD5DCE6);
                    barPaint.setAlpha(220);
                } else {
                    barPaint.setColor(0xFF5A6575);
                    barPaint.setAlpha(155);
                }
            }
            canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, barPaint);
        }
        barPaint.setAlpha(255);

        // 3) Selection boundary posts + [ and ] pill grab handles
        if (selOn) {
            float boxTop = Math.max(2f, centerY - maxH - 5f);
            float boxBot = Math.min(h - 2f, centerY + maxH + 5f);
            float tabW = 11f * density;
            float tabH = 18f * density;
            float r = 4f * density;

            if (xSelStart >= 0f && xSelStart <= w) {
                canvas.drawLine(xSelStart, boxTop, xSelStart, boxBot, selBorderPaint);
                // Top-left '[' handle tab
                rect.set(xSelStart - tabW * 0.5f, boxTop,
                        xSelStart + tabW * 0.5f, boxTop + tabH);
                canvas.drawRoundRect(rect, r, r, handlePaint);
                canvas.drawLine(xSelStart, boxTop + 4f * density,
                        xSelStart, boxTop + tabH - 4f * density, handleGripPaint);
            }
            if (xSelEnd >= 0f && xSelEnd <= w) {
                canvas.drawLine(xSelEnd, boxTop, xSelEnd, boxBot, selBorderPaint);
                // Bottom-right ']' handle tab
                rect.set(xSelEnd - tabW * 0.5f, boxBot - tabH,
                        xSelEnd + tabW * 0.5f, boxBot);
                canvas.drawRoundRect(rect, r, r, handlePaint);
                canvas.drawLine(xSelEnd, boxBot - tabH + 4f * density,
                        xSelEnd, boxBot - 4f * density, handleGripPaint);
            }
        }

        // 4) Playhead cursor line + top indicator cap
        if (xPlayhead >= pad - 2f && xPlayhead <= w - pad + 2f) {
            playPaint.setColor(selOn ? 0xFFF2F4F7 : 0xFFFF3B30);
            playPaint.setAlpha(245);
            float topY = Math.max(2f, centerY - maxH - 4f);
            float botY = Math.min(h - 2f, centerY + maxH + 4f);
            canvas.drawLine(xPlayhead, topY, xPlayhead, botY, playPaint);
            canvas.drawCircle(xPlayhead, topY + 3f * density, 3.2f * density, playPaint);
        }
    }
}
