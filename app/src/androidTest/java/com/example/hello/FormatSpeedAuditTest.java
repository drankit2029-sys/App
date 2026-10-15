package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaPlayer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audits why AMR converts so much faster than the other formats, and proves
 * the speed is real work done cheaply rather than skipped work.
 *
 * <p>AMR is the odd one out for three compounding reasons:
 * <ul>
 *   <li>It is the only format that decimates before encoding: 48 kHz to 8 kHz
 *       (AMR-NB) or 16 kHz (AMR-WB), so one sixth or one third of the samples
 *       ever reach the encoder.</li>
 *   <li>It encodes at 12.2 kbps / 23.85 kbps, against 128 kbps for M4A and
 *       far more for lossless, so the muxer handles a fraction of the data.</li>
 *   <li>Every sample it does handle is mono, half the channels of the
 *       source.</li>
 * </ul>
 *
 * <p>The bug this test exists to catch is the opposite one: an encoder that
 * returns quickly because it truncated or skipped frames. The take duration
 * shown in the library comes from the raw file, not the output, so a truncated
 * AMR would still look correct in the UI. These assertions therefore measure
 * the encoded file itself: decoded duration must match the source, and the
 * byte count must match the declared bitrate over that duration.
 */
@RunWith(AndroidJUnit4.class)
public class FormatSpeedAuditTest {

    private static final String TAG = "FormatSpeed";
    private static final int SECONDS = 10;
    private static final int RATE = 48000;

    private File makeTone(Context ctx, String name, int seconds, int ch)
            throws Exception {
        File raw = new File(RecordingEngine.takesDir(ctx), name + ".wav");
        long frames = (long) RATE * seconds;
        byte[] chunk = new byte[16384 * ch * 4];
        try (RandomAccessFile w = new RandomAccessFile(raw, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, RATE, frames * 4L * ch));
            long written = 0;
            while (written < frames) {
                int want = (int) Math.min(16384, frames - written);
                java.nio.ByteBuffer bb =
                        java.nio.ByteBuffer.wrap(chunk).order(
                                java.nio.ByteOrder.LITTLE_ENDIAN);
                bb.clear();
                for (int i = 0; i < want; i++) {
                    double t = (written + i) / (double) RATE;
                    float v = (float) (0.4 * Math.sin(2 * Math.PI * 440 * t));
                    for (int c = 0; c < ch; c++) bb.putFloat(v);
                }
                w.write(chunk, 0, want * ch * 4);
                written += want;
            }
        }
        return raw;
    }

    private boolean canEncode(String id) {
        try {
            return SaveFormat.of(id).available(
                    InstrumentationRegistry.getInstrumentation()
                            .getTargetContext());
        } catch (Throwable t) {
            return false;
        }
    }

    private int decodedDurationMs(File f) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            return mp.getDuration();
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                mp.release();
            } catch (Exception ignored) {}
        }
    }

    /** Nominal bytes for the format's declared bitrate over SECONDS. */
    private long nominalBytes(SaveFormat fmt) {
        int bitrate;
        switch (fmt) {
            case AMR_NB: bitrate = 12200; break;
            case AMR_WB: bitrate = 23850; break;
            case M4A: bitrate = 128000; break;
            case OPUS: bitrate = 96000; break;
            case FLAC: return -1; // lossless, no fixed bitrate
            default: return -1;
        }
        return bitrate * SECONDS / 8L;
    }

    @Test
    public void amrIsFastBecauseItEncodesFarLessDataAndKeepsFullDuration()
            throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File raw = makeTone(ctx, "speed", SECONDS, 2);
        String[] ids = {"wav16", "m4a", "flac", "opus", "amr_nb", "amr_wb"};

        Map<String, String> table = new LinkedHashMap<>();
        Map<String, Long> ms = new LinkedHashMap<>();

        try {
            for (String id : ids) {
                SaveFormat fmt = SaveFormat.of(id);
                if (!canEncode(id)) {
                    android.util.Log.i(TAG, id + ": unavailable here");
                    continue;
                }
                File out = null;
                try {
                    long t0 = System.nanoTime();
                    TakeSaver.Result r = TakeSaver.convertRaw(ctx, raw,
                            "Speed " + id, RecordPreset.MEMO, fmt, null);
                    long took = (System.nanoTime() - t0) / 1_000_000L;
                    out = r.file;
                    assertTrue("no output for " + id,
                            out != null && out.exists());
                    FormatConverter.validateFinal(out, fmt);

                    int dur = decodedDurationMs(out);
                    // The whole take must be there. A truncated or skipped
                    // encode shows up here, not in the library duration.
                    assertTrue(id + " decoded duration " + dur + "ms, expected "
                            + (SECONDS * 1000 - 600) + ".."
                            + (SECONDS * 1000 + 900),
                            dur > SECONDS * 1000 - 600
                                    && dur < SECONDS * 1000 + 900);

                    long nominal = nominalBytes(fmt);
                    if (nominal > 0) {
                        // Within +-35% of bitrate * duration: the encoder
                        // really did write that much audio.
                        double ratio = out.length() / (double) nominal;
                        assertTrue(id + " wrote " + out.length() + " bytes, "
                                        + "nominal " + nominal + " (ratio "
                                        + Math.round(ratio * 100) + "%)",
                                ratio > 0.65 && ratio < 1.35);
                    }
                    ms.put(id, took);
                    table.put(id, took + "ms, " + out.length() + "B, "
                            + dur + "ms audio");
                } finally {
                    if (out != null) {
                        // noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                }
            }

            for (Map.Entry<String, String> e : table.entrySet()) {
                android.util.Log.i(TAG, e.getKey() + " -> " + e.getValue());
            }

            // The user's observation, made an assertion: AMR really is the
            // quick one, because it hands the codec 1/6 of the samples.
            Long amr = ms.get("amr_nb");
            Long lossless = ms.get("flac");
            Long aac = ms.get("m4a");
            assertTrue("AMR-NB did not finish: " + ms, amr != null);
            // Compared against real codecs only: WAV16/WAV24 are byte
            // conversions with no encoder, so they can legitimately beat any
            // codec and say nothing about encoding cost.
            if (lossless != null) {
                assertTrue("AMR-NB (" + amr + "ms) should beat FLAC ("
                        + lossless + "ms)", amr < lossless);
            }
            if (aac != null) {
                assertTrue("AMR-NB (" + amr + "ms) should beat M4A/AAC ("
                        + aac + "ms)", amr < aac);
            }

            // And the decoded audio is real, not a valid-length empty file:
            // import it back and confirm the tone survived.
            File amrOut = TakeSaver.convertRaw(ctx, raw, "Speed amr back",
                    RecordPreset.MEMO, SaveFormat.AMR_NB, null).file;
            try {
                File back = AudioImporter.takeToFloatWav(amrOut,
                        RecordingEngine.takesDir(ctx), "Speed back", null);
                FormatConverter.WavInfo info =
                        FormatConverter.readFloatWav(back);
                assertEquals("imported rate", RATE, info.sampleRate);
                float peak = 0f;
                int n = info.dataBytes / 4;
                try (RandomAccessFile r = new RandomAccessFile(back, "r")) {
                    r.seek(info.dataStart);
                    byte[] b = new byte[Math.min(info.dataBytes, 65536)];
                    r.readFully(b);
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    while (bb.remaining() >= 4) {
                        peak = Math.max(peak, Math.abs(bb.getFloat()));
                    }
                }
                assertTrue("AMR import is silent (peak " + peak + ", frames "
                        + n + ")", peak > 0.05f);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                amrOut.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }
}
