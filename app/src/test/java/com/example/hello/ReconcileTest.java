package com.example.hello;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Startup-sweep robustness: truncated saves, garbage files and legacy rows
 * must never take the app down in a launch loop. Poison on disk degrades to
 * fallback durations; the sweep itself never throws.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReconcileTest {

    private File takesDir() {
        Context ctx = ApplicationProvider.getApplicationContext();
        return RecordingEngine.takesDir(ctx);
    }

    private void write(File f, byte[] data) throws Exception {
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(data);
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void poisonFilesNeverThrow() throws Exception {
        File dir = takesDir();
        // Truncated conversion remnants: empty container, garbage bytes,
        // and a larger-than-probe-gate corrupt file.
        write(new File(dir, "poison_empty_20261011_101500.m4a"), new byte[0]);
        byte[] junk = new byte[100];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (i * 7 + 3);
        write(new File(dir, "poison_junk_20261011_101500.wav"), junk);
        byte[] big = new byte[5000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 13 + 1);
        write(new File(dir, "poison_big_20261011_101500.m4a"), big);

        Context ctx = ApplicationProvider.getApplicationContext();
        TakesRepository.ReconcileResult rr = TakesRepository.reconcile(ctx);
        assertTrue(rr != null);

        // Second sweep: whatever was adopted is now referenced, so nothing
        // may be re-probed or re-adopted, and still nothing throws.
        TakesRepository.ReconcileResult rr2 = TakesRepository.reconcile(ctx);
        assertTrue(rr2 != null);
        assertTrue(rr2.adopted == 0);

        for (File f : new File[]{
                new File(dir, "poison_empty_20261011_101500.m4a"),
                new File(dir, "poison_junk_20261011_101500.wav"),
                new File(dir, "poison_big_20261011_101500.m4a")}) {
            for (Take t : new java.util.ArrayList<>(TakesRepository.all())) {
                if (f.getAbsolutePath().equals(t.filePath)) {
                    TakesRepository.delete(t.id);
                }
            }
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void validWavAdoptsWithRealDuration() throws Exception {
        File dir = takesDir();
        File good = new File(dir, "good_20261011_101500.wav");
        // 1 channel, 48 kHz, exactly one second of silence.
        byte[] hdr = Dsp.wavFloatHeader(1, 48000, 48000L * 4);
        byte[] body = new byte[48000 * 4];
        byte[] all = new byte[hdr.length + body.length];
        System.arraycopy(hdr, 0, all, 0, hdr.length);
        write(good, all);

        Context ctx = ApplicationProvider.getApplicationContext();
        TakesRepository.ReconcileResult rr = TakesRepository.reconcile(ctx);
        assertTrue(rr != null);
        assertTrue(rr.adopted >= 1);

        boolean found = false;
        for (Take t : TakesRepository.all()) {
            if (good.getAbsolutePath().equals(t.filePath)) {
                found = true;
                assertTrue(t.durationMs == 1000);
            }
        }
        assertTrue(found);
        if (found) {
            for (Take t : new java.util.ArrayList<>(TakesRepository.all())) {
                if (good.getAbsolutePath().equals(t.filePath)) {
                    TakesRepository.delete(t.id);
                }
            }
        }
        // noinspection ResultOfMethodCallIgnored
        good.delete();
    }
}
