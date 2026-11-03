package com.takeone.studio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Locks in the task.md interaction/audibility rules: mode-exclusive controls
 * must be inert, zero primary controls must silence their section, and
 * non-default secondary controls must still work at an active primary value.
 */
public class FxInteractionAndModeTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static float[] mix(int frames, double... hz) {
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            double s = 0;
            for (double h : hz) s += Math.sin(2.0 * Math.PI * h * f / SR);
            out[f] = (float) (s / hz.length);
        }
        return out;
    }

    private static float[] run(AudioProcessor p, float[] in, int frames, int ch) {
        p.prepare(SR, ch);
        float[] out = in.clone();
        p.processInPlace(out, frames, ch);
        return out;
    }

    private static void assertSame(float[] a, float[] b, float tol) {
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals("sample " + i, a[i], b[i], tol);
        }
    }

    private static void assertDiffers(float[] a, float[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (Math.abs(a[i] - b[i]) > 1e-5f) return;
        }
        throw new AssertionError("expected the controls to change the audio");
    }

    // ---- mode applicability ----

    @Test
    public void truncateSilenceTargetFollowsAction() {
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        p.getParam("ts_action").setEnumIndex(0);
        assertTrue(p.isParamApplicable("ts_trunc"));
        assertFalse(p.isParamApplicable("ts_compress"));
        p.getParam("ts_action").setEnumIndex(1);
        assertFalse(p.isParamApplicable("ts_trunc"));
        assertTrue(p.isParamApplicable("ts_compress"));
    }

    @Test
    public void classicRippleIsChebyshevOnly() {
        ClassicFiltersProcessor p = new ClassicFiltersProcessor();
        p.getParam("type").setEnumIndex(0);
        assertFalse(p.isParamApplicable("ripple"));
        p.getParam("type").setEnumIndex(1);
        assertTrue(p.isParamApplicable("ripple"));
        p.getParam("type").setEnumIndex(2);
        assertTrue(p.isParamApplicable("ripple"));
        p.getParam("type").setEnumIndex(3);
        assertFalse(p.isParamApplicable("ripple"));
    }

    @Test
    public void spectralGainIsDeleteOnlyForEqModes() {
        SpectralEditProcessor p = new SpectralEditProcessor();
        p.getParam("se_gain").setCurrentRaw(0);
        assertTrue(p.isParamApplicable("se_gain"));
        p.getParam("se_mode").setEnumIndex(2);
        assertFalse(p.isParamApplicable("se_gain"));
    }

    @Test
    public void reverbDryGainIsInactiveWhenWetOnly() {
        FreeverbReverbProcessor p = new FreeverbReverbProcessor();
        assertTrue(p.isParamApplicable("rv_dry"));
        p.getParam("rv_wetonly").setBoolean(true);
        assertFalse(p.isParamApplicable("rv_dry"));
    }

    @Test
    public void vocoderPitchIsInactiveForNoiseCarrier() {
        VocoderProcessor p = new VocoderProcessor();
        assertTrue(p.isParamApplicable("vc_pitch"));
        p.getParam("vc_carrier").setEnumIndex(1);
        assertFalse(p.isParamApplicable("vc_pitch"));
    }

    @Test
    public void amplifyPeakIsInactiveWhenClippingIsAllowed() {
        AmplifyProcessor p = new AmplifyProcessor();
        assertTrue(p.isParamApplicable("amp_peak"));
        p.getParam("amp_clip").setBoolean(true);
        assertFalse(p.isParamApplicable("amp_peak"));
    }

    @Test
    public void graphicEqExtraBandsAreInactiveInTenBandMode() {
        GraphicEqProcessor p = new GraphicEqProcessor();
        assertTrue(p.isParamApplicable("g9"));
        assertFalse(p.isParamApplicable("g10"));
        assertFalse(p.isParamApplicable("g30"));
        p.getParam("bands").setEnumIndex(1);
        assertTrue(p.isParamApplicable("g10"));
    }

    @Test
    public void timelineRepeatCountOnlyAppliesToRepeat() {
        TimelineUtilityProcessor p = new TimelineUtilityProcessor();
        assertFalse(p.isParamApplicable("tl_repeats"));
        p.getParam("tl_action").setEnumIndex(2);
        assertTrue(p.isParamApplicable("tl_repeats"));
    }

    @Test
    public void generatorControlsFollowGeneratorType() {
        AudioGeneratorTool p = new AudioGeneratorTool();
        assertTrue(p.isParamApplicable("gen_start"));
        p.getParam("gen_type").setEnumIndex(2); // silence
        assertFalse(p.isParamApplicable("gen_start"));
        assertTrue(p.isParamApplicable("gen_dur"));
    }

    // ---- mode-exclusive controls are inert ----

    @Test
    public void classicButterworthIgnoresRipple() {
        ClassicFiltersProcessor a = new ClassicFiltersProcessor();
        ClassicFiltersProcessor b = new ClassicFiltersProcessor();
        for (ClassicFiltersProcessor p : new ClassicFiltersProcessor[]{a, b}) {
            p.getParam("type").setEnumIndex(0);
            p.getParam("freq").setCurrentRaw(1200);
            p.getParam("pass").setEnumIndex(0);
        }
        a.getParam("ripple").setCurrentRaw(10);
        b.getParam("ripple").setCurrentRaw(600);
        assertSame(run(a, mix(4800, 300, 4000), 4800, 1),
                run(b, mix(4800, 300, 4000), 4800, 1), 0f);
    }

    @Test
    public void spectralDeleteIgnoresGain() {
        SpectralEditProcessor a = new SpectralEditProcessor();
        SpectralEditProcessor b = new SpectralEditProcessor();
        for (SpectralEditProcessor p : new SpectralEditProcessor[]{a, b}) {
            p.getParam("se_mode").setEnumIndex(2);
            p.getParam("se_f1").setCurrentRaw(800);
            p.getParam("se_f2").setCurrentRaw(2500);
        }
        a.getParam("se_gain").setCurrentRaw(0);
        b.getParam("se_gain").setCurrentRaw(24);
        assertSame(run(a, mix(4800, 500, 1500, 3500), 4800, 1),
                run(b, mix(4800, 500, 1500, 3500), 4800, 1), 0f);
    }

    @Test
    public void reverbWetOnlyIgnoresDryGain() {
        FreeverbReverbProcessor a = new FreeverbReverbProcessor();
        FreeverbReverbProcessor b = new FreeverbReverbProcessor();
        for (FreeverbReverbProcessor p : new FreeverbReverbProcessor[]{a, b}) {
            p.getParam("rv_wetonly").setBoolean(true);
        }
        a.getParam("rv_dry").setCurrentRaw(-4000);
        b.getParam("rv_dry").setCurrentRaw(0);
        assertSame(run(a, sine(4800, 1, 500, 0.4f), 4800, 1),
                run(b, sine(4800, 1, 500, 0.4f), 4800, 1), 0f);
    }

    @Test
    public void vocoderNoiseCarrierIgnoresPitch() {
        VocoderProcessor a = new VocoderProcessor();
        VocoderProcessor b = new VocoderProcessor();
        for (VocoderProcessor p : new VocoderProcessor[]{a, b}) {
            p.getParam("vc_carrier").setEnumIndex(1);
        }
        a.getParam("vc_pitch").setCurrentRaw(50);
        b.getParam("vc_pitch").setCurrentRaw(400);
        assertSame(run(a, sine(9600, 1, 600, 0.4f), 9600, 1),
                run(b, sine(9600, 1, 600, 0.4f), 9600, 1), 0f);
    }

    @Test
    public void amplifyClippingModeIgnoresPeakTarget() {
        AmplifyProcessor a = new AmplifyProcessor();
        AmplifyProcessor b = new AmplifyProcessor();
        for (AmplifyProcessor p : new AmplifyProcessor[]{a, b}) {
            p.getParam("amp_clip").setBoolean(true);
            p.getParam("amp_gain").setCurrentRaw(300);
        }
        a.getParam("amp_peak").setCurrentRaw(-4000);
        b.getParam("amp_peak").setCurrentRaw(-600);
        assertSame(run(a, sine(4800, 1, 500, 0.4f), 4800, 1),
                run(b, sine(4800, 1, 500, 0.4f), 4800, 1), 0f);
    }

    @Test
    public void tenBandGraphicEqIgnoresUpperBands() {
        GraphicEqProcessor a = new GraphicEqProcessor();
        GraphicEqProcessor b = new GraphicEqProcessor();
        a.getParam("bands").setEnumIndex(0);
        b.getParam("bands").setEnumIndex(0);
        a.getParam("g20").setCurrentRaw(0);
        b.getParam("g20").setCurrentRaw(1200);
        assertSame(run(a, mix(4800, 300, 3000), 4800, 1),
                run(b, mix(4800, 300, 3000), 4800, 1), 0f);
    }

    @Test
    public void timelineRepeatIsInertOutsideRepeatMode() {
        TimelineUtilityProcessor a = new TimelineUtilityProcessor();
        TimelineUtilityProcessor b = new TimelineUtilityProcessor();
        for (TimelineUtilityProcessor p : new TimelineUtilityProcessor[]{a, b}) {
            p.getParam("tl_action").setEnumIndex(1); // reverse
        }
        a.getParam("tl_repeats").setCurrentRaw(1);
        b.getParam("tl_repeats").setCurrentRaw(8);
        float[] in = sine(480, 1, 500, 0.3f);
        assertArrayEquals(a.processOfflineRegion(in.clone(), 480, 1),
                b.processOfflineRegion(in.clone(), 480, 1), 0f);
    }

    // ---- zero primary controls collapse their section ----

    @Test
    public void zeroAmountDspSectionsAreNotAudible() {
        ChorusFlangerProcessor chorus = new ChorusFlangerProcessor();
        chorus.getParam("ch_mix").setCurrentRaw(0);
        chorus.getParam("ch_depth").setCurrentRaw(900);
        assertFalse(chorus.isAudible());

        PhaserProcessor phaser = new PhaserProcessor();
        phaser.getParam("ph_mix").setCurrentRaw(0);
        phaser.getParam("ph_depth").setCurrentRaw(100);
        assertFalse(phaser.isAudible());

        TremoloProcessor trem = new TremoloProcessor();
        trem.getParam("tr_depth").setCurrentRaw(0);
        assertFalse(trem.isAudible());

        DistortionWaveshaperProcessor dist = new DistortionWaveshaperProcessor();
        dist.getParam("dist_drive").setCurrentRaw(0);
        assertFalse(dist.isAudible());

        MultibandCompressorProcessor mb = new MultibandCompressorProcessor();
        mb.getParam("mb_amount").setCurrentRaw(0);
        assertFalse(mb.isAudible());

        ProCompressorProcessor comp = new ProCompressorProcessor();
        comp.getParam("amt").setCurrentRaw(0);
        assertFalse(comp.isAudible());

        ProDeEsserProcessor deess = new ProDeEsserProcessor();
        deess.getParam("amt").setCurrentRaw(0);
        assertFalse(deess.isAudible());

        ProToneProcessor tone = new ProToneProcessor();
        tone.getParam("warmth").setCurrentRaw(0);
        tone.getParam("presence").setCurrentRaw(0);
        assertFalse(tone.isAudible());
    }

    // ---- fixed default gates still react in their active range ----

    @Test
    public void slopeFilterReactsWithNonDefaultCutoff() {
        SlopeFilterProcessor a = new SlopeFilterProcessor(false);
        SlopeFilterProcessor b = new SlopeFilterProcessor(false);
        for (SlopeFilterProcessor p : new SlopeFilterProcessor[]{a, b}) {
            p.getParam("freq").setCurrentRaw(500);
        }
        a.getParam("slope").setEnumIndex(1);
        b.getParam("slope").setEnumIndex(5);
        assertDiffers(run(a, mix(4800, 200, 2000), 4800, 1),
                run(b, mix(4800, 200, 2000), 4800, 1));
    }

    @Test
    public void eq6BandsAreInertAtZeroGainButHighCutSlopeReacts() {
        ParametricEq6BandProcessor a = new ParametricEq6BandProcessor();
        ParametricEq6BandProcessor b = new ParametricEq6BandProcessor();
        a.getParam("b2f").setCurrentRaw(900);
        b.getParam("b2f").setCurrentRaw(300);
        a.getParam("b2q").setCurrentRaw(30);
        b.getParam("b2q").setCurrentRaw(900);
        assertSame(run(a, mix(4800, 300, 4000), 4800, 1),
                run(b, mix(4800, 300, 4000), 4800, 1), 0f);

        ParametricEq6BandProcessor c = new ParametricEq6BandProcessor();
        ParametricEq6BandProcessor d = new ParametricEq6BandProcessor();
        for (ParametricEq6BandProcessor p : new ParametricEq6BandProcessor[]{c, d}) {
            p.getParam("b6f").setCurrentRaw(8000);
        }
        c.getParam("b6s").setEnumIndex(0);
        d.getParam("b6s").setEnumIndex(1);
        assertDiffers(run(c, mix(4800, 500, 12000), 4800, 1),
                run(d, mix(4800, 500, 12000), 4800, 1));
    }

    @Test
    public void shelfAtZeroGainIsFlat() {
        ShelfFilterProcessor a = new ShelfFilterProcessor();
        ShelfFilterProcessor b = new ShelfFilterProcessor();
        a.getParam("freq").setCurrentRaw(100);
        b.getParam("freq").setCurrentRaw(12000);
        a.getParam("slope").setCurrentRaw(200);
        b.getParam("slope").setCurrentRaw(30);
        assertSame(run(a, mix(4800, 300, 5000), 4800, 1),
                run(b, mix(4800, 300, 5000), 4800, 1), 0f);
    }

    @Test
    public void spectralDeleteBandEdgesReact() {
        SpectralEditProcessor a = new SpectralEditProcessor();
        SpectralEditProcessor b = new SpectralEditProcessor();
        for (SpectralEditProcessor p : new SpectralEditProcessor[]{a, b}) {
            p.getParam("se_mode").setEnumIndex(2);
        }
        a.getParam("se_f1").setCurrentRaw(200);
        a.getParam("se_f2").setCurrentRaw(900);
        b.getParam("se_f1").setCurrentRaw(2500);
        b.getParam("se_f2").setCurrentRaw(5000);
        assertDiffers(run(a, mix(4800, 500, 3000), 4800, 1),
                run(b, mix(4800, 500, 3000), 4800, 1));
    }

    @Test
    public void activeSecondaryControlsStillMatter() {
        DistortionWaveshaperProcessor d1 = new DistortionWaveshaperProcessor();
        DistortionWaveshaperProcessor d2 = new DistortionWaveshaperProcessor();
        for (DistortionWaveshaperProcessor p : new DistortionWaveshaperProcessor[]{d1, d2}) {
            p.getParam("dist_drive").setCurrentRaw(600);
            p.getParam("dist_type").setEnumIndex(1);
        }
        d1.getParam("dist_param").setCurrentRaw(20);
        d2.getParam("dist_param").setCurrentRaw(80);
        assertDiffers(run(d1, sine(4800, 1, 500, 0.4f), 4800, 1),
                run(d2, sine(4800, 1, 500, 0.4f), 4800, 1));

        MultibandCompressorProcessor m1 = new MultibandCompressorProcessor();
        MultibandCompressorProcessor m2 = new MultibandCompressorProcessor();
        for (MultibandCompressorProcessor p : new MultibandCompressorProcessor[]{m1, m2}) {
            p.getParam("mb_amount").setCurrentRaw(700);
        }
        m1.getParam("mb_xlow").setCurrentRaw(150);
        m2.getParam("mb_xlow").setCurrentRaw(800);
        assertDiffers(run(m1, mix(4800, 100, 400, 3000), 4800, 1),
                run(m2, mix(4800, 100, 400, 3000), 4800, 1));
    }

    // ---- state continuity and limiter safety ----

    @Test
    public void vocoderChunkedRenderMatchesContiguous() {
        float[] in = sine(9600, 1, 620, 0.4f);
        VocoderProcessor contiguous = new VocoderProcessor();
        contiguous.prepare(SR, 1);
        float[] full = in.clone();
        contiguous.processInPlace(full, 9600, 1);

        VocoderProcessor chunked = new VocoderProcessor();
        chunked.prepare(SR, 1);
        float[] first = Arrays.copyOfRange(in, 0, 4800);
        float[] second = Arrays.copyOfRange(in, 4800, 9600);
        chunked.processInPlace(first, 4800, 1);
        chunked.processInPlace(second, 4800, 1);
        float[] joined = new float[9600];
        System.arraycopy(first, 0, joined, 0, 4800);
        System.arraycopy(second, 0, joined, 4800, 4800);
        assertSame(full, joined, 1e-6f);
    }

    @Test
    public void limiterChunkedRenderMatchesContiguousAndRespectsCeiling() {
        float[] in = sine(9600, 1, 220, 0.9f);
        BrickwallLimiterProcessor contiguous = new BrickwallLimiterProcessor();
        contiguous.getParam("lim_ceiling").setCurrentRaw(-100);
        contiguous.getParam("lim_gain").setCurrentRaw(600);
        contiguous.prepare(SR, 1);
        float[] full = in.clone();
        contiguous.processInPlace(full, 9600, 1);

        BrickwallLimiterProcessor chunked = new BrickwallLimiterProcessor();
        chunked.getParam("lim_ceiling").setCurrentRaw(-100);
        chunked.getParam("lim_gain").setCurrentRaw(600);
        chunked.prepare(SR, 1);
        float[] first = Arrays.copyOfRange(in, 0, 4800);
        float[] second = Arrays.copyOfRange(in, 4800, 9600);
        chunked.processInPlace(first, 4800, 1);
        chunked.processInPlace(second, 4800, 1);
        float[] joined = new float[9600];
        System.arraycopy(first, 0, joined, 0, 4800);
        System.arraycopy(second, 0, joined, 4800, 4800);

        assertSame(full, joined, 1e-6f);
        float peak = 0;
        for (float v : full) peak = Math.max(peak, Math.abs(v));
        assertTrue("limiter overshoot: " + peak, peak <= 0.9f);
        assertTrue("lookahead must be reported", contiguous.getLatencyFrames() > 0);
    }

    @Test
    public void echoHasNoZeroTimeReflection() {
        EchoProcessor echo = new EchoProcessor();
        echo.getParam("echo_time").setCurrentRaw(100);
        echo.getParam("echo_decay").setCurrentRaw(0);
        echo.prepare(SR, 1);
        float[] in = new float[9600];
        in[0] = 0.5f;
        float[] out = in.clone();
        echo.processInPlace(out, 9600, 1);
        assertEquals("dry impulse must not get a zero-time reflection",
                0.5f, out[0], 0.0001f);
    }

    @Test
    public void fadeOutAndStudioFadeIgnoreAdjustableStartEndGains() {
        for (int type = 1; type <= 2; type++) {
            FadeProcessor a = new FadeProcessor();
            a.getParam("fade_type").setCurrentRaw(type);
            a.getParam("fade_len").setCurrentRaw(100);
            a.getParam("fade_start").setCurrentRaw(0);
            a.getParam("fade_end").setCurrentRaw(100);
            a.prepare(SR, 1);
            FadeProcessor b = new FadeProcessor();
            b.getParam("fade_type").setCurrentRaw(type);
            b.getParam("fade_len").setCurrentRaw(100);
            b.getParam("fade_start").setCurrentRaw(5);
            b.getParam("fade_end").setCurrentRaw(95);
            b.prepare(SR, 1);
            float[] in = new float[4800];
            Arrays.fill(in, 0.25f);
            assertSame(a.processOfflineRegion(in.clone(), 4800, 1),
                    b.processOfflineRegion(in.clone(), 4800, 1), 0f);
        }
    }

    @Test
    public void rhythmTrackIgnoresDisabledStartFrequency() {
        AudioGeneratorTool a = new AudioGeneratorTool();
        a.getParam("gen_type").setCurrentRaw(3);
        a.getParam("gen_start").setCurrentRaw(500);
        a.prepare(SR, 1);
        AudioGeneratorTool b = new AudioGeneratorTool();
        b.getParam("gen_type").setCurrentRaw(3);
        b.getParam("gen_start").setCurrentRaw(1500);
        b.prepare(SR, 1);
        float[] in = new float[4800];
        assertSame(a.processOfflineRegion(in.clone(), 4800, 1),
                b.processOfflineRegion(in.clone(), 4800, 1), 0f);
    }

    @Test
    public void plosiveCutoffIsInertWhenSensitivityIsZero() {
        PlosiveBreathSuppressorProcessor a = new PlosiveBreathSuppressorProcessor();
        a.getParam("plosive_sens").setCurrentRaw(0);
        a.getParam("plosive_fc").setCurrentRaw(130);
        a.getParam("breath_atten").setCurrentRaw(0);
        a.prepare(SR, 1);
        assertFalse(a.isAudible());
        PlosiveBreathSuppressorProcessor b = new PlosiveBreathSuppressorProcessor();
        b.getParam("plosive_sens").setCurrentRaw(0);
        b.getParam("plosive_fc").setCurrentRaw(220);
        b.getParam("breath_atten").setCurrentRaw(0);
        b.prepare(SR, 1);
        float[] in = new float[4800];
        for (int i = 0; i < in.length; i++) in[i] = (float) Math.sin(i * 0.05) * 0.3f;
        float[] outA = in.clone();
        float[] outB = in.clone();
        a.processInPlace(outA, 4800, 1);
        b.processInPlace(outB, 4800, 1);
        assertSame(outA, outB, 0f);
    }

    @Test
    public void loudnessCeilingDoesNotShiftProgramLoudness() {
        LoudnessNormalizationProcessor a = new LoudnessNormalizationProcessor();
        a.getParam("loud_target").setCurrentRaw(-1600);
        a.getParam("loud_ceiling").setCurrentRaw(-900);
        a.prepare(SR, 1);
        LoudnessNormalizationProcessor b = new LoudnessNormalizationProcessor();
        b.getParam("loud_target").setCurrentRaw(-1600);
        b.getParam("loud_ceiling").setCurrentRaw(-100);
        b.prepare(SR, 1);
        float[] in = new float[4800];
        for (int i = 0; i < in.length; i++) in[i] = 0.01f * (float) Math.sin(i * 0.05);
        float[] outA = in.clone();
        float[] outB = in.clone();
        a.processInPlace(outA, 4800, 1);
        b.processInPlace(outB, 4800, 1);
        assertSame(outA, outB, 0f);
    }
}
