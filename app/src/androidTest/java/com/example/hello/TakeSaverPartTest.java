package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * NEW-03 on device: conversions encode into {@code .part} first and only a
 * validated container is renamed to its final name — a kill mid-conversion
 * can never leave a corrupt final file, and no stray part survives success.
 */
@RunWith(AndroidJUnit4.class)
public class TakeSaverPartTest {

    private File makeRaw(Context ctx, String name) throws Exception {
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, name + "_" + System.nanoTime() + ".wav");
        int frames = 48000 * 2;
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            float[] buf = new float[frames * 2];
            for (int i = 0; i < frames * 2; i++) {
                buf[i] = (float) Math.sin(i * 0.05) * 0.4f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void convertRawAdoptsValidatedPartOnly() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File raw = makeRaw(ctx, "PartLife");
        File fin = null;
        try {
            TakeSaver.Result r = TakeSaver.convertRaw(ctx, raw, "Part Life",
                    RecordPreset.MEMO, SaveFormat.WAV16, null);
            fin = r.file;
            assertNotNull("no result file", fin);
            assertTrue("final file missing", fin.exists());
            assertTrue("final file too small", fin.length() > 1024);
            assertTrue("stray part survived success",
                    !ConversionJob.partFor(raw).exists());
            // Nothing half-written under a final-looking name either.
            FormatConverter.validateFinal(fin, SaveFormat.WAV16);
        } finally {
            if (fin != null && fin.exists()) fin.delete();
            File part = ConversionJob.partFor(raw);
            if (part.exists()) part.delete();
            if (raw.exists()) raw.delete();
        }
    }

    @Test
    public void convertRawFailureLeavesNoStrays() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File raw = new File(RecordingEngine.takesDir(ctx),
                "PartFail_" + System.nanoTime() + ".wav");
        RandomAccessFile w = new RandomAccessFile(raw, "rw");
        try {
            w.setLength(0);
            w.write(new byte[100]); // not a WAV at all
        } finally {
            w.close();
        }
        boolean threw = false;
        try {
            TakeSaver.convertRaw(ctx, raw, "Part Fail",
                    RecordPreset.MEMO, SaveFormat.M4A, null);
        } catch (Exception expected) {
            threw = true;
        }
        try {
            assertTrue("garbage raw must fail conversion", threw);
            assertTrue("stray part survived failure",
                    !ConversionJob.partFor(raw).exists());
        } finally {
            if (raw.exists()) raw.delete();
            File part = ConversionJob.partFor(raw);
            if (part.exists()) part.delete();
        }
    }
}
