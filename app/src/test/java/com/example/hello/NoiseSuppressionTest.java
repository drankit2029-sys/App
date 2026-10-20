package com.example.hello;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/**
 * Spectral Noise Suppression Pro: selection-learned profile analysis, profile
 * persistence, stationary-noise reduction, tonal preservation, adaptive
 * fallback, chunk invariance and rack wiring/latency.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NoiseSuppressionTest {

    private static float[] whiteNoise(int frames, int ch, long seed,
                                      float amp) {
        float[] out = new float[frames * ch];
        long x = seed | 1L;
        for (int i = 0; i < out.length; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            float v = (x >>> 40) / 8388608f - 1f;
            out[i] = v * amp;
        }
        return out;
    }

    private static float[] sine(int frames, int ch, float hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) Math.sin(2.0 * Math.PI * hz * f / 48000.0) * amp;
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static double rms(float[] a, int fromFrame, int toFrame, int ch) {
        double sum = 0;
        int n = 0;
        for (int f = fromFrame; f < toFrame; f++) {
            for (int c = 0; c < ch; c++) {
                double v = a[f * ch + c];
                sum += v * v;
                n++;
            }
        }
        return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    private static NoiseSuppressionProcessor processor(float[] profile,
                                                       int amt) {
        NoiseSuppressionProcessor p = new NoiseSuppressionProcessor();
        p.setNoiseProfile(profile);
        p.getParam("amt").setCurrentRaw(amt);
        p.prepare(48000, 1);
        return p;
    }

    @Test
    public void learnProfileBuildsFinitePositiveBins() {
        float[] noise = whiteNoise(48000, 1, 0x1234abcdL, 0.12f);
        float[] profile = NoiseSuppressionProcessor.learnProfile(noise, 1);
        assertNotNull(profile);
        assertEquals(NoiseSuppressionProcessor.BINS, profile.length);
        for (float v : profile) {
            assertTrue(v > 0f && !Float.isInfinite(v) && !Float.isNaN(v));
        }
    }

    @Test
    public void shortSelectionCannotProduceAProfile() {
        float[] shortNoise = whiteNoise(
                NoiseSuppressionProcessor.FFT_SIZE - 1, 1, 7L, 0.1f);
        assertNull(NoiseSuppressionProcessor.learnProfile(shortNoise, 1));
        assertNull(NoiseSuppressionProcessor.learnProfile(null, 1));
    }

    @Test
    public void learnedProfileSuppressesStationaryNoise() {
        int frames = 96000;
        float[] noise = whiteNoise(frames, 1, 0x51ee7L, 0.10f);
        float[] analysis = Arrays.copyOfRange(noise, 0, 24000);
        float[] profile = NoiseSuppressionProcessor.learnProfile(analysis, 1);
        assertNotNull(profile);

        float[] wet = noise.clone();
        processPadded(processor(profile, 100), wet, frames);

        double dry = rms(noise, 48000, frames, 1);
        double out = rms(wet, 48000, frames, 1);
        double reductionDb = 20.0 * Math.log10(dry / Math.max(1e-12, out));
        assertTrue("noise reduction only " + reductionDb + " dB",
                reductionDb > 6.0);
    }

    @Test
    public void learnedProfilePreservesLoudTone() {
        float[] noise = whiteNoise(24000, 1, 0xabcL, 0.02f);
        float[] profile = NoiseSuppressionProcessor.learnProfile(noise, 1);
        int frames = 48000;
        float[] tone = sine(frames, 1, 1000f, 0.5f);
        float[] wet = tone.clone();
        processPadded(processor(profile, 100), wet, frames);

        double dry = rms(tone, 4800, frames, 1);
        double out = rms(wet, 4800, frames, 1);
        double deltaDb = 20.0 * Math.log10(out / dry);
        assertTrue("tone changed by " + deltaDb + " dB",
                Math.abs(deltaDb) < 2.0);
    }

    @Test
    public void adaptiveFallbackSuppressesSteadyNoiseWithoutProfile() {
        int frames = 96000;
        float[] noise = whiteNoise(frames, 1, 0x777L, 0.08f);
        float[] wet = noise.clone();
        processPadded(processor(null, 100), wet, frames);

        double dry = rms(noise, 48000, frames, 1);
        double out = rms(wet, 48000, frames, 1);
        double reductionDb = 20.0 * Math.log10(dry / Math.max(1e-12, out));
        assertTrue("adaptive fallback reduction only " + reductionDb + " dB",
                reductionDb > 6.0);
    }

    @Test
    public void profileJsonRoundTripsWithinQuantizationStep() {
        float[] noise = whiteNoise(24000, 1, 0x9eL, 0.05f);
        float[] profile = NoiseSuppressionProcessor.learnProfile(noise, 1);
        JSONArray json = NoiseSuppressionProcessor.profileToJson(profile);
        float[] back = NoiseSuppressionProcessor.profileFromJson(json);
        assertNotNull(back);
        for (int i = 0; i < profile.length; i++) {
            double d = Dsp.linToDb(profile[i]) - Dsp.linToDb(back[i]);
            assertTrue("bin " + i + " drifted " + d + " dB",
                    Math.abs(d) <= 0.11);
        }
        assertNull(NoiseSuppressionProcessor.profileFromJson(new JSONArray()));
    }

    @Test
    public void chunkedProcessingMatchesSingleCallAndReportsLatency() {
        int frames = 12000;
        int latency = NoiseSuppressionProcessor.FFT_SIZE;
        float[] input = whiteNoise(frames, 1, 0x42L, 0.1f);
        float[] profile = NoiseSuppressionProcessor.learnProfile(
                Arrays.copyOfRange(input, 0, 4096), 1);

        // One call over the zero-padded take, exactly like an offline commit:
        // the reported latency drains through the padding.
        NoiseSuppressionProcessor whole = processor(profile, 80);
        float[] padded = Arrays.copyOf(input, frames + latency);
        whole.processInPlace(padded, frames + latency, 1);
        float[] expected = Arrays.copyOf(padded, frames);

        NoiseSuppressionProcessor chunked = processor(profile, 80);
        float[] work = Arrays.copyOf(input, frames + latency);
        int off = 0;
        int[] steps = {1, 37, 512, 2049, 3, 700, 4096};
        int i = 0;
        while (off < work.length) {
            int n = Math.min(steps[i % steps.length], work.length - off);
            float[] chunk = Arrays.copyOfRange(work, off, off + n);
            chunked.processInPlace(chunk, n, 1);
            System.arraycopy(chunk, 0, work, off, n);
            off += n;
            i++;
        }
        assertArrayEquals(expected, Arrays.copyOf(work, frames), 0.0f);
        assertEquals(latency, chunked.getLatencyFrames());
    }

    @Test
    public void rackPushesLearnedProfileAndPersistsItWithTheSlot() {
        float[] noise = whiteNoise(24000, 1, 0x31L, 0.04f);
        float[] profile = NoiseSuppressionProcessor.learnProfile(noise, 1);

        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        DspChain.EffectSlot slot = new DspChain.EffectSlot(
                DspChain.EffectType.NOISE_SUPPRESSION, true, 70, true);
        slot.noiseProfile = profile;
        cfg.slots.add(slot);
        cfg.syncLegacyFieldsFromSlots();

        DspChain chain = new DspChain(1, cfg);
        assertTrue(chain.isActive());
        assertEquals(NoiseSuppressionProcessor.FFT_SIZE,
                chain.getLatencyFrames());
        AudioProcessor proc = chain.getRack().getProcessor(
                chain.getRack().indexOf("noise"));
        assertTrue(proc instanceof NoiseSuppressionProcessor);
        assertSame(profile, ((NoiseSuppressionProcessor) proc)
                .getNoiseProfile());

        Context ctx = ApplicationProvider.getApplicationContext();
        cfg.save(ctx);
        DspChain.Config back = DspChain.Config.load(ctx);
        DspChain.EffectSlot loaded = null;
        for (DspChain.EffectSlot s : back.slots) {
            if (s.type == DspChain.EffectType.NOISE_SUPPRESSION) loaded = s;
        }
        assertNotNull(loaded);
        assertNotNull(loaded.noiseProfile);
        assertEquals(profile.length, loaded.noiseProfile.length);
        for (int i = 0; i < profile.length; i++) {
            double d = Dsp.linToDb(profile[i])
                    - Dsp.linToDb(loaded.noiseProfile[i]);
            assertTrue(Math.abs(d) <= 0.11);
        }
    }

    private static void processPadded(NoiseSuppressionProcessor p,
                                      float[] buf, int frames) {
        float[] work = Arrays.copyOf(buf,
                frames + NoiseSuppressionProcessor.FFT_SIZE);
        p.processInPlace(work, frames + NoiseSuppressionProcessor.FFT_SIZE, 1);
        System.arraycopy(work, 0, buf, 0, frames);
    }
}
