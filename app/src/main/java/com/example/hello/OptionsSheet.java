package com.example.hello;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/** Dark, on-brand bottom sheets replacing the stock Android dialogs. */
public final class OptionsSheet {

    private OptionsSheet() {}

    public static final class Item {
        public final int icon;
        public final String label;
        public final boolean destructive;
        public final boolean checked;
        public final Runnable action;

        public Item(int icon, String label, boolean destructive, boolean checked,
                    Runnable action) {
            this.icon = icon;
            this.label = label;
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
    }

    public static void show(Context ctx, String title, List<Item> items) {
        BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        View root = LayoutInflater.from(ctx).inflate(R.layout.sheet_options, null);
        ((TextView) root.findViewById(R.id.sheetTitle)).setText(title);
        LinearLayout rows = root.findViewById(R.id.sheetRows);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        for (Item item : items) {
            View row = inflater.inflate(R.layout.sheet_row, rows, false);
            ImageView icon = row.findViewById(R.id.rowIcon);
            TextView label = row.findViewById(R.id.rowLabel);
            ImageView check = row.findViewById(R.id.rowCheck);
            if (item.icon == 0) {
                icon.setVisibility(View.GONE);
            } else {
                icon.setImageResource(item.icon);
            }
            label.setText(item.label);
            if (item.destructive) label.setTextColor(0xFFFF3B30);
            check.setVisibility(item.checked ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (item.action != null) item.action.run();
            });
            rows.addView(row);
        }
        dialog.setContentView(root);
        dialog.show();
    }

    public interface SaveCallback {
        void onSave(String text);
    }

    public static void showInput(Context ctx, String title, String initial,
                                 String saveLabel, SaveCallback callback) {
        BottomSheetDialog dialog = new BottomSheetDialog(ctx);
        View root = LayoutInflater.from(ctx).inflate(R.layout.sheet_input, null);
        ((TextView) root.findViewById(R.id.sheetTitle)).setText(title);
        EditText input = root.findViewById(R.id.sheetInput);
        input.setText(initial);
        input.setSelection(input.getText().length());
        TextView cancel = root.findViewById(R.id.sheetCancel);
        TextView save = root.findViewById(R.id.sheetSave);
        cancel.setText(ctx.getString(R.string.cancel));
        save.setText(saveLabel);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onSave(input.getText().toString());
        });
        dialog.setContentView(root);
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
}
