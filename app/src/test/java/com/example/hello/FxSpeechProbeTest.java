package com.example.hello;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Guards the committed Kokoro fixture and the speech probe family. */
public class FxSpeechProbeTest {

    @Test
    public void fixtureIs48kMonoFloat() throws Exception {
        FxVerifySupport.WavData data =
                FxVerifySupport.readFloatWav(FxVerifySupport.speechFixture());
        assertEquals(48000, data.sampleRate);
        assertEquals(1, data.channels);
        assertTrue("fixture should be at least 12 s", data.frames >= 12 * 48000);
    }

    @Test
    public void speechProbesAreFiniteAndDistinct() {
        int frames = 48000;
        float[] clean = FxVerifySupport.buildProbe("speech", frames, 1, null);
        assertEquals(frames, clean.length);
        float peak = 0f;
        for (float v : clean) {
            assertTrue(Float.isFinite(v));
            peak = Math.max(peak, Math.abs(v));
        }
        assertTrue("speech should be audible", peak > 0.1f);
        assertTrue("speech should be normalised below full scale", peak <= 0.5001f);

        float[] noisy = FxVerifySupport.buildProbe("speech_noise", frames, 1, null);
        float[] clipped = FxVerifySupport.buildProbe("speech_clipped", frames, 1, null);
        float[] hum = FxVerifySupport.buildProbe("speech_hum", frames, 1, null);
        float[] gaps = FxVerifySupport.buildProbe("speech_gaps", frames, 1, null);
        assertNotEquals(clean[100], noisy[100]);
        assertNotEquals(clean[100], clipped[100]);
        assertNotEquals(clean[100], hum[100]);
        assertEquals(0f, gaps[(int) (3.2 * 48000)], 0f);

        float[] stereo = FxVerifySupport.buildProbe("speech_stereo", frames, 2, null);
        assertEquals(frames * 2, stereo.length);
        assertNotEquals(stereo[100 * 2], stereo[100 * 2 + 1]);
    }
}
