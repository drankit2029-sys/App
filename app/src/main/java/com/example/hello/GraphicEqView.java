package com.example.hello;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

/**
 * Interactive canvas for the graphic EQ: one vertical handle per band of the
 * selected layout (10 or 31 bands). Dragging a handle vertically sets that
 * band's gain; the live response curve is rebuilt from the same biquads the
 * processor uses.
 */
final class GraphicEqView extends EqCurveView {

    private static final double[] FREQ_10 = {
            31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final double[] FREQ_31 = {
            20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
            500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000,
            6300, 8000, 10000, 12500, 16000, 20000};

    private final GraphicEqProcessor scratch;
    private int active = -1;

    GraphicEqView(Context ctx, DspChain.EffectSlot slot, Runnable onMutated) {
        super(ctx, slot, 20f, onMutated);
        scratch = (GraphicEqProcessor) FxRackChain.createProcessor("geq");
        scratch.prepare(DspChain.SAMPLE_RATE, 2);
        syncFromSlot();
    }

    private void syncFromSlot() {
        for (FxParameter param : scratch.getParameters()) {
            int raw = "out".equals(param.id)
                    ? slot.value
                    : extra(param.id, param.defaultRaw);
            param.setCurrentRaw(param.clampRaw(raw));
        }
    }

    private double[] freqs() {
        FxParameter layout = param("bands");
        return layout != null && layout.getCurrentRaw() == 1
                ? FREQ_31 : FREQ_10;
    }

    private int bandCount() {
        return freqs().length;
    }

    @Override
    protected float responseDb(float f) {
        return (float) Dsp.cascadeMagDb(scratch.buildSections(), f,
                DspChain.SAMPLE_RATE);
    }

    private FxParameter param(String id) {
        for (FxParameter p : scratch.getParameters()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    @Override
    protected void onDrawHandles(Canvas canvas, int w, int h) {
        double[] fs = freqs();
        float zero = yForDb(0, h);
        for (int i = 0; i < fs.length; i++) {
            FxParameter g = param("g" + i);
            if (g == null) continue;
            float x = xForFreq((float) fs[i], w);
            float y = yForDb(g.getCurrentRaw() / 100f, h);
            grid.setColor(0xFF2E3946);
            canvas.drawLine(x, zero, x, y, grid);
            grid.setColor(0xFF232B36);
            handle.setColor(i == active ? 0xFF4EA1FF : 0xFFC7D0DB);
            canvas.drawCircle(x, y, (fs.length > 16 ? 3.5f : 6f) * density,
                    handle);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                active = nearestBand(e.getX(), w);
                return active >= 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (active < 0) return false;
                FxParameter g = param("g" + active);
                if (g == null) return false;
                int raw = Math.round(dbForY(e.getY(), h) * 100f);
                g.setCurrentRaw(g.clampRaw(raw));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (active < 0) return false;
                FxParameter g = param("g" + active);
                if (g != null) putExtra("g" + active, g.getCurrentRaw());
                active = -1;
                onMutated.run();
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    private int nearestBand(float x, int w) {
        double[] fs = freqs();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < fs.length; i++) {
            float d = Math.abs(xForFreq((float) fs[i], w) - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
