package com.example.hello;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Restrained press feedback for every tappable view.
 *
 * The effect is deliberately shallow — a small scale dip plus a barely-there
 * alpha change — and every transition runs on a falloff curve so the motion
 * decays quickly instead of flashing. Nothing here changes layout, so it can
 * safely coexist with the selection scale driven by the adapter.
 */
public final class PressAnim {

    /** Fast lift-off, long settle: the "falloff" easing used on release. */
    private static final PathInterpolator FALLOFF =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Opposite curve for pressing in: moves a little, then eases. */
    private static final PathInterpolator SETTLE_IN =
            new PathInterpolator(0.4f, 0f, 0.6f, 1f);

    private PressAnim() {}

    @SuppressLint("ClickableViewAccessibility")
    public static void apply(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // A disabled or non-clickable chip must not give press
                    // feedback: that is what made disabled actions look tappable.
                    if (!view.isEnabled() || !view.isClickable()) return false;
                    view.animate().cancel();
                    view.animate()
                            .scaleX(0.97f).scaleY(0.97f)
                            .alpha(0.9f)
                            .setDuration(70)
                            .setInterpolator(FALLOFF)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!view.isEnabled() || !view.isClickable()) return false;
                    view.animate().cancel();
                    view.animate()
                            .scaleX(1f).scaleY(1f)
                            .alpha(1f)
                            .setDuration(240)
                            .setInterpolator(SETTLE_IN)
                            .start();
                    break;
                default:
                    break;
            }
            return false; // let click listeners still fire
        });
    }

    public static void applyAll(View... views) {
        if (views == null) return;
        for (View v : views) apply(v);
    }

    /**
     * Gentler variant for elements that already have their own emphasis
     * (icons, chips): scales a touch and never dips alpha, so there is no
     * visible flash — just a soft give.
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void applySoft(View v, final float pressedScale) {
        if (v == null) return;
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!view.isEnabled() || !view.isClickable()) return false;
                    view.animate().cancel();
                    view.animate()
                            .scaleX(pressedScale).scaleY(pressedScale)
                            .setDuration(70)
                            .setInterpolator(FALLOFF)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(240)
                            .setInterpolator(SETTLE_IN)
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * Small confirmation pop for icons — a light nudge on the same falloff
     * curve. Used instead of a hard overshoot so nothing flashes.
     */
    public static void nudge(View v, float peak) {
        if (v == null) return;
        v.animate().cancel();
        v.animate().scaleX(peak).scaleY(peak)
                .setDuration(90).setInterpolator(FALLOFF)
                .withEndAction(() -> v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(260).setInterpolator(SETTLE_IN).start())
                .start();
    }

    /** One-shot entrance pop used for dialog icons. */
    public static void pop(View v) {
        if (v == null) return;
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        v.setAlpha(0f);
        v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }
}