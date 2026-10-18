package com.example.hello;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Teleprompter dialogs: the script editor (with .txt/.docx import).
 *
 * <p>Tuning (speed, words-per-line, size, mirror) lives in the studio's
 * inline {@code TeleValue} controls now; the old tuner sheet is gone.
 */
public final class TeleDialogs {

    private TeleDialogs() {}

    /**
     * Views of the currently open editor, so a file picked while the dialog
     * is up can drop the text straight in. Weak references: a static strong
     * reference would leak the whole destroyed Activity if it rotates while
     * the dialog is open. Cleared when the editor closes.
     */
    private static java.lang.ref.WeakReference<android.widget.EditText> openInput;
    private static java.lang.ref.WeakReference<TextView> openMeta;

    /** Replaces the open editor's contents with imported text. */
    public static void applyImported(Context ctx, String text, String name,
                                     int wpm) {
        android.widget.EditText input =
                openInput != null ? openInput.get() : null;
        TextView meta = openMeta != null ? openMeta.get() : null;
        if (input == null || text == null) return;
        input.setText(text);
        input.setSelection(input.getText().length());
        if (meta != null) {
            meta.setText(ctx.getString(R.string.tele_imported, name,
                    TeleMath.wordCount(text)));
        }
    }

    public interface ScriptCallback {
        void onScript(String script);
    }

    public interface ImportRequest {
        void onRequestImport();
    }

    /**
     * Window styling with landscape width and height bounds so the editor
     * modal never overflows the screen or hides its Save/Import action row.
     */
    private static void style(Dialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        Context ctx = d.getContext();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        boolean isLand = dm.widthPixels > dm.heightPixels;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(0.62f);
        WindowManager.LayoutParams lp = w.getAttributes();
        int maxW = dp(ctx, isLand ? 460 : 400);
        lp.width = Math.min(dm.widthPixels - dp(ctx, 32), maxW);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        w.setAttributes(lp);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View decor = w.getDecorView();
        if (decor != null) {
            int vPad = dp(ctx, isLand ? 8 : 16);
            decor.setPadding(0, vPad, 0, vPad);
        }
    }

    // ---- editor ----

    /** @return the shown dialog, so callers/tests can drive its controls. */
    public static Dialog showEditor(Context ctx, String script, int wpm,
                                    ScriptCallback onSave,
                                    ImportRequest onImport) {
        Dialog d = new Dialog(ctx, R.style.TakeOneCenterDialog);
        // Must precede setContentView, or the framework throws
        // "requestFeature() must be called before adding content".
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_tele_editor, null);
        d.setContentView(root);

        EditText input = root.findViewById(R.id.teleEditorInput);
        TextView meta = root.findViewById(R.id.teleEditorMeta);
        TextView importBtn = root.findViewById(R.id.teleImportBtn);
        TextView saveBtn = root.findViewById(R.id.teleSaveBtn);

        // Bound the script EditText height to the available screen height so
        // landscape mode never pushes Save/Import off-screen.
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        boolean isLand = dm.widthPixels > dm.heightPixels;
        ViewGroup.LayoutParams ilp = input.getLayoutParams();
        if (ilp != null) {
            int targetH = isLand
                    ? Math.max(dp(ctx, 88), Math.min(dp(ctx, 130), (int) (dm.heightPixels * 0.34f)))
                    : Math.max(dp(ctx, 160), Math.min(dp(ctx, 240), (int) (dm.heightPixels * 0.34f)));
            ilp.height = targetH;
            input.setLayoutParams(ilp);
        }
        input.setMaxHeight(isLand ? dp(ctx, 130) : dp(ctx, 240));

        input.setText(script == null ? "" : script);
        input.setSelection(input.getText().length());

        Runnable updateMeta = () -> {
            String s = input.getText().toString();
            int words = TeleMath.wordCount(s);
            int secs = TeleMath.estimatedSeconds(s, wpm);
            meta.setText(ctx.getString(R.string.tele_words_format, words,
                    secs + "s"));
        };
        updateMeta.run();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateMeta.run();
            }
        });

        PressAnim.apply(importBtn);
        importBtn.setOnClickListener(v -> {
            if (onImport != null) onImport.onRequestImport();
        });

        // Explicit Save is the only commit path: Back, tap-outside and
        // rotation dismissals must never overwrite the saved script.
        PressAnim.apply(saveBtn);
        saveBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            // An empty script is a legitimate state: it clears the prompter
            // instead of being permanently un-saveable.
            if (onSave != null) onSave.onScript(text);
            d.dismiss();
        });

        openInput = new java.lang.ref.WeakReference<>(input);
        openMeta = new java.lang.ref.WeakReference<>(meta);
        style(d);
        d.show();
        // Only an explicit Save commits. Back, tap-outside and rotation
        // dismissals must never overwrite the saved script.
        d.setOnDismissListener(dd -> {
            openInput = null;
            openMeta = null;
        });
        return d;
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }
}
