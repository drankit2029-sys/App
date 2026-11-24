package com.takeone.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
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
 * <p>Features:
 * <ul>
 *   <li><b>Half-Bar Playhead Highlight Rule</b>: A bar (or sub-bar) is centered
 *       at {@code bucket + 0.5f} and only highlights once the center playhead
 *       has crossed half of it ({@code x <= centerX}), never highlighting bars
 *       in front of the playhead.</li>
 *   <li><b>Dynamic Bar Concentration at High Zoom</b>: When {@code zoom > 1.0f},
 *       each bucket dynamically subdivides into up to 6 cosine-interpolated
 *       sub-bars so high zoom (e.g. 400%) feels dense, smooth, and responsive.</li>
 *   <li><b>Zoom-Adaptive 1:1 Scrolling</b>: Horizontal scroll sensitivity is
 *       strictly {@code 1f / gapPxLocked(w)}.</li>
 * </ul>
 */
public class WaveformView extends View {

    private static final int COLS = 150;
    /**
     * Constant high-concentration bar count across the screen regardless of
     * zoom level (150 bars on the left of the center playhead, 150 on the right).
     */
    public static final int SCREEN_BARS = 300;
    private static final int MAX_HISTORY = 8192;
    private static final long COARSE_PREVIEW_MIN_FRAMES = 960_000L;
    private static final int PEAKS_SIDECAR_MAGIC = 0x544F504B; // "TOPK"
    private static final int PEAK_CACHE_MAX = 16;
    private static final java.util.LinkedHashMap<String, float[]> PEAK_CACHE =
            new java.util.LinkedHashMap<String, float[]>(PEAK_CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, float[]> eldest) {
                    return size() > PEAK_CACHE_MAX;
                }
            };
    private final java.util.concurrent.atomic.AtomicInteger waveLoadGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleGripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fxEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fxSelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fxSelBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float[] history = new float[COLS];
    /**
     * Dry peaks captured when the FX rack opened, so the effect-applied
     * region can be re-spliced on every parameter change and restored when
     * the rack closes. Null when no FX region is being shown.
     */
    private float[] fxBaseHistory;
    private int fxBaseCount;
    private float fxBaseFramesPerBar = 2400f;
    private boolean fxRegionActive;
    /**
     * The region effects mode owns. It is the frozen apply target, so its bars
     * are highlighted in orange and the selection handles can never move
     * beyond it (a sub-selection is only for learning a profile).
     */
    private float fxRegionStartFrac = 0f;
    private float fxRegionEndFrac = 1f;
    private float fxOrigStartFrac = 0f;
    private float fxOrigEndFrac = 1f;
    private int count;
    private boolean live;
    private float zoom = 1f;
    private boolean followMinZoom = false;

    private final Deque<float[]> undoPeakStack = new ArrayDeque<>();
    private final Deque<Float> undoFramesPerBar = new ArrayDeque<>();
    private final Deque<float[]> redoPeakStack = new ArrayDeque<>();
    private final Deque<Float> redoFramesPerBar = new ArrayDeque<>();
    private float[] savedReplaceTailPeaks = null;
    private float[] stashedParentPeaks = null;
    private int stashedParentSelStart = 0;
    private int stashedParentSelEnd = 0;
    private float stashedParentFramesPerBar = 2400f;
    private final Deque<float[]> stashedParentUndoPeaks = new ArrayDeque<>();
    private final Deque<Float> stashedParentUndoFpb = new ArrayDeque<>();
    private final Deque<float[]> stashedParentRedoPeaks = new ArrayDeque<>();
    private final Deque<Float> stashedParentRedoFpb = new ArrayDeque<>();

    // Smooth morphing state for in-place Replace & frame-locked live speed
    private int lastMorphIdx = -1;
    private boolean replacingInPlace = false;
    private float lastMorphPeak = 0f;
    private float morphAlpha = 0f;
    private float framesPerBar = 2400f; // 2400 frames @ 48 kHz = 50 ms (20 bars/sec)

    // High-resolution sliding live tail buffer (1200 frames = 25 ms / 40 bars/sec,
    // covering the last ~51 seconds at constant crisp resolution even at 400% zoom
    // on multi-hour recordings where overview history[] has halved).
    private static final float LIVE_DETAIL_FPB = 1200f;
    private static final int LIVE_DETAIL_CAP = 2048;
    private float[] liveDetail = new float[LIVE_DETAIL_CAP];
    private int liveDetailCount = 0;
    private long liveDetailBaseFrame = 0L;
    private long liveDetailCurFrame = 0L;

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
        default void onScrollStarted() {}
    }

    private TimelineListener timelineListener;

    private float playheadPos = 0f;
    private int playheadIndex = 0;
    private boolean selectionActive = false;
    private boolean selectionLocked = false;
    private int selStartIdx = 0;
    private int selEndIdx = 0;
    /** Canonical float bucket positions: snap to one visual bar at any zoom. */
    private float selStartPos = 0f;
    private float selEndPos = 0f;

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
        fxEdgePaint.setStyle(Paint.Style.STROKE);
        fxEdgePaint.setColor(0xCCFF9F0A);
        fxEdgePaint.setStrokeWidth(1.5f);
        fxSelFillPaint.setStyle(Paint.Style.FILL);
        fxSelFillPaint.setColor(0x2EFF9F0A);
        fxSelBorderPaint.setStyle(Paint.Style.STROKE);
        fxSelBorderPaint.setColor(0xFFFF9F0A);
        fxSelBorderPaint.setStrokeWidth(2f);
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
        playheadPos = clampPlayheadToFxLocked(clamped * count);
        playheadIndex = Math.round(playheadPos);
        postInvalidateOnAnimation();
    }

    /**
     * Keeps the playhead inside the frozen effects-mode target. Outside
     * effects mode there is no constraint, so ordinary scrubbing is
     * unaffected.
     */
    private float clampPlayheadToFxLocked(float pos) {
        float bounded = Math.max(0f, Math.min(count, pos));
        if (!fxRegionActive || count <= 0) return bounded;
        float lo = fxRegionStartFrac * count;
        float hi = fxRegionEndFrac * count;
        return Math.max(lo, Math.min(hi, bounded));
    }

    public synchronized boolean hasSelection() {
        return selectionActive && count > 0
                && Math.max(selStartPos, selEndPos)
                - Math.min(selStartPos, selEndPos) > 1e-4f;
    }

    public synchronized float selectionStartFraction() {
        if (count <= 0) return 0f;
        float low = Math.min(selStartPos, selEndPos);
        return Math.max(0f, Math.min(1f, low / (float) count));
    }

    public synchronized float selectionEndFraction() {
        if (count <= 0) return 1f;
        float high = Math.max(selStartPos, selEndPos);
        return Math.max(0f, Math.min(1f, high / (float) count));
    }

    /** Buckets covered by one on-screen bar at the current zoom. */
    private float bucketsPerBarLocked() {
        return (COLS / (float) SCREEN_BARS) / Math.max(1e-5f, zoom);
    }

    /** Canonical selection floats -> integer buckets used by edits/tests. */
    private void syncSelectionIntsLocked() {
        float lo = Math.min(selStartPos, selEndPos);
        float hi = Math.max(selStartPos, selEndPos);
        selStartPos = Math.max(0f, Math.min(count, lo));
        selEndPos = Math.max(selStartPos, Math.min(count, hi));
        selStartIdx = Math.round(selStartPos);
        selEndIdx = Math.round(selEndPos);
    }

    private void setSelectionPositionsLocked(float start, float end) {
        selStartPos = start;
        selEndPos = end;
        syncSelectionIntsLocked();
    }

    private static float[] copyPeaksBounded(float[] src, int len, float[] reusable) {
        int n = Math.max(0, Math.min(len, Math.min(src.length, MAX_HISTORY)));
        if (reusable != null && reusable.length == n) {
            System.arraycopy(src, 0, reusable, 0, n);
            return reusable;
        }
        return Arrays.copyOf(src, n);
    }

    /** Snapshots the current in-memory waveform peaks before an edit operation. */
    public synchronized void recordUndoPeaks() {
        if (count <= 0) return;
        try {
            float[] recycled = null;
            while (undoPeakStack.size() >= EditEngine.MAX_UNDO_STEPS) {
                recycled = undoPeakStack.removeLast();
            }
            while (undoFramesPerBar.size() >= EditEngine.MAX_UNDO_STEPS) {
                undoFramesPerBar.removeLast();
            }
            if (recycled == null && !redoPeakStack.isEmpty()) {
                recycled = redoPeakStack.pollFirst();
            }
            redoPeakStack.clear();
            redoFramesPerBar.clear();
            undoPeakStack.addFirst(copyPeaksBounded(history, count, recycled));
            undoFramesPerBar.addFirst(framesPerBar);
        } catch (OutOfMemoryError ignored) {
            if (!undoPeakStack.isEmpty()) undoPeakStack.removeLast();
            if (!undoFramesPerBar.isEmpty()) undoFramesPerBar.removeLast();
        }
    }

    /** Discards the most recently recorded undo peak snapshot when an edit aborts before modifying the waveform. */
    public synchronized void discardTopUndoPeaks() {
        if (!undoPeakStack.isEmpty()) undoPeakStack.pollFirst();
        if (!undoFramesPerBar.isEmpty()) undoFramesPerBar.pollFirst();
    }

    /** Restores the most recent in-memory waveform peak snapshot on Undo and saves current peaks for Redo. */
    public synchronized boolean popUndoPeaks() {
        if (undoPeakStack.isEmpty()) return false;
        waveLoadGen.incrementAndGet();
        clearLiveDetailLocked();
        float[] snap = undoPeakStack.pollFirst();
        Float snapFrames = undoFramesPerBar.pollFirst();
        if (snap == null) return false;
        dropFxBaseLocked();
        if (count > 0) {
            try {
                float[] recycled = null;
                while (redoPeakStack.size() >= EditEngine.MAX_UNDO_STEPS) {
                    recycled = redoPeakStack.removeLast();
                }
                while (redoFramesPerBar.size() >= EditEngine.MAX_UNDO_STEPS) {
                    redoFramesPerBar.removeLast();
                }
                redoPeakStack.addFirst(copyPeaksBounded(history, count, recycled));
                redoFramesPerBar.addFirst(framesPerBar);
            } catch (OutOfMemoryError ignored) {}
        }
        if (snapFrames != null) framesPerBar = snapFrames;
        int snapLen = Math.min(snap.length, MAX_HISTORY);
        if (history.length < snapLen) {
            history = new float[Math.min(MAX_HISTORY, Math.max(COLS, snapLen))];
        }
        Arrays.fill(history, 0f);
        System.arraycopy(snap, 0, history, 0, snapLen);
        count = snapLen;
        playheadPos = count;
        playheadIndex = count;
        selectionActive = false;
        postInvalidateOnAnimation();
        return true;
    }

    /** Restores the most recent in-memory waveform peak snapshot on Redo and saves current peaks for Undo. */
    public synchronized boolean popRedoPeaks() {
        if (redoPeakStack.isEmpty()) return false;
        waveLoadGen.incrementAndGet();
        clearLiveDetailLocked();
        float[] snap = redoPeakStack.pollFirst();
        Float snapFrames = redoFramesPerBar.pollFirst();
        if (snap == null) return false;
        dropFxBaseLocked();
        if (count > 0) {
            try {
                float[] recycled = null;
                while (undoPeakStack.size() >= EditEngine.MAX_UNDO_STEPS) {
                    recycled = undoPeakStack.removeLast();
                }
                while (undoFramesPerBar.size() >= EditEngine.MAX_UNDO_STEPS) {
                    undoFramesPerBar.removeLast();
                }
                undoPeakStack.addFirst(copyPeaksBounded(history, count, recycled));
                undoFramesPerBar.addFirst(framesPerBar);
            } catch (OutOfMemoryError ignored) {}
        }
        if (snapFrames != null) framesPerBar = snapFrames;
        int snapLen = Math.min(snap.length, MAX_HISTORY);
        if (history.length < snapLen) {
            history = new float[Math.min(MAX_HISTORY, Math.max(COLS, snapLen))];
        }
        Arrays.fill(history, 0f);
        System.arraycopy(snap, 0, history, 0, snapLen);
        count = snapLen;
        playheadPos = count;
        playheadIndex = count;
        selectionActive = false;
        postInvalidateOnAnimation();
        return true;
    }

    /** Crops the in-memory waveform bars to the current selection `[selStartIdx, selEndIdx)` in-place. */
    public synchronized void cropToSelectionBuckets() {
        if (!hasSelection()) return;
        waveLoadGen.incrementAndGet();
        clearLiveDetailLocked();
        int s = Math.max(0, Math.min(count - 1, selStartIdx));
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

    /** Deletes the selected buckets `[selStartIdx, selEndIdx)` in-place and shifts the tail left. */
    public synchronized void deleteSelectionBuckets() {
        if (!hasSelection()) return;
        waveLoadGen.incrementAndGet();
        clearLiveDetailLocked();
        int s = Math.max(0, Math.min(count - 1, selStartIdx));
        int e = Math.max(s + 1, Math.min(count, selEndIdx));
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
     * Replaces the waveform bars inside {@code [startFrac, endFrac)} with the
     * peaks of the effect-applied audio, so effects mode shows the processed
     * audio directly instead of a translucent overlay.
     *
     * <p>The dry bars outside the region are untouched and the original peaks
     * are captured on the first call, so every rack change can be spliced on
     * top of the same dry baseline and {@link #clearFxRegionPeaks()} restores
     * the untouched waveform.
     *
     * @param peaks peak per history bucket (zero outside the region), or null
     *              to restore the dry waveform
     */
    public void setFxRegionPeaks(final float[] peaks,
                                 final float startFrac,
                                 final float endFrac) {
        setFxRegionPeaks(peaks, null, startFrac, endFrac);
    }

    public void setFxRegionPeaks(final float[] peaks,
                                 final float[] gainRatios,
                                 final float startFrac,
                                 final float endFrac) {
        synchronized (this) {
            if (count <= 0) {
                restoreWetBarsLocked();
                postInvalidateOnAnimation();
                return;
            }
            if (fxBaseHistory != null && fxBaseCount > 0 && count != fxBaseCount) {
                // Returning from a stretched offline FX render to an in-place FX render:
                // restore the dry bucket count first so [startFrac, endFrac) maps cleanly.
                if (history.length < fxBaseCount) {
                    history = new float[Math.max(COLS, fxBaseCount)];
                }
                Arrays.fill(history, 0f);
                System.arraycopy(fxBaseHistory, 0, history, 0, fxBaseCount);
                count = fxBaseCount;
                framesPerBar = fxBaseFramesPerBar;
            }
            fxOrigStartFrac = clampFrac(startFrac);
            fxOrigEndFrac = Math.max(
                    fxOrigStartFrac + 1f / Math.max(1, count),
                    clampFrac(endFrac));
            fxRegionStartFrac = fxOrigStartFrac;
            fxRegionEndFrac = fxOrigEndFrac;
            fxRegionActive = true;
            if (peaks == null || peaks.length == 0) {
                restoreWetBarsLocked();
                postInvalidateOnAnimation();
                return;
            }
            if (fxBaseHistory == null || fxBaseCount != count) {
                fxBaseHistory = Arrays.copyOf(history, count);
                fxBaseCount = count;
                fxBaseFramesPerBar = framesPerBar;
            } else {
                int n = Math.min(count, fxBaseHistory.length);
                System.arraycopy(fxBaseHistory, 0, history, 0, n);
            }
            int s = Math.max(0, Math.min(count - 1,
                    Math.round(fxRegionStartFrac * count)));
            int e = Math.max(s + 1, Math.min(count,
                    Math.round(fxRegionEndFrac * count)));
            int lim = Math.min(e, peaks.length);
            for (int i = s; i < lim; i++) {
                if (gainRatios != null && i < gainRatios.length
                        && gainRatios[i] >= 0f && fxBaseHistory[i] > 0.005f) {
                    history[i] = Math.max(0f, fxBaseHistory[i] * gainRatios[i]);
                } else {
                    history[i] = Math.max(0f, peaks[i]);
                }
            }
            playheadPos = clampPlayheadToFxLocked(playheadPos);
            playheadIndex = Math.round(playheadPos);
        }
        postInvalidateOnAnimation();
    }

    /**
     * Splices a duration-changing effect's rendered region peaks into the
     * dry baseline in real time, resizing the timeline (`count` and
     * `fxRegionStartFrac..fxRegionEndFrac`) while keeping `fxRegionActive`
     * and the playhead's relative position inside the FX region intact.
     */
    public void setFxRegionStretchedPeaks(final float[] regionPeaks,
                                          final float origStartFrac,
                                          final float origEndFrac,
                                          final long newTotalFrames) {
        setFxRegionStretchedPeaks(regionPeaks, null, origStartFrac,
                origEndFrac, newTotalFrames);
    }

    public void setFxRegionStretchedPeaks(final float[] regionPeaks,
                                          final float[] gainRatios,
                                          final float origStartFrac,
                                          final float origEndFrac,
                                          final long newTotalFrames) {
        synchronized (this) {
            if (count <= 0 && fxBaseCount <= 0) return;
            if (fxBaseHistory == null || fxBaseCount <= 0) {
                fxBaseHistory = Arrays.copyOf(history, count);
                fxBaseCount = count;
                fxBaseFramesPerBar = framesPerBar;
            }
            if (regionPeaks == null || regionPeaks.length == 0) {
                restoreWetBarsLocked();
                postInvalidateOnAnimation();
                return;
            }
            float oldSpan = Math.max(1e-5f,
                    (fxRegionEndFrac - fxRegionStartFrac) * Math.max(1, count));
            float relPlayhead = (playheadPos - fxRegionStartFrac * count) / oldSpan;
            relPlayhead = Math.max(0f, Math.min(1f, relPlayhead));

            boolean hadSubSel = selectionActive && count > 0;
            float relSelStart = 0f;
            float relSelEnd = 1f;
            if (hadSubSel) {
                float lo = Math.min(selStartPos, selEndPos);
                float hi = Math.max(selStartPos, selEndPos);
                relSelStart = Math.max(0f, Math.min(1f,
                        (lo - fxRegionStartFrac * count) / oldSpan));
                relSelEnd = Math.max(relSelStart, Math.min(1f,
                        (hi - fxRegionStartFrac * count) / oldSpan));
            }

            fxOrigStartFrac = clampFrac(origStartFrac);
            fxOrigEndFrac = Math.max(
                    fxOrigStartFrac + 1f / Math.max(1, fxBaseCount),
                    clampFrac(origEndFrac));
            int s = Math.max(0, Math.min(fxBaseCount - 1,
                    Math.round(fxOrigStartFrac * fxBaseCount)));
            int e = Math.max(s + 1, Math.min(fxBaseCount,
                    Math.round(fxOrigEndFrac * fxBaseCount)));
            int prefixLen = s;
            int suffixLen = Math.max(0, fxBaseCount - e);
            float[] boundedRegion = regionPeaks.length > MAX_HISTORY
                    ? resamplePeaksMax(regionPeaks, MAX_HISTORY) : regionPeaks;
            float[] boundedRatios = (gainRatios != null && gainRatios.length > MAX_HISTORY)
                    ? resamplePeaksMax(gainRatios, MAX_HISTORY) : gainRatios;
            int rLen = boundedRegion.length;
            int rawTotal = prefixLen + rLen + suffixLen;

            float[] combined = new float[Math.max(1, rawTotal)];
            if (prefixLen > 0) {
                System.arraycopy(fxBaseHistory, 0, combined, 0, prefixLen);
            }
            float baseRegionMean = 0f;
            int baseSpan = Math.max(1, e - s);
            for (int k = s; k < e && k < fxBaseHistory.length; k++) {
                baseRegionMean += fxBaseHistory[k];
            }
            baseRegionMean /= baseSpan;
            for (int i = 0; i < rLen; i++) {
                if (boundedRatios != null && i < boundedRatios.length
                        && boundedRatios[i] >= 0f) {
                    int srcIdx = s + Math.min(baseSpan - 1,
                            (int) ((long) i * baseSpan / Math.max(1, rLen)));
                    if (rLen == baseSpan && srcIdx < fxBaseHistory.length
                            && fxBaseHistory[srcIdx] > 0.005f) {
                        combined[prefixLen + i] = Math.max(0f,
                                fxBaseHistory[srcIdx] * boundedRatios[i]);
                    } else if (baseRegionMean > 0.005f) {
                        combined[prefixLen + i] = Math.max(0f,
                                baseRegionMean * boundedRatios[i]);
                    } else {
                        combined[prefixLen + i] = Math.max(0f, boundedRegion[i]);
                    }
                } else {
                    combined[prefixLen + i] = Math.max(0f, boundedRegion[i]);
                }
            }
            if (suffixLen > 0) {
                System.arraycopy(fxBaseHistory, e, combined,
                        prefixLen + rLen, suffixLen);
            }

            int newCount = rawTotal;
            int newStartBucket = prefixLen;
            int newEndBucket = prefixLen + rLen;
            if (rawTotal > MAX_HISTORY) {
                combined = resamplePeaksMax(combined, MAX_HISTORY);
                newCount = MAX_HISTORY;
                newStartBucket = Math.round(
                        (float) prefixLen * MAX_HISTORY / rawTotal);
                newEndBucket = Math.max(newStartBucket + 1,
                        Math.round((float) (prefixLen + rLen)
                                * MAX_HISTORY / rawTotal));
            }
            if (history.length < Math.max(COLS, newCount)) {
                history = new float[Math.max(COLS, newCount)];
            }
            Arrays.fill(history, 0f);
            System.arraycopy(combined, 0, history, 0, newCount);
            count = newCount;
            if (newTotalFrames > 0) {
                framesPerBar = Math.max(240f,
                        newTotalFrames / (float) Math.max(1, newCount));
            }

            fxRegionStartFrac = clampFrac(
                    newStartBucket / (float) Math.max(1, newCount));
            fxRegionEndFrac = Math.max(
                    fxRegionStartFrac + 1f / Math.max(1, newCount),
                    clampFrac(newEndBucket / (float) Math.max(1, newCount)));
            fxRegionActive = true;

            float newRegionStartPos = fxRegionStartFrac * count;
            float newRegionSpan = Math.max(1f,
                    (fxRegionEndFrac - fxRegionStartFrac) * count);
            playheadPos = clampPlayheadToFxLocked(
                    newRegionStartPos + relPlayhead * newRegionSpan);
            playheadIndex = Math.round(playheadPos);

            if (hadSubSel) {
                float ns = newRegionStartPos + relSelStart * newRegionSpan;
                float ne = newRegionStartPos + relSelEnd * newRegionSpan;
                if (ne - ns < 1f) {
                    ne = Math.min(fxRegionEndFrac * count, ns + 1f);
                }
                setSelectionPositionsLocked(ns, ne);
            }
            if (followMinZoom) {
                zoom = minimumZoom();
            } else {
                zoom = Math.max(minimumZoom(),
                        Math.min(zoom, ZOOM_STEPS[ZOOM_STEPS.length - 1]));
            }
        }
        postInvalidateOnAnimation();
    }

    /**
     * Marks the frozen effects-mode region before the first wet render
     * arrives. The region stays visible (and the handles stay clamped to it)
     * for as long as the rack is open, even when nothing is selected.
     */
    public void setFxRegionBounds(float startFrac, float endFrac) {
        synchronized (this) {
            float s = clampFrac(startFrac);
            fxRegionStartFrac = s;
            fxRegionEndFrac = Math.max(s + 1f / Math.max(1, count),
                    clampFrac(endFrac));
            if (fxBaseHistory == null) {
                fxOrigStartFrac = fxRegionStartFrac;
                fxOrigEndFrac = fxRegionEndFrac;
            }
            fxRegionActive = true;
            // Entering effects mode snaps an outside playhead onto the
            // nearest edge of the target instead of leaving it stranded.
            playheadPos = clampPlayheadToFxLocked(playheadPos);
            playheadIndex = Math.round(playheadPos);
        }
        postInvalidateOnAnimation();
    }

    public synchronized float fxRegionStartFraction() {
        return fxRegionStartFrac;
    }

    public synchronized float fxRegionEndFraction() {
        return fxRegionEndFrac;
    }

    /** Restores the dry bars but keeps the effects-mode region highlight. */
    public void clearFxRegionPeaks() {
        synchronized (this) {
            restoreWetBarsLocked();
        }
        postInvalidateOnAnimation();
    }

    /** Leaves effects mode: restores the dry bars and removes the highlight. */
    public void clearFxRegion() {
        synchronized (this) {
            restoreWetBarsLocked();
            fxRegionActive = false;
        }
        postInvalidateOnAnimation();
    }

    private static float clampFrac(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** True when the waveform is currently showing an effect-applied region. */
    public boolean hasFxRegion() {
        synchronized (this) {
            return fxRegionActive;
        }
    }

    private void restoreWetBarsLocked() {
        if (fxBaseHistory != null && fxBaseCount > 0) {
            float oldSpan = Math.max(1e-5f,
                    (fxRegionEndFrac - fxRegionStartFrac) * Math.max(1, count));
            float relPlayhead = (playheadPos - fxRegionStartFrac * count) / oldSpan;
            relPlayhead = Math.max(0f, Math.min(1f, relPlayhead));

            if (history.length < Math.max(COLS, fxBaseCount)) {
                history = new float[Math.max(COLS, fxBaseCount)];
            }
            Arrays.fill(history, 0f);
            System.arraycopy(fxBaseHistory, 0, history, 0, fxBaseCount);
            count = fxBaseCount;
            framesPerBar = fxBaseFramesPerBar;
            fxRegionStartFrac = fxOrigStartFrac;
            fxRegionEndFrac = Math.max(
                    fxOrigStartFrac + 1f / Math.max(1, count),
                    fxOrigEndFrac);
            float newStartPos = fxRegionStartFrac * count;
            float newSpan = Math.max(1f,
                    (fxRegionEndFrac - fxRegionStartFrac) * count);
            playheadPos = clampPlayheadToFxLocked(
                    newStartPos + relPlayhead * newSpan);
            playheadIndex = Math.round(playheadPos);
            if (selectionActive) {
                setSelectionPositionsLocked(newStartPos, newStartPos + newSpan);
            }
            if (followMinZoom) {
                zoom = minimumZoom();
            } else {
                zoom = Math.max(minimumZoom(),
                        Math.min(zoom, ZOOM_STEPS[ZOOM_STEPS.length - 1]));
            }
        }
        fxBaseHistory = null;
        fxBaseCount = 0;
    }

    /** Discards the dry baseline because the underlying peaks are replaced. */
    private void dropFxBaseLocked() {
        fxBaseHistory = null;
        fxBaseCount = 0;
        fxRegionActive = false;
    }

    /** Number of waveform buckets currently held, for FX preview rendering. */
    public int getHistoryCount() {
        synchronized (this) {
            return count;
        }
    }

    /** Dry baseline bucket count before any length-changing FX stretch. */
    public int getBaseHistoryCount() {
        synchronized (this) {
            return (fxBaseHistory != null && fxBaseCount > 0)
                    ? fxBaseCount : count;
        }
    }

    /**
     * Updates the in-memory waveform bars across `[startFrac, endFrac]` from
     * `raw` after `Apply FX` so the user visually sees the effect on the bars.
     */
    public void reloadSelectionPeaksFromWav(final java.io.File raw,
                                            final float startFrac,
                                            final float endFrac) {
        if (raw == null || !raw.exists()) return;
        final int myGen = waveLoadGen.incrementAndGet();
        new Thread(() -> {
            try {
                int curCount;
                synchronized (WaveformView.this) {
                    curCount = count;
                }
                if (curCount <= 0) return;
                int s = Math.max(0, Math.min(curCount - 1,
                        Math.round(startFrac * curCount)));
                int e = Math.max(s + 1, Math.min(curCount,
                        Math.round(endFrac * curCount)));
                float[] rangePeaks = loadPeaksForRange(raw, curCount, s, e, waveLoadGen, myGen);
                if (rangePeaks == null || rangePeaks.length != (e - s)) return;
                synchronized (WaveformView.this) {
                    if (count == curCount && waveLoadGen.get() == myGen) {
                        dropFxBaseLocked();
                        System.arraycopy(rangePeaks, 0, history, s, e - s);
                    }
                }
                postInvalidateOnAnimation();
            } catch (Exception ignored) {}
        }, "takeone-fx-peak-sync").start();
    }

    /**
     * Saves the parent waveform stashed by {@link #enterSubEditorMode()} so a
     * rotation inside Select & Replace does not lose the take behind it.
     */
    public synchronized void saveSubEditorState(Bundle out) {
        if (out == null || stashedParentPeaks == null) return;
        out.putBoolean("wfSubActive", true);
        out.putFloatArray("wfSubPeaks", stashedParentPeaks);
        out.putInt("wfSubStart", stashedParentSelStart);
        out.putInt("wfSubEnd", stashedParentSelEnd);
        out.putFloat("wfSubFpb", stashedParentFramesPerBar);
    }

    /** Restores the stashed parent saved by {@link #saveSubEditorState}. */
    public synchronized void restoreSubEditorState(Bundle in) {
        if (in == null || !in.getBoolean("wfSubActive", false)) return;
        float[] peaks = in.getFloatArray("wfSubPeaks");
        if (peaks == null || peaks.length == 0) return;
        stashedParentPeaks = peaks;
        stashedParentSelStart = Math.max(0,
                Math.min(peaks.length, in.getInt("wfSubStart", 0)));
        stashedParentSelEnd = Math.max(stashedParentSelStart,
                Math.min(peaks.length, in.getInt("wfSubEnd", peaks.length)));
        stashedParentFramesPerBar = in.getFloat("wfSubFpb", 2400f);
    }

    public synchronized void enterSubEditorMode() {
        dropFxBaseLocked();
        stashedParentPeaks = count > 0
                ? Arrays.copyOf(history, Math.min(count, MAX_HISTORY))
                : new float[0];
        stashedParentSelStart = Math.max(0, Math.min(stashedParentPeaks.length, selStartIdx));
        stashedParentSelEnd = Math.max(stashedParentSelStart,
                Math.min(stashedParentPeaks.length, selEndIdx));
        stashedParentFramesPerBar = framesPerBar;
        stashedParentUndoPeaks.clear();
        stashedParentUndoPeaks.addAll(undoPeakStack);
        stashedParentUndoFpb.clear();
        stashedParentUndoFpb.addAll(undoFramesPerBar);
        stashedParentRedoPeaks.clear();
        stashedParentRedoPeaks.addAll(redoPeakStack);
        stashedParentRedoFpb.clear();
        stashedParentRedoFpb.addAll(redoFramesPerBar);
        undoPeakStack.clear();
        undoFramesPerBar.clear();
        redoPeakStack.clear();
        redoFramesPerBar.clear();
        Arrays.fill(history, 0f);
        count = 0;
        playheadPos = 0f;
        playheadIndex = 0;
        selectionActive = false;
        selStartIdx = 0;
        selEndIdx = 0;
        followMinZoom = false;
        zoom = 1f;
        postInvalidateOnAnimation();
    }

    public synchronized void exitSubEditorMode(boolean commitReplacement) {
        if (stashedParentPeaks == null) return;
        float[] parent = stashedParentPeaks;
        int parentLen = Math.min(parent.length, MAX_HISTORY);
        int s = Math.max(0, Math.min(parentLen, stashedParentSelStart));
        int e = Math.max(s, Math.min(parentLen, stashedParentSelEnd));
        float parentFpb = Math.max(240f, stashedParentFramesPerBar);
        stashedParentPeaks = null;

        undoPeakStack.clear();
        undoPeakStack.addAll(stashedParentUndoPeaks);
        stashedParentUndoPeaks.clear();
        undoFramesPerBar.clear();
        undoFramesPerBar.addAll(stashedParentUndoFpb);
        stashedParentUndoFpb.clear();
        redoPeakStack.clear();
        redoPeakStack.addAll(stashedParentRedoPeaks);
        stashedParentRedoPeaks.clear();
        redoFramesPerBar.clear();
        redoFramesPerBar.addAll(stashedParentRedoFpb);
        stashedParentRedoFpb.clear();

        if (!commitReplacement) {
            if (history.length < parentLen) {
                history = new float[Math.min(MAX_HISTORY, Math.max(COLS, parentLen))];
            }
            Arrays.fill(history, 0f);
            System.arraycopy(parent, 0, history, 0, parentLen);
            count = parentLen;
            framesPerBar = parentFpb;
            playheadPos = s;
            playheadIndex = s;
            selectionActive = false;
            postInvalidateOnAnimation();
            return;
        }

        // Push the pre-replacement parent waveform onto the parent Undo stack:
        if (parentLen > 0) {
            try {
                while (undoPeakStack.size() >= EditEngine.MAX_UNDO_STEPS) {
                    undoPeakStack.removeLast();
                }
                while (undoFramesPerBar.size() >= EditEngine.MAX_UNDO_STEPS) {
                    undoFramesPerBar.removeLast();
                }
                redoPeakStack.clear();
                redoFramesPerBar.clear();
                undoPeakStack.addFirst(parent.length == parentLen
                        ? parent : Arrays.copyOf(parent, parentLen));
                undoFramesPerBar.addFirst(parentFpb);
            } catch (OutOfMemoryError ignored) {}
        }

        int repLen = Math.min(count, MAX_HISTORY);
        float repDensity = Math.max(240f, framesPerBar);
        long rawRepOut = Math.max(0L, Math.round(
                (double) repLen * repDensity / parentFpb));
        int tailLen = Math.max(0, parentLen - e);
        // Pre-compact parent bucket scale if a long replacement into a short
        // parent take would exceed 2 * MAX_HISTORY bars, preventing huge allocations.
        while (s + rawRepOut + tailLen > MAX_HISTORY * 2L && parentFpb < 1e9f) {
            parentFpb *= 2f;
            int halfParent = Math.max(1, (parentLen + 1) / 2);
            float[] compacted = new float[halfParent];
            for (int i = 0; i < halfParent; i++) {
                int i0 = i * 2;
                int i1 = Math.min(parentLen - 1, i0 + 1);
                compacted[i] = Math.max(parent[i0], parent[i1]);
            }
            parent = compacted;
            parentLen = halfParent;
            s = Math.min(parentLen, s / 2);
            e = Math.max(s, Math.min(parentLen, (e + 1) / 2));
            tailLen = Math.max(0, parentLen - e);
            rawRepOut = Math.max(0L, Math.round(
                    (double) repLen * repDensity / parentFpb));
        }
        stashedParentFramesPerBar = parentFpb;
        int repOutLen = (int) Math.min((long) MAX_HISTORY * 2L, rawRepOut);
        float[] rep = resamplePeaksMax(
                Arrays.copyOf(history, repLen), repOutLen);
        int total = s + rep.length + tailLen;
        float[] combined = new float[Math.max(total, 1)];
        if (s > 0) System.arraycopy(parent, 0, combined, 0, s);
        if (rep.length > 0) {
            System.arraycopy(rep, 0, combined, s, rep.length);
            if (s > 0) {
                combined[s] = 0.35f * parent[s - 1] + 0.65f * combined[s];
            }
        }
        int afterRep = s + rep.length;
        if (tailLen > 0) {
            System.arraycopy(parent, e, combined, afterRep, tailLen);
            if (afterRep > 0) {
                combined[afterRep] = 0.65f * combined[afterRep]
                        + 0.35f * combined[afterRep - 1];
            }
        }
        framesPerBar = stashedParentFramesPerBar;
        int newTotal = total;
        while (newTotal > MAX_HISTORY) {
            int half = Math.max(1, newTotal / 2);
            for (int i = 0; i < half; i++) {
                combined[i] = Math.max(combined[i * 2],
                        combined[Math.min(newTotal - 1, i * 2 + 1)]);
            }
            newTotal = half;
            framesPerBar *= 2f;
        }
        if (history.length < Math.max(COLS, newTotal)) {
            history = new float[Math.max(COLS, newTotal)];
        }
        Arrays.fill(history, 0f);
        if (newTotal > 0) System.arraycopy(combined, 0, history, 0, newTotal);
        count = Math.min(newTotal, history.length);
        int shownPlayhead = total > 0
                ? Math.round(afterRep * (float) count / total) : count;
        playheadPos = Math.max(0, Math.min(count, shownPlayhead));
        playheadIndex = Math.round(playheadPos);
        selectionActive = false;
        postInvalidateOnAnimation();
    }

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

    public void selectRange(float startFrac, float endFrac) {
        boolean active;
        float sf;
        float ef;
        synchronized (this) {
            if (selectionLocked || count <= 1) return;
            int minS = 0;
            int maxE = count;
            if (fxRegionActive) {
                minS = Math.max(0, Math.min(count - 1,
                        Math.round(fxRegionStartFrac * count)));
                maxE = Math.max(minS + 1, Math.min(count,
                        Math.round(fxRegionEndFrac * count)));
            }
            int s = Math.max(minS, Math.min(maxE,
                    Math.round(clampFrac(startFrac) * count)));
            int e = Math.max(minS, Math.min(maxE,
                    Math.round(clampFrac(endFrac) * count)));
            if (e < s) {
                int tmp = s;
                s = e;
                e = tmp;
            }
            if (e - s < 1) {
                if (e < maxE) e = Math.min(maxE, s + 1);
                else s = Math.max(minS, e - 1);
            }
            selStartPos = s;
            selEndPos = e;
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

    /** Locks or unlocks range selection (e.g. while Replace mode is armed). */
    public synchronized void setSelectionLocked(boolean locked) {
        this.selectionLocked = locked;
        if (locked && selectionActive) {
            selectionActive = false;
            invalidate();
        }
    }

    public synchronized boolean isSelectionLocked() {
        return selectionLocked;
    }

    public synchronized void toggleSelectionAtPlayhead() {
        if (selectionLocked || count <= 1) return;
        selectionActive = !selectionActive;
        if (selectionActive && fxRegionActive) {
            // Effects mode: the only legal range is inside the frozen target.
            selStartIdx = Math.max(0, Math.min(count - 1,
                    Math.round(fxRegionStartFrac * count)));
            selEndIdx = Math.max(selStartIdx + 1, Math.min(count,
                    Math.round(fxRegionEndFrac * count)));
        } else if (selectionActive) {
            int visibleCols = Math.max(8, Math.round(COLS / Math.max(0.25f, zoom)));
            int span = Math.max(2, Math.min(Math.max(2, count * 2 / 5), visibleCols / 3));
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
            if (selEndIdx <= selStartIdx) {
                selStartIdx = 0;
                selEndIdx = count;
            }
        }
        if (selectionActive) setSelectionPositionsLocked(selStartIdx, selEndIdx);
        else { selStartPos = 0f; selEndPos = 0f; }
        invalidate();
        if (timelineListener != null) {
            timelineListener.onSelectionChanged(selectionActive,
                    selectionStartFraction(), selectionEndFraction());
        }
    }

    public synchronized void clearSelection() {
        selectionActive = false;
        selStartPos = 0f;
        selEndPos = 0f;
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
        float clampedV = Math.max(0f, Math.min(1f, v));
        if (appending || count == 0) {
            push(clampedV);
            playheadPos = count;
            playheadIndex = count;
            lastMorphIdx = -1;
            morphAlpha = 0f;
        } else {
            float exactPos = Math.max(0f, Math.min(count, cursorFrac * count));
            int idx = Math.min(count - 1, Math.max(0, (int) Math.floor(exactPos)));
            if (idx != lastMorphIdx) {
                lastMorphIdx = idx;
                morphAlpha = 0.62f;
            } else {
                morphAlpha = Math.min(1.0f, morphAlpha + 0.20f);
            }
            history[idx] = history[idx] * (1.0f - morphAlpha) + clampedV * morphAlpha;
            playheadPos = Math.min(count, idx + 1f);
            playheadIndex = Math.round(playheadPos);
            postInvalidateOnAnimation();
        }
    }

    /**
     * Frame-accurate live waveform updater used during recording and Replace.
     *
     * <p>Locks the bucket advance rate to {@code framesPerBar} (2,400 frames =
     * 50 ms per bar, or {@code replaceEndFrame / count} when replacing an
     * existing take) so that when a Replace pass reaches {@code replaceEndFrame}
     * and seamlessly transitions into appending, the waveform continues at the
     * exact same speed without any abrupt jump or shape distortion on Pause!
     */

    public synchronized void setReplacingInPlace(boolean replacing) {
        if (this.replacingInPlace != replacing) {
            this.replacingInPlace = replacing;
            if (!replacing) {
                lastMorphIdx = -1;
                lastMorphPeak = 0f;
                morphAlpha = 0f;
            }
            postInvalidateOnAnimation();
        }
    }

    public synchronized void updateLiveFromFrames(float v, long curFrames,
                                                  long replaceEndFrame) {
        float clampedV = Math.max(0f, Math.min(1f, v));
        if (curFrames <= 0 && count == 0) return;
        if (framesPerBar <= 0f) framesPerBar = 2400f;

        if (replaceEndFrame > 0 && curFrames < replaceEndFrame && count > 0) {
            // In-place Replace before the original end of the take:
            replacingInPlace = true;
            float exactPos = Math.max(0f,
                    Math.min(count, (curFrames / (float) replaceEndFrame) * count));
            int idx = Math.min(count - 1, Math.max(0, (int) Math.floor(exactPos)));
            if (idx != lastMorphIdx) {
                if (lastMorphIdx >= 0 && idx > lastMorphIdx + 1) {
                    for (int skip = lastMorphIdx + 1; skip < idx && skip < count; skip++) {
                        history[skip] = clampedV;
                    }
                }
                lastMorphIdx = idx;
                lastMorphPeak = clampedV;
                morphAlpha = 0.62f;
            } else {
                if (clampedV > lastMorphPeak) {
                    lastMorphPeak = clampedV;
                }
                morphAlpha = Math.min(1.0f, morphAlpha + 0.20f);
            }
            history[idx] = history[idx] * (1.0f - morphAlpha)
                    + lastMorphPeak * morphAlpha;
            playheadPos = exactPos;
            playheadIndex = Math.round(playheadPos);
            postInvalidateOnAnimation();
            return;
        }
        replacingInPlace = false;
        // Update constant-resolution (25 ms / 40 Hz) live detail tail so 200%-400%
        // zoom stays crisp and smooth even on long takes after overview halving.
        updateLiveDetailLocked(clampedV, curFrames);

        // Appending (either a fresh recording or Replace after passing replaceEndFrame):
        // Advance strictly by audio frame position (curFrames / framesPerBar)
        // so the speed is 100% identical to pre-end Replace and independent of UI tick rate!
        float targetBuckets = curFrames / framesPerBar;
        int neededCount = Math.max(1, (int) Math.ceil(targetBuckets));
        if (count == 0) {
            push(clampedV);
        } else if (neededCount > count) {
            int toAdd = Math.min(8, neededCount - count);
            for (int i = 0; i < toAdd; i++) {
                push(clampedV);
            }
        } else {
            int last = count - 1;
            if (clampedV > history[last]) {
                history[last] = clampedV;
            }
        }
        playheadPos = Math.min(count, Math.max(0f, targetBuckets));
        playheadIndex = Math.round(playheadPos);
        lastMorphIdx = -1;
        morphAlpha = 0f;
        postInvalidateOnAnimation();
    }

    /**
     * Adopts the live peaks recorded continuously by {@link RecordingEngine}
     * (including while backgrounded) in <0.1 ms with zero disk I/O.
     */
    public synchronized boolean adoptLivePeaksSnapshot(RecordingEngine.LivePeaksSnapshot snap) {
        if (snap == null || snap.overview == null || snap.overviewCount <= 0) return false;
        waveLoadGen.incrementAndGet();
        dropFxBaseLocked();
        int n = Math.min(snap.overviewCount, Math.min(snap.overview.length, MAX_HISTORY));
        if (history.length < n) {
            int cap = history.length;
            while (cap < n && cap < MAX_HISTORY) cap = Math.min(MAX_HISTORY, cap * 2);
            history = new float[Math.max(cap, n)];
        }
        Arrays.fill(history, 0f);
        System.arraycopy(snap.overview, 0, history, 0, n);
        count = n;
        framesPerBar = snap.framesPerBar > 0f ? snap.framesPerBar : 2400f;
        playheadPos = Math.min(count, Math.max(0f, snap.curFrame / framesPerBar));
        playheadIndex = Math.round(playheadPos);
        if (snap.detail != null && snap.detailCount > 0) {
            int dn = Math.min(snap.detailCount, Math.min(snap.detail.length, LIVE_DETAIL_CAP));
            System.arraycopy(snap.detail, 0, liveDetail, 0, dn);
            liveDetailCount = dn;
            liveDetailBaseFrame = snap.detailBaseFrame;
            liveDetailCurFrame = snap.curFrame;
        } else {
            clearLiveDetailLocked();
        }
        zoom = Math.max(minimumZoom(), Math.min(zoom, ZOOM_STEPS[ZOOM_STEPS.length - 1]));
        postInvalidate();
        return true;
    }

    public synchronized float[] copyHistorySnapshot() {
        if (count <= 0) return new float[0];
        return Arrays.copyOf(history, count);
    }

    public synchronized float getFramesPerBar() {
        return framesPerBar;
    }

    public static java.io.File peaksFileFor(java.io.File wav) {
        if (wav == null) return null;
        return new java.io.File(wav.getAbsolutePath() + ".peaks");
    }

    public static void deletePeaksSidecar(java.io.File wav) {
        java.io.File f = peaksFileFor(wav);
        if (f != null && f.exists()) {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static void writePeaksSidecar(java.io.File wav, float[] peaks, int count,
                                         float fpb, long totalFrames) {
        if (wav == null || !wav.exists() || peaks == null || count <= 0) return;
        java.io.File out = peaksFileFor(wav);
        if (out == null) return;
        int n = Math.min(count, Math.min(peaks.length, MAX_HISTORY));
        java.io.File tmp = new java.io.File(out.getAbsolutePath() + ".tmp");
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp)))) {
            dos.writeInt(PEAKS_SIDECAR_MAGIC);
            dos.writeLong(wav.length());
            dos.writeLong(wav.lastModified());
            dos.writeLong(totalFrames);
            dos.writeFloat(fpb > 0f ? fpb : 2400f);
            dos.writeInt(n);
            for (int i = 0; i < n; i++) {
                dos.writeFloat(peaks[i]);
            }
            dos.flush();
        } catch (Exception ignored) {
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (out.exists()) {
            // noinspection ResultOfMethodCallIgnored
            out.delete();
        }
        if (!tmp.renameTo(out)) {
            // noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    public static void copyPeaksSidecar(java.io.File srcWav, java.io.File dstWav) {
        if (srcWav == null || dstWav == null || !dstWav.exists()) return;
        java.io.File srcSidecar = peaksFileFor(srcWav);
        if (srcSidecar == null || !srcSidecar.exists()) return;
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(srcSidecar)))) {
            int magic = dis.readInt();
            if (magic != PEAKS_SIDECAR_MAGIC) return;
            long srcSize = dis.readLong();
            dis.readLong(); // old mtime
            long totalFrames = dis.readLong();
            float fpb = dis.readFloat();
            int n = dis.readInt();
            if (n <= 0 || n > MAX_HISTORY) return;
            if (srcSize != dstWav.length()) return;
            float[] peaks = new float[n];
            for (int i = 0; i < n; i++) peaks[i] = dis.readFloat();
            writePeaksSidecar(dstWav, peaks, n, fpb, totalFrames);
        } catch (Exception ignored) {}
    }

    private static float[] readPeaksSidecar(java.io.File wav, int minBuckets) {
        if (wav == null || !wav.exists()) return null;
        java.io.File f = peaksFileFor(wav);
        if (f == null || !f.exists()) return null;
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            if (dis.readInt() != PEAKS_SIDECAR_MAGIC) return null;
            long storedSize = dis.readLong();
            long storedMtime = dis.readLong();
            dis.readLong(); // totalFrames
            dis.readFloat(); // fpb
            int n = dis.readInt();
            if (storedSize != wav.length()) return null;
            if (Math.abs(storedMtime - wav.lastModified()) > 3000L) return null;
            if (n <= 0 || n > MAX_HISTORY) return null;
            if (minBuckets > 0 && n < Math.min(minBuckets, 8)) return null;
            float[] peaks = new float[n];
            for (int i = 0; i < n; i++) peaks[i] = dis.readFloat();
            return peaks;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearLiveDetailLocked() {
        liveDetailCount = 0;
        liveDetailBaseFrame = 0L;
        liveDetailCurFrame = 0L;
    }

    private void updateLiveDetailLocked(float clampedV, long curFrames) {
        if (curFrames <= 0L) return;
        if (liveDetailCount == 0 || curFrames < liveDetailBaseFrame) {
            long startSlot = Math.max(0L, (long) Math.floor(curFrames / LIVE_DETAIL_FPB));
            liveDetailBaseFrame = Math.round(startSlot * LIVE_DETAIL_FPB);
            liveDetail[0] = clampedV;
            liveDetailCount = 1;
            liveDetailCurFrame = curFrames;
            return;
        }
        long relFrames = curFrames - liveDetailBaseFrame;
        int targetIdx = (int) (relFrames / LIVE_DETAIL_FPB);
        if (targetIdx < 0) targetIdx = 0;
        // If we jumped far ahead (e.g. returned from background), reset window
        // so backfillLiveGapFromWav can populate real peaks cleanly.
        if (targetIdx - liveDetailCount > 160) {
            long startSlot = Math.max(0L, (long) Math.floor(curFrames / LIVE_DETAIL_FPB));
            liveDetailBaseFrame = Math.round(startSlot * LIVE_DETAIL_FPB);
            liveDetail[0] = clampedV;
            liveDetailCount = 1;
            liveDetailCurFrame = curFrames;
            return;
        }
        while (targetIdx >= LIVE_DETAIL_CAP) {
            int drop = 512;
            int remain = Math.max(0, liveDetailCount - drop);
            if (remain > 0) {
                System.arraycopy(liveDetail, drop, liveDetail, 0, remain);
            }
            liveDetailCount = remain;
            liveDetailBaseFrame += Math.round(drop * LIVE_DETAIL_FPB);
            targetIdx -= drop;
        }
        if (targetIdx >= liveDetailCount) {
            for (int i = liveDetailCount; i <= targetIdx; i++) {
                liveDetail[i] = clampedV;
            }
            liveDetailCount = targetIdx + 1;
        } else if (clampedV > liveDetail[targetIdx]) {
            liveDetail[targetIdx] = clampedV;
        }
        liveDetailCurFrame = curFrames;
    }

    /**
     * Backfills waveform buckets missed while the app was backgrounded during
     * live recording by reading ONLY the missed frame range from {@code raw}.
     * Prevents flat silence plateaus and sudden catch-up snaps on resume.
     */
    public void backfillLiveGapFromWav(final java.io.File raw, final long targetFrame) {
        if (raw == null || !raw.exists() || targetFrame <= 0L) return;
        final long gapStartFrame;
        final int startBucket;
        final int endBucket;
        final float fpbSnapshot;
        final int myGen;
        synchronized (this) {
            if (replacingInPlace || count <= 0) return;
            if (framesPerBar <= 0f) framesPerBar = 2400f;
            gapStartFrame = Math.round(count * framesPerBar);
            if (targetFrame - gapStartFrame <= (long) (framesPerBar * 4f)) {
                return;
            }
            int targetCount = Math.max(count + 1, (int) Math.ceil(targetFrame / framesPerBar));
            while (targetCount > MAX_HISTORY) {
                halveHistory();
                targetCount = Math.max(count + 1, (int) Math.ceil(targetFrame / framesPerBar));
            }
            startBucket = count;
            if (history.length < targetCount) {
                int nextCap = history.length;
                while (nextCap < targetCount && nextCap < MAX_HISTORY) {
                    nextCap = Math.min(MAX_HISTORY, nextCap * 2);
                }
                float[] bigger = new float[Math.max(nextCap, targetCount)];
                System.arraycopy(history, 0, bigger, 0, count);
                history = bigger;
            }
            endBucket = Math.min(history.length, targetCount);
            fpbSnapshot = framesPerBar;
            // Advance count & playhead cleanly right away so live frames don't smear silence
            for (int i = count; i < endBucket; i++) {
                history[i] = 0f;
            }
            count = endBucket;
            playheadPos = Math.min(count, targetFrame / framesPerBar);
            playheadIndex = Math.round(playheadPos);
            myGen = waveLoadGen.get();
        }
        postInvalidateOnAnimation();

        new Thread(() -> {
            try (RandomAccessFile r = new RandomAccessFile(raw, "r")) {
                Dsp.WavHeader h = Dsp.parseWavHeader(r);
                int ch = Math.max(1, h.channels);
                long onDisk = Math.max(0L, r.length() - h.dataStart);
                long fileFrames = onDisk / (4L * ch);
                long readEnd = Math.min(fileFrames, targetFrame);
                long readStart = Math.max(0L, Math.min(gapStartFrame, readEnd));
                if (readEnd <= readStart) return;

                int fillBuckets = endBucket - startBucket;
                float[] gapPeaks = new float[Math.max(1, fillBuckets)];

                // Also populate the trailing high-res liveDetail window (last ~20s)
                long detailSpanFrames = Math.round((LIVE_DETAIL_CAP / 2) * LIVE_DETAIL_FPB);
                long detailStart = Math.max(readStart, readEnd - detailSpanFrames);
                int detailSlots = (int) Math.max(1,
                        Math.min(LIVE_DETAIL_CAP - 64,
                                Math.ceil((readEnd - detailStart) / LIVE_DETAIL_FPB)));
                float[] detailPeaks = new float[detailSlots];

                int framesPerRead = Math.max(1, 262144 / (4 * ch));
                byte[] in = new byte[framesPerRead * ch * 4];
                float[] fBuf = new float[framesPerRead * ch];
                java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                r.seek(h.dataStart + readStart * ch * 4L);
                long frame = readStart;
                while (frame < readEnd) {
                    if (waveLoadGen.get() != myGen) return;
                    int want = (int) Math.min(framesPerRead, readEnd - frame);
                    int samples = want * ch;
                    r.readFully(in, 0, samples * 4);
                    fb.clear();
                    fb.get(fBuf, 0, samples);
                    for (int f = 0; f < want; f++) {
                        float pk = 0f;
                        int base = f * ch;
                        for (int c = 0; c < ch; c++) {
                            float a = Math.abs(fBuf[base + c]);
                            if (a > pk) pk = a;
                        }
                        long absF = frame + f;
                        int gb = (int) ((absF - readStart) / fpbSnapshot);
                        if (gb >= 0 && gb < gapPeaks.length && pk > gapPeaks[gb]) {
                            gapPeaks[gb] = pk;
                        }
                        if (absF >= detailStart) {
                            int ds = (int) ((absF - detailStart) / LIVE_DETAIL_FPB);
                            if (ds >= 0 && ds < detailSlots && pk > detailPeaks[ds]) {
                                detailPeaks[ds] = pk;
                            }
                        }
                    }
                    frame += want;
                }

                synchronized (WaveformView.this) {
                    if (waveLoadGen.get() != myGen) return;
                    if (Math.abs(framesPerBar - fpbSnapshot) < 1f && count >= endBucket) {
                        int n = Math.min(gapPeaks.length, endBucket - startBucket);
                        if (n > 0 && startBucket + n <= history.length) {
                            System.arraycopy(gapPeaks, 0, history, startBucket, n);
                        }
                    }
                    if (detailSlots > 0 && liveDetailCurFrame <= readEnd + Math.round(LIVE_DETAIL_FPB * 80)) {
                        // Prepend backfilled detail peaks cleanly behind any newly pushed live frames
                        int liveExtra = (liveDetailCount > 0 && liveDetailBaseFrame >= readEnd)
                                ? Math.min(liveDetailCount, LIVE_DETAIL_CAP - detailSlots)
                                : 0;
                        if (liveExtra > 0) {
                            System.arraycopy(liveDetail, 0, liveDetail, detailSlots, liveExtra);
                        }
                        System.arraycopy(detailPeaks, 0, liveDetail, 0, detailSlots);
                        liveDetailBaseFrame = detailStart;
                        liveDetailCount = detailSlots + liveExtra;
                        if (liveDetailCurFrame < readEnd) liveDetailCurFrame = readEnd;
                    }
                }
                postInvalidateOnAnimation();
            } catch (Exception ignored) {}
        }, "takeone-wave-backfill").start();
    }

    /** Max-pools peaks so a splice matches the parent's bucket density. */
    private static float[] resamplePeaksMax(float[] src, int dst) {
        if (src == null || src.length == 0 || dst <= 0) return new float[0];
        if (dst == src.length) return src;
        float[] out = new float[dst];
        for (int i = 0; i < dst; i++) {
            int a = (int) ((long) i * src.length / dst);
            int b = (int) (((long) (i + 1) * src.length + dst - 1) / dst);
            if (b <= a) b = a + 1;
            if (b > src.length) b = src.length;
            float max = 0f;
            for (int j = a; j < b; j++) {
                if (src[j] > max) max = src[j];
            }
            out[i] = max;
        }
        return out;
    }

    /** Calibrates the bucket frame density when a session is loaded or Replace begins. */
    public synchronized void syncFramesPerBar(long totalFrames) {
        if (count > 0 && totalFrames > 0) {
            framesPerBar = Math.max(240f, totalFrames / (float) count);
        } else {
            framesPerBar = 2400f;
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
            followMinZoom = (nz <= lo + 0.001f);
            changed = Math.abs(nz - zoom) > 0.00005f;
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
        } else {
            lastMorphIdx = -1;
            lastMorphPeak = 0f;
            morphAlpha = 0f;
            replacingInPlace = false;
        }
        invalidate();
    }

    public void setMicLevel(float level) {
        push(Math.max(0f, Math.min(1f, level)));
    }

    public synchronized void push(float v) {
        float prevMin = minimumZoom();
        boolean wasAtMin = followMinZoom
                || (count >= COLS / 2 && zoom <= prevMin + 0.008f);
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
        framesPerBar *= 2f;
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
        waveLoadGen.incrementAndGet();
        clearLiveDetailLocked();
        Arrays.fill(history, 0f);
        count = 0;
        playheadPos = 0f;
        playheadIndex = 0;
        selectionActive = false;
        selStartIdx = 0;
        selEndIdx = 0;
        followMinZoom = false;
        savedReplaceTailPeaks = null;
        lastMorphIdx = -1;
        lastMorphPeak = 0f;
        morphAlpha = 0f;
        replacingInPlace = false;
        framesPerBar = 2400f;
        invalidate();
    }

    public void loadFromFloatWav(java.io.File raw) {
        loadFromFloatWav(raw, null);
    }

    /**
     * Synchronously reloads the full waveform at 20 bars/sec (2,400 frames per
     * bar) from {@code raw}, replacing the existing history so duration-changing
     * offline effects (Change Speed and Pitch, Change Tempo, Truncate Silence,
     * Timeline Repeat, Paulstretch) immediately reflect their new segment and
     * take duration on the timeline.
     */
    private static String peakCacheKey(java.io.File wav, int buckets) {
        return wav.getAbsolutePath() + "|" + wav.length() + "|" + wav.lastModified() + "|" + buckets;
    }

    private static float[] getCachedPeaks(java.io.File wav, int buckets) {
        String key = peakCacheKey(wav, buckets);
        synchronized (PEAK_CACHE) {
            float[] hit = PEAK_CACHE.get(key);
            return hit != null ? hit.clone() : null;
        }
    }

    private static void putCachedPeaks(java.io.File wav, int buckets, float[] peaks) {
        if (peaks == null || peaks.length == 0) return;
        String key = peakCacheKey(wav, buckets);
        synchronized (PEAK_CACHE) {
            PEAK_CACHE.put(key, peaks.clone());
        }
    }

    public void reloadFullFromWavSync(java.io.File raw) {
        if (raw == null || !raw.exists()) return;
        waveLoadGen.incrementAndGet();
        try {
            long payload = RecordingEngine.payloadBytesOf(raw);
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            long totalFrames = payload / 4 / Math.max(1, info.channels);
            int buckets20Hz = (int) Math.max(2,
                    Math.min(MAX_HISTORY, (totalFrames + 1200) / 2400));
            float[] peaks = loadPeaksExactBuckets(raw, buckets20Hz);
            if (peaks.length == 0) return;
            applyFullPeaksImmediate(peaks, totalFrames);
        } catch (Exception ignored) {}
    }

    void applyFullPeaksImmediate(float[] peaks, long totalFrames) {
        if (peaks == null || peaks.length == 0) return;
        synchronized (WaveformView.this) {
            clearLiveDetailLocked();
            dropFxBaseLocked();
            if (history.length < peaks.length) {
                history = new float[Math.min(MAX_HISTORY,
                        Math.max(COLS, peaks.length))];
            }
            Arrays.fill(history, 0f);
            count = Math.min(peaks.length, history.length);
            System.arraycopy(peaks, 0, history, 0, count);
            framesPerBar = totalFrames > 0
                    ? Math.max(240f, totalFrames / (float) count) : 2400f;
            playheadIndex = count;
            playheadPos = count;
            selectionActive = false;
            zoom = Math.max(minimumZoom(), Math.min(zoom,
                    ZOOM_STEPS[ZOOM_STEPS.length - 1]));
        }
        postInvalidate();
    }

    static float[] computeFullPeaksForWav(java.io.File raw) {
        if (raw == null || !raw.exists()) return new float[0];
        try {
            long payload = RecordingEngine.payloadBytesOf(raw);
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            long totalFrames = payload / 4 / Math.max(1, info.channels);
            int buckets20Hz = (int) Math.max(2,
                    Math.min(MAX_HISTORY, (totalFrames + 1200) / 2400));
            return loadPeaksExactBuckets(raw, buckets20Hz);
        } catch (Exception e) {
            return new float[0];
        }
    }

    public void loadFromFloatWav(java.io.File raw, Runnable onLoaded) {
        if (raw == null) return;
        final int myGen = waveLoadGen.incrementAndGet();
        new Thread(() -> {
            try {
                long payload = RecordingEngine.payloadBytesOf(raw);
                FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
                long totalFrames = payload / 4 / Math.max(1, info.channels);
                int buckets20Hz = (int) Math.max(8,
                        Math.min(MAX_HISTORY, (totalFrames + 1200) / 2400));
                float[] sidecar = readPeaksSidecar(raw, buckets20Hz);
                if (sidecar != null && sidecar.length > 0) {
                    putCachedPeaks(raw, buckets20Hz, sidecar);
                    if (waveLoadGen.get() == myGen) {
                        applyLoadedPeaks(sidecar, totalFrames, onLoaded);
                    }
                    return;
                }
                float[] cached = getCachedPeaks(raw, buckets20Hz);
                if (cached != null && cached.length > 0) {
                    if (waveLoadGen.get() == myGen) {
                        applyLoadedPeaks(cached, totalFrames, onLoaded);
                    }
                    return;
                }
                if (totalFrames > COARSE_PREVIEW_MIN_FRAMES) {
                    float[] coarse = loadPeaksCoarseFast(raw, buckets20Hz);
                    if (coarse.length > 0 && waveLoadGen.get() == myGen) {
                        applyLoadedPeaks(coarse, totalFrames, onLoaded);
                    }
                    float[] exact = loadPeaksExactBuckets(raw, buckets20Hz, waveLoadGen, myGen);
                    if (exact != null && exact.length > 0 && waveLoadGen.get() == myGen) {
                        refineLoadedPeaksInPlace(exact, myGen);
                    }
                    return;
                }
                float[] peaks = loadPeaksExactBuckets(raw, buckets20Hz, waveLoadGen, myGen);
                if (peaks == null || peaks.length == 0 || waveLoadGen.get() != myGen) return;
                applyLoadedPeaks(peaks, totalFrames, onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load").start();
    }

    public void loadFromFloatWavTwentyHz(java.io.File raw, Runnable onLoaded) {
        if (raw == null) return;
        final int myGen = waveLoadGen.incrementAndGet();
        new Thread(() -> {
            try {
                long payload = RecordingEngine.payloadBytesOf(raw);
                FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
                long totalFrames = payload / 4 / Math.max(1, info.channels);
                int buckets20Hz = (int) Math.max(8,
                        Math.min(MAX_HISTORY, (totalFrames + 1200) / 2400));
                float[] sidecar = readPeaksSidecar(raw, buckets20Hz);
                if (sidecar != null && sidecar.length > 0) {
                    putCachedPeaks(raw, buckets20Hz, sidecar);
                    if (waveLoadGen.get() == myGen) {
                        applyTwentyHzPeaks(sidecar, totalFrames, onLoaded);
                    }
                    return;
                }
                float[] cached = getCachedPeaks(raw, buckets20Hz);
                if (cached != null && cached.length > 0) {
                    if (waveLoadGen.get() == myGen) {
                        applyTwentyHzPeaks(cached, totalFrames, onLoaded);
                    }
                    return;
                }
                if (totalFrames > COARSE_PREVIEW_MIN_FRAMES) {
                    float[] coarse = loadPeaksCoarseFast(raw, buckets20Hz);
                    if (coarse.length > 0 && waveLoadGen.get() == myGen) {
                        applyTwentyHzPeaks(coarse, totalFrames, onLoaded);
                    }
                    float[] exact = loadPeaksExactBuckets(raw, buckets20Hz, waveLoadGen, myGen);
                    if (exact != null && exact.length > 0 && waveLoadGen.get() == myGen) {
                        refineLoadedPeaksInPlace(exact, myGen);
                    }
                    return;
                }
                float[] peaks = loadPeaksExactBuckets(raw, buckets20Hz, waveLoadGen, myGen);
                if (peaks == null || peaks.length == 0 || waveLoadGen.get() != myGen) return;
                applyTwentyHzPeaks(peaks, totalFrames, onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load-20hz").start();
    }

    private void applyTwentyHzPeaks(float[] peaks, long totalFrames, Runnable onLoaded) {
        synchronized (WaveformView.this) {
            if (history.length < peaks.length) {
                history = new float[Math.min(MAX_HISTORY,
                        Math.max(COLS, peaks.length))];
            }
            Arrays.fill(history, 0f);
            count = Math.min(peaks.length, history.length);
            System.arraycopy(peaks, 0, history, 0, count);
            framesPerBar = totalFrames > 0
                    ? Math.max(240f, totalFrames / (float) Math.max(1, count))
                    : 2400f;
            playheadIndex = count;
            playheadPos = count;
            zoom = Math.max(minimumZoom(), Math.min(zoom,
                    ZOOM_STEPS[ZOOM_STEPS.length - 1]));
        }
        postInvalidate();
        if (onLoaded != null) post(onLoaded);
    }

    private void refineLoadedPeaksInPlace(float[] exact, int expectedGen) {
        synchronized (WaveformView.this) {
            if (waveLoadGen.get() != expectedGen) return;
            int n = Math.min(exact.length, Math.min(count, history.length));
            if (n <= 0) return;
            if (fxBaseHistory != null && fxBaseCount >= n) {
                System.arraycopy(exact, 0, fxBaseHistory, 0, n);
            } else {
                System.arraycopy(exact, 0, history, 0, n);
            }
        }
        postInvalidate();
    }

    private static float[] loadPeaksCoarseFast(java.io.File wav, int buckets)
            throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            int ch = Math.max(1, h.channels);
            long totalFrames = dataBytes / 4L / ch;
            if (totalFrames <= 0) return new float[0];
            int bCount = (int) Math.max(1, Math.min(buckets, totalFrames));
            float[] peaks = new float[bCount];
            int probeCount = Math.min(bCount, 512);
            int windowFrames = 64;
            byte[] in = new byte[windowFrames * ch * 4];
            float[] fBuf = new float[windowFrames * ch];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            long frameBytes = ch * 4L;
            for (int p = 0; p < probeCount; p++) {
                int b0 = (int) ((long) p * bCount / probeCount);
                int b1 = (int) ((long) (p + 1) * bCount / probeCount);
                if (b1 <= b0) b1 = Math.min(bCount, b0 + 1);
                long startFrame = (long) p * totalFrames / probeCount;
                int want = (int) Math.min(windowFrames, totalFrames - startFrame);
                if (want <= 0) continue;
                r.seek(h.dataStart + startFrame * frameBytes);
                r.readFully(in, 0, want * ch * 4);
                fb.clear();
                fb.get(fBuf, 0, want * ch);
                float maxP = 0f;
                int samples = want * ch;
                for (int i = 0; i < samples; i++) {
                    float a = Math.abs(fBuf[i]);
                    if (a > maxP) maxP = a;
                }
                for (int b = b0; b < b1; b++) {
                    peaks[b] = maxP;
                }
            }
            return peaks;
        }
    }

    private static float[] loadPeaksExactBuckets(java.io.File wav, int buckets)
            throws Exception {
        return loadPeaksExactBuckets(wav, buckets, null, 0);
    }

    private static float[] loadPeaksExactBuckets(
            java.io.File wav, int buckets,
            java.util.concurrent.atomic.AtomicInteger genRef, int expectedGen)
            throws Exception {
        float[] cached = getCachedPeaks(wav, buckets);
        if (cached != null) return cached;
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            int ch = Math.max(1, h.channels);
            long totalFrames = dataBytes / 4L / ch;
            if (totalFrames <= 0) return new float[0];
            int bCount = (int) Math.max(1, Math.min(buckets, totalFrames));
            float[] peaks = new float[bCount];
            int framesPerRead = Math.max(1, 262144 / (4 * ch));
            byte[] in = new byte[framesPerRead * ch * 4];
            float[] fBuf = new float[framesPerRead * ch];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            r.seek(h.dataStart);
            long frame = 0L;
            int curBucket = 0;
            long nextBucketFrame = ((long) (curBucket + 1) * totalFrames + bCount - 1) / bCount;
            while (frame < totalFrames) {
                if (genRef != null && genRef.get() != expectedGen) return null;
                int want = (int) Math.min(framesPerRead, totalFrames - frame);
                int samples = want * ch;
                r.readFully(in, 0, samples * 4);
                fb.clear();
                fb.get(fBuf, 0, samples);
                if (ch == 1) {
                    int f = 0;
                    while (f < want) {
                        while (frame + f >= nextBucketFrame && curBucket + 1 < bCount) {
                            curBucket++;
                            nextBucketFrame = ((long) (curBucket + 1) * totalFrames + bCount - 1) / bCount;
                        }
                        int runEnd = (int) Math.min(want, Math.max(f + 1L, nextBucketFrame - frame));
                        float p = peaks[curBucket];
                        for (int i = f; i < runEnd; i++) {
                            float a = Math.abs(fBuf[i]);
                            if (a > p) p = a;
                        }
                        peaks[curBucket] = p;
                        f = runEnd;
                    }
                } else {
                    int f = 0;
                    while (f < want) {
                        while (frame + f >= nextBucketFrame && curBucket + 1 < bCount) {
                            curBucket++;
                            nextBucketFrame = ((long) (curBucket + 1) * totalFrames + bCount - 1) / bCount;
                        }
                        int runEnd = (int) Math.min(want, Math.max(f + 1L, nextBucketFrame - frame));
                        float p = peaks[curBucket];
                        int sIdx = f * ch;
                        int sEnd = runEnd * ch;
                        for (int i = sIdx; i < sEnd; i++) {
                            float a = Math.abs(fBuf[i]);
                            if (a > p) p = a;
                        }
                        peaks[curBucket] = p;
                        f = runEnd;
                    }
                }
                frame += want;
            }
            putCachedPeaks(wav, buckets, peaks);
            writePeaksSidecar(wav, peaks, peaks.length,
                    totalFrames > 0 ? Math.max(240f, totalFrames / (float) Math.max(1, peaks.length)) : 2400f,
                    totalFrames);
            return peaks;
        }
    }

    private static float[] loadPeaksForRange(
            java.io.File wav, int totalBuckets, int startBucket, int endBucket,
            java.util.concurrent.atomic.AtomicInteger genRef, int expectedGen)
            throws Exception {
        int rangeBuckets = endBucket - startBucket;
        if (rangeBuckets <= 0 || totalBuckets <= 0) return new float[0];
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = (h.dataBytes <= 0 || h.dataBytes > onDisk)
                    ? onDisk : h.dataBytes;
            int ch = Math.max(1, h.channels);
            long totalFrames = dataBytes / 4L / ch;
            if (totalFrames <= 0) return new float[0];
            long startFrame = (long) startBucket * totalFrames / totalBuckets;
            long endFrame = Math.min(totalFrames,
                    ((long) endBucket * totalFrames + totalBuckets - 1) / totalBuckets);
            if (endFrame <= startFrame) return new float[rangeBuckets];
            float[] peaks = new float[rangeBuckets];
            int framesPerRead = Math.max(1, 262144 / (4 * ch));
            byte[] in = new byte[framesPerRead * ch * 4];
            float[] fBuf = new float[framesPerRead * ch];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            r.seek(h.dataStart + startFrame * ch * 4L);
            long frame = startFrame;
            int curBucket = startBucket;
            long nextBucketFrame = ((long) (curBucket + 1) * totalFrames + totalBuckets - 1) / totalBuckets;
            while (frame < endFrame) {
                if (genRef != null && genRef.get() != expectedGen) return null;
                int want = (int) Math.min(framesPerRead, endFrame - frame);
                int samples = want * ch;
                r.readFully(in, 0, samples * 4);
                fb.clear();
                fb.get(fBuf, 0, samples);
                int f = 0;
                while (f < want) {
                    while (frame + f >= nextBucketFrame && curBucket + 1 < endBucket) {
                        curBucket++;
                        nextBucketFrame = ((long) (curBucket + 1) * totalFrames + totalBuckets - 1) / totalBuckets;
                    }
                    int runEnd = (int) Math.min(want, Math.max(f + 1L, nextBucketFrame - frame));
                    int outIdx = curBucket - startBucket;
                    float p = peaks[outIdx];
                    int sIdx = f * ch;
                    int sEnd = runEnd * ch;
                    for (int i = sIdx; i < sEnd; i++) {
                        float a = Math.abs(fBuf[i]);
                        if (a > p) p = a;
                    }
                    peaks[outIdx] = p;
                    f = runEnd;
                }
                frame += want;
            }
            return peaks;
        }
    }

    private void applyLoadedPeaks(float[] peaks, long totalFrames, Runnable onLoaded) {
        synchronized (WaveformView.this) {
            dropFxBaseLocked();
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
            // Scale framesPerBar to actual take duration when capped at MAX_HISTORY:
            framesPerBar = totalFrames > 0
                    ? Math.max(240f, totalFrames / (float) Math.max(1, n))
                    : 2400f;
            zoom = Math.max(minimumZoom(), Math.min(zoom,
                    ZOOM_STEPS[ZOOM_STEPS.length - 1]));
        }
        postInvalidate();
        if (onLoaded != null) post(onLoaded);
    }

    // ---- Red Waveform Center-Playhead Coordinate Math ----

    private float gapPxLocked(int w) {
        // Never clamp at 0.25px! At w=750, COLS=150, w/COLS = 5px, so 5% zoom
        // (zoom=0.05) was 0.25px and any zoom below 5% was blocked by the 0.25f
        // floor, pushing bars off the left of the screen instead of fitting
        // the whole take to the left of the center playhead!
        return Math.max(1e-5f, (w / (float) COLS) * zoom);
    }

    /**
     * Number of waveform bars across the full screen width — constant at
     * {@link #SCREEN_BARS} regardless of zoom level so the screen always has
     * the same dense concentration of bars.
     */
    static int screenBarCountForZoom(float z) {
        return SCREEN_BARS;
    }

    /**
     * Number of visual sub-bars rendered per history bucket at the current
     * zoom level. At zoom <= 1.0, 1 bar per bucket; as zoom increases up to
     * 4.0 (400%), bar concentration dynamically scales up to 4 sub-bars per
     * bucket so high zoom never looks sparse or hollow!
     */
    static int subBarsForZoom(float z) {
        if (z <= 1.15f) return 1;
        return Math.min(6, Math.max(2, Math.round(z)));
    }

    /**
     * Screen X coordinate of bucket boundary `idx` (0 .. count).
     * Since bucket `i` spans `[i, i + 1]` and its center is at `i + 0.5f`,
     * boundary `idx` is at `centerX - (playheadPos - idx) * gap` (and the center
     * of bucket `i` is at `centerX - (playheadPos - (i + 0.5f)) * gap`).
     */
    private float screenXForBucketLocked(float idx, int w) {
        if (w <= 0) return 0f;
        float centerX = w / 2f;
        float gap = gapPxLocked(w);
        return centerX - (playheadPos - idx) * gap;
    }

    private int bucketForScreenXLocked(float sx, int w) {
        return Math.round(bucketPosForScreenXLocked(sx, w));
    }

    /** Float bucket position, snapped to the current visual bar grid. */
    private float bucketPosForScreenXLocked(float sx, int w) {
        if (count <= 0 || w <= 0) return 0f;
        float centerX = w / 2f;
        float gap = gapPxLocked(w);
        float raw = playheadPos - (centerX - sx) / gap;
        float bpb = bucketsPerBarLocked();
        float snapped = Math.round(raw / bpb) * bpb;
        return Math.max(0f, Math.min((float) count, snapped));
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
                        float xStart = screenXForBucketLocked(selStartPos, w);
                        float xEnd = screenXForBucketLocked(selEndPos, w);
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
                        if (timelineListener != null) {
                            timelineListener.onScrollStarted();
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
                            playheadPos = clampPlayheadToFxLocked(
                                    playheadPos - (edge - x) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        } else if (x > w - edge && playheadPos < n) {
                            playheadPos = clampPlayheadToFxLocked(
                                    playheadPos + (x - (w - edge)) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        }
                        float pos = bucketPosForScreenXLocked(x, w);
                        float minS = 0f;
                        if (fxRegionActive) {
                            minS = Math.max(0f, Math.min(count - 1f,
                                    Math.round(fxRegionStartFrac * count)));
                        }
                        float maxStart = Math.max(minS,
                                selEndPos - bucketsPerBarLocked());
                        setSelectionPositionsLocked(
                                Math.max(minS, Math.min(maxStart, pos)),
                                selEndPos);
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
                            playheadPos = clampPlayheadToFxLocked(
                                    playheadPos - (edge - x) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        } else if (x > w - edge && playheadPos < n) {
                            playheadPos = clampPlayheadToFxLocked(
                                    playheadPos + (x - (w - edge)) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        }
                        float pos = bucketPosForScreenXLocked(x, w);
                        float maxE = n;
                        if (fxRegionActive) {
                            maxE = Math.max(1f, Math.min((float) n,
                                    Math.round(fxRegionEndFrac * count)));
                        }
                        float minEnd = Math.min(maxE,
                                selStartPos + bucketsPerBarLocked());
                        setSelectionPositionsLocked(selStartPos,
                                Math.min(maxE, Math.max(minEnd, pos)));
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
                        // Scrolling must keep working at every zoom, including the
                        // fit zoom where the whole take is already visible: it is
                        // how the user moves the playhead for editing.
                        float barsPerPx = 1f / gap;
                        playheadPos = clampPlayheadToFxLocked(
                                playheadPos - stepDx * barsPerPx);
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
                            playheadPos = clampPlayheadToFxLocked(tappedIdx);
                            playheadIndex = Math.round(playheadPos);
                            tappedEditor = true;
                            if (!selectionLocked && now - lastTapUpMs < 280L && count > 1) {
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
                            timelineListener.onScrollStarted();
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
        boolean repInPlace;
        float headPos;
        int pIdx;
        boolean selOn;
        int sIdx;
        int eIdx;
        float sPos;
        float ePos;
        float gap;
        float curZoom;
        boolean fxOn;
        float fxS;
        float fxE;
        final float[] hist;
        final float[] det;
        final int detCount;
        final long detBaseFrame;
        final float curFpb;
        synchronized (this) {
            hist = history;
            n = Math.min(count, hist != null ? hist.length : 0);
            det = liveDetail;
            repInPlace = replacingInPlace;
            detCount = (!repInPlace && savedReplaceTailPeaks == null && !fxRegionActive)
                    ? Math.min(liveDetailCount, det != null ? det.length : 0) : 0;
            detBaseFrame = liveDetailBaseFrame;
            curFpb = framesPerBar > 0f ? framesPerBar : 2400f;
            isLive = live;
            pIdx = playheadIndex;
            headPos = (playheadPos >= 0f && playheadPos <= n)
                    ? playheadPos
                    : ((pIdx >= 0 && pIdx <= n) ? pIdx : n);
            sPos = Math.min(selStartPos, selEndPos);
            ePos = Math.max(selStartPos, selEndPos);
            selOn = !isLive && selectionActive && ePos - sPos > 1e-4f
                    && count > 0;
            sIdx = selStartIdx;
            eIdx = selEndIdx;
            gap = gapPxLocked(w);
            curZoom = zoom;
            fxOn = fxRegionActive && n > 0;
            fxS = fxRegionStartFrac;
            fxE = fxRegionEndFrac;
        }
        final long detEndFrame = detBaseFrame + Math.round(detCount * LIVE_DETAIL_FPB);

        // Constant high concentration of bars on screen regardless of zoom:
        // Exactly SCREEN_BARS (300) bars fit across the full width `w`
        // (150 bars on the left of the center playhead, 150 on the right).
        // Each visual bar spans `bucketsPerBar` history buckets so its screen
        // pitch is always `bucketsPerBar * gap = w / SCREEN_BARS` at ANY zoom!
        final float barSpacingPx = w / (float) SCREEN_BARS;
        final float bucketsPerBar = (COLS / (float) SCREEN_BARS)
                / Math.max(1e-5f, curZoom);
        final float barW = Math.max(1.1f,
                Math.min(2.6f * density, barSpacingPx * 0.56f));
        barPaint.setStrokeWidth(barW);

        float xSelStart = centerX - (headPos - sPos) * gap;
        float xSelEnd = centerX - (headPos - ePos) * gap;

        // 1) Selection highlight box on the red waveform when paused
        if (selOn) {
            float left = Math.max(0f, xSelStart);
            float right = Math.min(w, xSelEnd);
            if (right > left) {
                float boxTop = Math.max(2f, centerY - maxH - 5f);
                float boxBot = Math.min(h - 2f, centerY + maxH + 5f);
                rect.set(left, boxTop, right, boxBot);
                canvas.drawRoundRect(rect, 6f * density, 6f * density,
                        selFillPaint);
            }
        }

        // 2) Draw Red Waveform Bars (constant screen concentration across all zoom levels)
        if (n > 0) {
            long kMin = (long) Math.floor((headPos - (centerX + barW) / gap) / bucketsPerBar) - 1L;
            long kMax = (long) Math.ceil((headPos + (w - centerX + barW) / gap) / bucketsPerBar) + 1L;
            long kStart = Math.max(0L, kMin);
            long kEnd = Math.min((long) Math.ceil(n / (double) bucketsPerBar), kMax);
            if (isLive && !repInPlace && savedReplaceTailPeaks == null) {
                // Live append capture only has buckets up to the interpolated
                // head; in-place Replace keeps the future bars visible.
                kEnd = Math.min(kEnd,
                        (long) Math.floor(headPos / bucketsPerBar));
            }

            for (long k = kStart; k <= kEnd; k++) {
                float bStart = k * bucketsPerBar;
                float bEnd = (k + 1) * bucketsPerBar;
                if (bStart >= n || bEnd <= 0f) continue;

                float barCenterBucket = (k + 0.5f) * bucketsPerBar;
                if (barCenterBucket > n) continue;

                float x = centerX - (headPos - barCenterBucket) * gap;
                if (x < -barW || x > w + barW) continue;

                float v = 0f;
                if (bucketsPerBar >= 1.0f) {
                    int i0 = Math.max(0, (int) Math.floor(bStart));
                    int i1 = Math.min(n - 1, (int) Math.ceil(bEnd) - 1);
                    if (i1 < i0) i1 = i0;
                    for (int idx = i0; idx <= i1; idx++) {
                        float hv = hist[idx];
                        if (hv > v) v = hv;
                    }
                } else if (detCount > 1 && curFpb > LIVE_DETAIL_FPB * 1.1f) {
                    float barFrame = barCenterBucket * curFpb;
                    if (barFrame >= detBaseFrame && barFrame < detEndFrame) {
                        float dPos = (barFrame - detBaseFrame) / LIVE_DETAIL_FPB;
                        int d0 = Math.max(0, Math.min(detCount - 1, (int) Math.floor(dPos)));
                        int d1 = Math.min(detCount - 1, d0 + 1);
                        float frac = dPos - d0;
                        v = det[d0] + (det[d1] - det[d0]) * frac;
                    } else {
                        int idx = Math.max(0, Math.min(n - 1, (int) Math.floor(barCenterBucket)));
                        float fracInBucket = barCenterBucket - idx;
                        float v0 = hist[idx];
                        float vPrev = (idx - 1 >= 0) ? hist[idx - 1] : v0;
                        float vNext = (idx + 1 < n) ? hist[idx + 1] : v0;
                        float targetNeighbor = (fracInBucket < 0.5f) ? vPrev : vNext;
                        float t = Math.abs(fracInBucket - 0.5f) * 2f;
                        float weight = t * t * (3f - 2f * t) * 0.42f;
                        v = v0 + (targetNeighbor - v0) * weight;
                    }
                } else {
                    int idx = Math.max(0, Math.min(n - 1, (int) Math.floor(barCenterBucket)));
                    float fracInBucket = barCenterBucket - idx;
                    float v0 = hist[idx];
                    float vPrev = (idx - 1 >= 0) ? hist[idx - 1] : v0;
                    float vNext = (idx + 1 < n) ? hist[idx + 1] : v0;
                    float targetNeighbor = (fracInBucket < 0.5f) ? vPrev : vNext;
                    float t = Math.abs(fracInBucket - 0.5f) * 2f;
                    float weight = t * t * (3f - 2f * t) * 0.42f;
                    v = v0 + (targetNeighbor - v0) * weight;
                }

                float dispV = v <= 0.85f ? v
                        : 0.85f + 0.15f * (float) Math.tanh((v - 0.85f) / 0.35f);
                float bh = Math.max(2f, dispV * maxH * 2f);

                boolean fxInside = fxOn && barCenterBucket >= fxS * n
                        && barCenterBucket <= fxE * n;
                if (fxOn && !fxInside) {
                    // Dim the untouched part of the take so the frozen FX
                    // target is the only fully bright region (no overlay box).
                    barPaint.setColor(0xFFFF3B30);
                    barPaint.setAlpha(0x47);
                } else if (selOn && barCenterBucket >= sPos
                        && barCenterBucket <= ePos) {
                    barPaint.setColor(0xFFFF453A);
                    barPaint.setAlpha(255);
                } else if (x <= centerX) {
                    // HALF-BAR HIGHLIGHT RULE:
                    // A bar only highlights when its center `x` has reached or
                    // crossed to the left of `centerX` (headPos >= barCenterBucket).
                    float vis = Math.min(1f, Math.max(0f, x / Math.max(1f, centerX)));
                    float age = 0.35f + 0.65f * vis;
                    barPaint.setColor(0xFFFF3B30);
                    barPaint.setAlpha(Math.round(255 * age));
                } else {
                    // Bar is in front of (to the right of) the playhead:
                    // keep dimmed translucent red until playhead crosses half of it.
                    float rightVis = Math.min(1f, Math.max(0f, (w - x) / Math.max(1f, centerX)));
                    barPaint.setColor(0xFFFF3B30);
                    barPaint.setAlpha(Math.round(65 + 45 * rightVis));
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

            Paint borderPaint = selBorderPaint;
            if (xSelStart >= -tabW && xSelStart <= w + tabW) {
                float cx = Math.max(tabW * 0.5f, Math.min(w - tabW * 0.5f, xSelStart));
                canvas.drawLine(cx, boxTop, cx, boxBot, borderPaint);
                rect.set(cx - tabW * 0.5f, boxTop, cx + tabW * 0.5f, boxTop + tabH);
                canvas.drawRoundRect(rect, r, r, handlePaint);
                canvas.drawLine(cx, boxTop + 4f * density,
                        cx, boxTop + tabH - 4f * density, handleGripPaint);
            }
            if (xSelEnd >= -tabW && xSelEnd <= w + tabW) {
                float cx = Math.max(tabW * 0.5f, Math.min(w - tabW * 0.5f, xSelEnd));
                canvas.drawLine(cx, boxTop, cx, boxBot, borderPaint);
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

