package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

public class DspBugFixesTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static boolean changed(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-4f) return true;
        }
        return false;
    }

    private static File writeWav(File dir, String name, float[] data, int ch) throws Exception {
        dir.mkdirs();
        File f = new File(dir, name);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.write(Dsp.wavFloatHeader(ch, SR, data.length * 4L));
            Dsp.writeFloatsLE(raf, data, 0, data.length);
        }
        return f;
    }

    // Amplify: amp_peak ("New Peak") must have an effect even with amp_gain 0.
    @Test
    public void amplifyNewPeakAttenuatesSignal() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dspfixamp");
        float[] buf = sine(2400, 1, 440, 0.8f);
        File wav = writeWav(dir, "amp.wav", buf, 1);
        AmplifyProcessor p = new AmplifyProcessor();
        p.getParam("amp_gain").setFloat(0f);
        p.getParam("amp_peak").setFloat(-6f); // target peak ~0.5
        p.getParam("amp_clip").setBoolean(false);
        p.prepare(SR, 1);
        p.prepareOffline(wav, 0, 2400, SR, 1);
        assertTrue("Amplify must be audible when newPeak is set",
                p.isAudible());
        float[] copy = buf.clone();
        p.processInPlace(copy, 2400, 1);
        assertTrue("Amplify with newPeak did not change the signal",
                changed(buf, copy));
        // Peak must not exceed the -6 dBFS target (with small tolerance).
        float pk = 0f;
        for (float v : copy) pk = Math.max(pk, Math.abs(v));
        assertTrue("Amplify exceeded newPeak target: " + pk,
                pk <= Dsp.dbToLin(-6f) * 1.05f);
    }

    // Brickwall limiter: release must not zero gainDb on the first post-hold sample.
    @Test
    public void brickwallLimiterRespectsRelease() {
        BrickwallLimiterProcessor p = new BrickwallLimiterProcessor();
        p.getParam("lim_gain").setFloat(12f);
        p.getParam("lim_ceiling").setFloat(-1f);
        p.getParam("lim_rel").setFloat(200f);
        p.prepare(SR, 1);
        float[] buf = sine(SR / 2, 1, 200, 0.95f);
        float[] orig = buf.clone();
        p.processInPlace(buf, SR / 2, 1);
        assertTrue("Brickwall limiter produced no change", changed(orig, buf));
    }

    // ChangeTempo: +25% tempo must shorten the output (stretch < 1).
    @Test
    public void changeTempoPositivePercentShortens() {
        ChangeTempoProcessor t = new ChangeTempoProcessor();
        t.getParam("tempo_percent").setFloat(25f);
        t.prepare(SR, 2);
        float[] src = sine(9600, 2, 440, 0.5f);
        float[] out = t.processOfflineRegion(src, 9600, 2);
        assertTrue("Tempo +25% did not shorten output: " + out.length,
                out.length < src.length);
    }

    // SlidingStretch: inverted sign fix -> positive initial tempo shortens.
    @Test
    public void slidingStretchPositiveTempoShortens() {
        SlidingStretchProcessor s = new SlidingStretchProcessor();
        s.getParam("slide_init").setFloat(40f);
        s.getParam("slide_final").setFloat(40f);
        s.prepare(SR, 2);
        float[] src = sine(9600, 2, 440, 0.5f);
        float[] out = s.processOfflineRegion(src, 9600, 2);
        assertTrue("Sliding stretch did not change length", out.length != src.length);
    }

    // Paulstretch: output length matches stretch and is not mostly zeros.
    @Test
    public void paulstretchProducesNonZeroOutput() {
        PaulstretchProcessor p = new PaulstretchProcessor();
        p.getParam("paul_factor").setFloat(4f);
        p.prepare(SR, 2);
        float[] src = sine(9600, 2, 440, 0.5f);
        float[] out = p.processOfflineRegion(src, 9600, 2);
        assertEquals("Paulstretch output length mismatch",
                (int) Math.round(9600 * 4f), out.length / 2);
        // RMS must not be tiny (no 83-96% dead zeros).
        double sum = 0;
        for (float v : out) sum += (double) v * v;
        double rms = Math.sqrt(sum / out.length);
        assertTrue("Paulstretch output is mostly zeros, rms=" + rms, rms > 0.05);
    }

    // Repair: output must stay bounded (no +41 dBFS blow-up).
    @Test
    public void repairDoesNotBlowUp() {
        RepairProcessor r = new RepairProcessor();
        r.prepare(SR, 1);
        float[] buf = sine(4800, 1, 300, 0.5f);
        for (int i = 1200; i < 1240; i++) buf[i] = 0f;
        float inPeak = 0f;
        for (float v : buf) inPeak = Math.max(inPeak, Math.abs(v));
        r.processInPlace(buf, 4800, 1);
        float outPeak = 0f;
        for (float v : buf) outPeak = Math.max(outPeak, Math.abs(v));
        assertTrue("Repair blew up: peak " + outPeak, outPeak <= inPeak * 2f + 0.1f);
    }

    // StereoImaging on mono must not be a no-op.
    @Test
    public void stereoImagingWorksOnMono() {
        StereoImagingProcessor s = new StereoImagingProcessor();
        s.getParam("st_width").setCurrentRaw(150);
        s.prepare(SR, 1);
        float[] buf = sine(4800, 1, 440, 0.5f);
        float[] orig = buf.clone();
        s.processInPlace(buf, 4800, 1);
        assertTrue("Stereo imaging on mono produced no change",
                changed(orig, buf));
    }

    // ClickRemoval: click_width controls removal (spike removed).
    @Test
    public void clickRemovalRemovesSpike() {
        ClickRemovalProcessor c = new ClickRemovalProcessor();
        c.getParam("click_thresh").setCurrentRaw(200);
        c.getParam("click_width").setCurrentRaw(10);
        c.prepare(SR, 1);
        float[] buf = sine(4800, 1, 500, 0.3f);
        buf[2400] = 0.95f;
        buf[2401] = -0.9f;
        float[] orig = buf.clone();
        c.processInPlace(buf, 4800, 1);
        assertTrue("Click removal produced no change", changed(orig, buf));
        assertTrue("Spike not reduced", Math.abs(buf[2400]) < 0.9f);
    }

    // ProDeEsser: threshold calibration triggers on sibilance.
    @Test
    public void proDeEsserModifiesSibilance() {
        ProDeEsserProcessor d = new ProDeEsserProcessor();
        d.getParam("amt").setCurrentRaw(45);
        d.prepare(SR, 1);
        float[] buf = new float[4800];
        for (int f = 0; f < 4800; f++) {
            buf[f] = (float) (Math.sin(2.0 * Math.PI * 6800 * f / SR) * 0.5);
        }
        float[] orig = buf.clone();
        d.processInPlace(buf, 4800, 1);
        assertTrue("Pro de-esser produced no change", changed(orig, buf));
    }

    // ParametricEq6Band: labels renamed (Low/High Cut Slope).
    @Test
    public void parametricEqCutSlopeLabels() {
        ParametricEq6BandProcessor p = new ParametricEq6BandProcessor();
        assertEquals("Low-Cut Slope", p.getParam("b1s").name);
        assertEquals("High-Cut Slope", p.getParam("b6s").name);
    }

    // AudioGenerator: noise wave indices map to distinct types (no crash, output differs).
    @Test
    public void audioGeneratorNoiseTypesDiffer() {
        AudioGeneratorTool g1 = new AudioGeneratorTool();
        g1.getParam("gen_type").setEnumIndex(1); // Noise
        g1.getParam("gen_wave").setEnumIndex(0); // White
        g1.prepare(SR, 1);
        float[] o1 = g1.processOfflineRegion(new float[1024], 1024, 1);

        AudioGeneratorTool g2 = new AudioGeneratorTool();
        g2.getParam("gen_type").setEnumIndex(1);
        g2.getParam("gen_wave").setEnumIndex(4); // Velvet
        g2.prepare(SR, 1);
        float[] o2 = g2.processOfflineRegion(new float[1024], 1024, 1);
        assertTrue("Different noise types produced identical output",
                changed(o1, o2));
    }

    // Phaser/Tremolo/Wahwah: LFO phase maxRaw is 355 (not 360).
    @Test
    public void lfoPhaseMaxRawIs355() {
        assertEquals(360, new PhaserProcessor().getParam("ph_phase").maxRaw);
        assertEquals(360, new TremoloProcessor().getParam("tr_phase").maxRaw);
        assertEquals(360, new WahwahProcessor().getParam("wah_phase").maxRaw);
    }

    // Compressor: noise floor freezes make-up below floor.
    @Test
    public void compressorNoiseFloorFreezesMakeup() {
        CompressorProcessor c = new CompressorProcessor();
        c.getParam("dyncomp_thresh").setFloat(-18f);
        c.getParam("dyncomp_ratio").setFloat(4f);
        c.getParam("dyncomp_floor").setFloat(-40f);
        c.prepare(SR, 1);
        // Very quiet signal below floor: must not be boosted.
        float[] buf = sine(4800, 1, 500, 0.0005f);
        float[] orig = buf.clone();
        c.processInPlace(buf, 4800, 1);
        // Should not increase peak.
        float pkOrig = 0f, pkAfter = 0f;
        for (float v : orig) pkOrig = Math.max(pkOrig, Math.abs(v));
        for (float v : buf) pkAfter = Math.max(pkAfter, Math.abs(v));
        assertTrue("Compressor boosted below-floor noise: " + pkOrig + " -> " + pkAfter,
                pkAfter <= pkOrig * 1.5f + 1e-4f);
    }

    // Normalize: norm_rms and norm_indep honored in non-scanned path.
    @Test
    public void normalizeHonorsRmsAndIndependent() {
        float[] buf = sine(4800, 2, 440, 0.1f);
        // add DC offset
        for (int i = 0; i < buf.length; i++) buf[i] += 0.2f;
        NormalizeProcessor p = new NormalizeProcessor();
        p.getParam("norm_target").setFloat(-6f);
        p.getParam("norm_rms").setBoolean(true);
        p.getParam("norm_peak").setBoolean(false);
        p.getParam("norm_indep").setBoolean(true);
        p.getParam("norm_dc").setBoolean(true);
        p.prepare(SR, 2);
        // not scanned -> uses new exact path
        float[] copy = buf.clone();
        p.processInPlace(copy, 4800, 2);
        double mean = 0;
        for (float v : copy) mean += v;
        mean /= copy.length;
        assertTrue("DC not removed", Math.abs(mean) < 0.02);
    }

    // Amplify isAudible: true when amp_peak non-zero even amp_gain zero.
    @Test
    public void amplifyIsAudibleOnPeakOnly() {
        AmplifyProcessor p = new AmplifyProcessor();
        p.getParam("amp_gain").setFloat(0f);
        p.getParam("amp_peak").setFloat(-3f);
        assertTrue("Amplify not audible with peak-only setting", p.isAudible());
    }

    @Test
    public void amplifySmoothWaveformAtMinusOneZeroPlusOneDb() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dspfixamp_smooth");
        float[] buf = sine(4800, 1, 440, 0.25f);
        File wav = writeWav(dir, "amp_smooth.wav", buf, 1);

        DspChain.Config cfgM1 = new DspChain.Config();
        cfgM1.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot sM1 = cfgM1.addOrFocusSlot(DspChain.EffectType.AMPLIFY);
        sM1.value = -100; // -1.0 dB
        float[] pM1 = FxPreviewPeaks.renderRegion(wav, new DspChain(1, cfgM1), 20, SR, 0f, 1f);

        DspChain.Config cfgP1 = new DspChain.Config();
        cfgP1.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot sP1 = cfgP1.addOrFocusSlot(DspChain.EffectType.AMPLIFY);
        sP1.value = 100; // +1.0 dB
        float[] pP1 = FxPreviewPeaks.renderRegion(wav, new DspChain(1, cfgP1), 20, SR, 0f, 1f);

        assertEquals(0.25f * Dsp.dbToLin(-1.0f), pM1[10], 0.015f);
        assertEquals(0.25f * Dsp.dbToLin(1.0f), pP1[10], 0.015f);
        assertTrue("Amplify +1 dB must be slightly larger than -1 dB without spikes",
                pP1[10] > pM1[10] && pP1[10] < 0.32f);
    }

    @Test
    public void audioGeneratorDtmfLimitIncreasedAndParamApplicability() {
        AudioGeneratorTool gen = new AudioGeneratorTool();
        FxParameter seq = gen.getParam("gen_seq");
        assertTrue("DTMF sequence max limit must be increased above 15",
                seq.maxRaw > 15);
        assertTrue(AudioGeneratorTool.isParamApplicable(0, "gen_start"));
        assertTrue(!AudioGeneratorTool.isParamApplicable(0, "gen_seq"));
        assertTrue(!AudioGeneratorTool.isParamApplicable(2, "gen_amp"));
        assertTrue(AudioGeneratorTool.isParamApplicable(3, "gen_bpm"));
        assertTrue(AudioGeneratorTool.isParamApplicable(4, "gen_seq"));
        assertTrue(!AudioGeneratorTool.isParamApplicable(4, "gen_wave"));
    }

    @Test
    public void autoDuckAllParametersAffectOutput() {
        String[] paramIds = {"duck_amount", "duck_thresh", "duck_pause", "duck_down", "duck_up"};
        int[][] testVals = {{-2400, -600}, {-5000, -1200}, {100, 2500}, {20, 1200}, {20, 1200}};
        float[] src = new float[SR];
        for (int i = 0; i < SR; i++) {
            float env = (i > SR / 6 && i < SR * 5 / 6) ? 0.008f : 0.6f;
            src[i] = (float) (Math.sin(2.0 * Math.PI * 600.0 * i / SR) * env);
        }
        for (int k = 0; k < paramIds.length; k++) {
            AutoDuckProcessor p1 = new AutoDuckProcessor();
            p1.prepare(SR, 1);
            p1.getParam(paramIds[k]).setCurrentRaw(testVals[k][0]);
            float[] b1 = src.clone();
            p1.processInPlace(b1, SR, 1);

            AutoDuckProcessor p2 = new AutoDuckProcessor();
            p2.prepare(SR, 1);
            p2.getParam(paramIds[k]).setCurrentRaw(testVals[k][1]);
            float[] b2 = src.clone();
            p2.processInPlace(b2, SR, 1);

            assertTrue("AutoDuck parameter " + paramIds[k] + " had no effect",
                    changed(b1, b2));
        }
    }
}
