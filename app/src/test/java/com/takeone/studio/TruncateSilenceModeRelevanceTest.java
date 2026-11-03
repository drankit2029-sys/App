package com.takeone.studio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * Verifies that the Truncate Silence targets are truly mode-exclusive in both
 * the in-memory and streaming renderers, and that the active target still
 * changes the output.
 */
public class TruncateSilenceModeRelevanceTest {

    private static final int SR = 48000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static float[] input() {
        int frames = 4 * SR;
        float[] buf = new float[frames];
        // 0.5 s tone, 1.0 s silence, 0.5 s tone, 1.0 s silence, 1.0 s tone.
        int a = SR / 2;
        int b = a + SR;
        int c = b + SR / 2;
        int d = c + SR;
        for (int f = 0; f < frames; f++) {
            boolean sounding = f < a || (f >= b && f < c) || f >= d;
            if (sounding) {
                buf[f] = (float) (0.5 * Math.sin(2.0 * Math.PI * 330.0 * f / SR));
            }
        }
        return buf;
    }

    private static float[] render(int action, int truncateTo, int compressTo) {
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        p.getParam("ts_thresh").setFloat(-40f);
        p.getParam("ts_min").setCurrentRaw(400);
        p.getParam("ts_action").setEnumIndex(action);
        p.getParam("ts_trunc").setCurrentRaw(truncateTo);
        p.getParam("ts_compress").setCurrentRaw(compressTo);
        p.prepare(SR, 1);
        float[] in = input();
        return p.processOfflineRegion(in, in.length, 1);
    }

    @Test
    public void compresstoIsInertInTruncateMode() {
        float[] low = render(0, 150, 5);
        float[] mid = render(0, 150, 35);
        float[] high = render(0, 150, 90);
        assertArrayEquals(low, mid, 0f);
        assertArrayEquals(mid, high, 0f);
    }

    @Test
    public void truncatetoIsInertInCompressMode() {
        float[] low = render(1, 10, 35);
        float[] mid = render(1, 150, 35);
        float[] high = render(1, 2000, 35);
        assertArrayEquals(low, mid, 0f);
        assertArrayEquals(mid, high, 0f);
    }

    @Test
    public void activeTargetsStillChangeOutput() {
        float[] shortGap = render(0, 50, 35);
        float[] longGap = render(0, 1500, 35);
        assertTrue("truncate target did not change the output",
                shortGap.length != longGap.length);

        float[] compressed = render(1, 150, 10);
        float[] lessCompressed = render(1, 150, 80);
        assertTrue("compress target did not change the output",
                compressed.length != lessCompressed.length);
    }

    @Test
    public void fileRendererHonoursTheSameModeGating() throws Exception {
        float[] in = input();
        File src = new File(tmp.getRoot(), "src.wav");
        FxVerifySupport.writeFloatWav(src, in, 1, SR);

        File mid = new File(tmp.getRoot(), "mid.wav");
        File high = new File(tmp.getRoot(), "high.wav");
        long midFrames = renderFile(src, mid, 0, 150, 35);
        long highFrames = renderFile(src, high, 0, 150, 90);
        assertTrue(midFrames > 0);
        assertTrue("Compress To changed the Truncate-mode file render",
                midFrames == highFrames);
        FxVerifySupport.WavData a = FxVerifySupport.readFloatWav(mid);
        FxVerifySupport.WavData b = FxVerifySupport.readFloatWav(high);
        assertArrayEquals(a.samples, b.samples, 0f);

        File truncLow = new File(tmp.getRoot(), "trunc_low.wav");
        File truncHigh = new File(tmp.getRoot(), "trunc_high.wav");
        long lowFrames = renderFile(src, truncLow, 1, 10, 35);
        long highTruncFrames = renderFile(src, truncHigh, 1, 2000, 35);
        assertTrue(lowFrames > 0);
        assertTrue("Truncate To changed the Compress-mode file render",
                lowFrames == highTruncFrames);
    }

    private static long renderFile(File src, File dst, int action,
                                   int truncateTo, int compressTo) throws Exception {
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        p.getParam("ts_thresh").setFloat(-40f);
        p.getParam("ts_min").setCurrentRaw(400);
        p.getParam("ts_action").setEnumIndex(action);
        p.getParam("ts_trunc").setCurrentRaw(truncateTo);
        p.getParam("ts_compress").setCurrentRaw(compressTo);
        long frames = p.processOfflineRangeToFile(src, 0, 4L * SR, dst, SR, 1);
        return frames;
    }
}
