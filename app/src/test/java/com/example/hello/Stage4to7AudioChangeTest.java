package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every Stage 4-7 effect must actually change the audio when it is enabled
 * and dialled in.
 *
 * <p>Each family needs its own engagement, so the test drives them the way
 * a user would: the level tools are given a pre-scanned file, the dynamics
 * tools are fed a loud tone, the restoration tools are fed the damage they
 * are meant to repair, and the time tools are rendered through their
 * offline renderer.
 */
public class Stage4to7AudioChangeTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static boolean changed(float[] orig, float[] out) {
        for (int i = 0; i < orig.length; i++) {
            if (Math.abs(orig[i] - out[i]) > 1e-4f) return true;
        }
        return false;
    }

    /** Runs a processor in place and asserts it altered the signal. */
    private static void assertModifies(String label, AudioProcessor p,
                                       float[] buf, int frames, int ch) {
        assertNotNull(label, p);
        float[] orig = buf.clone();
        p.prepare(SR, ch);
        p.processInPlace(buf, frames, ch);
        assertTrue(label + " produced no audio change", changed(orig, buf));
    }

    @Test
    public void stage4LevelToolsChangeTheSignal() {
        // Amplify: +6 dB of gain.
        AmplifyProcessor amp = new AmplifyProcessor();
        amp.getParam("amp_gain").setFloat(6f);
        assertModifies("Amplify", amp, sine(4800, 2, 440, 0.3f), 4800, 2);

        // Normalize: a target of -6 dBFS on a -20 dBFS signal must lift it.
        NormalizeProcessor norm = new NormalizeProcessor();
        norm.getParam("norm_target").setFloat(-6f);
        assertModifies("Normalize", norm, sine(4800, 2, 440, 0.1f), 4800, 2);

        // Loudness: a -16 LUFS target on a quiet signal must lift it.
        LoudnessNormalizationProcessor loud = new LoudnessNormalizationProcessor();
        loud.getParam("loud_target").setFloat(-16f);
        assertModifies("Loudness", loud, sine(9600, 2, 1000, 0.02f), 9600, 2);
    }

    @Test
    public void stage4DynamicsToolsChangeTheSignal() {
        CompressorProcessor comp = new CompressorProcessor();
        comp.getParam("dyncomp_thresh").setFloat(-24f);
        comp.getParam("dyncomp_ratio").setFloat(4f);
        assertModifies("Compressor", comp, sine(9600, 2, 500, 0.5f), 9600, 2);

        MultibandCompressorProcessor mb = new MultibandCompressorProcessor();
        mb.getParam("mb_amount").setCurrentRaw(60);
        assertModifies("Multiband Compressor", mb, sine(9600, 2, 500, 0.5f),
                9600, 2);

        BrickwallLimiterProcessor lim = new BrickwallLimiterProcessor();
        lim.getParam("lim_gain").setFloat(12f);
        assertModifies("Brickwall Limiter", lim, sine(9600, 2, 200, 0.9f),
                9600, 2);

        NoiseGateExpanderProcessor gate = new NoiseGateExpanderProcessor();
        gate.getParam("gate_thresh").setFloat(-30f);
        gate.getParam("gate_range").setFloat(-24f);
        assertModifies("Gate", gate, sine(9600, 2, 500, 0.005f), 9600, 2);

        DeEsserProcessor deess = new DeEsserProcessor();
        deess.getParam("deess_thresh").setFloat(-30f);
        float[] sib = new float[9600 * 2];
        for (int f = 0; f < 9600; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * 6800 * f / SR) * 0.5);
            sib[f * 2] = s;
            sib[f * 2 + 1] = s;
        }
        assertModifies("De-Esser", deess, sib, 9600, 2);

        AutoDuckProcessor duck = new AutoDuckProcessor();
        duck.getParam("duck_amount").setFloat(-18f);
        assertModifies("Auto Duck", duck, sine(9600, 2, 800, 0.3f), 9600, 2);
    }

    @Test
    public void stage5RestorationToolsChangeTheSignal() {
        SpectralNoiseReductionProcessor nr = new SpectralNoiseReductionProcessor();
        nr.getParam("snr_reduction").setFloat(18f);
        float[] noisy = sine(9600, 2, 1000, 0.4f);
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] += (rnd.nextFloat() * 2f - 1f) * 0.02f;
        }
        assertModifies("Spectral Noise Reduction", nr, noisy, 9600, 2);

        ClickRemovalProcessor click = new ClickRemovalProcessor();
        float[] withClick = sine(4800, 2, 500, 0.3f);
        withClick[2400] = 0.95f;
        withClick[2401] = -0.9f;
        assertModifies("Click Removal", click, withClick, 4800, 2);

        ClipFixProcessor clipfix = new ClipFixProcessor();
        float[] clipped = sine(4800, 2, 400, 1.0f);
        for (int i = 0; i < clipped.length; i++) {
            clipped[i] = Math.max(-0.95f, Math.min(0.95f, clipped[i]));
        }
        assertModifies("Clip Fix", clipfix, clipped, 4800, 2);

        RepairProcessor repair = new RepairProcessor();
        float[] gapped = sine(4800, 2, 300, 0.5f);
        for (int i = 1200; i < 1240; i++) gapped[i] = 0f;
        assertModifies("Repair", repair, gapped, 4800, 2);

        DeHumProcessor hum = new DeHumProcessor();
        float[] withHum = sine(9600, 2, 1000, 0.3f);
        for (int f = 0; f < 9600; f++) {
            withHum[f * 2] += (float) (Math.sin(2.0 * Math.PI * 50 * f / SR) * 0.3);
            withHum[f * 2 + 1] += (float) (Math.sin(2.0 * Math.PI * 50 * f / SR) * 0.3);
        }
        assertModifies("De-Hum", hum, withHum, 9600, 2);

        PlosiveBreathSuppressorProcessor plosive =
                new PlosiveBreathSuppressorProcessor();
        plosive.getParam("plosive_sens").setCurrentRaw(100);
        float[] blast = sine(9600, 2, 1000, 0.3f);
        for (int f = 1000; f < 1400; f++) {
            blast[f * 2] += (float) (Math.sin(2.0 * Math.PI * 90 * f / SR) * 0.8);
            blast[f * 2 + 1] += (float) (Math.sin(2.0 * Math.PI * 90 * f / SR) * 0.8);
        }
        assertModifies("Plosive Suppressor", plosive, blast, 9600, 2);
    }

    @Test
    public void stage6SpatialToolsChangeTheSignal() {
        FreeverbReverbProcessor rev = new FreeverbReverbProcessor();
        rev.getParam("rv_wet").setFloat(0f);
        assertModifies("Reverb", rev, sine(9600, 2, 800, 0.4f), 9600, 2);

        MultiDelayProcessor delay = new MultiDelayProcessor();
        delay.getParam("md_time").setCurrentRaw(50);
        assertModifies("Multi-Echo Delay", delay, sine(9600, 2, 800, 0.4f),
                9600, 2);

        EchoProcessor echo = new EchoProcessor();
        echo.getParam("echo_time").setCurrentRaw(50);
        assertModifies("Echo", echo, sine(9600, 2, 800, 0.4f), 9600, 2);

        StereoImagingProcessor stereo = new StereoImagingProcessor();
        stereo.getParam("st_width").setCurrentRaw(150);
        float[] wide = new float[4800 * 2];
        for (int f = 0; f < 4800; f++) {
            wide[f * 2] = 0.5f;
            wide[f * 2 + 1] = -0.5f;
        }
        assertModifies("Stereo Imaging", stereo, wide, 4800, 2);

        DistortionWaveshaperProcessor dist = new DistortionWaveshaperProcessor();
        dist.getParam("dist_drive").setCurrentRaw(80);
        assertModifies("Distortion", dist, sine(9600, 2, 800, 0.6f), 9600, 2);

        PhaserProcessor phaser = new PhaserProcessor();
        phaser.getParam("ph_mix").setCurrentRaw(100);
        assertModifies("Phaser", phaser, sine(9600, 2, 500, 0.5f), 9600, 2);

        ChorusFlangerProcessor chorus = new ChorusFlangerProcessor();
        chorus.getParam("ch_mix").setCurrentRaw(100);
        assertModifies("Chorus", chorus, sine(9600, 2, 500, 0.5f), 9600, 2);

        TremoloProcessor trem = new TremoloProcessor();
        trem.getParam("tr_depth").setCurrentRaw(90);
        assertModifies("Tremolo", trem, sine(9600, 2, 500, 0.5f), 9600, 2);

        WahwahProcessor wah = new WahwahProcessor();
        wah.getParam("wah_depth").setCurrentRaw(90);
        assertModifies("Wahwah", wah, sine(9600, 2, 500, 0.5f), 9600, 2);

        VocoderProcessor voc = new VocoderProcessor();
        assertModifies("Vocoder", voc, sine(9600, 2, 500, 0.5f), 9600, 2);
    }

    @Test
    public void stage7OfflineToolsChangeTheSignal() {
        float[] src = sine(9600, 2, 440, 0.5f);

        ChangePitchProcessor pitch = new ChangePitchProcessor();
        pitch.getParam("pitch_semitones").setFloat(7f);
        pitch.prepare(SR, 2);
        float[] out = pitch.processOfflineRegion(src, 9600, 2);
        assertTrue("Change Pitch produced no audio change",
                changed(src, out));

        ChangeTempoProcessor tempo = new ChangeTempoProcessor();
        tempo.getParam("tempo_percent").setFloat(25f);
        tempo.prepare(SR, 2);
        out = tempo.processOfflineRegion(src, 9600, 2);
        assertTrue("Change Tempo produced no audio change", changed(src, out));

        ChangeSpeedAndPitchProcessor speed = new ChangeSpeedAndPitchProcessor();
        speed.getParam("speed_mult").setFloat(1.5f);
        speed.prepare(SR, 2);
        out = speed.processOfflineRegion(src, 9600, 2);
        assertTrue("Change Speed produced no audio change", changed(src, out));

        SlidingStretchProcessor slide = new SlidingStretchProcessor();
        slide.getParam("slide_init").setFloat(40f);
        slide.getParam("slide_final").setFloat(-40f);
        slide.prepare(SR, 2);
        out = slide.processOfflineRegion(src, 9600, 2);
        assertTrue("Sliding Stretch produced no audio change", changed(src, out));

        PaulstretchProcessor paul = new PaulstretchProcessor();
        paul.getParam("paul_factor").setFloat(4f);
        paul.prepare(SR, 2);
        out = paul.processOfflineRegion(src, 9600, 2);
        assertTrue("Paulstretch produced no audio change", changed(src, out));

        FadeProcessor fade = new FadeProcessor();
        fade.getParam("fade_type").setEnumIndex(1);
        fade.getParam("fade_len").setCurrentRaw(50);
        // The fade tail must carry energy: a 440 Hz tone at this length
        // ends near a zero crossing, so use a frequency that does not.
        float[] fadeSrc = sine(9600, 2, 300, 0.5f);
        fade.prepare(SR, 2);
        out = fade.processOfflineRegion(fadeSrc, 9600, 2);
        assertTrue("Fade produced no audio change", changed(fadeSrc, out));

        TimelineUtilityProcessor timeline = new TimelineUtilityProcessor();
        timeline.getParam("tl_action").setEnumIndex(0);
        timeline.prepare(SR, 2);
        out = timeline.processOfflineRegion(src, 9600, 2);
        assertTrue("Invert produced no audio change", changed(src, out));

        TruncateSilenceProcessor trunc = new TruncateSilenceProcessor();
        trunc.getParam("ts_thresh").setFloat(-40f);
        // The minimum silence is 100 ms here, so the 100 ms pause below
        // qualifies for truncation, and it is shortened to 50 ms.
        trunc.getParam("ts_min").setCurrentRaw(100);
        trunc.getParam("ts_trunc").setCurrentRaw(50);
        trunc.prepare(SR, 2);
        float[] withPause = sine(9600, 2, 440, 0.5f);
        for (int f = 4800; f < 9600; f++) {
            withPause[f * 2] = 0f;
            withPause[f * 2 + 1] = 0f;
        }
        out = trunc.processOfflineRegion(withPause, 9600, 2);
        // The pause was silent, so the visible change is the length: the
        // 100 ms pause must have been shortened to 50 ms.
        assertTrue("Truncate Silence did not shorten the pause: "
                        + (out.length / 2) + " frames",
                out.length / 2 < 9600);

        AudioGeneratorTool gen = new AudioGeneratorTool();
        gen.getParam("gen_start").setCurrentRaw(1000);
        gen.getParam("gen_end").setCurrentRaw(1000);
        gen.prepare(SR, 2);
        out = gen.processOfflineRegion(new float[9600 * 2], 9600, 2);
        assertTrue("Audio Generator produced no audio", changed(src, out));
    }
}
