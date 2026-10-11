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
public class SaveFormatTest {

    @Test
    public void ofDefaultsToAuto() {
        assertEquals(SaveFormat.AUTO, SaveFormat.of(null));
        assertEquals(SaveFormat.AUTO, SaveFormat.of("nope"));
        assertEquals(SaveFormat.FLAC, SaveFormat.of("flac"));
    }

    @Test
    public void effectiveFollowsPreset() {
        assertEquals(SaveFormat.M4A,
                SaveFormat.AUTO.effective(RecordPreset.STANDARD));
        assertEquals(SaveFormat.WAV16,
                SaveFormat.AUTO.effective(RecordPreset.RAW_STUDIO));
        assertEquals(SaveFormat.WAV32,
                SaveFormat.WAV32.effective(RecordPreset.MUSIC));
    }

    @Test
    public void extensions() {
        assertEquals("m4a", SaveFormat.M4A.extension());
        assertEquals("wav", SaveFormat.WAV16.extension());
        assertEquals("wav", SaveFormat.WAV32.extension());
        assertEquals("3gp", SaveFormat.AMR_NB.extension());
        assertEquals("flac", SaveFormat.FLAC.extension());
        assertEquals("webm", SaveFormat.OPUS.extension());
    }

    @Test
    public void alwaysAvailable() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertTrue(SaveFormat.AUTO.available(ctx));
        assertTrue(SaveFormat.M4A.available(ctx));
        assertTrue(SaveFormat.WAV16.available(ctx));
        assertTrue(SaveFormat.WAV32.available(ctx));
    }

    @Test
    public void probingNeverCrashes() {
        // Under Robolectric there are no real encoders; the point is the
        // probe degrades gracefully instead of throwing.
        Context ctx = ApplicationProvider.getApplicationContext();
        for (SaveFormat f : SaveFormat.values()) {
            f.available(ctx);
        }
    }

    @Test
    public void persistRoundTrip() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SaveFormat.OPUS.save(ctx);
        assertEquals(SaveFormat.OPUS, SaveFormat.load(ctx));
        SaveFormat.AUTO.save(ctx);
        assertEquals(SaveFormat.AUTO, SaveFormat.load(ctx));
    }

    @Test
    public void takeSaverLabels() {
        assertEquals("M4A", TakeSaver.labelFor(RecordPreset.STANDARD, SaveFormat.AUTO));
        assertEquals("WAV",
                TakeSaver.labelFor(RecordPreset.RAW_STUDIO, SaveFormat.AUTO));
        assertEquals("WAV32",
                TakeSaver.labelFor(RecordPreset.MUSIC, SaveFormat.WAV32));
        assertEquals("AMR",
                TakeSaver.labelFor(RecordPreset.VOICE_MEMO, SaveFormat.AMR_NB));
    }

    @Test
    public void rawPresetKeepsLosslessOnly() {
        // A 32-bit float master through a voice codec is nonsense: only
        // lossless containers (or Auto, which resolves to one) are offered.
        assertTrue(SaveFormat.AUTO.supportsPreset(RecordPreset.RAW_STUDIO));
        assertTrue(SaveFormat.WAV16.supportsPreset(RecordPreset.RAW_STUDIO));
        assertTrue(SaveFormat.WAV32.supportsPreset(RecordPreset.RAW_STUDIO));
        assertTrue(SaveFormat.FLAC.supportsPreset(RecordPreset.RAW_STUDIO));
        assertFalse(SaveFormat.M4A.supportsPreset(RecordPreset.RAW_STUDIO));
        assertFalse(SaveFormat.OPUS.supportsPreset(RecordPreset.RAW_STUDIO));
        assertFalse(SaveFormat.VORBIS.supportsPreset(RecordPreset.RAW_STUDIO));
        assertFalse(SaveFormat.AMR_NB.supportsPreset(RecordPreset.RAW_STUDIO));
        assertFalse(SaveFormat.AMR_WB.supportsPreset(RecordPreset.RAW_STUDIO));
    }

    @Test
    public void lossyPresetsAllowAllButRawFloat() {
        for (RecordPreset p : new RecordPreset[]{
                RecordPreset.VOICE_MEMO, RecordPreset.STANDARD,
                RecordPreset.PODCAST, RecordPreset.MUSIC}) {
            assertTrue(SaveFormat.AUTO.supportsPreset(p));
            assertTrue(SaveFormat.M4A.supportsPreset(p));
            assertTrue(SaveFormat.OPUS.supportsPreset(p));
            assertTrue(SaveFormat.WAV16.supportsPreset(p));
            assertTrue(SaveFormat.FLAC.supportsPreset(p));
            assertFalse(SaveFormat.WAV32.supportsPreset(p));
        }
    }
}
