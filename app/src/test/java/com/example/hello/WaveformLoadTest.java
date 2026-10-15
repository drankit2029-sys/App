package com.example.hello;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * BUG-04: attaching an existing session (edit punch-in / crash recovery)
 * must fill the waveform from the file, not leave it blank.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WaveformLoadTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void loadFromFloatWavPopulatesHistory() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        WaveformView v = new WaveformView(a);
        v.measure(
                View.MeasureSpec.makeMeasureSpec(600,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120,
                        View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 600, 120);

        File f = new File(tmp.getRoot(), "sess.wav");
        int frames = 48000;
        float[] s = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float v2 = (float) (0.5 * Math.sin(i * 0.05));
            s[i * 2] = v2;
            s[i * 2 + 1] = v2;
        }
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            Dsp.writeFloatsLE(w, s, 0, s.length);
        } finally {
            w.close();
        }

        v.loadFromFloatWav(f);
        long t0 = System.currentTimeMillis();
        while (v.historyCount() == 0
                && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(50);
        }
        assertTrue("waveform stayed blank for an attached session",
                v.historyCount() > 100);
    }

    @Test
    public void loadPreservesLiveTailPushedDuringIO() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        WaveformView v = new WaveformView(a);
        v.measure(
                View.MeasureSpec.makeMeasureSpec(600,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120,
                        View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 600, 120);

        // Live mic peaks pushed BEFORE the load starts (autostart recovery
        // already recording while disk I/O is in flight).
        for (int i = 0; i < 50; i++) v.push(0.9f);

        File f = new File(tmp.getRoot(), "sess2.wav");
        int frames = 4800;
        float[] s = new float[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = (float) (0.2 * Math.sin(i * 0.05));
        }
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(Dsp.wavFloatHeader(1, 48000, (long) frames * 4));
            Dsp.writeFloatsLE(w, s, 0, s.length);
        } finally {
            w.close();
        }

        v.loadFromFloatWav(f);
        long t0 = System.currentTimeMillis();
        while (v.historyCount() < 60
                && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(50);
        }
        int n = v.historyCount();
        assertTrue("loaded peaks lost the live tail: " + n, n >= 60);
        // Loaded file peaks come first, live tail survives at the end.
        assertTrue("live tail clobbered",
                v.peakAtForTest(n - 1) > 0.85f);
        assertTrue("live tail clobbered",
                v.peakAtForTest(n - 50) > 0.85f);
    }
}
