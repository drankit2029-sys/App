package com.example.hello;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

/**
 * Interactive canvas for the filter-curve EQ: draggable nodes on a log-f axis
 * with piecewise-linear dB interpolation (the same curve the FIR designer
 * uses). Drag moves a node; long-press on empty space inserts one, long-press
 * on a node removes it.
 */
final class FilterCurveEqView extends EqCurveView {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = this::onLongPress;
    private int active = -1;
    private float downX;
    private float downY;
    private boolean longPressFired;

    FilterCurveEqView(Context ctx, DspChain.EffectSlot slot, Runnable onMutated) {
        super(ctx, slot, 24f, onMutated);
    }

    private int nodeCount() {
        return Math.max(2, Math.min(FilterCurveEqProcessor.MAX_NODES,
                extra("npts", 8)));
    }

    private double[][] readNodes() {
        int n = nodeCount();
        double[][] nodes = new double[n][2];
        for (int i = 0; i < n; i++) {
            nodes[i][0] = extra("pf" + i, (int) FilterCurveEqProcessor.defaultFreq(i));
            nodes[i][1] = extra("pg" + i, 0) / 100.0;
        }
        for (int i = 1; i < n; i++) {
            double[] v = nodes[i];
            int j = i - 1;
            while (j >= 0 && nodes[j][0] > v[0]) {
                nodes[j + 1] = nodes[j];
                j--;
            }
            nodes[j + 1] = v;
        }
        return nodes;
    }

    private void writeNodes(double[][] nodes) {
        putExtra("npts", nodes.length);
        for (int i = 0; i < nodes.length; i++) {
            putExtra("pf" + i, (int) Math.round(nodes[i][0]));
            putExtra("pg" + i, (int) Math.round(nodes[i][1] * 100.0));
        }
    }

    @Override
    protected float responseDb(float f) {
        double[][] nodes = readNodes();
        double out = slot.value / 100.0;
        return (float) (out + curveDbAt(nodes, f));
    }

    private static double curveDbAt(double[][] nodes, double f) {
        if (f <= nodes[0][0]) return nodes[0][1];
        int last = nodes.length - 1;
        if (f >= nodes[last][0]) return nodes[last][1];
        for (int i = 0; i < last; i++) {
            double f0 = nodes[i][0], f1 = nodes[i + 1][0];
            if (f >= f0 && f <= f1) {
                double t = (Math.log(f) - Math.log(f0))
                        / Math.max(1e-9, Math.log(f1) - Math.log(f0));
                return nodes[i][1] + t * (nodes[i + 1][1] - nodes[i][1]);
            }
        }
        return nodes[last][1];
    }

    @Override
    protected void onDrawHandles(Canvas canvas, int w, int h) {
        double[][] nodes = readNodes();
        for (int i = 0; i < nodes.length; i++) {
            float x = xForFreq((float) nodes[i][0], w);
            float y = yForDb((float) nodes[i][1], h);
            handle.setColor(i == active ? 0xFF4EA1FF : 0xFFF2F4F7);
            canvas.drawCircle(x, y, 6 * density, handle);
            canvas.drawCircle(x, y, 2.5f * density, grid);
        }
        if (active >= 0 && active < nodes.length) {
            String txt = Math.round(nodes[active][0]) + " Hz  "
                    + String.format(java.util.Locale.US, "%+.1f dB",
                    nodes[active][1]);
            canvas.drawText(txt, 8 * density, 12 * density, label);
        } else {
            canvas.drawText(nodes.length + " points  (hold: add/remove)",
                    8 * density, 12 * density, label);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                longPressFired = false;
                active = nearestNode(downX, downY, w, h);
                disallowParentIntercept(true);
                handler.postDelayed(longPress, 480);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (longPressFired) return true;
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * density) {
                    handler.removeCallbacks(longPress);
                }
                if (active < 0) return true;
                double[][] nodes = readNodes();
                double lo = active > 0 ? nodes[active - 1][0] + 1 : F_MIN;
                double hi = active < nodes.length - 1
                        ? nodes[active + 1][0] - 1 : F_MAX;
                if (hi < lo) hi = lo;
                double f = Math.max(lo, Math.min(hi, freqForX(e.getX(), w)));
                double g = dbForY(e.getY(), h);
                nodes[active][0] = f;
                nodes[active][1] = g;
                writeNodes(nodes);
                if (!slot.enabled) slot.enabled = true;
                onMutated.run();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                disallowParentIntercept(false);
                handler.removeCallbacks(longPress);
                if (active >= 0 && !longPressFired) {
                    if (!slot.enabled) slot.enabled = true;
                    onMutated.run();
                }
                active = -1;
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    private void onLongPress() {
        longPressFired = true;
        int w = getWidth();
        int h = getHeight();
        double[][] nodes = readNodes();
        int hit = nearestNode(downX, downY, w, h);
        if (hit >= 0) {
            if (nodes.length <= 2) return;
            double[][] out = new double[nodes.length - 1][2];
            for (int i = 0, j = 0; i < nodes.length; i++) {
                if (i != hit) out[j++] = nodes[i];
            }
            writeNodes(out);
        } else {
            if (nodes.length >= FilterCurveEqProcessor.MAX_NODES) return;
            double f = freqForX(downX, w);
            double g = dbForY(downY, h);
            int insert = nodes.length;
            for (int i = 0; i < nodes.length; i++) {
                if (nodes[i][0] > f) {
                    insert = i;
                    break;
                }
            }
            double[][] out = new double[nodes.length + 1][2];
            for (int i = 0, j = 0; i < out.length; i++) {
                if (i == insert) {
                    out[i][0] = f;
                    out[i][1] = g;
                } else {
                    out[i] = nodes[j++];
                }
            }
            writeNodes(out);
            active = insert;
        }
        if (!slot.enabled) slot.enabled = true;
        onMutated.run();
        invalidate();
    }

    private int nearestNode(float x, float y, int w, int h) {
        double[][] nodes = readNodes();
        int best = -1;
        float bestD = 34 * density;
        for (int i = 0; i < nodes.length; i++) {
            float nx = xForFreq((float) nodes[i][0], w);
            float ny = yForDb((float) nodes[i][1], h);
            float d = (float) Math.hypot(nx - x, ny - y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
