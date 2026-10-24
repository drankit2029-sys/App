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

/**
 * Save-pipeline file rules: unique output names and duration fallbacks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TakeSaverTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void uniqueFinalCollisionsNumberUp() throws Exception {
        File dir = new File(ctx().getCacheDir(), "uniq");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File raw = new File(dir, "Take.wav");
        new RandomAccessFile(raw, "rw").close();
        File first = TakeSaver.uniqueFinal(raw, "m4a");
        assertEquals("Take.m4a", first.getName());
        // Occupy the first name: the next call must uniquify.
        new RandomAccessFile(first, "rw").close();
        File second = TakeSaver.uniqueFinal(raw, "m4a");
        assertEquals("Take (2).m4a", second.getName());
        // noinspection ResultOfMethodCallIgnored
        first.delete();
        // noinspection ResultOfMethodCallIgnored
        raw.delete();
    }

    @Test
    public void rawScratchKeepsFirstWavSaveClean() throws Exception {
        File dir = new File(ctx().getCacheDir(), "uniqraw");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File raw = new File(dir, "Take_20261024_065622.raw.wav");
        new RandomAccessFile(raw, "rw").close();
        File fin = TakeSaver.uniqueFinal(raw, "wav");
        assertEquals("Take_20261024_065622.wav", fin.getName());
        assertTrue(!fin.getName().contains("(2)"));
        // noinspection ResultOfMethodCallIgnored
        raw.delete();
    }

    @Test
    public void durationOfGarbageFallsBack() throws Exception {
        File f = new File(ctx().getCacheDir(), "nodur.bin");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(new byte[100]);
        } finally {
            w.close();
        }
        assertEquals(1000, TakeSaver.durationOf(f));
        // noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Test
    public void durationOfMissingFallsBack() {
        File f = new File(ctx().getCacheDir(), "missing.wav");
        // noinspection ResultOfMethodCallIgnored
        f.delete();
        assertEquals(1000, TakeSaver.durationOf(f));
    }
}
