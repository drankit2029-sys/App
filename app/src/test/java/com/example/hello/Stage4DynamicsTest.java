package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Stage 4 suite: EBU R128 loudness integration, brickwall limiting, DC
 * offset removal, soft-knee compressor accuracy and the two-pass level tools.
 */
public class Stage4DynamicsTest {

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

    private static double rmsDb(float[] buf) {
        double sum = 0;
        for (float v : buf) sum += (double) v * v;
        return 10 * Math.log10(Math.max(1e-12, sum / buf.length));
    }

    /** Writes a float WAV and returns the file. */
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
    public void loudnessMeasuresOneKhzSineNearExpectedLufs() {
        // A 1 kHz sine at -20 dBFS has an integrated loudness of roughly
        // -20.0 LUFS (K-weighting adds about +0 dB at 1 kHz).
        int frames = SR * 2;
        float[] buf = sine(frames, 1, 1000, 0.1f);
        LoudnessNormalizationProcessor p = new LoudnessNormalizationProcessor();
        p.prepare(SR, 1);
        float[] copy = buf.clone();
        p.processInPlace(copy, frames, 1);
        // Without a pre-scan the processor falls back to its real-time
        // loudness follower, so it must still move the signal.
        assertTrue("Real-time loudness follower did not act",
                Math.abs(peak(copy) - peak(buf)) > 1e-4f);

        // The measurement path is exercised through the analyzer helper,
        // which uses the same K-weighting coefficients.
        float measured = AudioDiagnosticsTool.integratedLufs(buf, frames, 1, SR);
        assertTrue("LUFS should be near -20, was " + measured,
                measured > -23f && measured < -17f);
    }

    @Test
    public void loudnessNormalizerAppliesGainToHitTheTarget() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "stage4loud");
        dir.mkdirs();
        int frames = SR;
        float[] buf = sine(frames, 1, 1000, 0.05f);
        File wav = writeWav(dir, "loud.wav", buf, 1);

        LoudnessNormalizationProcessor p = new LoudnessNormalizationProcessor();
        p.getParam("loud_target").setFloat(-16f);
        p.getParam("loud_dual").setBoolean(false);
        p.prepare(SR, 1);
        p.prepareOffline(wav, 0, frames, SR, 1);

        float[] copy = buf.clone();
        p.processInPlace(copy, frames, 1);
        float after = AudioDiagnosticsTool.integratedLufs(copy, frames, 1, SR);
        assertTrue("Target -16 LUFS not reached: " + after,
                Math.abs(after - (-16f)) < 1.5f);
    }

    @Test
    public void brickwallLimiterNeverExceedsTheCeiling() {
        int frames = SR / 2;
        float[] buf = sine(frames, 1, 200, 0.95f);
        BrickwallLimiterProcessor p = new BrickwallLimiterProcessor();
        p.getParam("lim_gain").setFloat(12f);
        p.getParam("lim_ceiling").setFloat(-0.3f);
        p.prepare(SR, 1);
        p.processInPlace(buf, frames, 1);
        float ceiling = Dsp.dbToLin(-0.3f);
        // Allow a hair of interpolation overshoot.
        assertTrue("Peak " + peak(buf) + " exceeds ceiling " + ceiling,
                peak(buf) <= ceiling * 1.02f);
    }

    @Test
    public void normalizeRemovesDcOffsetAndHitsTheTargetPeak() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "stage4norm");
        dir.mkdirs();
        int frames = 4800;
        float[] buf = sine(frames, 1, 300, 0.4f);
        // Add a +0.2 DC offset.
        for (int i = 0; i < buf.length; i++) buf[i] += 0.2f;
        File wav = writeWav(dir, "norm.wav", buf, 1);

        NormalizeProcessor p = new NormalizeProcessor();
        p.getParam("norm_target").setFloat(-1f);
        p.prepare(SR, 1);
        p.prepareOffline(wav, 0, frames, SR, 1);

        float[] copy = buf.clone();
        p.processInPlace(copy, frames, 1);
        // The DC offset is gone.
        double mean = 0;
        for (float v : copy) mean += v;
        mean /= copy.length;
        assertTrue("DC offset not removed: " + mean, Math.abs(mean) < 0.01);
        // The peak sits at the -1 dBFS target.
        float target = Dsp.dbToLin(-1f);
        assertTrue("Peak " + peak(copy) + " vs target " + target,
                Math.abs(peak(copy) - target) < 0.02f);
    }

    @Test
    public void compressorSoftKneeTransferCurveIsAccurate() {
        DynamicsDetector.Envelope env = new DynamicsDetector.Envelope(SR);
        // Threshold -18 dB, ratio 3:1, 6 dB knee.
        float knee = 6f;
        float ratio = 3f;
        float over = 12f; // 12 dB above the threshold
        // A 12 dB over-threshold level with a 6 dB knee is fully in the
        // linear ratio region: 12 dB over, compressed by 2/3 -> -8 dB.
        float reduction = DynamicsDetector.softKneeGainDb(over, ratio, knee);
        assertTrue("Expected about -8 dB, was " + reduction,
                Math.abs(reduction + 8f) < 0.01f);

        // Below the knee the gain computer must be transparent.
        assertEquals(0f, DynamicsDetector.softKneeGainDb(-10f, ratio, knee),
                0.0001f);
    }

    @Test
    public void compressorReducesGainAboveTheThreshold() {
        int frames = SR / 4;
        float[] buf = sine(frames, 1, 500, 0.5f);
        CompressorProcessor p = new CompressorProcessor();
        p.getParam("dyncomp_thresh").setFloat(-18f);
        p.getParam("dyncomp_ratio").setFloat(4f);
        p.getParam("dyncomp_attack").setFloat(5f);
        p.getParam("dyncomp_release").setFloat(80f);
        p.prepare(SR, 1);
        double before = rmsDb(buf);
        p.processInPlace(buf, frames, 1);
        double after = rmsDb(buf);
        assertTrue("Compressor did not reduce the level: " + before + " -> "
                + after, after < before);
    }

    @Test
    public void gateAttenuatesBelowTheThreshold() {
        int frames = SR / 4;
        float[] buf = sine(frames, 1, 500, 0.005f); // about -46 dBFS
        NoiseGateExpanderProcessor p = new NoiseGateExpanderProcessor();
        p.getParam("gate_thresh").setFloat(-42f);
        p.getParam("gate_range").setFloat(-24f);
        p.getParam("gate_hpf").setCurrentRaw(120);
        // Keep the release short enough that the 250 ms probe reaches the
        // full range; the gate's release parameter is covered separately.
        p.getParam("gate_release").setFloat(20f);
        p.prepare(SR, 1);
        double before = rmsDb(buf);
        p.processInPlace(buf, frames, 1);
        double after = rmsDb(buf);
        assertTrue("Gate did not attenuate: " + before + " -> " + after,
                after < before - 10f);
    }

    @Test
    public void amplifyNeverClipsWhenClippingIsNotAllowed() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "stage4amp");
        dir.mkdirs();
        int frames = 2400;
        float[] buf = sine(frames, 1, 440, 0.8f);
        File wav = writeWav(dir, "amp.wav", buf, 1);

        AmplifyProcessor p = new AmplifyProcessor();
        p.getParam("amp_gain").setFloat(12f);
        p.getParam("amp_peak").setFloat(0f);
        p.getParam("amp_clip").setBoolean(false);
        p.prepare(SR, 1);
        p.prepareOffline(wav, 0, frames, SR, 1);

        float[] copy = buf.clone();
        p.processInPlace(copy, frames, 1);
        assertTrue("Amplify clipped: " + peak(copy), peak(copy) <= 1.0001f);
    }

    @Test
    public void everyStage4EffectIsRegisteredAndHasItsPrimaryParam() {
        DspChain.EffectType[] types = {
                DspChain.EffectType.AMPLIFY,
                DspChain.EffectType.NORMALIZE,
                DspChain.EffectType.LOUDNESS_NORMALIZATION,
                DspChain.EffectType.DYNAMIC_COMPRESSOR,
                DspChain.EffectType.MULTIBAND_COMPRESSOR,
                DspChain.EffectType.BRICKWALL_LIMITER_STAGE4,
                DspChain.EffectType.NOISE_GATE_EXPANDER,
                DspChain.EffectType.DEESSER_SPLIT,
                DspChain.EffectType.AUTO_DUCK};
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
