package com.example.hello;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
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

    private int min;
    private int max = 100;
    private int step = 1;
    private int value;
    private String unit = "";
    private OnValueChanged listener;

    private TextView caption;
    private TextView minus;
    private TextView plus;
    private TextView label;

    private float downX;
    private float lastX;
    private boolean scrubbing;
    private float downY;
    private int dragStartValue;
    private long lastTapAt;

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

        minus = button(ctx, "−");
        plus = button(ctx, "+");

        label = new TextView(ctx);
        label.setTextColor(0xFFF2F4F7);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setMaxLines(1);
        label.setContentDescription("value");

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
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastTapAt < 280) {
                lastTapAt = 0;
                postDelayed(() -> {
                }, 300);
                promptKeyboard();
            } else {
                lastTapAt = now;
                postDelayed(this::maybeOpenPicker, 280);
            }
        });
        label.setOnLongClickListener(v -> {
            if (label != null) label.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS);
            promptKeyboard();
            return true;
        });

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

    private void maybeOpenPicker() {
        if (android.os.SystemClock.uptimeMillis() - lastTapAt >= 270) {
            lastTapAt = 0;
            openPicker();
        }
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
     * <p>Taps still fall through to the children, which keep their behaviour.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                lastX = ev.getX();
                downY = ev.getY();
                scrubbing = false;
                return super.dispatchTouchEvent(ev);
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                float slop = dp(getContext(), 6);
                if (!scrubbing) {
                    // Claim the gesture only once it is clearly horizontal.
                    if (Math.abs(dx) < slop || Math.abs(dx) <= Math.abs(dy)) {
                        return super.dispatchTouchEvent(ev);
                    }
                    scrubbing = true;
                    dragStartValue = value;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                lastX = ev.getX();
                int delta = (int) Math.round((ev.getX() - downX)
                        / dp(getContext(), 7));
                setValue(dragStartValue + delta * step, true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scrubbing) {
                    scrubbing = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    performClick();
                    return true;
                }
                scrubbing = false;
                return super.dispatchTouchEvent(ev);
            default:
                return super.dispatchTouchEvent(ev);
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}