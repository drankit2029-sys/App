package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Vertical dBFS meter (-60..0&nbsp;dB) with green/amber/red segments and a
 * decaying peak-hold marker. Fed with the same 0..1 raw peak as the waveform.
 */
public class DbMeterView extends View {

    private static final float FLOOR_DB = -60f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float level; // 0..1 smoothed
    private float peak;  // 0..1 peak-hold

    public DbMeterView(Context context) {
        super(context);
        init();
    }

    public DbMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        bgPaint.setColor(0xFF171B21);
        peakPaint.setColor(0xFFF2F4F7);
        peakPaint.setStrokeWidth(3f);
        tickPaint.setColor(0xFF3A414B);
        tickPaint.setStrokeWidth(1f);
        tickPaint.setTextSize(16f);
    }

    /** Push one 0..1 peak sample; call at the studio tick cadence. */
    public void setLevel(float v) {
        v = Math.max(0f, Math.min(1f, v));
        // Fast attack, slow release smoothing.
        level = v > level ? v : level + (v - level) * 0.35f;
        peak = Math.max(v, peak - 0.012f);
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

        float pad = 6f;
        float barLeft = pad;
        // Thin meter: numeric labels only when there is room for them.
        boolean roomForLabels = w > 64;
        float barRight = roomForLabels ? w - pad - 34f : w - pad;
        if (barRight <= barLeft + 4) barRight = barLeft + 8;
        float top = pad;
        float bottom = h - pad;

        canvas.drawRect(barLeft, top, barRight, bottom, bgPaint);

        // Scale ticks every 12 dB.
        for (int db = 0; db >= -60; db -= 12) {
            float y = top + (0 - db) / 60f * (bottom - top);
            canvas.drawLine(barRight + 3, y, barRight + 9, y, tickPaint);
            if (roomForLabels) {
                canvas.drawText(String.valueOf(db), barRight + 11, y + 5, tickPaint);
            }
        }

        float db = toDb(level);
        float frac = (db - FLOOR_DB) / (0 - FLOOR_DB);
        float fillTop = bottom - frac * (bottom - top);

        // One vertical gradient (green -> amber -> red) mapped over the whole
        // bar, so color follows height smoothly instead of hard thresholds.
        fillPaint.setShader(new LinearGradient(0, top, 0, bottom,
                new int[]{0xFFFF3B30, 0xFFFF9F0A, 0xFF34C759},
                new float[]{0f, 0.25f, 0.55f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(barLeft, fillTop, barRight, bottom, fillPaint);
        fillPaint.setShader(null);

        float pdb = toDb(peak);
        float pfrac = (pdb - FLOOR_DB) / (0 - FLOOR_DB);
        float py = bottom - pfrac * (bottom - top);
        canvas.drawLine(barLeft, py, barRight, py, peakPaint);
    }
}
