package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaMuxer;
import android.os.Build;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link HardwareApiManager} verifying Android version-specific
 * API routing across SDK tiers (API 24..35) and chunked GPU/SIMD DSP kernels.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HardwareApiManagerTest {

    @After
    public void tearDown() {
        HardwareApiManager.setTestSdkIntOverride(-1);
        HardwareApiManager.get().setGpuEnabled(true);
    }

    @Test
    public void sdkVersionRoutingAdaptsAcrossAndroidVersions() {
        HardwareApiManager mgr = HardwareApiManager.get();

        // API 24 (Android 7.0 Nougat)
        HardwareApiManager.setTestSdkIntOverride(Build.VERSION_CODES.N);
        assertEquals(24, HardwareApiManager.sdkInt());
        assertFalse(mgr.isOpusMuxerSupported());
        assertEquals("webm", mgr.getOpusFileExtension());
        assertEquals(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
                mgr.getOpusMuxerOutputFormat());
        assertEquals(-1, mgr.getPhysicalMicrophoneCount(null));

        // API 26 (Android 8.0 Oreo)
        HardwareApiManager.setTestSdkIntOverride(Build.VERSION_CODES.O);
        assertTrue(mgr.isOpusMuxerSupported());
        assertEquals("webm", mgr.getOpusFileExtension());
        assertEquals(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
                mgr.getOpusMuxerOutputFormat());

        // API 29 (Android 10 Q)
        HardwareApiManager.setTestSdkIntOverride(Build.VERSION_CODES.Q);
        assertTrue(mgr.isOpusMuxerSupported());
        assertEquals("opus", mgr.getOpusFileExtension());
        assertEquals(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                mgr.getOpusMuxerOutputFormat());
    }

    @Test
    public void chunkedGainClipMixAndSanitizationAreExact() {
        HardwareApiManager mgr = HardwareApiManager.get();
        int n = 10000;
        float[] wet = new float[n];
        float[] dry = new float[n];
        for (int i = 0; i < n; i++) {
            wet[i] = 0.8f;
            dry[i] = 0.2f;
        }
        wet[500] = Float.NaN;
        wet[501] = Float.POSITIVE_INFINITY;

        // Gain = 2.0 (1.6 -> clamped to 0.9 ceiling), then 50% mix with dry (0.2):
        // expected = 0.5 * 0.2 + 0.5 * 0.9 = 0.55
        mgr.processGainClipMixChunked(wet, dry, n, 2.0f, 0.9f, 0.5f);
        assertEquals(0.55f, wet[0], 1e-4f);
        assertEquals(0.55f, wet[n - 1], 1e-4f);
        assertTrue(Float.isFinite(wet[500]));
        assertTrue(Float.isFinite(wet[501]));
    }

    @Test
    public void complexMultiplyAndWsolaCorrelationProduceExpectedResults() {
        HardwareApiManager mgr = HardwareApiManager.get();
        assertNotNull(mgr.getComputeTier());

        float[] xRe = {1f, 2f, 3f, -1f};
        float[] xIm = {0.5f, -1f, 2f, 0.5f};
        float[] hRe = {2f, 0.5f, -1f, 3f};
        float[] hIm = {-1f, 1.5f, 0.5f, -2f};
        mgr.complexMultiplyInPlace(xRe, xIm, hRe, hIm, 4);
        // k=0: (1 + 0.5i)*(2 - 1i) = 2.5 + 0i
        assertEquals(2.5f, xRe[0], 1e-5f);
        assertEquals(0.0f, xIm[0], 1e-5f);

        int frames = 512;
        float[] signal = new float[frames];
        for (int i = 0; i < frames; i++) {
            signal[i] = (float) Math.sin(2.0 * Math.PI * i / 32.0);
        }
        int best = mgr.findBestWsolaCandidate(signal, frames, 1,
                120, 140, 64, 64, 1, 128);
        // Period is 32 samples, so 64 + 2*32 = 128 has correlation 1.0
        assertEquals(128, best);
    }
}
