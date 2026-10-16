package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Roadmap Phase 2 splice helpers: raised-cosine crossfades, edge fades and
 * zero-crossing snap that keep Trim/Cut/Punch-In click-free.
 */
public class DspSpliceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void crossfadeBlendsOneToZero() {
        int frames = Dsp.CROSSFADE_FRAMES;
        float[] out = new float[frames];
        float[] in = new float[frames];
        float[] dst = new float[frames];
        Arrays.fill(out, 1f);
        Dsp.crossfadeFrames(out, 0, in, 0, dst, 0, frames, 1);
        assertTrue("head=" + dst[0], dst[0] > 0.99f);
        assertTrue("tail=" + dst[frames - 1], dst[frames - 1] < 0.01f);
        assertTrue("mid=" + dst[frames / 2],
                Math.abs(dst[frames / 2] - 0.5f) < 0.02f);
        for (int f = 1; f < frames; f++) {
            assertTrue("not monotonic at " + f, dst[f] <= dst[f - 1] + 1e-6f);
        }
    }

    @Test
    public void crossfadeStereoTracksMonoPerChannel() {
        int frames = 64;
        int ch = 2;
        float[] out = new float[frames * ch];
        float[] in = new float[frames * ch];
        float[] dst = new float[frames * ch];
        for (int i = 0; i < out.length; i += 2) {
            out[i] = 1f;
            out[i + 1] = 0.5f;
        }
        Dsp.crossfadeFrames(out, 0, in, 0, dst, 0, frames, ch);
        for (int f = 0; f < frames; f++) {
            assertEquals(dst[f * 2] * 0.5f, dst[f * 2 + 1], 1e-6f);
        }
    }

    @Test
    public void fadeInAndOutShapeEdges() {
        int frames = 240;
        float[] up = new float[frames];
        Arrays.fill(up, 1f);
        Dsp.applyFadeInPlace(up, 0, frames, 1, true);
        assertTrue("fade-in head=" + up[0], up[0] < 0.01f);
        assertTrue("fade-in tail=" + up[frames - 1], up[frames - 1] > 0.99f);
        float[] down = new float[frames];
        Arrays.fill(down, 1f);
        Dsp.applyFadeInPlace(down, 0, frames, 1, false);
        assertTrue("fade-out head=" + down[0], down[0] > 0.99f);
        assertTrue("fade-out tail=" + down[frames - 1],
                down[frames - 1] < 0.01f);
    }

    private File loudWithGap(int frames, int gapStart, int gapEnd)
            throws Exception {
        File f = tmp.newFile("snap.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(1, 48000, (long) frames * 4));
            float[] buf = new float[frames];
            for (int i = 0; i < frames; i++) {
                buf[i] = (i >= gapStart && i < gapEnd) ? 0f
                        : (float) Math.sin(i * 0.3) * 0.9f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void snapFindsSilence() throws Exception {
        int frames = 4800;
        File f = loudWithGap(frames, 2000, 2800);
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            long snapped = Dsp.snapToZeroCrossing(r, 44L, 1, 1900L,
                    frames, 240);
            assertTrue("snapped=" + snapped,
                    snapped >= 1990 && snapped <= 2150);
        } finally {
            r.close();
        }
    }

    @Test
    public void snapClampsEdges() throws Exception {
        int frames = 4800;
        File f = loudWithGap(frames, 2000, 2800);
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            assertEquals(0, Dsp.snapToZeroCrossing(r, 44L, 1, 0, frames, 240));
            assertEquals(0, Dsp.snapToZeroCrossing(r, 44L, 1, -5, frames, 240));
            assertEquals(frames,
                    Dsp.snapToZeroCrossing(r, 44L, 1, frames, frames, 240));
            assertEquals(frames, Dsp.snapToZeroCrossing(r, 44L, 1,
                    frames + 100, frames, 240));
        } finally {
            r.close();
        }
    }
}
