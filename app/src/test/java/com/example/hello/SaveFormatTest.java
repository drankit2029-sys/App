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
    public void autoPicksTheAppropriateContainer() {
        // Speech: AAC is transparent and far smaller — that is the point.
        assertEquals(SaveFormat.M4A,
                SaveFormat.AUTO.effective(RecordPreset.MEMO));
        assertEquals(SaveFormat.M4A,
                SaveFormat.AUTO.effective(RecordPreset.PODCAST));
        // Music: the capture is an unprocessed lossless master, so Auto keeps
        // the original 32-bit float rather than squeezing it through AAC.
        assertEquals(SaveFormat.WAV32,
                SaveFormat.AUTO.effective(RecordPreset.MUSIC));
        // An explicit choice always wins.
        assertEquals(SaveFormat.M4A,
                SaveFormat.M4A.effective(RecordPreset.MUSIC));
        assertEquals(SaveFormat.WAV16,
                SaveFormat.WAV16.effective(RecordPreset.MEMO));
    }

    @Test
    public void onlyMusicDefaultsToLossless() {
        assertTrue(RecordPreset.MUSIC.savesLosslessByDefault);
        assertFalse(RecordPreset.MEMO.savesLosslessByDefault);
        assertFalse(RecordPreset.PODCAST.savesLosslessByDefault);
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
        // Pure-Java lossless path: never gated on a platform encoder.
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
        assertEquals("M4A", TakeSaver.labelFor(RecordPreset.MEMO, SaveFormat.AUTO));
        assertEquals("M4A", TakeSaver.labelFor(RecordPreset.PODCAST, SaveFormat.AUTO));
        assertEquals("WAV32", TakeSaver.labelFor(RecordPreset.MUSIC, SaveFormat.AUTO));
        assertEquals("WAV", TakeSaver.labelFor(RecordPreset.MUSIC, SaveFormat.WAV16));
        assertEquals("AMR", TakeSaver.labelFor(RecordPreset.MEMO, SaveFormat.AMR_NB));
    }

    @Test
    public void wav32SurvivesItsOwnValidation() throws Exception {
        // The lossless save keeps the raw capture, so validation must accept
        // a 32-bit float WAV rather than only PCM16.
        java.io.File f = java.io.File.createTempFile("vfy32", ".wav");
        try {
            int frames = 480;
            byte[] hdr = Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4);
            byte[] all = new byte[hdr.length + frames * 2 * 4];
            System.arraycopy(hdr, 0, all, 0, hdr.length);
            java.io.FileOutputStream w = new java.io.FileOutputStream(f);
            try {
                w.write(all);
            } finally {
                w.close();
            }
            FormatConverter.validateFinal(f, SaveFormat.WAV32);
            assertTrue(Dsp.wavDurationMs(f) > 0);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void fallbackLadder() {
        assertEquals(SaveFormat.M4A, SaveFormat.OPUS.fallbackAfterCrash());
        assertEquals(SaveFormat.M4A, SaveFormat.VORBIS.fallbackAfterCrash());
        assertEquals(SaveFormat.M4A, SaveFormat.FLAC.fallbackAfterCrash());
        assertEquals(SaveFormat.M4A, SaveFormat.AMR_NB.fallbackAfterCrash());
        assertEquals(SaveFormat.M4A, SaveFormat.AMR_WB.fallbackAfterCrash());
        assertEquals(SaveFormat.WAV16, SaveFormat.M4A.fallbackAfterCrash());
        assertEquals(SaveFormat.WAV16, SaveFormat.AUTO.fallbackAfterCrash());
        assertEquals(null, SaveFormat.WAV16.fallbackAfterCrash());
    }

    @Test
    public void validateFinalAcceptsHealthyContainers() throws Exception {
        // WAV: real header + payload.
        java.io.File wav = java.io.File.createTempFile("vfy", ".wav");
        try {
            byte[] hdr = Dsp.wav16Header(1, 48000, 200);
            byte[] all = new byte[hdr.length + 200];
            System.arraycopy(hdr, 0, all, 0, hdr.length);
            java.io.FileOutputStream w = new java.io.FileOutputStream(wav);
            try {
                w.write(all);
            } finally {
                w.close();
            }
            FormatConverter.validateFinal(wav, SaveFormat.WAV16);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
        // MP4: ftyp up front, moov somewhere inside.
        java.io.File mp4 = java.io.File.createTempFile("vfy", ".m4a");
        try {
            byte[] body = new byte[200];
            body[4] = 'f';
            body[5] = 't';
            body[6] = 'y';
            body[7] = 'p';
            body[100] = 'm';
            body[101] = 'o';
            body[102] = 'o';
            body[103] = 'v';
            java.io.FileOutputStream w = new java.io.FileOutputStream(mp4);
            try {
                w.write(body);
            } finally {
                w.close();
            }
            FormatConverter.validateFinal(mp4, SaveFormat.M4A);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            mp4.delete();
        }
    }

    @Test
    public void validateFinalRejectsTornContainers() throws Exception {
        // Too small.
        java.io.File tiny = java.io.File.createTempFile("vfy", ".m4a");
        try {
            boolean thrown = false;
            try {
                FormatConverter.validateFinal(tiny, SaveFormat.M4A);
            } catch (IllegalStateException e) {
                thrown = true;
            }
            assertTrue(thrown);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            tiny.delete();
        }
        // ftyp present but no moov: teardown never finished.
        java.io.File torn = java.io.File.createTempFile("vfy", ".m4a");
        try {
            byte[] body = new byte[200];
            body[4] = 'f';
            body[5] = 't';
            body[6] = 'y';
            body[7] = 'p';
            java.io.FileOutputStream w = new java.io.FileOutputStream(torn);
            try {
                w.write(body);
            } finally {
                w.close();
            }
            boolean thrown = false;
            try {
                FormatConverter.validateFinal(torn, SaveFormat.M4A);
            } catch (IllegalStateException e) {
                thrown = true;
            }
            assertTrue(thrown);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            torn.delete();
        }
    }
}
