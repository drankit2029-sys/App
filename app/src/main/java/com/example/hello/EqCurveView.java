package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Shared canvas for the Stage 3 EQ editors: log-frequency x axis, dB y axis,
 * grid, live analyzer overlay and a sampled response curve. Subclasses own
 * the draggable handles and answer {@link #responseDb(float)} from their own
 * parameter model.
 */
abstract class EqCurveView extends View {

    static final float F_MIN = 20f;
    static final float F_MAX = 20000f;

    final DspChain.EffectSlot slot;
    final Runnable onMutated;
    final float dbRange;
    final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint spectrumFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final float density;

    EqCurveView(Context ctx, DspChain.EffectSlot slot, float dbRange,
                Runnable onMutated) {
        super(ctx);
        this.slot = slot;
        this.dbRange = dbRange;
        this.onMutated = onMutated;
        this.density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF10141B);
        setMinimumHeight(Math.round(150 * density));
        grid.setColor(0xFF232B36);
        grid.setStrokeWidth(Math.max(1f, density));
        line.setColor(0xFF4EA1FF);
        line.setStrokeWidth(Math.max(2f, 2f * density));
        line.setStyle(Paint.Style.STROKE);
        spectrumFill.setColor(0x22FFFFFF);
        spectrumFill.setStyle(Paint.Style.FILL);
        handle.setColor(0xFFF2F4F7);
        label.setColor(0xFF8A93A0);
        label.setTextSize(9f * density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        FxTelemetry.setEnabled(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        FxTelemetry.setEnabled(false);
        super.onDetachedFromWindow();
    }

    float xForFreq(float f, int w) {
        float pad = 6 * density;
        float t = (float) (Math.log(Math.max(F_MIN, Math.min(F_MAX, f)) / F_MIN)
                / Math.log(F_MAX / F_MIN));
        return pad + t * (w - 2 * pad);
    }

    float freqForX(float x, int w) {
        float pad = 6 * density;
        float t = (x - pad) / Math.max(1f, w - 2 * pad);
        t = Math.max(0f, Math.min(1f, t));
        return (float) (F_MIN * Math.pow(F_MAX / F_MIN, t));
    }

    float yForDb(float db, int h) {
        float half = h / 2f;
        return half - db / dbRange * (half - 6 * density);
    }

    float dbForY(float y, int h) {
        float half = h / 2f;
        float db = (half - y) / Math.max(1f, half - 6 * density) * dbRange;
        return Math.max(-dbRange, Math.min(dbRange, db));
    }

    int extra(String key, int def) {
        Integer v = slot.extraParams.get(key);
        return v == null ? def : v;
    }

    void putExtra(String key, int value) {
        slot.extraParams.put(key, value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Grid: octave lines + 0 dB axis.
        float[] marks = {50, 100, 200, 500, 1000, 2000, 5000, 10000};
        for (float f : marks) {
            float x = xForFreq(f, w);
            canvas.drawLine(x, 0, x, h, grid);
        }
        float zero = yForDb(0, h);
        canvas.drawLine(0, zero, w, zero, grid);
        syncFromSlot();
        drawSpectrum(canvas, w, h);
        drawCurve(canvas, w, h);
        onDrawHandles(canvas, w, h);
    }

    /** Re-reads slot parameters before painting so TeleValue edits are reflected. */
    protected void syncFromSlot() {
    }

    /** Prevents the parent ScrollView from stealing vertical handle drags. */
    final void disallowParentIntercept(boolean disallow) {
        android.view.ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private void drawSpectrum(Canvas canvas, int w, int h) {
        float[] spec = FxTelemetry.spectrum();
        if (spec == null) return;
        Path sp = new Path();
        sp.moveTo(0, h);
        for (int i = 0; i < spec.length; i++) {
            float f = 30f * (float) Math.pow(16000.0 / 30.0, i / (double) spec.length);
            float x = xForFreq(f, w);
            float norm = (spec[i] + 90f) / 90f;
            norm = Math.max(0f, Math.min(1f, norm));
            sp.lineTo(x, h - norm * h * 0.32f);
        }
        sp.lineTo(w, h);
        sp.close();
        canvas.drawPath(sp, spectrumFill);
    }

    private void drawCurve(Canvas canvas, int w, int h) {
        path.reset();
        int steps = Math.max(64, w / 4);
        for (int i = 0; i <= steps; i++) {
            float x = i / (float) steps * w;
            float f = freqForX(x, w);
            float y = yForDb(responseDb(f), h);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
    }

    /** Response of the currently edited settings, in dB, at {@code f}. */
    protected abstract float responseDb(float f);

    /** Draw draggable handles (and any per-view decorations). */
    protected abstract void onDrawHandles(Canvas canvas, int w, int h);
}
