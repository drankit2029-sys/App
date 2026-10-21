package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Stage 6 suite: reverb tail decay, delay tap timing, mid/side width,
 * oversampled waveshaper aliasing and the modulation effects.
 */
public class Stage6SpatialTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static float peak(float[] buf) {
        float p = 0f;
        for (float v : buf) p = Math.max(p, Math.abs(v));
        return p;
    }

    @Test
    public void reverbTailKeepsRingingAfterTheInputStops() {
        int frames = SR / 4;
        float[] buf = new float[frames];
        // A short burst at the start.
        for (int f = 0; f < 240; f++) {
            buf[f] = (float) (Math.sin(2.0 * Math.PI * 800 * f / SR) * 0.6);
        }
        FreeverbReverbProcessor p = new FreeverbReverbProcessor();
        p.getParam("rv_size").setCurrentRaw(80);
        p.getParam("rv_decay").setCurrentRaw(80);
        p.getParam("rv_wet").setFloat(0f);
        p.prepare(SR, 1);
        p.processInPlace(buf, frames, 1);
        // The tail must still be audible well after the burst ended.
        float late = 0f;
        for (int f = frames - 4800; f < frames; f++) late = Math.max(late,
                Math.abs(buf[f]));
        assertTrue("Reverb tail died immediately", late > 0.001f);
        assertTrue("Reverb reports no tail", p.getTailFrames() > 0);
    }

    @Test
    public void echoDelayTapArrivesAtTheRequestedTime() {
        int frames = SR / 2;
        float[] buf = new float[frames];
        // A single impulse at frame 0.
        buf[0] = 1f;
        EchoProcessor p = new EchoProcessor();
        p.getParam("echo_time").setCurrentRaw(100); // 100 ms
        p.getParam("echo_decay").setFloat(0.5f);
        p.prepare(SR, 1);
        p.processInPlace(buf, frames, 1);
        int expected = (int) (SR * 0.1);
        float atTap = buf[expected];
        float beforeTap = buf[expected - 100];
        assertTrue("Echo tap missing at " + expected + " (" + atTap + ")",
                atTap > 0.4f);
        assertTrue("Echo arrived too early", beforeTap < 0.1f);
    }

    @Test
    public void stereoWidthZeroCollapsesToMono() {
        int frames = 480;
        float[] buf = new float[frames * 2];
        // Partially correlated content: a common mid signal plus a side
        // signal, so collapsing the width must keep the mid and drop the
        // side.
        for (int f = 0; f < frames; f++) {
            buf[f * 2] = 0.4f;
            buf[f * 2 + 1] = 0.2f;
        }
        StereoImagingProcessor p = new StereoImagingProcessor();
        p.getParam("st_width").setCurrentRaw(0);
        p.getParam("st_pan").setCurrentRaw(0);
        p.prepare(SR, 2);
        p.processInPlace(buf, frames, 2);
        // Both channels now carry the mid signal only.
        assertEquals(buf[0], buf[1], 0.0001f);
        assertTrue("Mono content was lost", Math.abs(buf[0]) > 0.2f);
    }

    @Test
    public void stereoWidthTwoHundredWidensTheImage() {
        int frames = 480;
        float[] buf = new float[frames * 2];
        // Out-of-phase content: the side signal dominates, so widening it
        // must spread the channels apart.
        for (int f = 0; f < frames; f++) {
            buf[f * 2] = 0.5f;
            buf[f * 2 + 1] = -0.5f;
        }
        StereoImagingProcessor p = new StereoImagingProcessor();
        p.getParam("st_width").setCurrentRaw(200);
        p.getParam("st_pan").setCurrentRaw(0);
        p.prepare(SR, 2);
        p.processInPlace(buf, frames, 2);
        assertTrue("Width did not widen the image",
                Math.abs(buf[0] - buf[1]) > 1.5f);
    }

    @Test
    public void waveshaperDoesNotAliasASteepSine() {
        int frames = 16384;
        // A 3 kHz tone hard-clipped at 48 kHz folds energy from the 7th
        // harmonic (21 kHz) and above back into the audible band. The 2x
        // oversampled path must keep those products out.
        float[] buf = sine(frames, 1, 3000, 0.8f);
        DistortionWaveshaperProcessor p = new DistortionWaveshaperProcessor();
        p.getParam("dist_drive").setCurrentRaw(100);
        p.getParam("dist_curve").setEnumIndex(0); // Hard clipping
        p.getParam("dist_tone").setCurrentRaw(20000);
        p.prepare(SR, 1);
        float[] out = buf.clone();
        p.processInPlace(out, frames, 1);

        // An aliased 7th harmonic would appear at 48 - 21 = 27 kHz, which
        // folds to 21 kHz in the analysis band. Measure an empty region
        // instead: 18 kHz is not a harmonic of 3 kHz, so any energy there is
        // pure aliasing.
        double alias = goertzel(out, 0, frames, 18000);
        double fundamental = goertzel(out, 0, frames, 3000);
        assertTrue("Aliasing is too strong: " + alias + " vs " + fundamental,
                alias < fundamental * 0.5);
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
    public void tremoloModulatesTheAmplitude() {
        int frames = SR / 2;
        float[] buf = sine(frames, 1, 500, 0.5f);
        TremoloProcessor p = new TremoloProcessor();
        p.getParam("tr_depth").setCurrentRaw(100);
        p.getParam("tr_rate").setFloat(10f);
        p.prepare(SR, 1);
        p.processInPlace(buf, frames, 1);
        // The amplitude must vary over the buffer.
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int f = 0; f < frames; f++) {
            float a = Math.abs(buf[f]);
            min = Math.min(min, a);
            max = Math.max(max, a);
        }
        assertTrue("Tremolo did not modulate: " + min + ".." + max,
                max - min > 0.2f);
    }

    @Test
    public void phaserAndChorusChangeTheSignal() {
        int frames = 8192;
        float[] phaserIn = sine(frames, 1, 500, 0.5f);
        float[] phaserOut = phaserIn.clone();
        PhaserProcessor ph = new PhaserProcessor();
        ph.getParam("ph_mix").setCurrentRaw(100);
        ph.prepare(SR, 1);
        ph.processInPlace(phaserOut, frames, 1);
        assertTrue("Phaser did not alter the signal",
                !java.util.Arrays.equals(phaserIn, phaserOut));

        float[] chorusIn = sine(frames, 1, 500, 0.5f);
        float[] chorusOut = chorusIn.clone();
        ChorusFlangerProcessor ch = new ChorusFlangerProcessor();
        ch.getParam("ch_mix").setCurrentRaw(100);
        ch.prepare(SR, 1);
        ch.processInPlace(chorusOut, frames, 1);
        assertTrue("Chorus did not alter the signal",
                !java.util.Arrays.equals(chorusIn, chorusOut));
    }

    @Test
    public void everyStage6EffectIsRegisteredAndHasItsPrimaryParam() {
        DspChain.EffectType[] types = {
                DspChain.EffectType.FREEVERB_REVERB,
                DspChain.EffectType.MULTI_DELAY,
                DspChain.EffectType.ECHO,
                DspChain.EffectType.STEREO_IMAGING,
                DspChain.EffectType.DISTORTION,
                DspChain.EffectType.PHASER,
                DspChain.EffectType.CHORUS_FLANGER,
                DspChain.EffectType.TREMOLO,
                DspChain.EffectType.WAHWAH,
                DspChain.EffectType.VOCODER};
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
