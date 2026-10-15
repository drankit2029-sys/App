package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * BUG-01 + BUG-08 on device: compressed imports land at 48 kHz with the
 * right duration/pitch (no 3x/6x chipmunking), and mono sources stay mono.
 */
@RunWith(AndroidJUnit4.class)
public class AudioImportResampleTest {

    private File makeFloatWav(Context ctx, String name, int frames, int ch,
                              int rate, double freq) throws Exception {
        File f = new File(ctx.getCacheDir(), name);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, rate, (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float v = (float) (0.4 * Math.sin(2 * Math.PI * freq * i
                        / rate));
                for (int c = 0; c < ch; c++) buf[i * ch + c] = v;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
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
                float a = Math.abs(fb.get(i));
                if (a > peak) peak = a;
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
            byte[] raw = new byte[(int) Math.min(frames, 48000) * ch * 4];
            r.seek(info.dataStart);
            r.readFully(raw);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int n = raw.length / 4 / ch;
            int crossings = 0;
            float prev = fb.get(0);
            for (int f = 1; f < n; f++) {
                float cur = fb.get(f * ch);
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

    @Test
    public void monoFloatStaysMono() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File src = makeFloatWav(ctx, "imp_mono.wav", 48000, 1, 48000, 440.0);
        File dstParent = RecordingEngine.takesDir(ctx);
        File out = null;
        try {
            out = AudioImporter.takeToFloatWav(src, dstParent,
                    "Import Mono", null);
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(out);
            assertEquals("mono was upmixed to stereo", 1, info.channels);
            assertEquals(48000, info.sampleRate);
            assertEquals(440.0, pitchHz(out), 5.0);
        } finally {
            if (src.exists()) src.delete();
            if (out != null && out.exists()) out.delete();
        }
    }

    @Test
    public void rate44kM4aImportsAt48kWithRightDurationAndPitch()
            throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        // 2 s of 440 Hz at 44.1 kHz, AAC-encoded on device.
        File float44 = makeFloatWav(ctx, "imp44.wav", 88200, 2, 44100, 440.0);
        File m4a = new File(ctx.getCacheDir(), "imp44.m4a");
        File dstParent = RecordingEngine.takesDir(ctx);
        File out = null;
        try {
            FormatConverter.toM4a(float44, m4a, 128000, null);
            out = AudioImporter.takeToFloatWav(m4a, dstParent,
                    "Import 44k", null);
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(out);
            assertEquals(48000, info.sampleRate);
            assertEquals(2, info.channels);
            long frames = info.dataBytes / 4 / 2;
            // 2.0 s, tolerant of AAC encoder/decoder padding.
            assertTrue("duration " + frames + " frames",
                    Math.abs(frames - 96000) < 14400);
            // The old bug played this back ~9% fast; pitch must be 440 Hz.
            assertEquals(440.0, pitchHz(out), 8.0);
            // NEW-02: byte-swapped PCM would decode to erratic full-scale
            // garbage instead of a 0.4 sine.
            assertTrue("peak " + peakAbs(out),
                    peakAbs(out) > 0.25f && peakAbs(out) < 0.6f);
        } finally {
            if (float44.exists()) float44.delete();
            if (m4a.exists()) m4a.delete();
            if (out != null && out.exists()) out.delete();
        }
    }
}
