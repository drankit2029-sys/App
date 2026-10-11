package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Runs the real encode/mux path on a real device image, one format per test
 * method. Robolectric stubs MediaCodec out, so this is the only way to see
 * which container actually works on a given Android build.
 *
 * <p>Each method is independent on purpose: a native crash in one encoder
 * kills the process and would hide the results of the rest.
 */
@RunWith(AndroidJUnit4.class)
public class EncodeFormatsTest {

    private static final String TAG = "ENCFMT";

    private File makeFloatWav(String name) throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File f = new File(ctx.getCacheDir(), name);
        int frames = 48000; // 1.0s at 48 kHz, stereo
        byte[] hdr = Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(hdr);
            for (int i = 0; i < frames * 2; i++) {
                w.writeFloat((float) Math.sin(i * 0.03) * 0.5f);
            }
        } finally {
            w.close();
        }
        return f;
    }

    private File out(String ext) {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File f = new File(ctx.getCacheDir(), "out_" + System.nanoTime() + ext);
        // noinspection ResultOfMethodCallIgnored
        f.delete();
        return f;
    }

    @Test
    public void wav16() throws Exception {
        File src = makeFloatWav("wav16_in.wav");
        File dst = out(".wav");
        FormatConverter.toWav16(src, dst, null);
        Log.i(TAG, "PASS wav16 bytes=" + dst.length());
        assertTrue(dst.length() > 1000);
    }

    @Test
    public void m4a() throws Exception {
        File src = makeFloatWav("m4a_in.wav");
        File dst = out(".m4a");
        FormatConverter.toM4a(src, dst, 128000, null);
        Log.i(TAG, "PASS m4a bytes=" + dst.length());
        FormatConverter.validateFinal(dst, SaveFormat.M4A);
        assertTrue(dst.length() > 512);
    }

    @Test
    public void opus() throws Exception {
        File src = makeFloatWav("opus_in.wav");
        File dst = out(".webm");
        FormatConverter.toOpus(src, dst, 128000, null);
        Log.i(TAG, "PASS opus bytes=" + dst.length());
        FormatConverter.validateFinal(dst, SaveFormat.OPUS);
        assertTrue(dst.length() > 512);
    }

    @Test
    public void vorbis() throws Exception {
        File src = makeFloatWav("vorbis_in.wav");
        File dst = out(".webm");
        FormatConverter.toVorbis(src, dst, 128000, null);
        Log.i(TAG, "PASS vorbis bytes=" + dst.length());
        FormatConverter.validateFinal(dst, SaveFormat.VORBIS);
        assertTrue(dst.length() > 512);
    }

    @Test
    public void flac() throws Exception {
        File src = makeFloatWav("flac_in.wav");
        File dst = out(".flac");
        FormatConverter.toFlac(src, dst, null);
        Log.i(TAG, "PASS flac bytes=" + dst.length());
        FormatConverter.validateFinal(dst, SaveFormat.FLAC);
        assertTrue(dst.length() > 512);
    }

    @Test
    public void amrNb() throws Exception {
        File src = makeFloatWav("amrnb_in.wav");
        File dst = out(".3gp");
        FormatConverter.toAmr(src, dst, false, null);
        Log.i(TAG, "PASS amrnb bytes=" + dst.length());
        assertTrue(dst.length() > 512);
    }

    @Test
    public void amrWb() throws Exception {
        File src = makeFloatWav("amrwb_in.wav");
        File dst = out(".3gp");
        FormatConverter.toAmr(src, dst, true, null);
        Log.i(TAG, "PASS amrwb bytes=" + dst.length());
        assertTrue(dst.length() > 512);
    }

    /** The full save pipeline, exactly as the save button drives it. */
    @Test
    public void fullSavePipeline() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine engine = RecordingEngine.get();
        engine.clearSession();
        engine.startNewSession(ctx, "Instrumentation", 2);
        File raw = engine.rawFile;
        assertNotNull(raw);
        int frames = 24000;
        RandomAccessFile w = new RandomAccessFile(raw, "rw");
        try {
            w.seek(w.length());
            for (int i = 0; i < frames * 2; i++) {
                w.writeFloat((float) Math.sin(i * 0.05) * 0.4f);
            }
        } finally {
            w.close();
        }
        TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx,
                RecordPreset.MUSIC, SaveFormat.WAV16, null);
        Log.i(TAG, "PASS pipeline file=" + r.file.getName()
                + " bytes=" + r.file.length() + " dur=" + r.durationMs);
        assertTrue(r.file.exists());
        engine.clearSession();
    }
}
