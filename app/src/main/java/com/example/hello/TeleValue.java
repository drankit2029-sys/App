package com.example.hello;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.util.Locale;

/**
 * Four-way value control: minus, value, plus.
 *
 * <ul>
 *   <li><b>Tap</b> minus/plus to step the value.</li>
 *   <li><b>Tap the value</b> to open a vertical scrolling list (NumberPicker).</li>
 *   <li><b>Double-tap or long-press the value</b> to type an exact number.</li>
 *   <li><b>Tap and scroll left/right</b> anywhere on the control to scrub the
 *       value continuously, without lifting off.</li>
 * </ul>
 *
 * Used for teleprompter text size / speed / words-per-line and for waveform
 * zoom, so the same muscle memory applies in both places.
 */
public class TeleValue extends LinearLayout {

    public interface OnValueChanged {
        void onValueChanged(int value);
    }

    /**
     * Continuous scrub notifications. Unlike {@link OnValueChanged}, these fire
     * while the finger is still down; the final committed value is reported
     * through {@link #onScrubFinished(int)} rather than the discrete listener.
     */
    public interface OnScrubListener {
        void onScrubValueChanged(int value);
        void onScrubFinished(int value);
    }

    private static final int DOUBLE_TAP_MS = 280;
    private static final int PICKER_DELAY_MS = 280;

    private int min;
    private int max = 100;
    private int step = 1;
    private int value;
    private String unit = "";
    private OnValueChanged listener;
    private OnScrubListener scrubListener;

    private TextView caption;
    private LinearLayout controlRow;
    private boolean stacked;
    private TextView minus;
    private TextView plus;
    private TextView label;

    private float downX;
    private boolean scrubbing;
    private float downY;
    private int activePointerId = -1;
    private boolean downOnLabel;
    private boolean movedBeyondSlop;
    private boolean longPressFired;
    private int dragStartValue;
    private float dragStartX;
    private long lastTapAt = -DOUBLE_TAP_MS - 1;
    private Runnable pendingPicker;
    private final Runnable longPressCheck = () -> {
        // A genuine hold is stationary on the value. Movement cancels this
        // before it fires, so beginning a scroll cannot summon the keyboard.
        if (scrubbing || movedBeyondSlop || longPressFired || !downOnLabel) return;
        longPressFired = true;
        clearChildPress();
        if (label != null) {
            label.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        showKeyboardInput();
    };

    public TeleValue(Context context) {
        this(context, null);
    }

    /** Required for XML inflation; without it the studio screen crashes. */
    public TeleValue(Context context, AttributeSet attrs) {
        super(context, attrs);
        build(context);
    }

    private void build(Context ctx) {
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        caption = new TextView(ctx);
        caption.setTextSize(8.5f);
        caption.setGravity(Gravity.CENTER);
        caption.setTextColor(0xFF8A93A0);
        caption.setSingleLine(true);
        caption.setVisibility(GONE);
        addView(caption, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_search);
        row.setPadding(dp(ctx, 2), 0, dp(ctx, 2), 0);
        controlRow = row;

        minus = button(ctx, "−");
        plus = button(ctx, "+");

        label = new TextView(ctx);
        label.setTextColor(0xFFF2F4F7);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setMaxLines(1);
        label.setContentDescription("value");
        // The system long-press listener is intentionally not used: Android
        // would schedule the keyboard as soon as the finger rests, even if the
        // user then begins a scrub. A value hold is detected below instead.
        label.setLongClickable(false);

        row.addView(minus, new LayoutParams(dp(ctx, 26), dp(ctx, 24)));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0,
                        LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, lp);
        row.addView(plus, new LayoutParams(dp(ctx, 26), dp(ctx, 24)));

        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                dp(ctx, 26)));

        minus.setOnClickListener(v -> setValue(value - step, true));
        plus.setOnClickListener(v -> setValue(value + step, true));

        // Tap once -> picker. Double-tap -> keyboard.
        label.setOnClickListener(v -> {
            // A click can only arrive here when dispatchTouchEvent delivered a
            // clean tap. Scrubs, long holds, and moved gestures are consumed
            // before reaching the child.
            if (scrubbing || movedBeyondSlop || longPressFired) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastTapAt < DOUBLE_TAP_MS) {
                lastTapAt = 0;
                cancelPendingPicker();
                showKeyboardInput();
            } else {
                lastTapAt = now;
                cancelPendingPicker();
                pendingPicker = this::maybeOpenPicker;
                postDelayed(pendingPicker, PICKER_DELAY_MS);
            }
        });

    }

    private void cancelPendingPicker() {
        if (pendingPicker != null) {
            removeCallbacks(pendingPicker);
            pendingPicker = null;
        }
    }

    private void clearChildPress() {
        if (minus != null) {
            minus.cancelLongPress();
            minus.setPressed(false);
        }
        if (plus != null) {
            plus.cancelLongPress();
            plus.setPressed(false);
        }
        if (label != null) {
            label.cancelLongPress();
            label.setPressed(false);
        }
    }

    private boolean isOnLabel(float x, float y) {
        if (label == null) return false;
        Rect hit = new Rect();
        label.getHitRect(hit);
        return hit.contains(Math.round(x), Math.round(y));
    }

    private TextView button(Context ctx, String glyph) {
        TextView t = new TextView(ctx);
        t.setText(glyph);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFF2F4F7);
        t.setBackgroundResource(R.drawable.bg_round_btn);
        t.setContentDescription(glyph.equals("+") ? "increase" : "decrease");
        return t;
    }

    public boolean isStacked() {
        return stacked;
    }

    /** Small name shown above the stepper, so controls need no external label. */
    public void setCaption(String s) {
        if (caption == null) return;
        if (s == null || s.isEmpty()) {
            caption.setVisibility(GONE);
        } else {
            caption.setText(s);
            caption.setVisibility(VISIBLE);
        }
    }

    /**
     * Stacks minus/value/plus vertically for a narrow rail, such as waveform
     * zoom. Gesture handling is unchanged: a horizontal scrub still adjusts
     * the value, while taps retain their four behaviours.
     */
    public void setStacked(boolean stacked) {
        if (controlRow == null || minus == null || label == null || plus == null) {
            return;
        }
        this.stacked = stacked;
        int button = dp(getContext(), 26);
        int height = dp(getContext(), 24);
        controlRow.setOrientation(stacked ? VERTICAL : HORIZONTAL);
        controlRow.setGravity(stacked ? Gravity.CENTER_HORIZONTAL
                : Gravity.CENTER_VERTICAL);
        controlRow.removeAllViews();
        if (stacked) {
            // The row must wrap its content: it was built for a single 24dp
            // horizontal strip, which would crush a vertical stack to 2px.
            controlRow.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            controlRow.addView(minus, new LayoutParams(button, height));
            controlRow.addView(label, new LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(getContext(), 20)));
            controlRow.addView(plus, new LayoutParams(button, height));
        } else {
            controlRow.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, height));
            controlRow.addView(minus, new LayoutParams(button, height));
            controlRow.addView(label, new LayoutParams(0,
                    LayoutParams.WRAP_CONTENT, 1f));
            controlRow.addView(plus, new LayoutParams(button, height));
        }
        requestLayout();
    }

    private void maybeOpenPicker() {
        if (android.os.SystemClock.uptimeMillis() - lastTapAt >= 270) {
            lastTapAt = 0;
            openPicker();
        }
    }

    /** Test hook: whether a single-tap picker is still scheduled. */
    boolean hasPendingPickerForTest() {
        return pendingPicker != null;
    }

    /** Test hook: runs the pending value-hold check without waiting. */
    void runPendingLongPressForTest() {
        longPressCheck.run();
    }

    public void configure(int min, int max, int step, int value, String unit) {
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.unit = unit == null ? "" : unit;
        setValue(value, false);
    }

    public void setOnValueChanged(OnValueChanged l) {
        listener = l;
    }

    public void setOnScrubListener(OnScrubListener l) {
        scrubListener = l;
    }

    public int getValue() {
        return value;
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    public void setValue(int v, boolean notify) {
        int nv = clamp(v);
        if (nv == value && label != null && label.getText().length() > 0) {
            return;
        }
        value = nv;
        if (label != null) {
            label.setText(unit.isEmpty() ? String.valueOf(value)
                    : value + unit);
        }
        if (notify && listener != null) listener.onValueChanged(value);
    }

    // ---- vertical scrolling list ----

    private void openPicker() {
        showValuePicker();
    }

    /** Overridable presentation hook so gesture routing is unit-testable. */
    protected void showValuePicker() {
        final Context ctx = getContext();
        if (ctx == null) return;
        try {
            NumberPicker np = new NumberPicker(ctx);
            np.setMinValue(min);
            np.setMaxValue(max);
            np.setValue(value);
            np.setWrapSelectorWheel(false);
            final Dialog d = new Dialog(ctx, R.style.TakeOneCenterDialog);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.bg_modal);
            int pad = dp(ctx, 18);
            box.setPadding(pad, pad, pad, pad);
            TextView title = new TextView(ctx);
            title.setText(label != null ? label.getText().toString() : "");
            title.setGravity(Gravity.CENTER);
            title.setTextColor(0xFFF2F4F7);
            title.setTextSize(15);
            title.setPadding(0, 0, 0, dp(ctx, 8));
            box.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
            box.addView(np, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
            TextView ok = new TextView(ctx);
            ok.setText(R.string.settings_done);
            ok.setTextSize(14);
            ok.setGravity(Gravity.CENTER);
            ok.setTextColor(0xFFFFFFFF);
            ok.setBackgroundResource(R.drawable.bg_btn_primary);
            ok.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
            LinearLayout.LayoutParams okp = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            okp.topMargin = dp(ctx, 10);
            box.addView(ok, okp);
            d.setContentView(box);
            np.setOnValueChangedListener((p, i, v) -> setValue(v, true));
            ok.setOnClickListener(v -> d.dismiss());
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.62f);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.CENTER;
                w.setAttributes(lp);
            }
            d.show();
        } catch (Exception ignored) {
        }
    }

    // ---- keyboard ----

    private void promptKeyboard() {
        showKeyboardInput();
    }

    /** Overridable presentation hook so gesture routing is unit-testable. */
    protected void showKeyboardInput() {
        final Context ctx = getContext();
        if (ctx == null) return;
        try {
            final EditText input = new EditText(ctx);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(value));
            input.setSelectAllOnFocus(true);
            int pad = dp(ctx, 20);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.bg_modal);
            box.setPadding(pad, pad, pad, pad);
            box.addView(input, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
            final Dialog d = new Dialog(ctx, R.style.TakeOneCenterDialog);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setContentView(box);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.62f);
                w.setSoftInputMode(WindowManager.LayoutParams
                        .SOFT_INPUT_STATE_VISIBLE);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.CENTER;
                w.setAttributes(lp);
            }
            d.setOnShowListener(dd -> input.requestFocus());
            d.show();
            input.post(() -> {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) ctx
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input,
                            android.view.inputmethod.InputMethodManager
                                    .SHOW_IMPLICIT);
                }
            });
            d.setOnDismissListener(dd -> {
                try {
                    int v = Integer.parseInt(input.getText().toString().trim());
                    setValue(v, true);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * Horizontal scrubs are intercepted here rather than in an
     * {@code OnTouchListener}: the minus, value and plus children are all
     * clickable, so they consume ACTION_DOWN and the parent would never see
     * the gesture at all.
     *
     * <p>Only a clean tap reaches the children. Motion beyond touch slop is
     * consumed here, which also prevents a scrolling finger from tripping the
     * delayed single-tap picker or a pending value hold.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelPendingPicker();
                removeCallbacks(longPressCheck);
                downX = ev.getX();
                downY = ev.getY();
                activePointerId = eventPointerId(ev, 0);
                downOnLabel = isOnLabel(ev.getX(), ev.getY());
                movedBeyondSlop = false;
                scrubbing = false;
                longPressFired = false;
                if (downOnLabel) {
                    postDelayed(longPressCheck,
                            ViewConfiguration.getLongPressTimeout());
                }
                return super.dispatchTouchEvent(ev);
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger invalidates tap/double-tap/hold semantics.
                cancelPendingPicker();
                removeCallbacks(longPressCheck);
                movedBeyondSlop = true;
                clearChildPress();
                return true;
            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex < 0) return true;
                float x = ev.getX(pointerIndex);
                float y = ev.getY(pointerIndex);
                float dx = x - downX;
                float dy = y - downY;
                float slop = ViewConfiguration.get(getContext())
                        .getScaledTouchSlop();
                if (!movedBeyondSlop
                        && Math.max(Math.abs(dx), Math.abs(dy)) > slop) {
                    movedBeyondSlop = true;
                    removeCallbacks(longPressCheck);
                    cancelPendingPicker();
                    clearChildPress();
                }
                if (!scrubbing) {
                    // Claim the gesture only once it is clearly horizontal.
                    if (!movedBeyondSlop || Math.abs(dx) <= Math.abs(dy)) {
                        // A vertical move is consumed so it cannot become a
                        // tap when the finger lifts.
                        return movedBeyondSlop || super.dispatchTouchEvent(ev);
                    }
                    scrubbing = true;
                    dragStartValue = value;
                    dragStartX = x;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                int delta = (int) Math.round((x - dragStartX)
                        / dp(getContext(), 7));
                setLiveValue(dragStartValue + delta * step);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int lifted = eventPointerId(ev, ev.getActionIndex());
                if (lifted == activePointerId) {
                    return finishInterruptedGesture();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressCheck);
                if (scrubbing) {
                    scrubbing = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    // A scrub reports through the scrub listener only. The
                    // discrete listener remains reserved for taps, picker and
                    // keyboard commits.
                    if (scrubListener != null) scrubListener.onScrubFinished(value);
                    performClick();
                    return true;
                }
                if (longPressFired || movedBeyondSlop) {
                    longPressFired = false;
                    movedBeyondSlop = false;
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            default:
                return super.dispatchTouchEvent(ev);
        }
    }

    private boolean finishInterruptedGesture() {
        removeCallbacks(longPressCheck);
        cancelPendingPicker();
        activePointerId = -1;
        if (scrubbing) {
            scrubbing = false;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (scrubListener != null) scrubListener.onScrubFinished(value);
            performClick();
        }
        movedBeyondSlop = true;
        return true;
    }

    private static int eventPointerId(MotionEvent ev, int pointerIndex) {
        try {
            return ev.getPointerId(pointerIndex);
        } catch (Exception e) {
            return -1;
        }
    }

    private void setLiveValue(int v) {
        int before = value;
        setValue(v, false);
        if (value != before && scrubListener != null) {
            scrubListener.onScrubValueChanged(value);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingPicker();
        removeCallbacks(longPressCheck);
        scrubbing = false;
        movedBeyondSlop = false;
        longPressFired = false;
        super.onDetachedFromWindow();
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}