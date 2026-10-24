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
        // Music: an unprocessed stereo master, so Auto stays lossless — but
        // compressed, because the next step is loading this into an editor.
        // Either lossless outcome is correct: FLAC when the device has an
        // encoder, pure-Java WAV24 when it does not.
        SaveFormat musicEff = SaveFormat.AUTO.effective(RecordPreset.MUSIC);
        assertTrue("expected lossless, got " + musicEff,
                musicEff == SaveFormat.FLAC || musicEff == SaveFormat.WAV24);
        // An explicit choice always wins.
        assertEquals(SaveFormat.M4A,
                SaveFormat.M4A.effective(RecordPreset.MUSIC));
        assertEquals(SaveFormat.WAV16,
                SaveFormat.WAV16.effective(RecordPreset.MEMO));
    }

    @Test
    public void onlyMusicDefaultsToLossless() {
        assertTrue(RecordPreset.MUSIC.autoSavesLossless);
        assertFalse(RecordPreset.MEMO.autoSavesLossless);
        assertFalse(RecordPreset.PODCAST.autoSavesLossless);
    }

    @Test
    public void extensions() {
        assertEquals("m4a", SaveFormat.M4A.extension());
        assertEquals("wav", SaveFormat.WAV16.extension());
        assertEquals("wav", SaveFormat.WAV24.extension());
        assertEquals("3gp", SaveFormat.AMR_NB.extension());
        assertEquals("flac", SaveFormat.FLAC.extension());
        assertEquals(android.os.Build.VERSION.SDK_INT >= 29
                ? "opus" : "webm", SaveFormat.OPUS.extension());
    }

    @Test
    public void alwaysAvailable() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertTrue(SaveFormat.AUTO.available(ctx));
        assertTrue(SaveFormat.M4A.available(ctx));
        assertTrue(SaveFormat.WAV16.available(ctx));
        // Pure-Java lossless path: never gated on a platform encoder.
        assertTrue(SaveFormat.WAV24.available(ctx));
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
        assertEquals("FLAC", TakeSaver.labelFor(RecordPreset.MUSIC, SaveFormat.AUTO));
        assertEquals("WAV", TakeSaver.labelFor(RecordPreset.MUSIC, SaveFormat.WAV16));
        assertEquals("WAV24", TakeSaver.labelFor(RecordPreset.MUSIC, SaveFormat.WAV24));
        assertEquals("AMR", TakeSaver.labelFor(RecordPreset.MEMO, SaveFormat.AMR_NB));
    }

    @Test
    public void wav24SurvivesItsOwnValidation() throws Exception {
        java.io.File f = java.io.File.createTempFile("vfy24", ".wav");
        try {
            int frames = 480;
            byte[] hdr = Dsp.wav24Header(2, 48000, (long) frames * 2 * 3);
            byte[] all = new byte[hdr.length + frames * 2 * 3];
            System.arraycopy(hdr, 0, all, 0, hdr.length);
            java.io.FileOutputStream w = new java.io.FileOutputStream(f);
            try {
                w.write(all);
            } finally {
                w.close();
            }
            FormatConverter.validateFinal(f, SaveFormat.WAV24);
            // 24-bit must be understood by the duration reader too, or the
            // library and orphan adoption would show 00:00 for these files.
            assertTrue(Dsp.wavDurationMs(f) > 0);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void storageEstimatesTrackChannelsAndClamp() {
        // WAV is exact and scales with the preset's channel count.
        assertEquals(48000L * 2 * 2 * 60,
                SaveFormat.WAV16.bytesPerMinute(RecordPreset.MUSIC));
        assertEquals(48000L * 1 * 2 * 60,
                SaveFormat.WAV16.bytesPerMinute(RecordPreset.MEMO));
        assertEquals(48000L * 2 * 3 * 60,
                SaveFormat.WAV24.bytesPerMinute(RecordPreset.MUSIC));
        // Music's Opus clamps 320k -> 256k, and the estimate must match the
        // file rather than the nominal preset bitrate.
        assertEquals(256000L / 8 * 60,
                SaveFormat.OPUS.bytesPerMinute(RecordPreset.MUSIC));
        assertEquals(320000L / 8 * 60,
                SaveFormat.M4A.bytesPerMinute(RecordPreset.MUSIC));
        // AMR always decimates to mono, so it is preset-independent.
        assertEquals(SaveFormat.AMR_NB.bytesPerMinute(RecordPreset.MUSIC),
                SaveFormat.AMR_NB.bytesPerMinute(RecordPreset.MEMO));
        // Auto reports whatever it resolves to.
        assertEquals(SaveFormat.M4A.bytesPerMinute(RecordPreset.MEMO),
                SaveFormat.AUTO.bytesPerMinute(RecordPreset.MEMO));
        assertEquals(SaveFormat.FLAC.bytesPerMinute(RecordPreset.MUSIC),
                SaveFormat.AUTO.bytesPerMinute(RecordPreset.MUSIC));
        // FLAC must actually be smaller than the PCM it replaces.
        assertTrue(SaveFormat.FLAC.bytesPerMinute(RecordPreset.MUSIC)
                < SaveFormat.WAV16.bytesPerMinute(RecordPreset.MUSIC));
    }

    @Test
    public void sizeLabelsAreCompactAndMarkEstimates() {
        assertEquals("17 MB/min",
                SaveFormat.WAV24.sizePerMinuteLabel(RecordPreset.MUSIC));
        assertEquals("11 MB/min",
                SaveFormat.WAV16.sizePerMinuteLabel(RecordPreset.MUSIC));
        assertEquals("0.7 MB/min",
                SaveFormat.M4A.sizePerMinuteLabel(RecordPreset.MEMO));
        assertEquals("0.1 MB/min",
                SaveFormat.AMR_NB.sizePerMinuteLabel(RecordPreset.MUSIC));
        // Content-dependent sizes are flagged so they never read as exact.
        assertTrue(SaveFormat.FLAC.sizePerMinuteLabel(RecordPreset.MUSIC)
                .startsWith("~"));
        assertFalse(SaveFormat.WAV16.sizeIsApproximate());
        assertTrue(SaveFormat.FLAC.sizeIsApproximate());
    }

    @Test
    public void fallbackLadder() {
        assertEquals(SaveFormat.M4A, SaveFormat.OPUS.fallbackAfterCrash());
        // FLAC must never silently degrade to lossy AAC when pure-Java WAV24
        // is available.
        assertEquals(SaveFormat.WAV24, SaveFormat.FLAC.fallbackAfterCrash());
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

    @Test
    public void encoderMimes() {
        assertEquals(android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
                SaveFormat.M4A.encoderMime());
        assertEquals("audio/flac", SaveFormat.FLAC.encoderMime());
        assertEquals("audio/opus", SaveFormat.OPUS.encoderMime());
        assertEquals("audio/3gpp", SaveFormat.AMR_NB.encoderMime());
        assertEquals("audio/amr-wb", SaveFormat.AMR_WB.encoderMime());
        assertTrue(SaveFormat.WAV16.encoderMime() == null);
        assertTrue(SaveFormat.WAV24.encoderMime() == null);
        assertTrue(SaveFormat.AUTO.encoderMime() == null);
    }

    @Test
    public void nonAutoEffectiveIsIdentity() {
        for (SaveFormat f : SaveFormat.values()) {
            if (f == SaveFormat.AUTO) continue;
            assertEquals(f, f.effective(RecordPreset.MEMO));
            assertEquals(f, f.effective(null));
        }
    }

    @Test
    public void wav16BytesAreExact() {
        // Null preset defaults to stereo: 48000 * 2ch * 2B * 60.
        assertEquals(48000L * 2 * 2 * 60, SaveFormat.WAV16.bytesPerMinute(null));
        assertEquals(48000L * 2 * 2 * 60,
                SaveFormat.WAV16.bytesPerMinute(RecordPreset.PODCAST));
        assertEquals(48000L * 1 * 2 * 60,
                SaveFormat.WAV16.bytesPerMinute(RecordPreset.MEMO));
    }

    @Test
    public void amrRatesAreFixed() {
        assertEquals(12200L / 8 * 60, SaveFormat.AMR_NB.bytesPerMinute(null));
        assertEquals(23850L / 8 * 60, SaveFormat.AMR_WB.bytesPerMinute(null));
    }

    @Test
    public void sizeApproximationFlags() {
        assertTrue(SaveFormat.FLAC.sizeIsApproximate());
        assertTrue(SaveFormat.AUTO.sizeIsApproximate());
        assertTrue(!SaveFormat.M4A.sizeIsApproximate());
        assertTrue(!SaveFormat.WAV16.sizeIsApproximate());
    }
}
