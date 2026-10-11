package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Survives an edit punch-in across process death: when a library take is
 * opened for editing, its id/title/session path are stashed. If the app is
 * killed mid-edit, the unfinished-take flow reattaches the raw file and the
 * studio re-adopts the linkage (path-matched, so a *different* take recorded
 * afterwards can never inherit it). Cleared on save, discard, or any fresh
 * recording.
 */
public final class EditLink {

    private static final String PREFS = "takeone";
    private static final String KEY_ID = "edit_source_id";
    private static final String KEY_TITLE = "edit_source_title";
    private static final String KEY_RAW = "edit_raw_path";

    private EditLink() {}

    public static void save(Context ctx, long id, String title, String rawPath) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_ID, id)
                    .putString(KEY_TITLE, title)
                    .putString(KEY_RAW, rawPath)
                    .apply();
        } catch (Exception ignored) {}
    }

    public static void clear(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().remove(KEY_ID).remove(KEY_TITLE).remove(KEY_RAW).apply();
        } catch (Exception ignored) {}
    }

    /** True when a stashed edit linkage belongs to this exact session file. */
    public static boolean matches(Context ctx, String rawPath) {
        if (rawPath == null) return false;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getLong(KEY_ID, -1) != -1
                    && rawPath.equals(p.getString(KEY_RAW, null));
        } catch (Exception e) {
            return false;
        }
    }

    public static long id(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_ID, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String title(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_TITLE, null);
        } catch (Exception e) {
            return null;
        }
    }
}
