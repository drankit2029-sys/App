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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RecordPresetTest {

    @Test
    public void ofDefaultsToStandard() {
        assertEquals(RecordPreset.STANDARD, RecordPreset.of(null));
        assertEquals(RecordPreset.STANDARD, RecordPreset.of("nope"));
        assertEquals(RecordPreset.MUSIC, RecordPreset.of("music"));
        assertEquals(RecordPreset.RAW_STUDIO, RecordPreset.of("raw"));
    }

    @Test
    public void captureGeometry() {
        assertEquals(1, RecordPreset.VOICE_MEMO.channels);
        assertEquals(2, RecordPreset.PODCAST.channels);
        assertEquals(2, RecordPreset.MUSIC.channels);
        assertFalse(RecordPreset.VOICE_MEMO.wantsStereo());
        assertTrue(RecordPreset.MUSIC.wantsStereo());
        assertTrue(RecordPreset.MUSIC.wantsUnprocessed());
        assertTrue(RecordPreset.RAW_STUDIO.savesWav);
        assertFalse(RecordPreset.STANDARD.savesWav);
    }

    @Test
    public void bitrates() {
        assertEquals(64000, RecordPreset.VOICE_MEMO.aacBitrate);
        assertEquals(128000, RecordPreset.STANDARD.aacBitrate);
        assertEquals(256000, RecordPreset.PODCAST.aacBitrate);
        assertEquals(320000, RecordPreset.MUSIC.aacBitrate);
    }

    @Test
    public void persistRoundTrip() {
        Context ctx = ApplicationProvider.getApplicationContext();
        RecordPreset.MUSIC.save(ctx);
        assertEquals(RecordPreset.MUSIC, RecordPreset.load(ctx));
        RecordPreset.RAW_STUDIO.save(ctx);
        assertEquals(RecordPreset.RAW_STUDIO, RecordPreset.load(ctx));
    }
}
