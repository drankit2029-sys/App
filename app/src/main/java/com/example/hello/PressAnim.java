package com.example.hello;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Small scale+fade press feedback applied to every tappable view. */
public final class PressAnim {

    private PressAnim() {}

    @SuppressLint("ClickableViewAccessibility")
    public static void apply(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate()
                        .scaleX(0.93f).scaleY(0.93f)
                        .alpha(0.82f)
                        .setDuration(90)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                view.animate().cancel();
                view.animate()
                        .scaleX(1f).scaleY(1f)
                        .alpha(1f)
                        .setDuration(160)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
            return false; // let click listeners still fire
        });
    }

    public static void applyAll(View... views) {
        if (views == null) return;
        for (View v : views) apply(v);
    }

    /** One-shot pop used for dialog icons, hearts, chip selection. */
    public static void pop(View v) {
        if (v == null) return;
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        v.setAlpha(0f);
        v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }
}
