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
    private final Deque<Float> undoFramesPerBar = new ArrayDeque<>();
    private float[] savedReplaceTailPeaks = null;
    private float[] stashedParentPeaks = null;
    private int stashedParentSelStart = 0;
    private int stashedParentSelEnd = 0;
    private float stashedParentFramesPerBar = 2400f;

    // Smooth morphing state for in-place Replace & frame-locked live speed
    private int lastMorphIdx = -1;
    private float morphAlpha = 0f;
    private float framesPerBar = 2400f; // 2400 frames @ 48 kHz = 50 ms (20 bars/sec)

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
        undoFramesPerBar.addFirst(framesPerBar);
    }

    /** Restores the most recent in-memory waveform peak snapshot on Undo. */
    public synchronized boolean popUndoPeaks() {
        if (undoPeakStack.isEmpty()) return false;
        float[] snap = undoPeakStack.pollFirst();
        Float snapFrames = undoFramesPerBar.pollFirst();
        if (snap == null) return false;
        if (snapFrames != null) framesPerBar = snapFrames;
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

    /** Deletes the selected buckets `[selStartIdx, selEndIdx)` in-place and shifts the tail left. */
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
     * Updates the in-memory waveform bars across `[startFrac, endFrac]` from
     * `raw` after `Apply FX` so the user visually sees the effect on the bars.
     */
    public void reloadSelectionPeaksFromWav(final java.io.File raw,
                                            final float startFrac,
                                            final float endFrac) {
        if (raw == null || !raw.exists()) return;
        new Thread(() -> {
            try {
                int curCount;
                synchronized (WaveformView.this) {
                    curCount = count;
                }
                if (curCount <= 0) return;
                float[] updated = loadPeaksExactBuckets(raw, curCount);
                if (updated.length != curCount) return;
                synchronized (WaveformView.this) {
                    if (count == curCount) {
                        int s = Math.max(0, Math.min(count - 1,
                                Math.round(startFrac * count)));
                        int e = Math.max(s + 1, Math.min(count,
                                Math.round(endFrac * count)));
                        for (int i = s; i < e; i++) {
                            history[i] = updated[i];
                        }
                    }
                }
                postInvalidateOnAnimation();
            } catch (Exception ignored) {}
        }, "takeone-fx-peak-sync").start();
    }

    public synchronized void enterSubEditorMode() {
        stashedParentPeaks = count > 0 ? Arrays.copyOf(history, count) : new float[0];
        stashedParentSelStart = Math.max(0, Math.min(count, selStartIdx));
        stashedParentSelEnd = Math.max(stashedParentSelStart, Math.min(count, selEndIdx));
        stashedParentFramesPerBar = framesPerBar;
        undoPeakStack.clear();
        undoFramesPerBar.clear();
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
        int s = Math.max(0, Math.min(parent.length, stashedParentSelStart));
        int e = Math.max(s, Math.min(parent.length, stashedParentSelEnd));
        stashedParentPeaks = null;
        undoPeakStack.clear();
        undoFramesPerBar.clear();

        if (!commitReplacement) {
            if (history.length < parent.length) {
                history = new float[Math.max(COLS, parent.length)];
            }
            Arrays.fill(history, 0f);
            System.arraycopy(parent, 0, history, 0, parent.length);
            count = parent.length;
            framesPerBar = stashedParentFramesPerBar;
            playheadPos = s;
            playheadIndex = s;
            selectionActive = false;
            postInvalidateOnAnimation();
            return;
        }

        int repLen = count;
        // The sub-session records at 20 bars/sec; the parent may have been
        // compacted (framesPerBar > 2400). Convert the replacement into the
        // parent's bucket scale before splicing, otherwise duration doubles
        // or halves as soon as the editor closes.
        float repDensity = Math.max(240f, framesPerBar);
        int repOutLen = (int) Math.max(0, Math.round(
                repLen * repDensity / Math.max(240f, stashedParentFramesPerBar)));
        float[] rep = resamplePeaksMax(
                Arrays.copyOf(history, repLen), repOutLen);
        int tailLen = Math.max(0, parent.length - e);
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
            int s = Math.round(Math.max(0f, Math.min(1f, startFrac)) * count);
            int e = Math.round(Math.max(0f, Math.min(1f, endFrac)) * count);
            if (e < s) {
                int tmp = s;
                s = e;
                e = tmp;
            }
            if (e - s < 1) {
                if (e < count) e = Math.min(count, s + 1);
                else s = Math.max(0, e - 1);
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
        if (selectionActive) {
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
    public synchronized void updateLiveFromFrames(float v, long curFrames,
                                                  long replaceEndFrame) {
        float clampedV = Math.max(0f, Math.min(1f, v));
        if (curFrames <= 0 && count == 0) return;
        if (framesPerBar <= 0f) framesPerBar = 2400f;

        if (replaceEndFrame > 0 && curFrames < replaceEndFrame && count > 0) {
            // In-place Replace before the original end of the take:
            float exactPos = Math.max(0f,
                    Math.min(count, (curFrames / (float) replaceEndFrame) * count));
            int idx = Math.min(count - 1, Math.max(0, (int) Math.floor(exactPos)));
            if (idx != lastMorphIdx) {
                lastMorphIdx = idx;
                morphAlpha = 0.62f;
            } else {
                morphAlpha = Math.min(1.0f, morphAlpha + 0.20f);
            }
            history[idx] = history[idx] * (1.0f - morphAlpha) + clampedV * morphAlpha;
            playheadPos = exactPos;
            playheadIndex = Math.round(playheadPos);
            postInvalidateOnAnimation();
            return;
        }

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
            } else {
                history[last] = history[last] * 0.85f + clampedV * 0.15f;
            }
        }
        playheadPos = Math.min(count, Math.max(0f, targetBuckets));
        playheadIndex = Math.round(playheadPos);
        lastMorphIdx = -1;
        morphAlpha = 0f;
        postInvalidateOnAnimation();
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
            morphAlpha = 0f;
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
        morphAlpha = 0f;
        framesPerBar = 2400f;
        invalidate();
    }

    public void loadFromFloatWav(java.io.File raw) {
        loadFromFloatWav(raw, null);
    }

    public void loadFromFloatWav(java.io.File raw, Runnable onLoaded) {
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
                applyLoadedPeaks(peaks, onLoaded);
            } catch (Exception ignored) {}
        }, "takeone-wave-load").start();
    }

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
                    framesPerBar = 2400f;
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
            // Loaded peaks are bucketed at 20 bars/sec; re-anchor the live
            // tail to the same density after a recovery/attach load.
            framesPerBar = 2400f;
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
        if (count <= 0 || w <= 0) return 0;
        float centerX = w / 2f;
        float gap = gapPxLocked(w);
        float idx = playheadPos - (centerX - sx) / gap;
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
                            playheadPos = Math.max(0f, playheadPos - (edge - x) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        } else if (x > w - edge && playheadPos < n) {
                            playheadPos = Math.min(n, playheadPos + (x - (w - edge)) / (gap * 3f));
                            playheadIndex = Math.round(playheadPos);
                        }
                        int idx = bucketForScreenXLocked(x, w);
                        selStartIdx = Math.max(0, Math.min(selEndIdx - 1, idx));
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
                        selEndIdx = Math.min(n, Math.max(selStartIdx + 1, idx));
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
        float headPos;
        int pIdx;
        boolean selOn;
        int sIdx;
        int eIdx;
        float gap;
        float curZoom;
        synchronized (this) {
            n = count;
            isLive = live;
            pIdx = playheadIndex;
            headPos = (playheadPos >= 0f && playheadPos <= n)
                    ? playheadPos
                    : ((pIdx >= 0 && pIdx <= n) ? pIdx : n);
            selOn = !isLive && selectionActive && selEndIdx > selStartIdx && count > 0;
            sIdx = selStartIdx;
            eIdx = selEndIdx;
            gap = gapPxLocked(w);
            curZoom = zoom;
        }

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

        float xSelStart = centerX - (headPos - sIdx) * gap;
        float xSelEnd = centerX - (headPos - eIdx) * gap;

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

        // 2) Draw Red Waveform Bars (constant screen concentration across all zoom levels)
        if (n > 0) {
            long kMin = (long) Math.floor((headPos - (centerX + barW) / gap) / bucketsPerBar) - 1L;
            long kMax = (long) Math.ceil((headPos + (w - centerX + barW) / gap) / bucketsPerBar) + 1L;
            long kStart = Math.max(0L, kMin);
            long kEnd = Math.min((long) Math.ceil(n / (double) bucketsPerBar), kMax);

            for (long k = kStart; k <= kEnd; k++) {
                float bStart = k * bucketsPerBar;
                float bEnd = (k + 1) * bucketsPerBar;
                if (bStart >= n || bEnd <= 0f) continue;

                float barCenterBucket = (k + 0.5f) * bucketsPerBar;
                if (barCenterBucket > n) continue;

                float x = centerX - (headPos - barCenterBucket) * gap;
                if (x < -barW || x > w + barW) continue;

                float v = 0f;
                synchronized (this) {
                    if (bucketsPerBar >= 1.0f) {
                        int i0 = Math.max(0, (int) Math.floor(bStart));
                        int i1 = Math.min(n - 1, (int) Math.ceil(bEnd) - 1);
                        if (i1 < i0) i1 = i0;
                        for (int idx = i0; idx <= i1; idx++) {
                            if (history[idx] > v) v = history[idx];
                        }
                    } else {
                        int idx = Math.max(0, Math.min(n - 1, (int) Math.floor(barCenterBucket)));
                        float fracInBucket = barCenterBucket - idx;
                        float v0 = history[idx];
                        float vPrev = (idx - 1 >= 0) ? history[idx - 1] : v0;
                        float vNext = (idx + 1 < n) ? history[idx + 1] : v0;
                        float targetNeighbor = (fracInBucket < 0.5f) ? vPrev : vNext;
                        float distFromCenter = Math.abs(fracInBucket - 0.5f) * 2f;
                        float weight = 0.5f * (1f - (float) Math.cos(Math.PI * distFromCenter)) * 0.42f;
                        v = v0 * (1f - weight) + targetNeighbor * weight;
                    }
                }

                float bh = Math.max(2f, v * maxH * 2f);

                if (selOn && barCenterBucket >= sIdx && barCenterBucket <= eIdx) {
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
