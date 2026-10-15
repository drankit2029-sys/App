package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaPlayer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Format round trip on a real decoder (FMT-01/FMT-02/FMT-03).
 *
 * <p>For every save format: encode a known tone, pass the strict container
 * validation, prove the platform can actually open it (MediaPlayer.prepare),
 * and prove the Edit path can decode it back to a 48 kHz float session at the
 * right pitch. This is the test that catches a malformed FLAC header, which
 * previously produced files Android silently refused to play or edit.
 */
@RunWith(AndroidJUnit4.class)
public class FormatRoundTripTest {

    private File makeTone(Context ctx, String name, int frames, int ch)
            throws Exception {
        File f = new File(ctx.getCacheDir(), name);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float v = (float) (0.4 * Math.sin(i * 0.05));
                for (int c = 0; c < ch; c++) buf[i * ch + c] = v;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    private static boolean canEncode(String id) {
        return SaveFormat.of(id).available(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test
    public void everyFormatValidatesPlaysAndImports() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        String[] ids = {"wav16", "wav24", "m4a", "flac", "opus", "amr_nb",
                "amr_wb"};
        List<String> problems = new ArrayList<>();
        for (String id : ids) {
            SaveFormat fmt = SaveFormat.of(id);
            if (!canEncode(id)) continue; // probe-gated on this device
            File raw = makeTone(ctx, "rt_" + id, 96000, 2); // 2.0 s
            File out = null;
            File back = null;
            try {
                TakeSaver.Result r = TakeSaver.convertRaw(ctx, raw,
                        "RoundTrip " + id, RecordPreset.PODCAST, fmt, null);
                out = r.file;
                assertNotNull("no output for " + id, out);
                // Strict container validation.
                FormatConverter.validateFinal(out, fmt);

                // Real decode through the platform.
                MediaPlayer mp = new MediaPlayer();
                try {
                    mp.setDataSource(out.getAbsolutePath());
                    mp.prepare();
                    int dur = mp.getDuration();
                    if (dur < 1200 || dur > 3200) {
                        problems.add(id + " duration " + dur + "ms");
                    }
                } catch (Exception e) {
                    problems.add(id + " not playable: " + e);
                } finally {
                    try {
                        mp.release();
                    } catch (Exception ignored) {}
                }

                // Edit path: decode back to a 48 kHz float session.
                back = AudioImporter.takeToFloatWav(out,
                        RecordingEngine.takesDir(ctx), "RT " + id, null);
                FormatConverter.WavInfo info =
                        FormatConverter.readFloatWav(back);
                if (info.sampleRate != 48000) {
                    problems.add(id + " import rate " + info.sampleRate);
                }
                if (info.dataBytes <= 0) problems.add(id + " empty import");
                double pitch = pitchHz(back);
                // ~382 Hz tone; allow for lossy codecs and resampling.
                if (pitch < 330 || pitch > 440) {
                    problems.add(id + " pitch " + Math.round(pitch) + "Hz");
                }
                float peak = peakAbs(back);
                if (peak < 0.15f || peak > 0.8f) {
                    problems.add(id + " peak " + peak);
                }
            } catch (Exception e) {
                problems.add(id + " threw " + e);
            } finally {
                if (out != null) {
                    // noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
                if (back != null) {
                    // noinspection ResultOfMethodCallIgnored
                    back.delete();
                }
                if (raw.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    raw.delete();
                }
            }
        }
        assertTrue("round-trip problems: " + problems, problems.isEmpty());
    }

    private static float peakAbs(File wav) throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        RandomAccessFile r = new RandomAccessFile(wav, "r");
        try {
            int ch = info.channels;
            long frames = info.dataBytes / 4 / ch;
            int n = (int) Math.min(frames, 48000);
            byte[] raw = new byte[n * ch * 4];
            r.seek(info.dataStart);
            r.readFully(raw);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float peak = 0f;
            for (int i = 0; i < n * ch; i++) {
                peak = Math.max(peak, Math.abs(fb.get(i)));
            }
            return peak;
        } finally {
            r.close();
        }
    }

    private static double pitchHz(File wav) throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        RandomAccessFile r = new RandomAccessFile(wav, "r");
        try {
            int ch = info.channels;
            long frames = info.dataBytes / 4 / ch;
            int n = (int) Math.min(frames, 48000);
            byte[] raw = new byte[n * ch * 4];
            r.seek(info.dataStart);
            r.readFully(raw);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int crossings = 0;
            float prev = fb.get(0);
            for (int i = 1; i < n; i++) {
                float cur = fb.get(i * ch);
                if ((prev <= 0f && cur > 0f) || (prev >= 0f && cur < 0f)) {
                    crossings++;
                }
                prev = cur;
            }
            return crossings / 2.0 / (n / (double) info.sampleRate);
        } finally {
            r.close();
        }
    }
}