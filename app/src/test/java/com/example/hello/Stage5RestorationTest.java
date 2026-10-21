package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Stage 5 suite: STFT reconstruction fidelity, spectral noise reduction,
 * de-clicker, de-clipper, LPC repair, hum removal and plosive suppression.
 */
public class Stage5RestorationTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static double snrDb(float[] ref, float[] test) {
        double sig = 0;
        double noise = 0;
        for (int i = 0; i < ref.length; i++) {
            sig += (double) ref[i] * ref[i];
            double d = ref[i] - test[i];
            noise += d * d;
        }
        if (noise < 1e-30) return 300.0;
        return 10 * Math.log10(sig / noise);
    }

    private static File writeWav(File dir, String name, float[] data, int ch)
            throws Exception {
        File f = new File(dir, name);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.write(Dsp.wavFloatHeader(ch, SR, data.length * 4L));
            Dsp.writeFloatsLE(raf, data, 0, data.length);
        }
        return f;
    }

    @Test
    public void stftReconstructsTheSignalAboveOneHundredDbSnr() {
        int frames = 8192;
        float[] buf = sine(frames, 1, 1000, 0.5f);
        StftProcessor stft = new StftProcessor(1024, 4) {
            @Override
            protected void processSpectrum(float[] re, float[] im,
                                           int binCount, int channel) {
                // Identity: the overlap-add must reproduce the input.
            }
        };
        float[] out = buf.clone();
        stft.process(out, frames, 1);
        double snr = snrDb(buf, out);
        assertTrue("STFT round trip SNR only " + snr + " dB", snr > 110.0);
    }

    @Test
    public void spectralNoiseReductionAttenuatesStationaryNoise() {
        int frames = 16384;
        float[] buf = sine(frames, 1, 1000, 0.4f);
        // Add white noise at -40 dBFS.
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < buf.length; i++) {
            buf[i] += (rnd.nextFloat() * 2f - 1f) * 0.01f;
        }
        SpectralNoiseReductionProcessor p = new SpectralNoiseReductionProcessor();
        p.getParam("snr_reduction").setFloat(24f);
        p.getParam("snr_sensitivity").setFloat(6f);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);
        // The tone must survive while the broadband floor drops.
        double before = rmsOf(buf, frames);
        double after = rmsOf(out, frames);
        assertTrue("Noise floor not reduced: " + before + " -> " + after,
                after < before * 0.75);
    }

    private static double rmsOf(float[] buf, int frames) {
        double sum = 0;
        for (int i = 0; i < frames; i++) sum += (double) buf[i] * buf[i];
        return Math.sqrt(sum / frames);
    }

    @Test
    public void clickRemovalRepairsAnIsolatedSpike() {
        int frames = 4800;
        float[] buf = sine(frames, 1, 500, 0.3f);
        // Plant a single large click.
        buf[2400] = 0.95f;
        ClickRemovalProcessor p = new ClickRemovalProcessor();
        p.getParam("click_thresh").setCurrentRaw(200);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);
        // The click must be gone: the repaired sample sits near the
        // neighbouring sine samples again.
        float peakBefore = Math.abs(buf[2400]);
        float peakAfter = Math.abs(out[2400]);
        assertTrue("Click not repaired: " + peakBefore + " -> " + peakAfter,
                peakAfter < peakBefore * 0.5f);
    }

    @Test
    public void clipFixRestoresAClippedCrest() {
        int frames = 4800;
        float[] buf = sine(frames, 1, 400, 1.0f);
        // Hard-clip the waveform at 0.95.
        for (int i = 0; i < buf.length; i++) {
            buf[i] = Math.max(-0.95f, Math.min(0.95f, buf[i]));
        }
        int clipped = 0;
        for (float v : buf) if (Math.abs(v) >= 0.9499f) clipped++;
        assertTrue("Test signal was not clipped", clipped > 10);

        ClipFixProcessor p = new ClipFixProcessor();
        p.getParam("clip_headroom").setFloat(0f);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);
        // The reconstructed crest must exceed the flat clipped plateau.
        float max = 0f;
        for (float v : out) max = Math.max(max, Math.abs(v));
        assertTrue("Crest not restored, peak " + max, max > 0.97f);

        // With headroom the whole signal is scaled down afterwards.
        ClipFixProcessor p2 = new ClipFixProcessor();
        p2.getParam("clip_headroom").setFloat(-6f);
        p2.prepare(SR, 1);
        float[] out2 = buf.clone();
        p2.processInPlace(out2, frames, 1);
        float max2 = 0f;
        for (float v : out2) max2 = Math.max(max2, Math.abs(v));
        assertTrue("Headroom not applied: " + max2, max2 < 0.55f);
    }

    @Test
    public void lpcRepairInterpolatesAGapWithoutLeavingSilence() {
        int frames = 2048;
        float[] buf = sine(frames, 1, 300, 0.5f);
        // Punch a 32-sample hole.
        for (int i = 1000; i < 1032; i++) buf[i] = 0f;
        RepairProcessor p = new RepairProcessor();
        p.getParam("repair_order").setCurrentRaw(16);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);
        double energy = 0;
        for (int i = 1000; i < 1032; i++) energy += (double) out[i] * out[i];
        assertTrue("Gap was not repaired", energy > 1e-4);
    }

    @Test
    public void deHumRemovesTheMainsHum() {
        int frames = 16384;
        float[] buf = sine(frames, 1, 1000, 0.3f);
        // Add 50 Hz hum at the same level as the tone.
        for (int f = 0; f < frames; f++) {
            buf[f] += (float) (Math.sin(2.0 * Math.PI * 50 * f / SR) * 0.3);
        }
        DeHumProcessor p = new DeHumProcessor();
        p.getParam("hum_freq").setCurrentRaw(50);
        p.getParam("hum_harm").setCurrentRaw(3);
        p.getParam("hum_depth").setFloat(-40f);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);

        double humBefore = goertzel(buf, 2000, 8000, 50);
        double humAfter = goertzel(out, 2000, 8000, 50);
        assertTrue("Hum not removed: " + humBefore + " -> " + humAfter,
                humAfter < humBefore * 0.2);
    }

    private static double goertzel(float[] buf, int from, int len, double hz) {
        double w = 2.0 * Math.PI * hz / SR;
        double coeff = 2.0 * Math.cos(w);
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        for (int i = from; i < from + len && i < buf.length; i++) {
            s0 = buf[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(w);
        double imag = s2 * Math.sin(w);
        return Math.hypot(real, imag) / (len / 2.0);
    }

    @Test
    public void plosiveSuppressorTamesSubBassBursts() {
        int frames = 9600;
        float[] buf = sine(frames, 1, 1000, 0.3f);
        // A low-frequency blast.
        for (int f = 1000; f < 1400; f++) {
            buf[f] += (float) (Math.sin(2.0 * Math.PI * 90 * f / SR) * 0.8);
        }
        PlosiveBreathSuppressorProcessor p =
                new PlosiveBreathSuppressorProcessor();
        p.getParam("plosive_sens").setCurrentRaw(100);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);
        double before = 0;
        double after = 0;
        for (int f = 1000; f < 1400; f++) {
            before += (double) buf[f] * buf[f];
            after += (double) out[f] * out[f];
        }
        assertTrue("Plosive not tamed: " + before + " -> " + after,
                after < before);
    }

    @Test
    public void everyStage5EffectIsRegisteredAndHasItsPrimaryParam() {
        DspChain.EffectType[] types = {
                DspChain.EffectType.SPECTRAL_NOISE_REDUCTION,
                DspChain.EffectType.CLICK_REMOVAL,
                DspChain.EffectType.CLIP_FIX,
                DspChain.EffectType.REPAIR,
                DspChain.EffectType.DE_HUM,
                DspChain.EffectType.PLOSIVE_SUPPRESSOR};
        for (DspChain.EffectType t : types) {
            AudioProcessor p = FxRackChain.createProcessor(t.id);
            assertNotNull("Processor missing for " + t.id, p);
            String primary = DspChain.primaryParamId(t);
            assertNotNull("No primary param for " + t.id, primary);
            assertNotNull("Primary param " + primary + " missing on " + t.id,
                    p.getParam(primary));
            assertEquals("Wrong category for " + t.id,
                    p.getCategory(), DspChain.categoryOf(t));
        }
    }
}
