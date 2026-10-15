package com.example.hello;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;

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
     * is up can drop the text straight in. Cleared when the editor closes.
     */
    private static android.widget.EditText openInput;
    private static TextView openMeta;

    /** Replaces the open editor's contents with imported text. */
    public static void applyImported(Context ctx, String text, String name,
                                     int wpm) {
        if (openInput == null || text == null) return;
        openInput.setText(text);
        openInput.setSelection(openInput.getText().length());
        if (openMeta != null) {
            openMeta.setText(ctx.getString(R.string.tele_imported, name,
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
     * Window styling only. NOTE: requestWindowFeature is NOT called here —
     * the framework rejects it once a view has been added, so each factory
     * must call it immediately after constructing the Dialog.
     */
    private static void style(Dialog d) {
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setDimAmount(0.62f);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        w.setAttributes(lp);
        View decor = w.getDecorView();
        if (decor != null) {
            int m = Math.round(decor.getResources().getDisplayMetrics()
                    .density * 20);
            decor.setPadding(m, 0, m, 0);
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

        // Explicit Save, so committing the script is never a guess. Closing
        // the dialog still commits, which keeps "tap away to keep" working.
        // A4-10: `committed` stops the dismiss listener from firing a second
        // onScript() for the same edit (it used to run on every Save tap).
        final boolean[] committed = {false};
        PressAnim.apply(saveBtn);
        saveBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            committed[0] = true;
            // An empty script is a legitimate state: it clears the prompter
            // instead of being permanently un-saveable.
            if (onSave != null) onSave.onScript(text);
            d.dismiss();
        });

        openInput = input;
        openMeta = meta;
        style(d);
        d.show();
        // Saving is the positive action, delivered via the IME/done affordance
        // on the multiline field plus a tap-outside commit.
        d.setOnDismissListener(dd -> {
            openInput = null;
            openMeta = null;
            if (committed[0]) return;
            String s = input.getText().toString().trim();
            if (onSave != null) onSave.onScript(s);
        });
        return d;
    }

}
