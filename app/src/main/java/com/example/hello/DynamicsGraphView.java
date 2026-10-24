package com.example.hello;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/**
 * Fairlight-style interactive dynamics canvas: draws the input-vs-output
 * transfer curve of the compressor (threshold, ratio slope, soft knee and
 * noise-floor freeze) plus a live gain-reduction meter, and lets the user
 * drag the threshold / ratio handle directly on the graph.
 */
final class DynamicsGraphView extends View {

    private final DspChain.EffectSlot slot;
    private final Runnable onMutated;
    private final float density;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knee = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean dragging = false;

    private static final float DB_MIN = -60f;
    private static final float DB_MAX = 0f;

    DynamicsGraphView(Context ctx, DspChain.EffectSlot slot) {
        this(ctx, slot, null);
    }

    DynamicsGraphView(Context ctx, DspChain.EffectSlot slot, Runnable onMutated) {
        super(ctx);
        this.slot = slot;
        this.onMutated = onMutated;
        this.density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF10141B);
        setMinimumHeight(Math.round(150 * density));
        grid.setColor(0xFF232B36);
        grid.setStrokeWidth(Math.max(1f, density));
        curve.setColor(0xFF4EA1FF);
        curve.setStrokeWidth(Math.max(2f, 2f * density));
        curve.setStyle(Paint.Style.STROKE);
        knee.setColor(0xFFFFB020);
        knee.setStrokeWidth(Math.max(1.5f, 1.5f * density));
        knee.setStyle(Paint.Style.FILL_AND_STROKE);
        grTrack.setColor(0xFF1A2029);
        grBar.setColor(0xFFFF4D4F);
        text.setColor(0xFF8A93A0);
        text.setTextSize(9.5f * density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        FxTelemetry.attachView();
    }

    @Override
    protected void onDetachedFromWindow() {
        FxTelemetry.detachView();
        super.onDetachedFromWindow();
    }

    private int extra(String key, int def) {
        Integer v = slot.extraParams.get(key);
        return v == null ? def : v;
    }

    private float xForDb(float db, int w) {
        float pad = 8 * density;
        float meterReserve = 18 * density;
        float usableW = Math.max(1f, w - 2 * pad - meterReserve);
        float t = (db - DB_MIN) / (DB_MAX - DB_MIN);
        return pad + t * usableW;
    }

    private float dbForX(float x, int w) {
        float pad = 8 * density;
        float meterReserve = 18 * density;
        float usableW = Math.max(1f, w - 2 * pad - meterReserve);
        float t = (x - pad) / usableW;
        t = Math.max(0f, Math.min(1f, t));
        return DB_MIN + t * (DB_MAX - DB_MIN);
    }

    private float yForDb(float db, int h) {
        float pad = 8 * density;
        float t = (db - DB_MIN) / (DB_MAX - DB_MIN);
        return h - pad - t * (h - 2 * pad);
    }

    private float dbForY(float y, int h) {
        float pad = 8 * density;
        float t = (h - pad - y) / Math.max(1f, h - 2 * pad);
        t = Math.max(0f, Math.min(1f, t));
        return DB_MIN + t * (DB_MAX - DB_MIN);
    }

    private float currentThresholdDb() {
        if (slot.type == DspChain.EffectType.COMPRESSOR) {
            float amt = Math.max(0f, Math.min(1f, slot.value / 100f));
            return -2f - 21f * amt;
        }
        if (slot.type == DspChain.EffectType.MULTIBAND_COMPRESSOR) {
            return extra("mb_midt", -1800) / 100f;
        }
        return slot.value / 100f;
    }

    private float currentRatio() {
        if (slot.type == DspChain.EffectType.COMPRESSOR) {
            float amt = Math.max(0f, Math.min(1f, slot.value / 100f));
            float baseRatio = extra("comp_ratio", 600) / 100f;
            return Math.max(1f, 1f + (baseRatio - 1f) * amt);
        }
        if (slot.type == DspChain.EffectType.MULTIBAND_COMPRESSOR) {
            float amt = Math.max(0f, Math.min(1f, slot.value / 100f));
            float midR = extra("mb_midr", 300) / 100f;
            return Math.max(1f, 1f + (midR - 1f) * amt);
        }
        return Math.max(1.1f, extra("dyncomp_ratio", 300) / 100f);
    }

    private float currentKneeDb() {
        if (slot.type == DspChain.EffectType.COMPRESSOR) {
            return extra("comp_knee", 6);
        }
        if (slot.type == DspChain.EffectType.MULTIBAND_COMPRESSOR) {
            return 6f;
        }
        return extra("dyncomp_knee", 600) / 100f;
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                disallowParentIntercept(true);
                updateFromTouch(e.getX(), e.getY(), w, h);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                updateFromTouch(e.getX(), e.getY(), w, h);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                disallowParentIntercept(false);
                updateFromTouch(e.getX(), e.getY(), w, h);
                return true;
            default:
                return false;
        }
    }

    private void updateFromTouch(float x, float y, int w, int h) {
        float inDb = dbForX(x, w);
        float outDb = dbForY(y, h);
        if (slot.type == DspChain.EffectType.COMPRESSOR) {
            float normThresh = (-2f - inDb) / 21f;
            int amt = Math.max(0, Math.min(100, Math.round(normThresh * 100f)));
            slot.value = amt;
            float thresh = -2f - 21f * (amt / 100f);
            float spanIn = Math.max(3f, -thresh);
            float desiredOutAtZero = Math.max(thresh + 0.5f, Math.min(0f, outDb));
            float effRatio = spanIn / Math.max(0.5f, desiredOutAtZero - thresh);
            int ratioRaw = Math.max(100, Math.min(1200, Math.round(effRatio * 100f)));
            slot.extraParams.put("comp_ratio", ratioRaw);
        } else if (slot.type == DspChain.EffectType.MULTIBAND_COMPRESSOR) {
            int threshRaw = Math.max(-6000, Math.min(0, Math.round(inDb * 100f)));
            slot.extraParams.put("mb_midt", threshRaw);
            slot.extraParams.put("mb_lowt", Math.max(-6000, threshRaw - 400));
            slot.extraParams.put("mb_hight", Math.max(-6000, threshRaw - 200));
            float normY = Math.max(0f, Math.min(1f, (0f - outDb) / 36f));
            slot.value = Math.max(0, Math.min(100, Math.round(normY * 100f)));
        } else {
            int threshRaw = Math.max(-6000, Math.min(-100, Math.round(inDb * 100f)));
            slot.value = threshRaw;
            float thresh = threshRaw / 100f;
            float spanIn = Math.max(2f, -thresh);
            float desiredOutAtZero = Math.max(thresh + 0.5f, Math.min(-0.1f, outDb));
            float r = spanIn / Math.max(0.5f, desiredOutAtZero - thresh);
            int ratioRaw = Math.max(110, Math.min(2000, Math.round(r * 100f)));
            slot.extraParams.put("dyncomp_ratio", ratioRaw);
        }
        if (!slot.enabled) slot.enabled = true;
        if (onMutated != null) onMutated.run();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (int db = -60; db <= 0; db += 12) {
            float y = yForDb(db, h);
            canvas.drawLine(0, y, w, y, grid);
        }
        for (int db = -60; db <= 0; db += 20) {
            float x = xForDb(db, w);
            canvas.drawLine(x, 0, x, h, grid);
        }

        float threshold = currentThresholdDb();
        float ratio = currentRatio();
        float kneeDb = currentKneeDb();
        boolean upward = slot.type == DspChain.EffectType.DYNAMIC_COMPRESSOR
                && extra("dyncomp_upward", 0) != 0;

        // Unity reference line.
        path.reset();
        path.moveTo(xForDb(DB_MIN, w), yForDb(DB_MIN, h));
        path.lineTo(xForDb(DB_MAX, w), yForDb(DB_MAX, h));
        canvas.drawPath(path, grid);

        // Transfer curve.
        path.reset();
        boolean started = false;
        float slope = 1f - 1f / Math.max(1.0001f, ratio);
        for (int i = 0; i <= 120; i++) {
            float inDb = DB_MIN + (DB_MAX - DB_MIN) * i / 120f;
            float over = inDb - threshold;
            float outDb = inDb;
            if (upward) {
                if (over < 0f) {
                    outDb = inDb - slope * over;
                }
            } else {
                float gainDb = DynamicsDetector.softKneeGainDb(over, ratio, kneeDb);
                outDb = inDb + gainDb;
            }
            float x = xForDb(inDb, w);
            float y = yForDb(Math.max(DB_MIN, Math.min(DB_MAX, outDb)), h);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, curve);

        // Threshold handle.
        float tx = xForDb(threshold, w);
        float ty = yForDb(threshold, h);
        knee.setColor(dragging ? 0xFF4EA1FF : 0xFFFFB020);
        canvas.drawCircle(tx, ty, 6f * density, knee);
        canvas.drawCircle(tx, ty, 2.5f * density, grid);

        // Readout on top-left.
        text.setTextAlign(Paint.Align.LEFT);
        String summary = String.format(java.util.Locale.US,
                "Thresh %.1f dB  •  Ratio %.1f:1", threshold, ratio);
        canvas.drawText(summary, 8 * density, 14 * density, text);

        // Gain reduction meter on the right edge.
        float meterW = 8 * density;
        float meterX = w - meterW - 4 * density;
        canvas.drawRect(meterX, 8 * density, meterX + meterW,
                h - 8 * density, grTrack);
        float grDb = Math.abs(FxTelemetry.lastGainReductionDb());
        if (grDb < 0.05f) {
            float over = DB_MAX - threshold;
            grDb = Math.min(24f, Math.abs(slope * Math.max(0f, over)));
        }
        float frac = Math.max(0f, Math.min(1f, grDb / 24f));
        float barH = (h - 16 * density) * frac;
        canvas.drawRect(meterX, h - 8 * density - barH, meterX + meterW,
                h - 8 * density, grBar);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(java.util.Locale.US, "-%.1f dB", grDb),
                meterX - 4 * density, 14 * density, text);
        if (FxTelemetry.isEnabled()) {
            postInvalidateDelayed(50);
        }
    }
}
