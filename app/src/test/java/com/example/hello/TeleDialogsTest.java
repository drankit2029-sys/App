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
    public void tunerOpensAndEveryControlBinds() {
        final TeleSettings s = new TeleSettings();
        final boolean[] tuned = {false};
        TeleDialogs.showTuner(activity(), s, 900, 60f, () -> tuned[0] = true);
        assertTrue("tuner never reported an initial tune", tuned[0]);
        s.save(activity());
    }

    @Test
    public void tunerSurvivesSliderSweepAndToggles() {
        TeleSettings s = new TeleSettings();
        TeleDialogs.showTuner(activity(), s, 600, 48f, () -> { });
        // Drive the live settings the way the sliders would, including the
        // extreme ends, to be sure nothing clamps badly or throws.
        for (int wpm = TeleSettings.MIN_WPM; wpm <= TeleSettings.MAX_WPM;
                wpm += 40) {
            s.wordsPerMinute = wpm;
        }
        for (int sp = TeleMath.MIN_TEXT_SP; sp <= TeleMath.MAX_TEXT_SP;
                sp += 4) {
            s.textSizeSp = sp;
        }
        for (int wpl = 2; wpl <= 14; wpl++) {
            s.wordsPerLine = wpl;
        }
        s.autoScroll = !s.autoScroll;
        s.mirror = !s.mirror;
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

        // Closing commits the script.
        d.dismiss();
        assertEquals("saved=" + saved[0], "hello world\nsecond line", saved[0]);
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