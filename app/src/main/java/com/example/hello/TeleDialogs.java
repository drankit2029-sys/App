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
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Teleprompter dialogs: the script editor (with .txt/.docx import) and the
 * tuner that controls reading speed, words per line, text size and the
 * display toggles.
 *
 * <p>The tuner reports every change live so the script behind it re-wraps as
 * the sliders move — that is what makes the words-per-line clamp legible
 * instead of a silent adjustment.
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

    public interface TuneCallback {
        /** Fired on every change, and once on open. */
        void onTuned();
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

        openInput = input;
        openMeta = meta;
        style(d);
        d.show();
        // Saving is the positive action, delivered via the IME/done affordance
        // on the multiline field plus a tap-outside commit.
        d.setOnDismissListener(dd -> {
            openInput = null;
            openMeta = null;
            String s = input.getText().toString().trim();
            if (!s.isEmpty() && onSave != null) onSave.onScript(s);
        });
        return d;
    }

    // ---- tuner ----

    /**
     * @param availWidthPx teleprompter width, so the words-per-line clamp can
     *                      be evaluated and explained against real space
     */
    /** @return the shown dialog, so callers/tests can drive its controls. */
    public static Dialog showTuner(Context ctx, TeleSettings s,
                                   int availWidthPx, float textSizePx,
                                   TuneCallback cb) {
        Dialog d = new Dialog(ctx, R.style.TakeOneCenterDialog);
        // Must precede setContentView, or the framework throws
        // "requestFeature() must be called before adding content".
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_tele_tuner, null);
        d.setContentView(root);

        SeekBar speed = root.findViewById(R.id.tuneSpeed);
        SeekBar wpl = root.findViewById(R.id.tuneWpl);
        SeekBar size = root.findViewById(R.id.tuneSize);
        TextView speedVal = root.findViewById(R.id.tuneSpeedVal);
        TextView wplVal = root.findViewById(R.id.tuneWplVal);
        TextView sizeVal = root.findViewById(R.id.tuneSizeVal);
        TextView clampNote = root.findViewById(R.id.tuneClampNote);
        TextView auto = root.findViewById(R.id.tuneAuto);
        TextView mirror = root.findViewById(R.id.tuneMirror);
        TextView done = root.findViewById(R.id.tuneDone);

        speed.setMax(TeleSettings.MAX_WPM - TeleSettings.MIN_WPM);
        wpl.setMax(TeleMath.MAX_WORDS_PER_LINE - TeleMath.MIN_WORDS_PER_LINE);
        size.setMax(TeleMath.MAX_TEXT_SP - TeleMath.MIN_TEXT_SP);
        speed.setProgress(s.wordsPerMinute - TeleSettings.MIN_WPM);
        wpl.setProgress(s.wordsPerLine - TeleMath.MIN_WORDS_PER_LINE);
        size.setProgress(s.textSizeSp - TeleMath.MIN_TEXT_SP);

        final float density = ctx.getResources().getDisplayMetrics().density;

        Runnable paint = () -> {
            speedVal.setText(s.wordsPerMinute + " wpm");
            sizeVal.setText(s.textSizeSp + " sp");
            float px = s.textSizeSp * density;
            int effective = TeleMath.wordsPerLine(availWidthPx, px,
                    s.wordsPerLine);
            wplVal.setText(effective + (effective != s.wordsPerLine
                    ? " (" + s.wordsPerLine + " requested)" : ""));
            boolean clamped = TeleMath.wordsPerLineWasClamped(availWidthPx, px,
                    s.wordsPerLine);
            clampNote.setVisibility(clamped ? View.VISIBLE : View.GONE);
            if (clamped) {
                clampNote.setText(ctx.getString(R.string.tele_clamped,
                        s.wordsPerLine, effective));
            }
            auto.setBackgroundResource(s.autoScroll
                    ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost);
            auto.setTextColor(s.autoScroll ? 0xFFFFFFFF : 0xFFF2F4F7);
            mirror.setBackgroundResource(s.mirror
                    ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost);
            mirror.setTextColor(s.mirror ? 0xFFFFFFFF : 0xFFF2F4F7);
            if (cb != null) cb.onTuned();
        };

        SeekBar.OnSeekBarChangeListener listener =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean u) {
                        if (sb == speed) {
                            s.wordsPerMinute = TeleSettings.MIN_WPM + p;
                        } else if (sb == wpl) {
                            s.wordsPerLine = TeleMath.MIN_WORDS_PER_LINE + p;
                        } else if (sb == size) {
                            s.textSizeSp = TeleMath.MIN_TEXT_SP + p;
                        }
                        paint.run();
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                };
        speed.setOnSeekBarChangeListener(listener);
        wpl.setOnSeekBarChangeListener(listener);
        size.setOnSeekBarChangeListener(listener);

        auto.setOnClickListener(v -> {
            s.autoScroll = !s.autoScroll;
            paint.run();
        });
        mirror.setOnClickListener(v -> {
            s.mirror = !s.mirror;
            paint.run();
        });
        PressAnim.apply(auto);
        PressAnim.apply(mirror);
        PressAnim.apply(done);
        done.setOnClickListener(v -> d.dismiss());

        style(d);
        d.show();
        paint.run();
        d.setOnDismissListener(dd -> {
            s.save(ctx);
            if (cb != null) cb.onTuned();
        });
        return d;
    }
}