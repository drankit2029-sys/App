package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Teleprompter preference rules: defaults, clamping, persistence and the
 * inline-control setters that must never race a stale snapshot.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TeleSettingsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void defaultsAreSane() {
        TeleSettings.invalidateCache();
        TeleSettings s = TeleSettings.load(ctx());
        assertEquals(120, s.wordsPerMinute);
        assertEquals(5, s.wordsPerLine);
        assertEquals(26, s.textSizeSp);
        assertFalse(s.mirror);
        assertTrue(s.autoScroll);
    }

    @Test
    public void loadClampsCorruptPrefs() {
        TeleSettings.invalidateCache();
        ctx().getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                .putInt("tele_wpm", 9999)
                .putInt("tele_wpl", -4)
                .putInt("tele_size", 0)
                .putInt("tele_count", 99)
                .apply();
        TeleSettings.invalidateCache();
        TeleSettings s = TeleSettings.load(ctx());
        assertEquals(TeleSettings.MAX_WPM, s.wordsPerMinute);
        assertEquals(TeleMath.MIN_WORDS_PER_LINE, s.wordsPerLine);
        assertEquals(TeleMath.MIN_TEXT_SP, s.textSizeSp);
        assertTrue("countdown must clamp into range",
                s.countdownSec >= 0 && s.countdownSec <= 10);
    }

    @Test
    public void settersClampAndPersist() {
        TeleSettings.invalidateCache();
        TeleSettings.setTextSize(ctx(), 500);
        TeleSettings.setWordsPerMinute(ctx(), 1);
        TeleSettings.setWordsPerLine(ctx(), 100);
        TeleSettings s = TeleSettings.load(ctx());
        assertEquals(TeleMath.MAX_TEXT_SP, s.textSizeSp);
        assertEquals(TeleSettings.MIN_WPM, s.wordsPerMinute);
        assertEquals(TeleMath.MAX_WORDS_PER_LINE, s.wordsPerLine);
    }

    @Test
    public void settersRoundTripThroughPrefs() {
        TeleSettings.invalidateCache();
        TeleSettings.setTextSize(ctx(), 33);
        TeleSettings.setWordsPerMinute(ctx(), 200);
        TeleSettings.setWordsPerLine(ctx(), 7);
        TeleSettings.toggleMirror(ctx(), true);
        // Drop the cache: values must survive on disk alone.
        TeleSettings.invalidateCache();
        TeleSettings s = TeleSettings.load(ctx());
        assertEquals(33, s.textSizeSp);
        assertEquals(200, s.wordsPerMinute);
        assertEquals(7, s.wordsPerLine);
        assertTrue(s.mirror);
    }

    @Test
    public void toggleMirrorFlipsBothWays() {
        TeleSettings.invalidateCache();
        TeleSettings.toggleMirror(ctx(), true);
        assertTrue(TeleSettings.load(ctx()).mirror);
        TeleSettings.toggleMirror(ctx(), false);
        assertFalse(TeleSettings.load(ctx()).mirror);
    }

    @Test
    public void loadReturnsSameCachedInstance() {
        TeleSettings.invalidateCache();
        assertTrue(TeleSettings.load(ctx()) == TeleSettings.load(ctx()));
    }

    @Test
    public void saveRefreshesCache() {
        TeleSettings.invalidateCache();
        TeleSettings s = new TeleSettings();
        s.wordsPerMinute = 150;
        s.save(ctx());
        assertEquals(150, TeleSettings.load(ctx()).wordsPerMinute);
    }
}
