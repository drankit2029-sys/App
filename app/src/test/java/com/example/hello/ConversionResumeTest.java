package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * WAV resume is byte-exact: a conversion interrupted halfway and resumed
 * must produce a file identical to an uninterrupted conversion. That is the
 * mechanism behind "minimum reconversion".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConversionResumeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private File makeRaw(String name, int frames) throws Exception {
        File f = new File(ctx().getCacheDir(), name);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            java.util.Random rnd = new java.util.Random(11);
            byte[] chunk = new byte[65536];
            long left = (long) frames * 2 * 4;
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                rnd.nextBytes(chunk);
                w.write(chunk, 0, n);
                left -= n;
            }
        } finally {
            w.close();
        }
        return f;
    }

    private static byte[] slurp(File f) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] out = new byte[(int) r.length()];
            r.readFully(out);
            return out;
        } finally {
            r.close();
        }
    }

    @Test
    public void resumedWav16IsByteIdentical() throws Exception {
        File raw = makeRaw("resume-src.wav", 48000);
        try {
            // Uninterrupted reference conversion.
            TakeSaver.Result full = TakeSaver.convertRaw(ctx(), raw,
                    "Resume", RecordPreset.MEMO, SaveFormat.WAV16, null);
            byte[] expect;
            try {
                expect = slurp(full.file);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                full.file.delete();
            }
            // Interrupted attempt: convert the first 12000 frames by hand
            // into a partial file, exactly as the pump would leave it
            // (12000 stereo frames = 24000 samples).
            File part = new File(ctx().getCacheDir(), "resume.part");
            // noinspection ResultOfMethodCallIgnored
            part.delete();
            RandomAccessFile w = new RandomAccessFile(part, "rw");
            try {
                w.setLength(0);
                w.write(Dsp.wav16Header(2, 48000, 0));
                RandomAccessFile r = new RandomAccessFile(raw, "r");
                try {
                    byte[] in = new byte[12000 * 2 * 4];
                    byte[] out = new byte[12000 * 2 * 2];
                    java.nio.FloatBuffer fb =
                            java.nio.ByteBuffer.wrap(in)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .asFloatBuffer();
                    java.nio.ShortBuffer sb =
                            java.nio.ByteBuffer.wrap(out)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer();
                    r.seek(44);
                    r.readFully(in);
                    FormatConverter.toInt16(fb, 12000 * 2, sb);
                    w.write(out);
                } finally {
                    r.close();
                }
            } finally {
                w.close();
            }
            ConversionJob job = new ConversionJob("resume-1", "Resume",
                    raw.getAbsolutePath(), part.getAbsolutePath(),
                    "memo", "wav16", 2, 96000, 1000, 48000);
            job.framesDone = 12000;
            TakeSaver.Result resumed = ConversionService.resumeWavConvert(
                    ctx(), raw, part, RecordPreset.MEMO, SaveFormat.WAV16,
                    job, null);
            byte[] got;
            try {
                got = slurp(resumed.file);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                resumed.file.delete();
            }
            assertEquals("resumed length differs",
                    expect.length, got.length);
            assertTrue("resumed bytes differ",
                    Arrays.equals(expect, got));
        } finally {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    @Test
    public void misalignedPartialRestartsCleanly() throws Exception {
        File raw = makeRaw("resume-src2.wav", 48000);
        try {
            File part = new File(ctx().getCacheDir(), "resume2.part");
            RandomAccessFile w = new RandomAccessFile(part, "rw");
            try {
                // Garbage length: cannot line up with any frame count.
                w.write(new byte[100]);
            } finally {
                w.close();
            }
            ConversionJob job = new ConversionJob("resume-2", "Resume",
                    raw.getAbsolutePath(), part.getAbsolutePath(),
                    "memo", "wav16", 2, 96000, 1000, 48000);
            job.framesDone = 12000;
            TakeSaver.Result r = ConversionService.resumeWavConvert(
                    ctx(), raw, part, RecordPreset.MEMO, SaveFormat.WAV16,
                    job, null);
            try {
                assertTrue(r.file.length() > 1000);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                r.file.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /**
     * AUDIT-3-01: a resumed WAV16/WAV24 take is integer PCM, so the duration
     * must come from a PCM-aware reader. Before the fix every resumed take
     * landed in the library as 00:01.
     */
    @Test
    public void resumedWavKeepsRealDuration() throws Exception {
        File raw = makeRaw("resume-dur.wav", 48000 * 3);
        try {
            File part = new File(ctx().getCacheDir(), "resume-dur.part");
            // noinspection ResultOfMethodCallIgnored
            part.delete();
            ConversionJob job = new ConversionJob("resume-dur", "Dur",
                    raw.getAbsolutePath(), part.getAbsolutePath(),
                    "memo", "wav16", 2, 96000, 1000, 144000);
            job.framesDone = 144000; // already fully encoded
            TakeSaver.Result r = ConversionService.resumeWavConvert(
                    ctx(), raw, part, RecordPreset.MEMO, SaveFormat.WAV16,
                    job, null);
            try {
                assertTrue("resumed take duration " + r.durationMs
                                + "ms (expected ~3000)",
                        r.durationMs > 2500L && r.durationMs < 3500L);
                assertEquals(3000L, Dsp.wavDurationMs(r.file));
            } finally {
                // noinspection ResultOfMethodCallIgnored
                r.file.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /**
     * AUDIT-3-02(b): `job.framesDone` is only a 5%-rounded estimate while a
     * conversion runs, so it must not be trusted over a valid, frame-aligned
     * partial already on disk.
     */
    @Test
    public void resumeAdoptsFrameAlignedPartialDespiteStaleFramesDone()
            throws Exception {
        File raw = makeRaw("resume-est.wav", 48000);
        try {
            File part = new File(ctx().getCacheDir(), "resume-est.part");
            // noinspection ResultOfMethodCallIgnored
            part.delete();
            int doneFrames = 12000;
            // Fill the partial with a marker value that a fresh conversion
            // could never produce, so "was the partial adopted?" is directly
            // observable in the result.
            final short MARK = 9999;
            RandomAccessFile w = new RandomAccessFile(part, "rw");
            try {
                w.setLength(0);
                w.write(Dsp.wav16Header(2, 48000, 0));
                byte[] out = new byte[doneFrames * 2 * 2];
                java.nio.ShortBuffer sb =
                        java.nio.ByteBuffer.wrap(out)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer();
                for (int i = 0; i < doneFrames * 2; i++) sb.put(i, MARK);
                w.write(out);
            } finally {
                w.close();
            }
            // Deliberately wrong (rounded) estimate, as saved by updateProgress.
            ConversionJob job = new ConversionJob("resume-est", "Est",
                    raw.getAbsolutePath(), part.getAbsolutePath(),
                    "memo", "wav16", 2, 96000, 1000, 48000);
            job.framesDone = 11900; // != 12000 on disk
            TakeSaver.Result r = ConversionService.resumeWavConvert(
                    ctx(), raw, part, RecordPreset.MEMO, SaveFormat.WAV16,
                    job, null);
            try {
                FormatConverter.validateFinal(r.file, SaveFormat.WAV16);
                assertEquals(1000L, Dsp.wavDurationMs(r.file));
                // The marker must survive at the very front: proof the
                // on-disk partial was adopted instead of being discarded.
                RandomAccessFile r2 =
                        new RandomAccessFile(r.file, "r");
                short first;
                try {
                    byte[] head = new byte[8];
                    r2.seek(44);
                    r2.readFully(head);
                    first = java.nio.ByteBuffer.wrap(head)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .getShort(0);
                } finally {
                    r2.close();
                }
                assertEquals("stale estimate discarded the partial",
                        MARK, first);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                r.file.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /**
     * AUDIT-3-02(a): enqueue must read geometry from the raw file, not from
     * an idle engine (which reports dataBytes 0 -> framesTotal 1).
     */
    @Test
    public void enqueueReadsGeometryFromRawFile() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        File raw = makeRaw("enqueue-geom.wav", 48000);
        try {
            ConversionJob job = ConversionJobs.enqueue(ctx(), raw,
                    "Geom", RecordPreset.MEMO, SaveFormat.WAV16, 1000);
            assertEquals("channels must come from the raw header",
                    2, job.channels);
            assertEquals("framesTotal must come from the raw header",
                    48000L, job.framesTotal);
            assertTrue(ConversionJobs.cancel(job.jobId, true));
        } finally {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }
}
