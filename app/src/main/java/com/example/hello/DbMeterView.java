package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Slim vertical dBFS meter (-60..0&nbsp;dB).
 *
 * <p>Designed to live as a thin rail beside the timer: a rounded capsule
 * track with a soft glow at the fill head, a single continuous colour ramp
 * rather than hard colour bands, and a peak-hold tick that decays slowly.
 */
public class DbMeterView extends View {

    private static final float FLOOR_DB = -60f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float level; // 0..1 smoothed
    private float peak;  // 0..1 peak-hold
    private float radius;

    public DbMeterView(Context context) {
        super(context);
        init();
    }

    public DbMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint.setColor(0xFF171B21);
        glowPaint.setColor(0x66FFFFFF);
        peakPaint.setColor(0xFFF2F4F7);
    }

    /** Push one 0..1 peak sample; call at the studio tick cadence. */
    public void setLevel(float v) {
        v = Math.max(0f, Math.min(1f, v));
        // Fast attack so transients are not hidden, slow release so the bar
        // does not flicker between words.
        level = v > level ? v : level + (v - level) * 0.22f;
        peak = Math.max(v, peak - 0.010f);
        invalidate();
    }

    public void reset() {
        level = 0f;
        peak = 0f;
        invalidate();
    }

    private static float toDb(float v) {
        if (v <= 0.0001f) return FLOOR_DB;
        return Math.max(FLOOR_DB, 20f * (float) Math.log10(v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        radius = Math.min(w / 2f, 6f);
        float inset = 0f;
        float top = inset;
        float bottom = h - inset;

        rect.set(0, top, w, bottom);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        float db = toDb(level);
        float frac = (db - FLOOR_DB) / (0 - FLOOR_DB);
        float fillTop = bottom - frac * (bottom - top);
        if (fillTop >= bottom - 0.5f) return;

        // One smooth ramp across the full height: red at the top, amber
        // through the mids, green near the floor. No banding.
        fillPaint.setShader(new LinearGradient(0, top, 0, bottom,
                new int[]{0xFFFF453A, 0xFFFF9F0A, 0xFF34C759},
                new float[]{0f, 0.30f, 0.62f},
                Shader.TileMode.CLAMP));
        rect.set(0, fillTop, w, bottom);
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        fillPaint.setShader(null);

        // Soft cap highlight at the fill head.
        glowPaint.setAlpha(70);
        float capH = Math.min(10f, Math.max(3f, h * 0.02f));
        rect.set(0, fillTop, w, fillTop + capH);
        canvas.drawRoundRect(rect, radius, radius, glowPaint);
        glowPaint.setAlpha(255);

        float pdb = toDb(peak);
        float pfrac = (pdb - FLOOR_DB) / (0 - FLOOR_DB);
        float py = bottom - pfrac * (bottom - top);
        if (py > top && py < bottom) {
            peakPaint.setStrokeWidth(Math.max(1.5f, w * 0.16f));
            peakPaint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(w * 0.28f, py, w * 0.72f, py, peakPaint);
        }
    }
}