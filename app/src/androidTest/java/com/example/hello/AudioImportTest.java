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
 * Punch-in import on a real device: float WAV passthrough and compressed
 * (M4A) decode into a fresh 32-bit float session.
 */
@RunWith(AndroidJUnit4.class)
public class AudioImportTest {

    private File makeFloatWav(Context ctx, String name, int frames, int ch)
            throws Exception {
        File f = new File(ctx.getCacheDir(), name);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames * ch; i++) {
                buf[i] = (float) Math.sin(i * 0.05) * 0.4f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void floatWavPassthroughIsStereoFloat() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File src = makeFloatWav(ctx, "imp_float.wav", 48000, 2);
        File dstParent = RecordingEngine.takesDir(ctx);
        try {
            File out = AudioImporter.takeToFloatWav(src, dstParent,
                    "Import Float", null);
            try {
                FormatConverter.WavInfo info =
                        FormatConverter.readFloatWav(out);
                assertEquals(2, info.channels);
                assertEquals(48000, info.sampleRate);
                assertEquals(48000L * 2 * 4, info.dataBytes);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
        }
    }

    @Test
    public void monoFloatWavStaysMono() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File src = makeFloatWav(ctx, "imp_mono.wav", 24000, 1);
        File dstParent = RecordingEngine.takesDir(ctx);
        try {
            File out = AudioImporter.takeToFloatWav(src, dstParent,
                    "Import Mono", null);
            try {
                FormatConverter.WavInfo info =
                        FormatConverter.readFloatWav(out);
                assertEquals(1, info.channels);
                assertEquals(24000L * 1 * 4, info.dataBytes);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
        }
    }

    @Test
    public void m4aDecodesToFloatSession() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File src = makeFloatWav(ctx, "imp_src.wav", 48000, 2);
        File m4a = new File(ctx.getCacheDir(),
                "imp_src_" + System.nanoTime() + ".m4a");
        File dstParent = RecordingEngine.takesDir(ctx);
        try {
            FormatConverter.toM4a(src, m4a, 128000, null);
            File out = AudioImporter.takeToFloatWav(m4a, dstParent,
                    "Import M4A", null);
            try {
                FormatConverter.WavInfo info =
                        FormatConverter.readFloatWav(out);
                assertEquals(2, info.channels);
                assertEquals(48000, info.sampleRate);
                assertTrue("empty import", info.dataBytes > 0);
            } finally {
                // noinspection ResultOfMethodCallIgnored
                out.delete();
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
            // noinspection ResultOfMethodCallIgnored
            m4a.delete();
        }
    }

    @Test
    public void garbageFileIsRejected() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File src = new File(ctx.getCacheDir(), "imp_garbage.bin");
        RandomAccessFile w = new RandomAccessFile(src, "rw");
        try {
            w.write(new byte[500]);
        } finally {
            w.close();
        }
        File dstParent = RecordingEngine.takesDir(ctx);
        try {
            boolean threw = false;
            try {
                AudioImporter.takeToFloatWav(src, dstParent, "Garbage", null);
            } catch (Exception expected) {
                threw = true;
            }
            assertTrue("garbage must be rejected", threw);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
        }
    }
}
