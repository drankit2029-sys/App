package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Teleprompter preferences: reading speed, text size, words per line and the
 * display toggles. Persisted so a script keeps behaving the same way between
 * takes and launches.
 */
public final class TeleSettings {

    private static final String PREFS = "takeone";
    private static final String P = "tele_";

    /** Words per minute. Slower is the sane default for a first read. */
    public static final int MIN_WPM = 60;
    public static final int MAX_WPM = 300;

    public int wordsPerMinute = 120;
    public int wordsPerLine = 5;
    public int textSizeSp = 26;
    public boolean mirror = false;
    public boolean autoScroll = true;
    /** Extra hold at the very start so the performer can find the first line. */
    public int countdownSec = 3;

    private static TeleSettings cached;

    public static TeleSettings load(Context ctx) {
        if (cached != null) return cached;
        TeleSettings s = new TeleSettings();
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            s.wordsPerMinute = clamp(p.getInt(P + "wpm", s.wordsPerMinute),
                    MIN_WPM, MAX_WPM);
            s.wordsPerLine = clamp(p.getInt(P + "wpl", s.wordsPerLine),
                    TeleMath.MIN_WORDS_PER_LINE, TeleMath.MAX_WORDS_PER_LINE);
            s.textSizeSp = clamp(p.getInt(P + "size", s.textSizeSp),
                    TeleMath.MIN_TEXT_SP, TeleMath.MAX_TEXT_SP);
            s.mirror = p.getBoolean(P + "mirror", false);
            s.autoScroll = p.getBoolean(P + "auto", true);
            s.countdownSec = clamp(p.getInt(P + "count", 3), 0, 10);
        } catch (Exception ignored) {
        }
        cached = s;
        return s;
    }

    public void save(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(P + "wpm", wordsPerMinute)
                    .putInt(P + "wpl", wordsPerLine)
                    .putInt(P + "size", textSizeSp)
                    .putBoolean(P + "mirror", mirror)
                    .putBoolean(P + "auto", autoScroll)
                    .putInt(P + "count", countdownSec)
                    .apply();
        } catch (Exception ignored) {}
        cached = this;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}