package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Unified Red-Waveform interactive recording & non-linear editing timeline.
 *
 * <p>When Pause is pressed, the red waveform stays <b>as-is</b> (zero reload or
 * density jump) and enables smooth 1:1 horizontal scrolling across the center
 * playhead, draggable {@code [} and {@code ]} selection handles, and in-place
 * peak editing for Trim, Select & Replace, and Undo.
 */
public class WaveformView extends View {

    private static final int COLS = 150;
    private static final int MAX_HISTORY = 8192;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleGripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float[] history = new float[COLS];
    private int count;
    private boolean live;
    private float zoom = 1f;
    private boolean followMinZoom = false;

    private final Deque<float[]> undoPeakStack = new ArrayDeque<>();
    private float[] savedReplaceTailPeaks = null;

    private float downX = 0f;
    private float downY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private float touchAccum = 0f;
    private long lastTapUpMs = 0L;
    public static final float[] ZOOM_STEPS = {0.5f, 1f, 2f, 4f};

    public interface OnZoomGesture {
        void onZoomGesture(float direction, float totalDy);
    }

    private OnZoomGesture zoomListener;

    public interface TimelineListener {
        void onPlayheadScrubbed(int bucketIndex, float fraction);
        void onSelectionChanged(boolean active, float startFrac, float endFrac);
    }

    private TimelineListener timelineListener;

    private float playheadPos = 0f;
    private int playheadIndex = 0;
    private boolean selectionActive = false;
    private int selStartIdx = 0;
    private int selEndIdx = 0;

    private static final int DRAG_NONE = 0;
    private static final int DRAG_ZOOM = 1;
    private static final int DRAG_SCROLL = 2;
    private static final int DRAG_SEL_START = 3;
    private static final int DRAG_SEL_END = 4;
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
        playPaint.setColor(0xFFFF3B30);
        playPaint.setStrokeWidth(2.2f);
        selFillPaint.setStyle(Paint.Style.FILL);
        selFillPaint.setColor(0x2EFF3B30);
        selBorderPaint.setStyle(Paint.Style.STROKE);
        selBorderPaint.setColor(0xFFFF554A);
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
        return Math.max(0f, Math.min(1f, playheadPos / (float) count));
    }

    public synchronized void setPlayheadFraction(float frac) {
        float clamped = Math.max(0f, Math.min(1f, frac));
        playheadPos = clamped * count;
        playheadIndex = Math.round(playheadPos);
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

    /** Snapshots the current in-memory waveform peaks before an edit operation. */
    public synchronized void recordUndoPeaks() {
        if (count <= 0) return;
        while (undoPeakStack.size() >= 3) {
            undoPeakStack.removeLast();
        }
        undoPeakStack.addFirst(Arrays.copyOf(history, count));
    }

    /** Restores the most recent in-memory waveform peak snapshot on Undo. */
    public synchronized boolean popUndoPeaks() {
        if (undoPeakStack.isEmpty()) return false;
        float[] snap = undoPeakStack.pollFirst();
        if (snap == null) return false;
        if (history.length < snap.length) {
            history = new float[Math.max(COLS, snap.length)];
        }
        Arrays.fill(history, 0f);
        System.arraycopy(snap, 0, history, 0, snap.length);
        count = snap.length;
        playheadPos = count;
        playheadIndex = count;
        selectionActive = false;
        postInvalidateOnAnimation();
        return true;
    }

    /** Crops the in-memory waveform bars to the current selection `[selStartIdx, selEndIdx)` in-place. */
    public synchronized void cropToSelectionBuckets() {
        if (!hasSelection()) return;
        int s = Math.max(0, Math.min(count, selStartIdx));
        int e = Math.max(s + 1, Math.min(count, selEndIdx));
        int keep = e - s;
        System.arraycopy(history, s, history, 0, keep);
        Arrays.fill(history, keep, count, 0f);
        count = keep;
        playheadPos = count;
        playheadIndex = count;
        selectionActive = false;
        postInvalidateOnAnimation();
    }

    /**Deletes the selected buckets `[selStartIdx, selEndIdx)` in-place and shifts the tail left. */
    public synchronized void deleteSelectionBuckets() {
        if (!hasSelection()) return;
        int s = Math.max(0, Math.min(count, selStartIdx));
        int e = Math.max(s, Math.min(count, selEndIdx));
        int tail = count - e;
        if (tail > 0) {
            System.arraycopy(history, e, history, s, tail);
        }
        int newCount = s + tail;
        Arrays.fill(history, newCount, count, 0f);
        count = newCount;
        playheadPos = s;
        playheadIndex = s;
        selectionActive = false;
        postInvalidateOnAnimation();
    }

    /**
     * Stashes the post-selection peak bars `[selEndIdx, count)` and truncates
     * the live waveform to `selStartIdx` so variable-length Select & Replace
     * appends new red bars from `selStartIdx` and splices the tail bars back on pause!
     */
    public synchronized void beginReplaceSelection() {
        if (!hasSelection()) return;
        int s = Math.max(0, Math.min(count, selStartIdx));
        int e = Math.max(s, Math.min(count, selEndIdx));
        int tailLen = Math.max(0, count - e);
        savedReplaceTailPeaks = tailLen > 0
                ? Arrays.copyOfRange(history, e, count) : new float[0];
        Arrays.fill(history, s, count, 0f);
        count = s;
        playheadPos = s;
        playheadIndex = s;
        selectionActive = false;
        postInvalidateOnAnimation();
    }

    /** Appends the stashed post-selection peak bars back after Select & Replace recording finishes. */
    public synchronized void finishReplaceSelection() {
        float[] tail = savedReplaceTailPeaks;
        savedReplaceTailPeaks = null;
        int replaceEnd = count;
        if (tail != null && tail.length > 0) {
            for (float v : tail) {
                push(v);
            }
        }
        playheadPos = replaceEnd;
        playheadIndex = replaceEnd;
        selectionActive = false;
        postInvalidateOnAnimation();
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
        if (count <= 1) return;
        selectionActive = !selectionActive;
        if (selectionActive) {
            int visibleCols = Math.max(8, Math.round(COLS / Math.max(0.25f, zoom)));
            // Select ~35% of the take (or ~1/3 of visible columns) so the
            // selection is always a meaningful, easy-to-grab audio segment.
            int span = Math.max(4, Math.min(count / 3, visibleCols / 4));
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

    public synchronized void truncateToPlayhead() {
        int keep = Math.max(0, Math.min(count, playheadIndex));
        if (keep < count) {
            Arrays.fill(history, keep, count, 0f);
            count = keep;
            playheadPos = keep;
            playheadIndex = keep;
        }
        selectionActive = false;
        postInvalidateOnAnimation();
    }

    public synchronized void truncateToFraction(float frac) {
        int keep = Math.max(0, Math.min(count, Math.round(frac * count)));
        if (keep < count) {
            Arrays.fill(history, keep, count, 0f);
            count = keep;
            playheadPos = keep;
            playheadIndex = keep;
        }
        selectionActive = false;
        postInvalidateOnAnimation();
    }

    public synchronized void pushOrOverwrite(float v, float cursorFrac, boolean appending) {
        if (appending || count == 0) {
            push(v);
            playheadPos = count;
            playheadIndex = count;
        } else {
            int idx = Math.min(count - 1, Math.max(0, Math.round(cursorFrac * count)));
            history[idx] = Math.max(0f, Math.min(1f, v));
            playheadPos = idx + 1;
            playheadIndex = idx + 1;
            postInvalidateOnAnimation();
        }
    }

    public synchronized float minimumZoom() {
        int n = Math.max(count, 1);
        return (float) Math.min(ZOOM_STEPS[0],
                Math.max(0.005f, COLS / (2f * n)));
    }

    public synchronized int historyCount() {
        return count;
    }

    synchronized float peakAtForTest(int i) {
        if (i < 0 || i >= count) return -1f;
        return history[i];
    }

    public synchronized boolean isFollowingMinZoom() {
        return followMinZoom;
    }

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
        if (live) {
            selectionActive = false;
        }
        invalidate();
    }

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
        playheadPos = count;
        playheadIndex = count;
        if (wasAtMin) {
            followMinZoom = true;
            zoom = minimumZoom();
        }
        postInvalidateOnAnimation();
    }

    private void halveHistory() {
        int half = count / 2;
        for (int i = 0; i < half; i++) {
            history[i] = Math.max(history[i * 2], history[i * 2 + 1]);
        }
        count = half;
        playheadIndex = Math.min(half, (playheadIndex + 1) / 2);
        playheadPos = playheadIndex;
        selStartIdx = Math.min(half, selStartIdx / 2);
        selEndIdx = Math.min(half, (selEndIdx + 1) / 2);
    }

    public synchronized void clear() {
        Arrays.fill(history, 0f);
        count = 0;
        playheadPos = 0f;
        playheadIndex = 0;
        selectionActive = false;
        selStartIdx = 0;
        selEndIdx = 0;
        followMinZoom = false;
        savedReplaceTailPeaks = null;
        invalidate();
    }

    public void loadFromFloatWav(java.io.File raw) {
        loadFromFloatWav(raw, null);
    }

    public void loadFromFloatWav(java.io.File raw, Runnable onLoaded) {
        if (raw == null) return;
        new Thread(() -> {
            try {
                float[] peaks = Dsp.loadPeaks(raw, MAX_HISTORY);
                if (peaks.length == 0) return;
                applyLoadedPeaks(peaks, onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load").start();
    }

    /**
     * Loads an attached Edit/Recovery session at the exact same 20-bars/sec
     * (50 ms per bar = 2400 frames @ 48 kHz) scale as live recording so an
     * opened take matches live recording spacing and never overfills the screen.
     */
    public void loadFromFloatWavTwentyHz(java.io.File raw, Runnable onLoaded) {
        if (raw == null) return;
        new Thread(() -> {
            try {
                long payload = RecordingEngine.payloadBytesOf(raw);
                FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
                long totalFrames = payload / 4 / Math.max(1, info.channels);
                int buckets20Hz = (int) Math.max(8,
                        Math.min(MAX_HISTORY, (totalFrames + 1200) / 2400));
                float[] peaks = loadPeaksExactBuckets(raw, buckets20Hz);
                if (peaks.length == 0) return;
                synchronized (WaveformView.this) {
                    if (history.length < peaks.length) {
                        history = new float[Math.min(MAX_HISTORY,
                                Math.max(COLS, peaks.length))];
                    }
                    Arrays.fill(history, 0f);
                    count = Math.min(peaks.length, history.length);
                    System.arraycopy(peaks, 0, history, 0, count);
                    playheadIndex = count;
                    playheadPos = count;
                    zoom = Math.max(minimumZoom(), Math.min(zoom,
                            ZOOM_STEPS[ZOOM_STEPS.length - 1]));
                }
                postInvalidate();
                if (onLoaded != null) post(onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load-20hz").start();
    }

    private static float[] loadPeaksExactBuckets(java.io.File wav, int buckets)
            throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            long totalFrames = dataBytes / 4 / Math.max(1, h.channels);
            if (totalFrames <= 0) return new float[0];
            int bCount = (int) Math.max(1, Math.min(buckets, totalFrames));
            float[] peaks = new float[bCount];
            int framesPerRead = Math.max(1, 65536 / 4 / h.channels);
            byte[] in = new byte[framesPerRead * h.channels * 4];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            r.seek(h.dataStart);
            long frame = 0;
            while (frame < totalFrames) {
                int want = (int) Math.min(framesPerRead, totalFrames - frame);
                r.readFully(in, 0, want * h.channels * 4);
                fb.clear();
                fb.limit(want * h.channels);
                for (int f = 0; f < want; f++) {
                    float peak = 0f;
                    for (int c = 0; c < h.channels; c++) {
                        float a = Math.abs(fb.get(f * h.channels + c));
                        if (a > peak) peak = a;
                    }
                    int b = (int) ((frame + f) * bCount / totalFrames);
                    if (b >= bCount) b = bCount - 1;
                    if (peak > peaks[b]) peaks[b] = peak;
                }
                frame += want;
            }
            return peaks;
        }
    }

    private void applyLoadedPeaks(float[] peaks, Runnable onLoaded) {
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
            playheadIndex = count;
            playheadPos = count;
            zoom = Math.max(minimumZoom(), Math.min(zoom,
                    ZOOM_STEPS[ZOOM_STEPS.length - 1]));
        }
        postInvalidate();
        if (onLoaded != null) post(onLoaded);
    }

    // ---- Red Waveform Center-Playhead Coordinate Math ----

    private float gapPxLocked(int w) {
        return Math.max(0.25f, (w / (float) COLS) * zoom);
    }

    private float screenXForBucketLocked(float idx, int w) {
        if (w <= 0) return 0f;
        float centerX = w / 2f;
        float gap = gapPxLocked(w);
        float barW = Math.max(1.5f, gap * 0.45f);
        return centerX - (playheadPos - 1f - idx) * gap - barW / 2f;
    }

    private int bucketForScreenXLocked(float sx, int w) {
        if (count <= 0 || w <= 0) return 0;
        float centerX = w / 2f;
        float gap = gapPxLocked(w);
        float barW = Math.max(1.5f, gap * 0.45f);
        float idx = playheadPos - 1f - (centerX - barW / 2f - sx) / gap;
        return Math.max(0, Math.min(count, Math.round(idx)));
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
                        float hitRadius = 28f * density;
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
                float stepDx = x - lastTouchX;
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
                        dragMode = DRAG_SCROLL;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    } else if (Math.abs(totalDy) > slop) {
                        dragMode = DRAG_ZOOM;
                    }
                }

                if (dragMode == DRAG_SEL_START) {
                    synchronized (this) {
                        float edge = 28f * density;
                        float gap = gapPxLocked(w);
                        if (x < edge && playheadPos > 0) {
                            playheadPos = Math.max(0f, playheadPos - (edge - x) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        } else if (x > w - edge && playheadPos < n) {
                            playheadPos = Math.min(n, playheadPos + (x - (w - edge)) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        }
                        int idx = bucketForScreenXLocked(x, w);
                        selStartIdx = Math.max(0, Math.min(selEndIdx - 2, idx));
                    }
                    invalidate();
                    notifySelectionAndPlayhead();
                    return true;
                }
                if (dragMode == DRAG_SEL_END) {
                    synchronized (this) {
                        float edge = 28f * density;
                        float gap = gapPxLocked(w);
                        if (x < edge && playheadPos > 0) {
                            playheadPos = Math.max(0f, playheadPos - (edge - x) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        } else if (x > w - edge && playheadPos < n) {
                            playheadPos = Math.min(n, playheadPos + (x - (w - edge)) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        }
                        int idx = bucketForScreenXLocked(x, w);
                        selEndIdx = Math.min(n, Math.max(selStartIdx + 2, idx));
                    }
                    invalidate();
                    notifySelectionAndPlayhead();
                    return true;
                }
                if (dragMode == DRAG_SCROLL) {
                    int pIdx;
                    float pFrac;
                    synchronized (this) {
                        float gap = gapPxLocked(w);
                        // Dragging waveform right (stepDx > 0) moves earlier audio
                        // to the center playhead (decreases playheadPos); dragging
                        // left (stepDx < 0) advances toward the end.
                        // Scale sensitivity so a full-width swipe can traverse a
                        // long take even when zoomed in, while remaining 1:1 at
                        // normal zoom.
                        float barsPerPx = Math.max(1f / gap, n / (float) Math.max(w, 1));
                        playheadPos = Math.max(0f, Math.min(n,
                                playheadPos - stepDx * barsPerPx));
                        playheadIndex = Math.round(playheadPos);
                        pIdx = playheadIndex;
                        pFrac = playheadFraction();
                    }
                    invalidate();
                    if (timelineListener != null) {
                        timelineListener.onPlayheadScrubbed(pIdx, pFrac);
                    }
                    return true;
                }

                // Vertical drag zoom
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
                            playheadPos = tappedIdx;
                            playheadIndex = tappedIdx;
                            tappedEditor = true;
                            if (now - lastTapUpMs < 280L && count > 1) {
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
        float headPos;
        int pIdx;
        boolean selOn;
        int sIdx;
        int eIdx;
        float gap;
        synchronized (this) {
            n = count;
            isLive = live;
            pIdx = playheadIndex;
            headPos = isLive ? ((pIdx > 0 && pIdx <= n) ? pIdx : n) : playheadPos;
            selOn = !isLive && selectionActive && selEndIdx > selStartIdx && count > 0;
            sIdx = selStartIdx;
            eIdx = selEndIdx;
            gap = gapPxLocked(w);
        }

        float barW = Math.max(1.5f, gap * 0.45f);
        barPaint.setStrokeWidth(barW);

        float xSelStart = centerX - (headPos - 1f - sIdx) * gap - barW / 2f;
        float xSelEnd = centerX - (headPos - 1f - eIdx) * gap - barW / 2f;

        // 1) Selection highlight box on the red waveform when paused
        if (selOn) {
            float left = Math.max(0f, xSelStart);
            float right = Math.min(w, xSelEnd);
            if (right > left) {
                float boxTop = Math.max(2f, centerY - maxH - 5f);
                float boxBot = Math.min(h - 2f, centerY + maxH + 5f);
                rect.set(left, boxTop, right, boxBot);
                canvas.drawRoundRect(rect, 6f * density, 6f * density, selFillPaint);
            }
        }

        // 2) Draw Red Waveform Bars (both live & paused — identical scale & position)
        if (n > 0) {
            int iMin = Math.max(0, (int) Math.floor(headPos - 1f - (centerX + barW) / gap));
            int iMax = Math.min(n - 1, (int) Math.ceil(headPos - 1f + (w - centerX + barW) / gap));
            int step = Math.max(1, (iMax - iMin + 1) / 300);

            for (int i = iMin; i <= iMax; i += step) {
                float v = 0f;
                synchronized (this) {
                    int lim = Math.min(n, i + step);
                    for (int k = i; k < lim; k++) {
                        if (history[k] > v) v = history[k];
                    }
                }
                float x = centerX - (headPos - 1f - i) * gap - barW / 2f;
                if (x < -barW || x > w + barW) continue;
                float bh = Math.max(2f, v * maxH * 2f);

                if (selOn && i >= sIdx && i < eIdx) {
                    barPaint.setColor(0xFFFF453A);
                    barPaint.setAlpha(255);
                } else if (i < headPos) {
                    float vis = Math.min(1f, Math.max(0f, x / Math.max(1f, centerX)));
                    float age = 0.35f + 0.65f * vis;
                    barPaint.setColor(0xFFFF3B30);
                    barPaint.setAlpha(Math.round(255 * age));
                } else {
                    float rightVis = Math.min(1f, Math.max(0f, (w - x) / Math.max(1f, centerX)));
                    barPaint.setColor(0xFFFF3B30);
                    barPaint.setAlpha(Math.round(90 + 65 * rightVis));
                }
                canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, barPaint);
            }
        }
        barPaint.setAlpha(255);

        // 3) Selection boundary posts + [ and ] grab handles on the red waveform
        if (selOn) {
            float boxTop = Math.max(2f, centerY - maxH - 5f);
            float boxBot = Math.min(h - 2f, centerY + maxH + 5f);
            float tabW = 12f * density;
            float tabH = 19f * density;
            float r = 4f * density;

            if (xSelStart >= -tabW && xSelStart <= w + tabW) {
                float cx = Math.max(tabW * 0.5f, Math.min(w - tabW * 0.5f, xSelStart));
                canvas.drawLine(cx, boxTop, cx, boxBot, selBorderPaint);
                rect.set(cx - tabW * 0.5f, boxTop, cx + tabW * 0.5f, boxTop + tabH);
                canvas.drawRoundRect(rect, r, r, handlePaint);
                canvas.drawLine(cx, boxTop + 4f * density,
                        cx, boxTop + tabH - 4f * density, handleGripPaint);
            }
            if (xSelEnd >= -tabW && xSelEnd <= w + tabW) {
                float cx = Math.max(tabW * 0.5f, Math.min(w - tabW * 0.5f, xSelEnd));
                canvas.drawLine(cx, boxTop, cx, boxBot, selBorderPaint);
                rect.set(cx - tabW * 0.5f, boxBot - tabH, cx + tabW * 0.5f, boxBot);
                canvas.drawRoundRect(rect, r, r, handlePaint);
                canvas.drawLine(cx, boxBot - tabH + 4f * density,
                        cx, boxBot - 4f * density, handleGripPaint);
            }
        }

        // 4) Center Playhead Line + Top Indicator Cap
        playPaint.setColor(0xFFFF3B30);
        playPaint.setAlpha((isLive || n > 0) ? 255 : 90);
        float topY = Math.max(2f, centerY - maxH - 4f);
        float botY = Math.min(h - 2f, centerY + maxH + 4f);
        canvas.drawLine(centerX, topY, centerX, botY, playPaint);
        if (!isLive && n > 0) {
            canvas.drawCircle(centerX, topY + 3f * density, 3.2f * density, playPaint);
        }
    }
}

