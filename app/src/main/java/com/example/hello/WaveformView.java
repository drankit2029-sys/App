package com.example.hello;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** Animated waveform bars. Red + lively while recording, dim + gentle when idle. */
public class WaveformView extends View {

    private static final int BARS = 48;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] levels = new float[BARS];
    private final float[] targets = new float[BARS];
    private final Random random = new Random();
    private ValueAnimator animator;
    private boolean live = false;
    private float micLevel = 0f;
    private int tick = 0;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < BARS; i++) {
            levels[i] = 0.12f;
            targets[i] = 0.12f;
        }
    }

    public void setLive(boolean live) {
        this.live = live;
        if (live) {
            startAnim(90);
        } else {
            micLevel = 0f;
            startAnim(600);
        }
    }

    public void setMicLevel(float level) {
        micLevel = Math.max(0f, Math.min(1f, level));
    }

    private void startAnim(long frameMs) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofInt(0, 100000);
        animator.setDuration(frameMs * 1000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        final long[] last = {0};
        animator.addUpdateListener(a -> {
            long now = System.currentTimeMillis();
            if (now - last[0] < frameMs) return;
            last[0] = now;
            tick++;
            updateTargets();
            for (int i = 0; i < BARS; i++) {
                levels[i] += (targets[i] - levels[i]) * 0.45f;
            }
            invalidate();
        });
        animator.start();
    }

    private void updateTargets() {
        for (int i = 0; i < BARS; i++) {
            double wave = Math.sin((tick * 0.35) + (i * 0.42)) * 0.5 + 0.5;
            if (live) {
                float energy = 0.25f + micLevel * 0.75f;
                float jitter = random.nextFloat() * 0.55f * energy;
                targets[i] = (float) Math.min(1.0,
                        0.14 + wave * 0.5 * energy + jitter + micLevel * 0.25);
            } else {
                targets[i] = (float) (0.10 + wave * 0.08);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnim(live ? 90 : 600);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = w / (float) BARS;
        float barW = Math.max(3f, gap * 0.52f);
        paint.setStrokeWidth(barW);
        paint.setColor(live ? 0xFFFF3B30 : 0xFF3A414B);
        float centerY = h / 2f;
        float maxH = h * 0.46f;
        for (int i = 0; i < BARS; i++) {
            float x = gap * i + gap / 2f;
            float bh = Math.max(4f, levels[i] * maxH * 2f);
            canvas.drawLine(x, centerY - bh / 2f, x, centerY + bh / 2f, paint);
        }
    }
}
