package com.example.hello;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

/**
 * Interactive canvas for the 6-band parametric EQ. All six bands have a
 * draggable handle: bands 2-5 move in both frequency and gain, while the
 * low-cut (band 1) and high-cut (band 6) handles move horizontally to set
 * their corner frequency. The graph therefore always exposes six band
 * handles, not only the four bell/shelf bands.
 */
final class ParametricEqView extends EqCurveView {

    private static final String[] FREQ_IDS = {
            "b1f", "b2f", "b3f", "b4f", "b5f", "b6f"};
    /** Null for bands 1 and 6, which are cuts without a gain parameter. */
    private static final String[] GAIN_IDS = {
            null, "b2g", "b3g", "b4g", "b5g", null};

    private final ParametricEq6BandProcessor scratch;
    private int active = -1;

    ParametricEqView(Context ctx, DspChain.EffectSlot slot, Runnable onMutated) {
        super(ctx, slot, 18f, onMutated);
        AudioProcessor p = FxRackChain.createProcessor("eq6");
        scratch = (ParametricEq6BandProcessor) p;
        scratch.prepare(DspChain.SAMPLE_RATE, 2);
        syncFromSlot();
    }

    @Override
    protected void syncFromSlot() {
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
        float zero = yForDb(0, h);
        for (int i = 0; i < FREQ_IDS.length; i++) {
            FxParameter fq = param(FREQ_IDS[i]);
            if (fq == null) continue;
            float x = xForFreq(fq.getCurrentRaw(), w);
            boolean cut = GAIN_IDS[i] == null;
            float y = zero;
            if (!cut) {
                FxParameter gn = param(GAIN_IDS[i]);
                if (gn == null) continue;
                y = yForDb(gn.getCurrentRaw() / 100f, h);
            }
            if (i == active) {
                handle.setColor(0xFF4EA1FF);
            } else if (cut) {
                handle.setColor(0xFFFFB020);
            } else {
                handle.setColor(0xFFF2F4F7);
            }
            float radius = cut ? 8 * density : 7 * density;
            canvas.drawCircle(x, y, radius, handle);
            canvas.drawCircle(x, y, 3 * density, grid);
            if (cut) {
                String num = i == 0 ? "1" : "6";
                label.setColor(0xFF0B0D10);
                label.setTextAlign(android.graphics.Paint.Align.CENTER);
                label.setTextSize(9 * density);
                canvas.drawText(num, x, y + 3 * density, label);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth();
        int h = getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                syncFromSlot();
                active = nearestHandle(e.getX(), e.getY(), w, h);
                if (active >= 0) {
                    disallowParentIntercept(true);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (active < 0) return false;
                FxParameter fq = param(FREQ_IDS[active]);
                if (fq == null) return false;
                int freq = Math.round(freqForX(e.getX(), w));
                fq.setCurrentRaw(fq.clampRaw(freq));
                putExtra(FREQ_IDS[active], fq.getCurrentRaw());
                String gainId = GAIN_IDS[active];
                if (gainId != null) {
                    FxParameter gn = param(gainId);
                    if (gn != null) {
                        int gain = Math.round(dbForY(e.getY(), h) * 100f);
                        gn.setCurrentRaw(gn.clampRaw(gain));
                        putExtra(gainId, gn.getCurrentRaw());
                    }
                }
                if (!slot.enabled) slot.enabled = true;
                onMutated.run();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (active < 0) return false;
                disallowParentIntercept(false);
                commitActive(active);
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
        float zero = yForDb(0, h);
        for (int i = 0; i < FREQ_IDS.length; i++) {
            FxParameter fq = param(FREQ_IDS[i]);
            if (fq == null) continue;
            float hx = xForFreq(fq.getCurrentRaw(), w);
            float hy = zero;
            String gainId = GAIN_IDS[i];
            if (gainId != null) {
                FxParameter gn = param(gainId);
                if (gn == null) continue;
                hy = yForDb(gn.getCurrentRaw() / 100f, h);
            }
            float d = (float) Math.hypot(hx - x, hy - y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void commitActive(int idx) {
        if (idx >= 0 && idx < FREQ_IDS.length) {
            FxParameter fq = param(FREQ_IDS[idx]);
            if (fq != null) {
                putExtra(FREQ_IDS[idx], fq.getCurrentRaw());
            }
            String gainId = GAIN_IDS[idx];
            if (gainId != null) {
                FxParameter gn = param(gainId);
                if (gn != null) putExtra(gainId, gn.getCurrentRaw());
            }
        }
        if (!slot.enabled) slot.enabled = true;
        onMutated.run();
    }

    /** Number of draggable band handles exposed by the canvas (six). */
    static int bandCount() {
        return FREQ_IDS.length;
    }
}
