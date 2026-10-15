package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Opens the teleprompter dialogs for real.
 *
 * <p>This exists because a dialog that inflates and binds fine can still
 * throw the moment it shows — the framework rejects
 * {@code requestWindowFeature()} once content is attached, which is exactly
 * how the tuner and editor were crashing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TeleDialogsTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test
    public void inlineSettingsSurviveFullRangeSweep() {
        // The dead tuner sheet is gone; its replacement is the inline
        // TeleValue controls, which commit through these setters. Sweep the
        // full ranges the way a scrub would, including the extreme ends.
        android.app.Activity a = activity();
        for (int wpm = TeleSettings.MIN_WPM; wpm <= TeleSettings.MAX_WPM;
                wpm += 40) {
            TeleSettings.setWordsPerMinute(a, wpm);
        }
        for (int sp = TeleMath.MIN_TEXT_SP; sp <= TeleMath.MAX_TEXT_SP;
                sp += 4) {
            TeleSettings.setTextSize(a, sp);
        }
        for (int wpl = TeleMath.MIN_WORDS_PER_LINE;
                wpl <= TeleMath.MAX_WORDS_PER_LINE; wpl++) {
            TeleSettings.setWordsPerLine(a, wpl);
        }
        TeleSettings got = TeleSettings.load(a);
        assertEquals(TeleSettings.MAX_WPM, got.wordsPerMinute);
        assertEquals(TeleMath.MAX_TEXT_SP, got.textSizeSp);
        assertEquals(TeleMath.MAX_WORDS_PER_LINE, got.wordsPerLine);
    }

    @Test
    public void wplControlSpansFullRangeLive() {
        TeleValue tv = new TeleValue(activity());
        tv.configure(TeleMath.MIN_WORDS_PER_LINE,
                TeleMath.MAX_WORDS_PER_LINE, 1, 5, "");
        final int[] seen = {-1};
        tv.setOnValueChanged(v -> seen[0] = v);
        tv.setValue(TeleMath.MAX_WORDS_PER_LINE, true);
        assertEquals(TeleMath.MAX_WORDS_PER_LINE, seen[0]);
        tv.setValue(TeleMath.MIN_WORDS_PER_LINE, true);
        assertEquals(TeleMath.MIN_WORDS_PER_LINE, seen[0]);
    }

    @Test
    public void editorOpensImportAndSaves() {
        final boolean[] imported = {false};
        final String[] saved = {null};
        android.app.Dialog d = TeleDialogs.showEditor(activity(),
                "hello world\nsecond line", 120,
                text -> saved[0] = text,
                () -> imported[0] = true);

        // The import button must actually fire the hook when tapped.
        View importBtn = d.findViewById(R.id.teleImportBtn);
        assertTrue("no import button", importBtn != null);
        importBtn.performClick();
        assertTrue("import hook never fired", imported[0]);

        // Save commits explicitly.
        View saveBtn = d.findViewById(R.id.teleSaveBtn);
        assertTrue("no save button", saveBtn != null);
        saveBtn.performClick();
        assertEquals("saved=" + saved[0], "hello world\nsecond line", saved[0]);
        // Closing without saving keeps whatever was last committed.
        d.dismiss();
    }

    @Test
    public void editorSavesEmptyScriptOnceToClear() {
        // A4-10: an empty script is a legitimate state — it clears the
        // prompter. The old code refused to commit it, so a script could
        // never be cleared once set.
        final String[] saved = {null};
        final int[] calls = {0};
        android.app.Dialog d = TeleDialogs.showEditor(activity(), "   ", 120,
                text -> {
                    if (calls[0] == 0) saved[0] = text;
                    calls[0]++;
                }, null);
        d.findViewById(R.id.teleSaveBtn).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals("empty script must commit", 1, calls[0]);
        assertEquals("", saved[0]);
    }

    @Test
    public void editorHandlesEmptyAndHugeScripts() {
        TeleDialogs.showEditor(activity(), "", 120, text -> { }, null);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            big.append("word").append(i).append(' ');
        }
        TeleDialogs.showEditor(activity(), big.toString(), 120,
                text -> { }, null);
        assertEquals(20000, TeleMath.wordCount(big.toString()));
    }
}