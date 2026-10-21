package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Fairlight-style dynamics canvas: draws the input-vs-output transfer curve
 * of the compressor (threshold, ratio slope, soft knee and noise-floor
 * freeze) plus a live gain-reduction meter.
 *
 * <p>The curve is rebuilt from the slot's own parameters on every frame, so
 * a TeleValue edit is reflected immediately. The GR bar reads the live
 * reduction reported by {@link FxTelemetry} when the rack is playing and
 * falls back to the static curve depth when it is idle.
 */
final class DynamicsGraphView extends View {

    private final DspChain.EffectSlot slot;
    private final float density;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knee = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final float DB_MIN = -60f;
    private static final float DB_MAX = 0f;

    DynamicsGraphView(Context ctx, DspChain.EffectSlot slot) {
        super(ctx);
        this.slot = slot;
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
        knee.setStyle(Paint.Style.STROKE);
        grTrack.setColor(0xFF1A2029);
        grBar.setColor(0xFFFF4D4F);
        text.setColor(0xFF8A93A0);
        text.setTextSize(9f * density);
    }

    private int extra(String key, int def) {
        Integer v = slot.extraParams.get(key);
        return v == null ? def : v;
    }

    private float xForDb(float db, int w) {
        float pad = 8 * density;
        float t = (db - DB_MIN) / (DB_MAX - DB_MIN);
        return pad + t * (w - 2 * pad);
    }

    private float dbForX(float x, int w) {
        float pad = 8 * density;
        float t = (x - pad) / Math.max(1f, w - 2 * pad);
        t = Math.max(0f, Math.min(1f, t));
        return DB_MIN + t * (DB_MAX - DB_MIN);
    }

    private float yForDb(float db, int h) {
        float pad = 8 * density;
        float t = (db - DB_MIN) / (DB_MAX - DB_MIN);
        return h - pad - t * (h - 2 * pad);
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

        float threshold = -18f;
        float ratio = 3f;
        float kneeDb = 6f;
        if (slot.type == DspChain.EffectType.DYNAMIC_COMPRESSOR
                || slot.type == DspChain.EffectType.MULTIBAND_COMPRESSOR) {
            threshold = extra("dyncomp_thresh", -1800) / 100f;
            ratio = extra("dyncomp_ratio", 300) / 100f;
            kneeDb = extra("dyncomp_knee", 600) / 100f;
        }

        // Unity reference line.
        path.reset();
        path.moveTo(xForDb(DB_MIN, w), yForDb(DB_MIN, h));
        path.lineTo(xForDb(DB_MAX, w), yForDb(DB_MAX, h));
        canvas.drawPath(path, grid);

        // Transfer curve.
        path.reset();
        boolean started = false;
        for (int i = 0; i <= 120; i++) {
            float inDb = DB_MIN + (DB_MAX - DB_MIN) * i / 120f;
            float over = inDb - threshold;
            float outDb = inDb;
            if (over > 0f) {
                float slope = 1f - 1f / Math.max(1.0001f, ratio);
                if (kneeDb > 0f && over < kneeDb) {
                    outDb = inDb - slope * over * over / (2f * kneeDb);
                } else {
                    outDb = inDb - slope * over;
                }
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

        // Threshold marker.
        float tx = xForDb(threshold, w);
        float ty = yForDb(threshold, h);
        canvas.drawCircle(tx, ty, 4 * density, knee);

        // Gain reduction meter on the right edge.
        float meterW = 8 * density;
        float meterX = w - meterW - 4 * density;
        canvas.drawRect(meterX, 8 * density, meterX + meterW,
                h - 8 * density, grTrack);
        float grDb = Math.abs(FxTelemetry.lastGainReductionDb());
        if (grDb < 0.05f) {
            // Idle: show the static maximum reduction of the curve.
            float over = DB_MAX - threshold;
            float slope = 1f - 1f / Math.max(1.0001f, ratio);
            grDb = Math.min(24f, Math.abs(slope * over));
        }
        float frac = Math.max(0f, Math.min(1f, grDb / 24f));
        float barH = (h - 16 * density) * frac;
        canvas.drawRect(meterX, h - 8 * density - barH, meterX + meterW,
                h - 8 * density, grBar);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(java.util.Locale.US, "-%.1f dB", grDb),
                w - 2 * density, 14 * density, text);
    }
}
