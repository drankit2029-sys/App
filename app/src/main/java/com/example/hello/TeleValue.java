package com.example.hello;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Three-way value control: minus, value, plus.
 *
 * <ul>
 *   <li><b>Tap</b> minus/plus to step the value.</li>
 *   <li><b>Double-tap the value</b> to type an exact number.</li>
 *   <li><b>Tap and scroll left/right</b> anywhere on the control to scrub the
 *       value continuously, without lifting off.</li>
 * </ul>
 *
 * A single tap deliberately does nothing: no popup list and no keyboard.
 * Used for teleprompter text size / speed / words-per-line, the FX rack and
 * waveform zoom, so the same muscle memory applies everywhere.
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

    private int min;
    private int max = 100;
    private int step = 1;
    private int value;
    private String unit = "";

    public interface StepRule {
        int stepFor(int currentValue, int direction);
    }

    /**
     * Optional presentation override so a consumer can render dB, Hz or ratio
     * labels without subclassing. When unset, the numeric value is joined
     * with the configured unit.
     */
    public interface ValueFormatter {
        String formatValueText(int value);
    }

    private int valueScale = 1;
    private StepRule stepRule;
    private ValueFormatter valueFormatter;

    public void setValueFormatter(ValueFormatter formatter) {
        this.valueFormatter = formatter;
        if (label != null) {
            label.setText(formatValueText(value));
        }
    }

    /**
     * Fixed-point divisor for high-resolution controls (e.g. 100 for basis
     * points where 10000 = 100% and 92 = 0.92%). Default is 1 (integer).
     */
    public void setValueScale(int scale) {
        this.valueScale = Math.max(1, scale);
        if (label != null) {
            label.setText(formatValueText(value));
        }
    }

    public int getValueScale() {
        return valueScale;
    }

    public void setStepRule(StepRule rule) {
        this.stepRule = rule;
    }

    private int effectiveStep(int fromValue, int dir) {
        if (stepRule != null) {
            return Math.max(1, stepRule.stepFor(fromValue, dir));
        }
        return Math.max(1, step);
    }

    private String formatNumberPart(int v) {
        if (valueScale <= 1) {
            return String.valueOf(v);
        }
        if (v % valueScale == 0) {
            return String.valueOf(v / valueScale);
        }
        double d = v / (double) valueScale;
        if (Math.abs((long) v) >= 10L * valueScale) {
            String s = String.format(Locale.US, "%.1f", d);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s;
        }
        String s = String.format(Locale.US, "%.2f", d);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
                break;
            }
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private String formatValueText(int v) {
        if (valueFormatter != null) {
            return valueFormatter.formatValueText(v);
        }
        String num = formatNumberPart(v);
        return unit.isEmpty() ? num : num + unit;
    }

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
    private boolean verticalScrubbing;
    private boolean verticalScrubEnabled;
    private float downY;
    private int activePointerId = -1;
    private boolean movedBeyondSlop;
    private int dragStartValue;
    private float dragStartPos;
    private long lastTapAt = -DOUBLE_TAP_MS - 1;
    private boolean compact;
    private Dialog keyboardDialog;

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

        minus.setOnClickListener(v -> {
            int s = effectiveStep(value, -1);
            int next = value - s;
            // If stepping down lands within one step of min, snap to min.
            if (value > min && next < min) next = min;
            setValue(next, true);
        });
        plus.setOnClickListener(v -> {
            int s = effectiveStep(value, 1);
            setValue(value + s, true);
        });

        // A single tap is intentionally inert. Only a double-tap opens the
        // exact-value keyboard; a hold does nothing, so resting a finger on a
        // control can never cover the panel with a dialog.
        label.setOnClickListener(v -> {
            if (scrubbing || movedBeyondSlop) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastTapAt < DOUBLE_TAP_MS) {
                lastTapAt = 0;
                showKeyboardInput();
            } else {
                lastTapAt = now;
            }
        });

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
            // High-resolution labels ("0.92%") also need a slightly more
            // compact text size to fit cleanly inside the 40dp rail.
            // Plus sits on top, minus at the bottom, matching the drag
            // direction (up increases, down decreases).
            label.setTextSize(10.5f);
            controlRow.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            controlRow.addView(plus, new LayoutParams(button, height));
            controlRow.addView(label, new LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(getContext(), 20)));
            controlRow.addView(minus, new LayoutParams(button, height));
        } else {
            label.setTextSize(12f);
            controlRow.setLayoutParams(new LayoutParams(
                    LayoutParams.MATCH_PARENT, height));
            controlRow.addView(minus, new LayoutParams(button, height));
            controlRow.addView(label, new LayoutParams(0,
                    LayoutParams.WRAP_CONTENT, 1f));
            controlRow.addView(plus, new LayoutParams(button, height));
        }
        requestLayout();
    }

    /**
     * Compacts the control for dense FX cards: smaller caption, label and
     * stepper buttons without changing its gestures. Ignored in stacked mode
     * because that rail already has its own sizing.
     */
    public void setCompact(boolean compact) {
        this.compact = compact;
        Context ctx = getContext();
        if (ctx == null) return;
        if (caption != null) {
            caption.setTextSize(compact ? 8f : 8.5f);
        }
        if (label != null) {
            label.setTextSize(compact ? 11f : 12f);
        }
        if (!stacked && controlRow != null
                && minus != null && plus != null) {
            int size = dp(ctx, compact ? 22 : 26);
            minus.setLayoutParams(new LayoutParams(size, size));
            plus.setLayoutParams(new LayoutParams(size, size));
            if (controlRow.getLayoutParams() instanceof LayoutParams) {
                LayoutParams rp = (LayoutParams)
                        controlRow.getLayoutParams();
                rp.height = size;
                controlRow.setLayoutParams(rp);
            }
        }
        requestLayout();
    }

    public boolean isCompact() {
        return compact;
    }

    /** Values the wheel may show when {@code step > 1}, padded to range. */
    private int[] steppedValues() {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        if (stepRule != null) {
            int cur = min;
            out.add(cur);
            while (cur < max) {
                int s = Math.max(1, stepRule.stepFor(cur, 1));
                int next = (cur == min && s > 1)
                        ? ((cur / s) + 1) * s
                        : cur + s;
                if (next <= cur) next = cur + 1;
                if (next >= max) {
                    out.add(max);
                    break;
                }
                out.add(next);
                cur = next;
            }
        } else {
            int rem = ((min % step) + step) % step;
            int first = (rem == 0) ? min : Math.min(max, min + (step - rem));
            if (first > min && (valueScale > 1 || min < 0)) {
                out.add(min);
            }
            for (int v = first; v <= max; v += step) {
                out.add(v);
            }
            if (!out.isEmpty() && out.get(out.size() - 1) < max) {
                out.add(max);
            }
            if (out.isEmpty()) {
                for (int v = min; v <= max; v++) out.add(v);
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    private String[] steppedLabels() {
        int[] vals = steppedValues();
        String[] out = new String[vals.length];
        for (int i = 0; i < vals.length; i++) {
            out[i] = formatValueText(vals[i]);
        }
        return out;
    }

    /** Test hook: the wheel labels for the configured step. */
    String[] steppedLabelsForTest() {
        return steppedLabels();
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

    /**
     * Allows vertical drags to scrub the value (drag up increases), reported
     * through the same {@link OnScrubListener} as horizontal scrubs. Off by
     * default so a vertical pass over an unrelated control stays inert.
     */
    public void setVerticalScrub(boolean enabled) {
        verticalScrubEnabled = enabled;
    }

    public int getValue() {
        return value;
    }

    public int getMin() {
        return min;
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    public void setValue(int v, boolean notify) {
        int nv = clamp(v);
        String formatted = formatValueText(nv);
        if (nv == value && label != null
                && formatted.contentEquals(label.getText())) {
            return;
        }
        value = nv;
        if (label != null) {
            label.setText(formatted);
        }
        if (notify && listener != null) listener.onValueChanged(value);
    }

    // ---- keyboard ----

    private String captionTitle() {
        CharSequence cap = caption != null ? caption.getText() : null;
        if (cap != null && cap.length() > 0) {
            return cap.toString();
        }
        return getContext().getString(R.string.tele_value_picker);
    }

    private static void styleModalWindow(Dialog dialog) {
        OptionsSheet.styleAsCentered(dialog);
    }

    /** Overridable presentation hook so gesture routing is unit-testable. */
    protected void showKeyboardInput() {
        final Context ctx = getContext();
        if (ctx == null) return;
        if (keyboardDialog != null && keyboardDialog.isShowing()) return;
        try {
            final Dialog d = new Dialog(ctx, R.style.TakeOneCenterDialog);
            keyboardDialog = d;
            d.setOnDismissListener(dd -> {
                if (keyboardDialog == d) keyboardDialog = null;
            });
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);

            int pad = dp(ctx, 20);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.bg_modal);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                box.setElevation(dp(ctx, 16));
            }
            box.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(ctx);
            title.setText(captionTitle());
            title.setGravity(Gravity.CENTER);
            title.setTextColor(0xFFF2F4F7);
            title.setTextSize(18);
            title.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            box.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));

            TextView sub = new TextView(ctx);
            sub.setText(formatValueText(min) + " – " + formatValueText(max));
            sub.setGravity(Gravity.CENTER);
            sub.setTextColor(0xFF9AA3AF);
            sub.setTextSize(12);
            LayoutParams subLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(ctx, 4);
            box.addView(sub, subLp);

            LinearLayout inputBox = new LinearLayout(ctx);
            inputBox.setOrientation(LinearLayout.VERTICAL);
            inputBox.setBackgroundResource(R.drawable.bg_search);
            inputBox.setPadding(dp(ctx, 14), dp(ctx, 2),
                    dp(ctx, 14), dp(ctx, 2));
            LayoutParams ibLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            ibLp.topMargin = dp(ctx, 16);

            final EditText input = new EditText(ctx);
            input.setBackground(null);
            input.setGravity(Gravity.CENTER);
            input.setTextColor(0xFFF2F4F7);
            input.setHintTextColor(0xFF5D6672);
            input.setTextSize(16);
            input.setSingleLine(true);
            input.setMaxLines(1);
            // Signed keyboards appear only for ranges that can go negative
            // (e.g. a -24..+24 dB trim), so every existing positive-only
            // control keeps its familiar keypad.
            int inputType = InputType.TYPE_CLASS_NUMBER;
            if (min < 0) {
                inputType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
            }
            if (valueScale > 1) {
                inputType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
            }
            input.setInputType(inputType);
            input.setText(formatNumberPart(value));
            input.setSelectAllOnFocus(true);
            inputBox.addView(input, new LayoutParams(
                    LayoutParams.MATCH_PARENT, dp(ctx, 50)));
            box.addView(inputBox, ibLp);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LayoutParams rowLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(ctx, 14);

            TextView cancelBtn = new TextView(ctx);
            cancelBtn.setText(ctx.getString(R.string.cancel));
            cancelBtn.setTextSize(14);
            cancelBtn.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            cancelBtn.setGravity(Gravity.CENTER);
            cancelBtn.setTextColor(0xFF9AA3AF);
            cancelBtn.setBackgroundResource(R.drawable.bg_btn_ghost);

            TextView okBtn = new TextView(ctx);
            okBtn.setText(ctx.getString(R.string.settings_done));
            okBtn.setTextSize(14);
            okBtn.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            okBtn.setGravity(Gravity.CENTER);
            okBtn.setTextColor(0xFFFFFFFF);
            okBtn.setBackgroundResource(R.drawable.bg_btn_primary);

            LayoutParams half = new LayoutParams(0, dp(ctx, 48), 1f);
            half.rightMargin = dp(ctx, 6);
            LayoutParams half2 = new LayoutParams(0, dp(ctx, 48), 1f);
            half2.leftMargin = dp(ctx, 6);
            row.addView(cancelBtn, half);
            row.addView(okBtn, half2);
            box.addView(row, rowLp);
            PressAnim.apply(cancelBtn);
            PressAnim.apply(okBtn);

            d.setContentView(box);
            input.setImeOptions(android.view.inputmethod.EditorInfo
                    .IME_ACTION_DONE);
            input.setOnEditorActionListener((v2, actionId, ev) -> {
                if (actionId == android.view.inputmethod.EditorInfo
                        .IME_ACTION_DONE) {
                    commitTyped(input);
                    d.dismiss();
                    return true;
                }
                return false;
            });
            cancelBtn.setOnClickListener(v -> d.dismiss());
            okBtn.setOnClickListener(v -> {
                commitTyped(input);
                d.dismiss();
            });
            styleModalWindow(d);
            Window w = d.getWindow();
            if (w != null) {
                w.setSoftInputMode(WindowManager.LayoutParams
                        .SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
            // Back / outside-tap dismisses WITHOUT committing: cancelling
            // must never mutate the value.
        } catch (Exception ignored) {
            keyboardDialog = null;
        }
    }

    /** Parses and commits the typed value exactly once. */
    private void commitTyped(EditText input) {
        try {
            String raw = input.getText().toString().trim();
            if (raw.isEmpty()) return;
            int v = valueScale > 1
                    ? (int) Math.round(Double.parseDouble(raw) * valueScale)
                    : Integer.parseInt(raw);
            setValue(v, true);
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
     * consumed here, so a scrolling finger can never turn into a value change
     * or a stray double-tap.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                activePointerId = eventPointerId(ev, 0);
                movedBeyondSlop = false;
                scrubbing = false;
                verticalScrubbing = false;
                return super.dispatchTouchEvent(ev);
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger invalidates tap and double-tap semantics.
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
                    clearChildPress();
                }
                if (!scrubbing && !verticalScrubbing) {
                    // Claim the gesture once its direction is clear. A
                    // vertical move is consumed so it cannot become a tap
                    // when the finger lifts — unless vertical scrubbing is
                    // enabled, in which case it drives the value.
                    if (!movedBeyondSlop) {
                        return super.dispatchTouchEvent(ev);
                    }
                    if (Math.abs(dx) > Math.abs(dy)) {
                        scrubbing = true;
                        lastTapAt = 0;
                        dragStartValue = value;
                        dragStartPos = x;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    } else if (verticalScrubEnabled) {
                        verticalScrubbing = true;
                        lastTapAt = 0;
                        dragStartValue = value;
                        // Anchor at the current position like horizontal
                        // scrub does: anchoring at the touch-down point
                        // makes the value jump by the slop distance.
                        dragStartPos = y;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    } else {
                        return true;
                    }
                }
                if (verticalScrubbing) {
                    // Drag upward increases the value, drag downward decreases it,
                    // matching the direction the finger moves away from the
                    // starting point.
                    int delta = (int) Math.round((dragStartPos - y)
                            / dp(getContext(), 7));
                    int s = effectiveStep(dragStartValue, delta >= 0 ? 1 : -1);
                    setLiveValue(dragStartValue + delta * s);
                    return true;
                }
                int delta = (int) Math.round((x - dragStartPos)
                        / dp(getContext(), 7));
                scrubTo(dragStartValue, delta);
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
                if (scrubbing || verticalScrubbing) {
                    scrubbing = false;
                    verticalScrubbing = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    // A scrub reports through the scrub listener only. The
                    // discrete listener remains reserved for +/- and keyboard
                    // commits.
                    if (scrubListener != null) scrubListener.onScrubFinished(value);
                    performClick();
                    return true;
                }
                if (movedBeyondSlop) {
                    movedBeyondSlop = false;
                    return true;
                }
                return super.dispatchTouchEvent(ev);
            default:
                return super.dispatchTouchEvent(ev);
        }
    }

    private boolean finishInterruptedGesture() {
        activePointerId = -1;
        if (scrubbing || verticalScrubbing) {
            scrubbing = false;
            verticalScrubbing = false;
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

    /**
     * Applies a scrub delta measured in 7dp ticks. Without a step rule this
     * is one fixed step per tick; with a piecewise rule (e.g. zoom) each
     * tick steps from the running value so bracket boundaries are honored
     * instead of extrapolating a single step across the whole drag.
     */
    private void scrubTo(int startValue, int delta) {
        if (delta == 0) {
            setLiveValue(startValue);
            return;
        }
        if (stepRule == null) {
            int s = effectiveStep(startValue, delta >= 0 ? 1 : -1);
            setLiveValue(startValue + delta * s);
            return;
        }
        int dir = delta >= 0 ? 1 : -1;
        int v = startValue;
        for (int i = 0; i < Math.abs(delta); i++) {
            v += effectiveStep(v, dir);
        }
        setLiveValue(v);
    }

    @Override
    protected void onDetachedFromWindow() {
        scrubbing = false;
        verticalScrubbing = false;
        movedBeyondSlop = false;
        super.onDetachedFromWindow();
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
