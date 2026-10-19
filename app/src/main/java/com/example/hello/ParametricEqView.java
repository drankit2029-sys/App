package com.example.hello;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

/**
 * Interactive canvas for the 6-band parametric EQ: four draggable bell/shelf
 * handles (bands 2-5) over the live response curve. Horizontal drag moves the
 * band frequency, vertical drag its gain; edits are written to the slot's
 * extra parameters on gesture end.
 */
final class ParametricEqView extends EqCurveView {

    private static final String[] FREQ_IDS = {"b2f", "b3f", "b4f", "b5f"};
    private static final String[] GAIN_IDS = {"b2g", "b3g", "b4g", "b5g"};

    private final ParametricEq6BandProcessor scratch;
    private int active = -1;

    ParametricEqView(Context ctx, DspChain.EffectSlot slot, Runnable onMutated) {
        super(ctx, slot, 18f, onMutated);
        AudioProcessor p = FxRackChain.createProcessor("eq6");
        scratch = (ParametricEq6BandProcessor) p;
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
        for (int i = 0; i < FREQ_IDS.length; i++) {
            FxParameter fq = param(FREQ_IDS[i]);
            FxParameter gn = param(GAIN_IDS[i]);
            if (fq == null || gn == null) continue;
            float x = xForFreq(fq.getCurrentRaw(), w);
            float y = yForDb(gn.getCurrentRaw() / 100f, h);
            if (i == active) {
                handle.setColor(0xFF4EA1FF);
            } else {
                handle.setColor(0xFFF2F4F7);
            }
            canvas.drawCircle(x, y, 7 * density, handle);
            canvas.drawCircle(x, y, 3 * density, grid);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                active = nearestHandle(e.getX(), e.getY(), w, h);
                return active >= 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (active < 0) return false;
                FxParameter fq = param(FREQ_IDS[active]);
                FxParameter gn = param(GAIN_IDS[active]);
                if (fq == null || gn == null) return false;
                int freq = Math.round(freqForX(e.getX(), w));
                fq.setCurrentRaw(fq.clampRaw(freq));
                int gain = Math.round(dbForY(e.getY(), h) * 100f);
                gn.setCurrentRaw(gn.clampRaw(gain));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (active < 0) return false;
                commit();
                active = -1;
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    private int nearestHandle(float x, float y, int w, int h) {
        int best = -1;
        float bestD = 30 * density;
        for (int i = 0; i < FREQ_IDS.length; i++) {
            FxParameter fq = param(FREQ_IDS[i]);
            FxParameter gn = param(GAIN_IDS[i]);
            if (fq == null || gn == null) continue;
            float hx = xForFreq(fq.getCurrentRaw(), w);
            float hy = yForDb(gn.getCurrentRaw() / 100f, h);
            float d = (float) Math.hypot(hx - x, hy - y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void commit() {
        for (int i = 0; i < FREQ_IDS.length; i++) {
            FxParameter fq = param(FREQ_IDS[i]);
            FxParameter gn = param(GAIN_IDS[i]);
            if (fq == null || gn == null) continue;
            putExtra(FREQ_IDS[i], fq.getCurrentRaw());
            putExtra(GAIN_IDS[i], gn.getCurrentRaw());
        }
        onMutated.run();
    }
}
