package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RecordPresetTest {

    @After
    public void clearTakeonePrefs() {
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("takeone", Context.MODE_PRIVATE)
                .edit().clear().commit();
        TeleSettings.invalidateCache();
    }


    @Test
    public void ofDefaultsToMemo() {
        assertEquals(RecordPreset.MEMO, RecordPreset.of(null));
        assertEquals(RecordPreset.MEMO, RecordPreset.of("nope"));
        assertEquals(RecordPreset.MUSIC, RecordPreset.of("music"));
        assertEquals(RecordPreset.PODCAST, RecordPreset.of("podcast"));
    }

    @Test
    public void legacyIdsMigrate() {
        assertEquals(RecordPreset.MEMO, RecordPreset.of("voice"));
        assertEquals(RecordPreset.MEMO, RecordPreset.of("standard"));
        assertEquals(RecordPreset.MUSIC, RecordPreset.of("raw"));
    }

    @Test
    public void captureGeometry() {
        assertEquals(1, RecordPreset.MEMO.channels);
        assertEquals(2, RecordPreset.PODCAST.channels);
        assertEquals(2, RecordPreset.MUSIC.channels);
        assertFalse(RecordPreset.MEMO.wantsStereo());
        assertTrue(RecordPreset.PODCAST.wantsStereo());
        assertTrue(RecordPreset.MUSIC.wantsStereo());
        assertFalse(RecordPreset.MEMO.wantsUnprocessed());
        assertFalse(RecordPreset.PODCAST.wantsUnprocessed());
        assertTrue(RecordPreset.MUSIC.wantsUnprocessed());
    }

    @Test
    public void bitrates() {
        assertEquals(96000, RecordPreset.MEMO.aacBitrate);
        assertEquals(256000, RecordPreset.PODCAST.aacBitrate);
        assertEquals(320000, RecordPreset.MUSIC.aacBitrate);
    }

    @Test
    public void ofNullDefaultsToMemo() {
        assertEquals(RecordPreset.MEMO, RecordPreset.of(null));
    }

    @Test
    public void exactlyThreePresets() {
        assertEquals(3, RecordPreset.all().length);
    }

    @Test
    public void onlyMusicIsUnprocessedAndLossless() {
        for (RecordPreset preset : RecordPreset.all()) {
            boolean music = preset == RecordPreset.MUSIC;
            assertEquals(music, preset.wantsUnprocessed());
            assertEquals(music, preset.autoSavesLossless);
        }
    }

    @Test
    public void persistRoundTrip() {
        Context ctx = ApplicationProvider.getApplicationContext();
        RecordPreset.MUSIC.save(ctx);
        assertEquals(RecordPreset.MUSIC, RecordPreset.load(ctx));
        RecordPreset.PODCAST.save(ctx);
        assertEquals(RecordPreset.PODCAST, RecordPreset.load(ctx));
    }
}
