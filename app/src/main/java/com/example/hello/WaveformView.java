package com.example.hello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

/**
 * Professional scrolling waveform: a fixed horizontal center line with the
 * recent raw-audio peak history drawn as vertical bars, newest at the right.
 * Driven by {@link RecordingEngine#level} samples pushed from the studio tick
 * (20 samples/s at a 50&nbsp;ms cadence ≈ a 7&nbsp;s window).
 */
public class WaveformView extends View {

    private static final int COLS = 140;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] history = new float[COLS];
    private int count;
    private boolean live;

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
        linePaint.setColor(0xFF5D6672);
        linePaint.setStrokeWidth(2f);
        gridPaint.setColor(0xFF20262E);
        gridPaint.setStrokeWidth(1f);
        Arrays.fill(history, 0f);
    }

    public void setLive(boolean live) {
        this.live = live;
        invalidate();
    }

    /** Push one 0..1 peak sample from the raw capture path. */
    public void setMicLevel(float level) {
        push(Math.max(0f, Math.min(1f, level)));
    }

    public synchronized void push(float v) {
        if (count < COLS) {
            history[count++] = v;
        } else {
            System.arraycopy(history, 1, history, 0, COLS - 1);
            history[COLS - 1] = v;
        }
        postInvalidateOnAnimation();
    }

    public synchronized void clear() {
        Arrays.fill(history, 0f);
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float centerY = h / 2f;
        // Faint quarter grid + the pro center line everything hangs off.
        canvas.drawLine(0, h * 0.25f, w, h * 0.25f, gridPaint);
        canvas.drawLine(0, h * 0.75f, w, h * 0.75f, gridPaint);
        canvas.drawLine(0, centerY, w, centerY, linePaint);

        float gap = w / (float) COLS;
        float barW = Math.max(2f, gap * 0.62f);
        barPaint.setStrokeWidth(barW);
        float maxH = h * 0.46f;

        int n;
        synchronized (this) {
            n = count;
        }
        int colorLive = 0xFFFF3B30;
        int colorIdle = 0xFF3A414B;
        for (int i = 0; i < n; i++) {
            float v;
            synchronized (this) {
                v = history[i];
            }
            // Newest at the right edge.
            float x = w - (n - i) * gap + gap / 2f;
            float bh = Math.max(3f, v * maxH * 2f);
            // Older bars fade out to the left like a DAW history.
            float age = n <= 1 ? 1f : 0.35f + 0.65f * (i / (float) (n - 1));
            int base = live ? colorLive : colorIdle;
            barPaint.setColor(base);
            barPaint.setAlpha(Math.round(255 * age));
            canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, barPaint);
        }
        barPaint.setAlpha(255);
    }
}
