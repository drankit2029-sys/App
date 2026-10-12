package com.example.hello;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Centered modal dialogs (overlaying the current screen) replacing the old
 * bottom sheets. Sort options appear as distinct pills, rename is a centered
 * input modal, deletes ask for confirmation, and every success/failure gets
 * its own styled popup. All dialogs pop/scale in and out.
 */
public final class OptionsSheet {

    private OptionsSheet() {}

    public static final class Item {
        public final int icon;
        public final String label;
        public final String sub;
        public final boolean destructive;
        public final boolean checked;
        public final Runnable action;

        public Item(int icon, String label, boolean destructive, boolean checked,
                    Runnable action) {
            this(icon, label, null, destructive, checked, action);
        }

        public Item(int icon, String label, String sub, boolean destructive,
                    boolean checked, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.sub = sub;
            this.destructive = destructive;
            this.checked = checked;
            this.action = action;
        }

        public static Item of(int icon, String label, Runnable action) {
            return new Item(icon, label, false, false, action);
        }

        public static Item danger(int icon, String label, Runnable action) {
            return new Item(icon, label, true, false, action);
        }

        /** Two-line row: title + smaller spec line (presets, save formats). */
        public static Item detail(String label, String sub, boolean checked,
                                  Runnable action) {
            return new Item(0, label, sub, false, checked, action);
        }
    }

    /** One save-format toggle chip inside the unified recording dialog. */
    public static final class Chip {
        public final String label;
        /** Approximate storage per minute, e.g. "17 MB/min". */
        public final String size;
        public final boolean checked;
        public final Runnable action;

        public Chip(String label, String size, boolean checked,
                    Runnable action) {
            this.label = label;
            this.size = size;
            this.checked = checked;
            this.action = action;
        }

        public static Chip of(String label, String size, boolean checked,
                              Runnable action) {
            return new Chip(label, size, checked, action);
        }
    }

    /**
     * Live state behind the unified recording dialog. Lists are re-queried
     * after every tap so checkmarks follow the actual selection; order must
     * be stable across calls.
     */
    public interface SettingsState {
        java.util.List<Item> presets();
        java.util.List<Chip> formats();
        String formatHint();
    }

    public interface SaveCallback {
        void onSave(String text);
    }

    /** Input modal that also reports when the user backs out / cancels. */
    public static void showInputWithCancel(Context ctx, String title, String initial,
                                           String saveLabel, SaveCallback callback,
                                           Runnable onCancel) {
        showInputInternal(ctx, title, initial, saveLabel, callback, onCancel, false);
    }

    /**
     * Same, but {@code allowSame} skips the "already the current name" error so
     * accepting the suggested default is one tap.
     */
    public static void showInputWithCancel(Context ctx, String title, String initial,
                                           String saveLabel, SaveCallback callback,
                                           Runnable onCancel, boolean allowSame) {
        showInputInternal(ctx, title, initial, saveLabel, callback, onCancel, allowSame);
    }

    // ---- centered pill options (sort, export, …) ----

    public static void show(Context ctx, String title, List<Item> items) {
        showOptions(ctx, title, null, items);
    }

    public static void showOptions(Context ctx, String title, String subtitle,
                                   List<Item> items) {
        Dialog dialog = new Dialog(ctx, R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_options, null);
        dialog.setContentView(root);

        TextView titleView = root.findViewById(R.id.dialogTitle);
        titleView.setText(title);
        TextView subView = root.findViewById(R.id.dialogSubtitle);
        if (subtitle != null && !subtitle.isEmpty()) {
            subView.setText(subtitle);
            subView.setVisibility(View.VISIBLE);
        }

        LinearLayout container = root.findViewById(R.id.dialogOptions);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (int i = 0; i < items.size(); i++) {
            final Item item = items.get(i);
            View pillWrap = new FrameLayout(ctx);
            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) wrapLp.topMargin = dp(ctx, 8);
            pillWrap.setLayoutParams(wrapLp);

            View row = inflater.inflate(R.layout.dialog_pill_row, (FrameLayout) pillWrap, false);
            row.setBackgroundResource(item.checked
                    ? R.drawable.bg_pill_option_selected
                    : R.drawable.bg_pill_option);
            TextView label = row.findViewById(R.id.pillLabel);
            label.setText(item.label);
            if (item.destructive) {
                label.setTextColor(0xFFFF3B30);
            } else if (item.checked) {
                label.setTextColor(0xFFF2F4F7);
            }
            TextView sub = row.findViewById(R.id.pillSub);
            if (sub != null) {
                if (item.sub != null && !item.sub.isEmpty()) {
                    sub.setText(item.sub);
                    sub.setVisibility(View.VISIBLE);
                } else {
                    sub.setVisibility(View.GONE);
                }
            }
            ImageView iconView = row.findViewById(R.id.pillDot);
            if (item.icon != 0) {
                // Real action icon (share / folder / …) tinted to the row.
                iconView.setImageResource(item.icon);
                iconView.setAlpha(item.checked ? 1f : 0.8f);
                iconView.setColorFilter(item.destructive
                        ? 0xFFFF3B30 : 0xFFF2F4F7);
                iconView.setVisibility(View.VISIBLE);
            } else {
                // Sort-style rows keep a small leading dot as the selection cue.
                iconView.setImageResource(R.drawable.bg_thumb);
                iconView.setColorFilter(item.checked ? 0xFFFF3B30 : 0xFF5D6672);
                int inset = dp(ctx, 5);
                iconView.setPadding(inset, inset, inset, inset);
                iconView.setVisibility(View.VISIBLE);
            }
            ImageView check = row.findViewById(R.id.pillCheck);
            check.setVisibility(item.checked ? View.VISIBLE : View.GONE);

            PressAnim.apply(row);
            row.setOnClickListener(v -> {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction(() -> {
                    dialog.dismiss();
                    if (item.action != null) item.action.run();
                }).start();
            });
            ((FrameLayout) pillWrap).addView(row);
            container.addView(pillWrap);

            // Staggered entrance: each pill slides up + fades in.
            row.setAlpha(0f);
            row.setTranslationY(dp(ctx, 14));
            row.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(60 + i * 55L)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .start();
        }

        TextView cancel = root.findViewById(R.id.dialogCancel);
        cancel.setText(ctx.getString(R.string.cancel));
        PressAnim.apply(cancel);
        cancel.setOnClickListener(v -> dialog.dismiss());

        styleAsCentered(dialog);
        dialog.show();
    }

    // ---- unified recording settings: presets + format toggles ----

    /**
     * One dialog for everything that shapes the next take: the preset pills
     * on top, the save-format toggles below. Taps mutate the caller's state
     * through the item actions and the dialog rebinds in place (no
     * dismiss-rebuild flicker); Done closes.
     */
    public static void showRecordingSettings(Context ctx, String title,
                                             String presetLabel, String presetHint,
                                             String formatLabel, SettingsState state,
                                             String doneLabel) {
        Dialog dialog = new Dialog(ctx, R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        // Solid card like every other dialog: without a background the gaps
        // between rows show the dimmed activity straight through.
        root.setBackgroundResource(R.drawable.bg_modal);
        if (android.os.Build.VERSION.SDK_INT >= 21) root.setElevation(dp(ctx, 16));
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root,
                new android.widget.ScrollView.LayoutParams(
                        android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                        android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(0xFFF2F4F7);
        root.addView(titleView);

        root.addView(sectionLabel(ctx, presetLabel));
        if (presetHint != null && !presetHint.isEmpty()) {
            TextView hint = new TextView(ctx);
            hint.setText(presetHint);
            hint.setTextSize(11);
            hint.setTextColor(0xFF9AA3AF);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            hlp.bottomMargin = dp(ctx, 4);
            root.addView(hint, hlp);
        }

        LayoutInflater inflater = LayoutInflater.from(ctx);
        java.util.List<View> pillRows = new java.util.ArrayList<>();
        java.util.List<TextView> pillLabels = new java.util.ArrayList<>();
        java.util.List<ImageView> pillChecks = new java.util.ArrayList<>();
        java.util.List<Integer> pillDefaultColors = new java.util.ArrayList<>();
        // Built after the pills but referenced by their tap handler, so the
        // chip state travels in boxes created up front.
        final java.util.List<View> chipViews = new java.util.ArrayList<>();
        final java.util.List<TextView> chipNames = new java.util.ArrayList<>();
        final java.util.List<TextView> chipSizes = new java.util.ArrayList<>();
        final TextView formatHintView = new TextView(ctx);
        formatHintView.setTextSize(11);
        formatHintView.setTextColor(0xFF9AA3AF);
        for (Item item : state.presets()) {
            final Item it = item;
            View row = inflater.inflate(R.layout.dialog_pill_row, root, false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(ctx, 8);
            root.addView(row, rlp);
            TextView label = row.findViewById(R.id.pillLabel);
            label.setText(it.label);
            TextView sub = row.findViewById(R.id.pillSub);
            if (sub != null) {
                if (it.sub != null && !it.sub.isEmpty()) {
                    sub.setText(it.sub);
                    sub.setVisibility(View.VISIBLE);
                } else {
                    sub.setVisibility(View.GONE);
                }
            }
            ImageView dot = row.findViewById(R.id.pillDot);
            dot.setImageResource(R.drawable.bg_thumb);
            int inset = dp(ctx, 5);
            dot.setPadding(inset, inset, inset, inset);
            dot.setVisibility(View.VISIBLE);
            ImageView check = row.findViewById(R.id.pillCheck);
            pillRows.add(row);
            pillLabels.add(label);
            pillChecks.add(check);
            pillDefaultColors.add(label.getTextColors().getDefaultColor());
            PressAnim.apply(row);
            row.setOnClickListener(v -> {
                if (it.action != null) it.action.run();
                rebindSettings(state, pillRows, pillLabels, pillChecks,
                        pillDefaultColors, chipViews, chipNames, chipSizes,
                        formatHintView);
            });
        }

        View divider = new View(ctx);
        divider.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        dlp.topMargin = dp(ctx, 14);
        dlp.bottomMargin = dp(ctx, 2);
        root.addView(divider, dlp);

        root.addView(sectionLabel(ctx, formatLabel));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.bottomMargin = dp(ctx, 4);
        root.addView(formatHintView, flp);

        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        for (Chip chip : state.formats()) {
            final Chip c = chip;
            // Two lines: format name over its approximate size per minute,
            // so storage cost is visible before committing to a format.
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.bg_chip);
            int hPad = dp(ctx, 6);
            cell.setPadding(hPad, dp(ctx, 8), hPad, dp(ctx, 8));
            cell.setMinimumHeight(dp(ctx, 46));

            TextView name = new TextView(ctx);
            name.setText(c.label);
            name.setTextSize(12);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(name);

            TextView size = new TextView(ctx);
            size.setText(c.size);
            size.setTextSize(10);
            size.setGravity(Gravity.CENTER);
            size.setSingleLine(true);
            size.setAlpha(0.85f);
            cell.addView(size);

            android.widget.GridLayout.LayoutParams glp =
                    new android.widget.GridLayout.LayoutParams();
            glp.width = 0;
            glp.columnSpec = android.widget.GridLayout.spec(
                    android.widget.GridLayout.UNDEFINED, 1f);
            int m = dp(ctx, 4);
            glp.setMargins(m, m, m, m);
            cell.setLayoutParams(glp);
            grid.addView(cell);
            chipViews.add(cell);
            chipNames.add(name);
            chipSizes.add(size);
            PressAnim.apply(cell);
            cell.setOnClickListener(v -> {
                if (c.action != null) c.action.run();
                rebindSettings(state, pillRows, pillLabels, pillChecks,
                        pillDefaultColors, chipViews, chipNames, chipSizes,
                        formatHintView);
            });
        }

        TextView done = new TextView(ctx);
        done.setText(doneLabel);
        done.setTextSize(15);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        done.setTextColor(0xFFFFFFFF);
        done.setGravity(Gravity.CENTER);
        done.setBackgroundResource(R.drawable.bg_btn_primary);
        done.setMinHeight(dp(ctx, 48));
        int vPad = dp(ctx, 13);
        done.setPadding(dp(ctx, 16), vPad, dp(ctx, 16), vPad);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(ctx, 16);
        root.addView(done, blp);
        PressAnim.apply(done);
        done.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(scroll);
        rebindSettings(state, pillRows, pillLabels, pillChecks,
                pillDefaultColors, chipViews, chipNames, chipSizes,
                formatHintView);
        styleAsCentered(dialog);
        dialog.show();
    }

    private static TextView sectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setLetterSpacing(0.1f);
        tv.setTextColor(0xFF9AA3AF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 12);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static void rebindSettings(SettingsState state,
                                       java.util.List<View> pillRows,
                                       java.util.List<TextView> pillLabels,
                                       java.util.List<ImageView> pillChecks,
                                       java.util.List<Integer> pillColors,
                                       java.util.List<View> chipViews,
                                       java.util.List<TextView> chipNames,
                                       java.util.List<TextView> chipSizes,
                                       TextView hintView) {
        java.util.List<Item> presets;
        java.util.List<Chip> formats;
        try {
            presets = state.presets();
            formats = state.formats();
        } catch (Exception ignored) {
            return;
        }
        for (int i = 0; i < pillRows.size() && i < presets.size(); i++) {
            Item it = presets.get(i);
            pillRows.get(i).setBackgroundResource(it.checked
                    ? R.drawable.bg_pill_option_selected
                    : R.drawable.bg_pill_option);
            pillLabels.get(i).setTextColor(it.checked
                    ? 0xFFF2F4F7 : pillColors.get(i));
            pillChecks.get(i).setVisibility(
                    it.checked ? View.VISIBLE : View.GONE);
            ImageView dot = pillRows.get(i).findViewById(R.id.pillDot);
            if (dot != null) dot.setColorFilter(
                    it.checked ? 0xFFFF3B30 : 0xFF5D6672);
        }
        for (int i = 0; i < chipViews.size() && i < formats.size(); i++) {
            Chip c = formats.get(i);
            chipViews.get(i).setBackgroundResource(c.checked
                    ? R.drawable.bg_chip_selected
                    : R.drawable.bg_chip);
            int col = c.checked ? 0xFFFFFFFF : 0xFF9AA3AF;
            chipNames.get(i).setTextColor(col);
            chipSizes.get(i).setTextColor(col);
            // Size text follows the resolved format, so a preset switch
            // (mono vs stereo) updates the storage estimate in place.
            chipSizes.get(i).setText(c.size);
            chipNames.get(i).setText(c.label);
        }
        try {
            hintView.setText(state.formatHint());
        } catch (Exception ignored) {}
    }

    // ---- centered rename / input modal ----

    public static void showInput(Context ctx, String title, String initial,
                                 String saveLabel, SaveCallback callback) {
        showInputInternal(ctx, title, initial, saveLabel, callback, null, false);
    }

    private static void showInputInternal(Context ctx, String title, String initial,
                                          String saveLabel, SaveCallback callback,
                                          Runnable onCancel, boolean allowSame) {
        Dialog dialog = new Dialog(ctx, R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_input, null);
        dialog.setContentView(root);

        ((TextView) root.findViewById(R.id.dialogTitle)).setText(title);
        EditText input = root.findViewById(R.id.dialogInput);
        TextView error = root.findViewById(R.id.dialogError);
        input.setText(initial == null ? "" : initial);
        input.setHint(ctx.getString(R.string.rename_hint));
        try {
            input.setSelection(input.getText().length());
        } catch (Exception ignored) {}

        TextView cancel = root.findViewById(R.id.dialogCancel);
        TextView save = root.findViewById(R.id.dialogSave);
        cancel.setText(ctx.getString(R.string.cancel));
        save.setText(saveLabel);
        PressAnim.apply(cancel);
        PressAnim.apply(save);

        final boolean[] settled = {false};
        cancel.setOnClickListener(v -> {
            hideKeyboard(ctx, input);
            settled[0] = true;
            dialog.dismiss();
            if (onCancel != null) onCancel.run();
        });
        try {
            dialog.setOnCancelListener(d -> {
                if (!settled[0] && onCancel != null) onCancel.run();
            });
        } catch (Exception ignored) {}
        save.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                error.setText(ctx.getString(R.string.rename_empty_error));
                error.setVisibility(View.VISIBLE);
                shake(input);
                return;
            }
            if (!allowSame && text.equals(initial != null ? initial.trim() : "")) {
                error.setText(ctx.getString(R.string.rename_same_error));
                error.setVisibility(View.VISIBLE);
                shake(input);
                return;
            }
            hideKeyboard(ctx, input);
            settled[0] = true;
            dialog.dismiss();
            if (callback != null) callback.onSave(text);
        });

        // Entrance pop for the whole card is handled by window anim;
        // focus + keyboard slide in after.
        styleAsCentered(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        input.post(() -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    // ---- confirm delete modal ----

    public static void showConfirm(Context ctx, String title, String message,
                                   String confirmLabel, boolean danger,
                                   Runnable onConfirm) {
        showConfirm(ctx, title, message, confirmLabel,
                danger ? R.drawable.ic_trash : R.drawable.ic_check, danger, onConfirm);
    }

    public static void showConfirm(Context ctx, String title, String message,
                                   String confirmLabel, int iconRes,
                                   boolean danger, Runnable onConfirm) {
        Dialog dialog = new Dialog(ctx, R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_confirm, null);
        dialog.setContentView(root);

        ((TextView) root.findViewById(R.id.dialogTitle)).setText(title);
        ((TextView) root.findViewById(R.id.dialogMessage)).setText(message);
        ImageView icon = root.findViewById(R.id.dialogIcon);
        icon.setImageResource(iconRes);
        View iconBg = root.findViewById(R.id.dialogIcon).getParent() instanceof View
                ? (View) root.findViewById(R.id.dialogIcon).getParent() : null;

        TextView cancel = root.findViewById(R.id.dialogCancel);
        TextView confirm = root.findViewById(R.id.dialogConfirm);
        cancel.setText(ctx.getString(R.string.cancel));
        confirm.setText(confirmLabel);
        if (danger) {
            confirm.setBackgroundResource(R.drawable.bg_btn_primary);
            confirm.setTextColor(0xFFFFFFFF);
        } else {
            confirm.setBackgroundResource(R.drawable.bg_btn_primary);
        }
        PressAnim.apply(cancel);
        PressAnim.apply(confirm);

        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction(() -> {
                dialog.dismiss();
                if (onConfirm != null) onConfirm.run();
            }).start();
        });

        styleAsCentered(dialog);
        dialog.show();
        if (iconBg != null) PressAnim.pop(iconBg);
    }

    // ---- success / failure result popup ----

    public static void showResult(Context ctx, boolean success,
                                  String title, String message) {
        showResult(ctx, success, title, message, null);
    }

    public static void showResult(Context ctx, boolean success,
                                  String title, String message,
                                  Runnable onOk) {
        Dialog dialog = new Dialog(ctx, R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_result, null);
        dialog.setContentView(root);

        FrameLayout iconBg = root.findViewById(R.id.resultIconBg);
        ImageView icon = root.findViewById(R.id.resultIcon);
        iconBg.setBackgroundResource(success
                ? R.drawable.bg_icon_success : R.drawable.bg_icon_danger);
        icon.setImageResource(success ? R.drawable.ic_check : R.drawable.ic_close);
        // Failure icon tints red for clarity.
        icon.setColorFilter(success ? 0xFF34C759 : 0xFFFF3B30);

        ((TextView) root.findViewById(R.id.resultTitle)).setText(title);
        TextView msg = root.findViewById(R.id.resultMessage);
        if (message != null && !message.isEmpty()) {
            msg.setText(message);
            msg.setVisibility(View.VISIBLE);
        }
        TextView ok = root.findViewById(R.id.resultOk);
        boolean needsButton = onOk != null;
        ok.setVisibility(needsButton ? View.VISIBLE : View.GONE);
        if (needsButton) {
            ok.setText(ctx.getString(R.string.ok_got_it));
            PressAnim.apply(ok);
            ok.setOnClickListener(v -> {
                dialog.dismiss();
                onOk.run();
            });
        }

        dialog.setCancelable(true);
        styleAsCentered(dialog);
        dialog.show();
        PressAnim.pop(iconBg);

        // Auto-dismiss success toasts so they feel like confirmations, not chores.
        // Failures stay until tapped (or also auto-dismiss after a beat longer).
        long delay = success ? 1600 : 2400;
        if (onOk == null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) {}
            }, delay);
        }
    }

    // ---- helpers ----

    private static void styleAsCentered(Dialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(0.62f);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        // Side margin so the modal floats as a card over the current screen.
        lp.horizontalMargin = 0f;
        w.setAttributes(lp);
        // Pad the decor view so MATCH_PARENT still leaves breathing room.
        View decor = w.getDecorView();
        if (decor != null) {
            int m = dp(dialog.getContext(), 28);
            decor.setPadding(m, 0, m, 0);
        }
    }

    private static void hideKeyboard(Context ctx, View v) {
        try {
            InputMethodManager imm = (InputMethodManager)
                    ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private static void shake(View v) {
        v.animate().cancel();
        v.animate()
                .translationX(14f).setDuration(55)
                .withEndAction(() -> v.animate().translationX(-12f).setDuration(55)
                        .withEndAction(() -> v.animate().translationX(8f).setDuration(55)
                                .withEndAction(() -> v.animate().translationX(0f)
                                        .setDuration(80).start()).start()).start())
                .start();
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
