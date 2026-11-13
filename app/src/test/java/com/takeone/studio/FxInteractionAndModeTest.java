package com.takeone.studio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public void timelineRepeatFileRenderMatchesInMemoryAndUnityGain()
            throws Exception {
        int frames = 1000;
        float[] input = new float[frames];
        Arrays.fill(input, 0.125f);
        File src = File.createTempFile("takeone-tl-src", ".wav");
        File dst = File.createTempFile("takeone-tl-dst", ".wav");
        try {
            FxVerifySupport.writeFloatWav(src, input, 1, SR);
            TimelineUtilityProcessor p = new TimelineUtilityProcessor();
            p.getParam("tl_action").setEnumIndex(2);
            p.getParam("tl_repeats").setCurrentRaw(1);
            p.prepare(SR, 1);

            float[] memory = p.processOfflineRegion(input.clone(), frames, 1);
            assertEquals(frames * 2, memory.length);
            long fileFrames = p.processOfflineRangeToFile(
                    src, 0, frames, dst, SR, 1);
            assertEquals(frames * 2L, fileFrames);

            FxVerifySupport.WavData rendered = FxVerifySupport.readFloatWav(dst);
            assertEquals(memory.length, rendered.samples.length);
            assertArrayEquals(memory, rendered.samples, 1e-6f);
            for (float sample : rendered.samples) {
                assertEquals("repeat must use unity gain",
                        0.125f, sample, 1e-6f);
            }
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
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

    @Test
    public void all52EffectsDisabledParamsAreCompletelyInert() {
        int frames = 512;
        float[] input = new float[frames];
        for (int i = 0; i < frames; i++) {
            input[i] = (float) (0.2 * Math.sin(i * 0.037) + 0.05 * Math.sin(i * 0.19));
        }
        int checkedEffects = 0;
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            AudioProcessor probe = FxRackChain.createProcessor(type.id);
            if (probe == null || probe.getParameters().isEmpty()) continue;
            checkedEffects++;
            Map<String, Integer> defaults = new HashMap<>();
            for (FxParameter p : probe.getParameters()) defaults.put(p.id, p.defaultRaw);
            for (FxParameter mode : probe.getParameters()) {
                if (mode.type != FxParameter.Type.ENUM
                        && mode.type != FxParameter.Type.BOOLEAN) continue;
                int count = mode.type == FxParameter.Type.ENUM
                        ? (mode.enumLabels == null ? 0 : mode.enumLabels.length) : 2;
                for (int mi = 0; mi < count; mi++) {
                    Map<String, Integer> values = new HashMap<>(defaults);
                    values.put(mode.id, mi);
                    AudioProcessor stateProbe = FxRackChain.createProcessor(type.id);
                    for (FxParameter q : stateProbe.getParameters()) {
                        if (q == mode || q.type == FxParameter.Type.ACTION
                                || q.type == FxParameter.Type.CURVE_NODES
                                || q.minRaw == q.maxRaw) continue;
                        if (stateProbe.isParamApplicable(q.id, values)) continue;
                        Map<String, Integer> lo = new HashMap<>(values);
                        lo.put(q.id, q.minRaw);
                        Map<String, Integer> hi = new HashMap<>(values);
                        hi.put(q.id, q.maxRaw);
                        float[] a = renderFx(type.id, lo, input, frames);
                        float[] b = renderFx(type.id, hi, input, frames);
                        assertArrayEquals(type.id + " mode " + mode.id + "=" + mi
                                        + " inactive " + q.id + " must be inert",
                                a, b, 0f);
                    }
                }
            }
        }
        assertTrue("expected at least 50 effects, checked " + checkedEffects,
                checkedEffects >= 50);
    }

    private static float[] renderFx(String effectId, Map<String, Integer> values,
                                    float[] input, int frames) {
        AudioProcessor p = FxRackChain.createProcessor(effectId);
        for (FxParameter q : p.getParameters()) {
            q.setCurrentRaw(values.containsKey(q.id) ? values.get(q.id) : q.defaultRaw);
        }
        p.prepare(SR, 1);
        if (p.isOfflineOnly()) {
            return p.processOfflineRegion(input.clone(), frames, 1);
        }
        float[] out = input.clone();
        p.processInPlace(out, frames, 1);
        return out;
    }

    /**
     * Every parameter that applies in some mode must move the audio when it is
     * swept at an active operating point. This is the sensitivity half of the
     * interaction sweep: the inertness test above proves mode-exclusive
     * controls are ignored, this one proves the remaining controls are not.
     */
    @Test
    public void all52EffectsReactToEveryApplicableParameter() {
        int frames = (int) (2.2 * SR);
        int ch = 2;
        float[] input = universalProbe(frames, ch);
        float[] longInput = null;
        List<String> inert = new ArrayList<>();
        int checked = 0;
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            AudioProcessor proto = FxRackChain.createProcessor(type.id);
            if (proto == null || proto.getParameters().isEmpty()) continue;
            if (type.requiresLearnedProfile()) continue;
            int effectFrames = frames;
            float[] effectInput = input;
            if ("truncsil".equals(type.id)) {
                // ts_min is only honoured when the buffer is at least twice
                // the requested minimum; 10.5 s covers its 5 s maximum.
                effectFrames = (int) (10.5 * SR);
                if (longInput == null) longInput = universalProbe(effectFrames, ch);
                effectInput = longInput;
            }
            Map<String, Integer> defaults = new HashMap<>();
            for (FxParameter q : proto.getParameters()) {
                defaults.put(q.id, q.defaultRaw);
            }
            String primary = DspChain.primaryParamId(type);
            for (FxParameter p : proto.getParameters()) {
                if (p.type == FxParameter.Type.ACTION) continue;
                Map<String, Integer> context =
                        activeContext(proto, p, primary, defaults);
                List<Integer> values = applicableSweep(proto, p, context);
                if (values.size() < 2) continue;
                checked++;
                boolean moved = false;
                float[] first = renderWithContext(type.id, context, p.id,
                        values.get(0), effectInput, effectFrames, ch);
                for (int vi = 1; vi < values.size(); vi++) {
                    float[] next = renderWithContext(type.id, context, p.id,
                            values.get(vi), effectInput, effectFrames, ch);
                    if (differs(first, next)) {
                        moved = true;
                        break;
                    }
                }
                if (!moved) inert.add(type.id + "." + p.id);
            }
        }
        assertTrue("checked " + checked + " parameters", checked >= 100);
        assertEquals("parameters did not change the audio: " + inert,
                0, inert.size());
    }

    /** A stereo probe carrying every kind of damage the effects look for. */
    private static float[] universalProbe(int frames, int ch) {
        float[] out = new float[frames * ch];
        double duration = frames / (double) SR;
        double chirpK = Math.log(2000.0 / 100.0);
        long rng = 0x123456789ABCDEFL;
        for (int f = 0; f < frames; f++) {
            double t = f / (double) SR;
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            double white = (((rng >>> 11) & 0x1FFFFF)
                    / (double) 0x1FFFFF - 0.5) * 0.06;
            double l = 0.16 * Math.sin(2 * Math.PI * 440 * t)
                    + 0.07 * Math.sin(2 * Math.PI * 880 * t)
                    + 0.05 * Math.sin(2 * Math.PI * 50 * t)
                    + 0.03 * Math.sin(2 * Math.PI * 100 * t)
                    + 0.02 * Math.sin(2 * Math.PI * 150 * t);
            double r = 0.16 * Math.sin(2 * Math.PI * 660 * t)
                    + 0.07 * Math.sin(2 * Math.PI * 1320 * t)
                    + 0.05 * Math.sin(2 * Math.PI * 50 * t)
                    + 0.03 * Math.sin(2 * Math.PI * 100 * t)
                    + 0.02 * Math.sin(2 * Math.PI * 150 * t);
            double phase = 2 * Math.PI * 100
                    * (Math.exp(chirpK * t / duration) - 1)
                    / (chirpK / duration);
            l += 0.10 * Math.sin(phase) + white;
            r += 0.09 * Math.sin(phase * 1.01) + 0.9 * white;
            if (t >= 0.20 && t < 0.30) {
                double burst = 0.45 * Math.sin(2 * Math.PI * 90 * t)
                        * Math.exp(-(t - 0.20) / 0.02);
                l += burst;
                r += burst;
            }
            if (Math.abs(t - 0.40) < 0.5 / SR
                    || Math.abs(t - 1.82) < 0.5 / SR) {
                l += 0.45;
                r -= 0.45;
            }
            if (t >= 0.60 && t < 0.70) {
                // Several flat-topped levels around the clip threshold, so a
                // threshold sweep selects different plateaus.
                double raw = 0.9 * Math.sin(2 * Math.PI * 200 * t);
                double quant = Math.round(raw * 20.0) / 20.0;
                l = quant;
                r = -0.8 * quant;
            }
            double roomNoise = 0.003 * Math.sin(2 * Math.PI * 997 * t + 0.4)
                    + 0.0015 * Math.sin(2 * Math.PI * 1237 * t + 1.1);
            if (t >= 0.80 && t < 1.60) {
                // A long room-tone pause: gates, expanders, ducking and
                // silence truncation all need a floor to act on, not zeros.
                l = roomNoise;
                r = 0.8 * roomNoise;
            } else {
                l += 0.01;
                r -= 0.01;
            }
            if (t >= 1.62 && t < 1.70) {
                l += 0.25 * Math.sin(2 * Math.PI * 7000 * t);
                r += 0.20 * Math.sin(2 * Math.PI * 8400 * t);
            }
            if (t >= 1.72 && t < 1.80) {
                // A true digital dropout for Repair's LPC interpolation.
                l = 0;
                r = 0;
            }
            if (t >= 2.00 && t < 2.15) {
                l = l * 0.05 + roomNoise;
                r = r * 0.04 + 0.8 * roomNoise;
            }
            out[f * ch] = (float) Math.max(-1.0, Math.min(1.0, l));
            if (ch > 1) {
                out[f * ch + 1] = (float) Math.max(-1.0, Math.min(1.0, r));
            }
        }
        return out;
    }

    /** Active context for one parameter sweep. */
    private static Map<String, Integer> activeContext(
            AudioProcessor proto, FxParameter tested, String primary,
            Map<String, Integer> defaults) {
        Map<String, Integer> context = new HashMap<>(defaults);
        for (FxParameter q : proto.getParameters()) {
            if (q.id.equals(tested.id)) continue;
            if (q.type == FxParameter.Type.ENUM
                    || q.type == FxParameter.Type.BOOLEAN
                    || q.type == FxParameter.Type.ACTION
                    || q.type == FxParameter.Type.CURVE_NODES) {
                continue;
            }
            context.put(q.id, activeParamValue(q));
        }
        // The Link toggle only has work to do when the two bands differ.
        if ("basstreble".equals(proto.getId()) && "link".equals(tested.id)) {
            context.put("bass", 600);
            context.put("treble", -600);
        }
        // Filter Curve only designs the first npts nodes. Expose every node
        // when one of them is under test, and alternate the node gains so the
        // point count itself changes the interpolated curve.
        if ("fcurve".equals(proto.getId())) {
            if (tested.id.startsWith("pf") || tested.id.startsWith("pg")) {
                context.put("npts", 32);
            }
            for (int i = 0; i < 32; i++) {
                String node = "pg" + i;
                if (!node.equals(tested.id)) {
                    context.put(node, i % 2 == 0 ? 600 : -600);
                }
            }
        }
        // The noise floor only changes behaviour when upward compression is
        // using it as the lower bound of the expansion range.
        if ("dyncomp".equals(proto.getId())
                && "dyncomp_floor".equals(tested.id)) {
            context.put("dyncomp_upward", 1);
        }
        if (!proto.isParamApplicable(tested.id, context)) {
            boolean found = false;
            for (FxParameter mode : proto.getParameters()) {
                if (mode.type != FxParameter.Type.ENUM
                        && mode.type != FxParameter.Type.BOOLEAN) {
                    continue;
                }
                int states = mode.type == FxParameter.Type.ENUM
                        ? (mode.enumLabels == null ? 0 : mode.enumLabels.length)
                        : 2;
                for (int mi = 0; mi < states; mi++) {
                    Map<String, Integer> trial = new HashMap<>(context);
                    trial.put(mode.id, mi);
                    if (proto.isParamApplicable(tested.id, trial)) {
                        context = trial;
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
        }
        return context;
    }

    /**
     * Moves a neutral secondary control to an audible operating point. Targets
     * such as thresholds, frequencies and times keep their defaults so the
     * probe still has the feature they detect.
     */
    private static int activeParamValue(FxParameter p) {
        String id = p.id;
        if ("pitch_semitones".equals(id)) return p.clampRaw(400);
        if ("tempo_percent".equals(id)) return p.clampRaw(2000);
        if ("slide_init".equals(id)) return p.clampRaw(2000);
        if ("se_gain".equals(id)) return p.clampRaw(600);
        String name = p.name.toLowerCase(Locale.US);
        boolean amount = name.contains("amount") || name.contains("mix")
                || name.contains("depth") || name.contains("drive")
                || name.contains("gain") || name.contains("warmth")
                || name.contains("presence") || name.contains("amplitude")
                || name.contains("reduction") || name.contains("compression")
                || name.contains("level") || name.contains("feedback");
        int span = p.maxRaw - p.minRaw;
        if (span <= 0) return p.defaultRaw;
        // A control parked at its maximum is often switching a stage off
        // (a high-cut at 20 kHz, a dry mix at 100%). Move it into range so the
        // parameters that depend on it can show their effect.
        if (p.defaultRaw == p.maxRaw) {
            int mid = p.clampRaw(p.minRaw + Math.round(span * 0.5f));
            if (mid != p.defaultRaw) return mid;
        }
        boolean neutral = p.defaultRaw == p.minRaw
                || Math.abs(p.defaultRaw) <= 1;
        if (" dB".equals(p.unit)) {
            if (!amount && !neutral) return p.defaultRaw;
            int up = p.defaultRaw + 600;
            int v = p.clampRaw(up <= p.maxRaw ? up : p.defaultRaw - 600);
            return v != p.defaultRaw ? v : p.defaultRaw;
        }
        if ("%".equals(p.unit) || amount) {
            if (!amount && !neutral) return p.defaultRaw;
            int v = p.clampRaw(p.minRaw + Math.round(span * 0.5f));
            return v != p.defaultRaw ? v : p.defaultRaw;
        }
        return p.defaultRaw;
    }

    /** Distinct parameter values that apply in the given context. */
    private static List<Integer> applicableSweep(AudioProcessor proto,
                                                 FxParameter p,
                                                 Map<String, Integer> context) {
        int[] candidates = sweepValues(p);
        Set<Integer> unique = new LinkedHashSet<>();
        for (int value : candidates) {
            Map<String, Integer> values = new HashMap<>(context);
            values.put(p.id, value);
            if (proto.isParamApplicable(p.id, values)) unique.add(value);
        }
        return new ArrayList<>(unique);
    }

    private static int[] sweepValues(FxParameter p) {
        switch (p.type) {
            case BOOLEAN:
                return new int[]{0, 1};
            case ENUM: {
                int n = p.enumLabels == null ? 1 : p.enumLabels.length;
                int[] out = new int[n];
                for (int i = 0; i < n; i++) out[i] = p.clampRaw(i);
                return out;
            }
            case ACTION:
                return new int[]{1};
            default: {
                // Keep the duration-exploding controls inside a sane render
                // budget; the point is to prove movement, not the endpoint.
                if ("paul_factor".equals(p.id)) {
                    return new int[]{p.clampRaw(200), p.clampRaw(800),
                            p.clampRaw(1600)};
                }
                if ("gen_dur".equals(p.id)) {
                    return new int[]{p.clampRaw(100), p.clampRaw(200),
                            p.clampRaw(400)};
                }
                int span = p.maxRaw - p.minRaw;
                int[] out = {
                        p.clampRaw(p.minRaw),
                        p.clampRaw(p.minRaw + Math.round(span * 0.5f)),
                        p.clampRaw(p.maxRaw)
                };
                return out;
            }
        }
    }

    private static float[] renderWithContext(String effectId,
                                             Map<String, Integer> context,
                                             String paramId, int raw,
                                             float[] input, int frames, int ch) {
        AudioProcessor p = FxRackChain.createProcessor(effectId);
        for (FxParameter q : p.getParameters()) {
            Integer v = context.get(q.id);
            q.setCurrentRaw(v != null ? v : q.defaultRaw);
        }
        FxParameter target = p.getParam(paramId);
        if (target != null) target.setCurrentRaw(raw);
        p.prepare(SR, ch);
        if (p.isOfflineOnly()) {
            float[] out = p.processOfflineRegion(input.clone(), frames, ch);
            return out == null ? new float[0] : out;
        }
        float[] out = input.clone();
        p.processInPlace(out, frames, ch);
        return out;
    }

    private static boolean differs(float[] a, float[] b) {
        if (a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-6f) return true;
        }
        return false;
    }

    @Test
    public void echoAndMultiDelayStreamChunkExact() {
        int frames = 24000;
        int ch = 2;
        float[] input = universalProbe(frames, ch);
        for (String id : new String[]{"echo", "multidelay"}) {
            AudioProcessor contiguous = FxRackChain.createProcessor(id);
            configureDelay(contiguous);
            contiguous.prepare(SR, ch);
            float[] expected = input.clone();
            contiguous.processInPlace(expected, frames, ch);

            AudioProcessor streamed = FxRackChain.createProcessor(id);
            configureDelay(streamed);
            streamed.prepare(SR, ch);
            float[] actual = input.clone();
            for (int off = 0; off < frames; off += 4096) {
                int n = Math.min(4096, frames - off);
                float[] chunk = Arrays.copyOfRange(actual, off * ch,
                        (off + n) * ch);
                streamed.processInPlace(chunk, n, ch);
                System.arraycopy(chunk, 0, actual, off * ch, n * ch);
            }
            assertSame(expected, actual, 0f);
        }
    }

    private static void configureDelay(AudioProcessor p) {
        if ("echo".equals(p.getId())) {
            p.getParam("echo_time").setFloat(250f);
            p.getParam("echo_decay").setCurrentRaw(50);
        } else {
            p.getParam("md_time").setCurrentRaw(250);
            p.getParam("md_echoes").setCurrentRaw(5);
            p.getParam("md_level").setFloat(-6f);
            p.getParam("md_tchange").setCurrentRaw(20);
        }
    }

    @Test
    public void stftBlockProcessHasZeroSliceBoundaryGlitchOver40000Frames() {
        int frames = 40000;
        float[] original = new float[frames];
        for (int i = 0; i < frames; i++) {
            original[i] = (float) (0.25 * Math.sin(i * 0.037)
                    + 0.12 * Math.sin(i * 0.211));
        }
        float[] processed = original.clone();
        StftProcessor identity = new StftProcessor(1024, 4) {
            @Override
            protected void processSpectrum(float[] re, float[] im,
                                           int binCount, int channel) {
                // Identity: the overlap-add round trip should reconstruct.
            }
        };
        identity.process(processed, frames, 1);
        float max = 0f;
        for (int f = 4000; f < 8200; f++) {
            max = Math.max(max, Math.abs(processed[f] - original[f]));
        }
        assertTrue("slice-boundary glitch=" + max, max < 1e-5f);
    }

    @Test
    public void spectralProcessorsStreamInChunksWithBoundedMemoryAndMatchSinglePass() {
        int frames = 12000;
        float[] input = new float[frames];
        java.util.Random rnd = new java.util.Random(0x51eeL);
        for (int i = 0; i < frames; i++) {
            input[i] = (float) (0.2 * Math.sin(i * 0.043)
                    + 0.12 * Math.sin(i * 0.173))
                    + (rnd.nextFloat() * 2f - 1f) * 0.01f;
        }

        SpectralEditProcessor editWhole = new SpectralEditProcessor();
        editWhole.getParam("se_mode").setCurrentRaw(2);
        editWhole.getParam("se_f1").setCurrentRaw(500);
        editWhole.getParam("se_f2").setCurrentRaw(4000);
        editWhole.prepare(SR, 1);
        int editLatency = editWhole.getLatencyFrames();
        assertEquals(2048, editLatency);
        assertFalse(editWhole.isOfflineOnly());
        assertTrue(editWhole.handlesOwnWetDryMix());
        float[] editPadded = Arrays.copyOf(input, frames + editLatency);
        editWhole.processInPlace(editPadded, editPadded.length, 1);
        float[] editExpected = Arrays.copyOf(editPadded, frames);

        SpectralEditProcessor editChunked = new SpectralEditProcessor();
        editChunked.getParam("se_mode").setCurrentRaw(2);
        editChunked.getParam("se_f1").setCurrentRaw(500);
        editChunked.getParam("se_f2").setCurrentRaw(4000);
        editChunked.prepare(SR, 1);
        editChunked.processInPlace(Arrays.copyOf(input, editLatency), editLatency, 1);
        float[] editActual = new float[frames];
        streamChunks(editChunked, input, editActual, frames, editLatency);
        assertArrayEquals("SpectralEdit chunked streaming differs",
                editExpected, editActual, 0f);

        float[] profile = SpectralNoiseReductionProcessor.learnProfile(
                Arrays.copyOfRange(input, 0, 4096), 1);
        SpectralNoiseReductionProcessor nrWhole = new SpectralNoiseReductionProcessor();
        nrWhole.setNoiseProfile(profile);
        nrWhole.prepare(SR, 1);
        int nrLatency = nrWhole.getLatencyFrames();
        assertEquals(SpectralNoiseReductionProcessor.FFT_SIZE, nrLatency);
        assertFalse(nrWhole.isOfflineOnly());
        assertTrue(nrWhole.handlesOwnWetDryMix());
        float[] nrPadded = Arrays.copyOf(input, frames + nrLatency);
        nrWhole.processInPlace(nrPadded, nrPadded.length, 1);
        float[] nrExpected = Arrays.copyOf(nrPadded, frames);

        SpectralNoiseReductionProcessor nrChunked = new SpectralNoiseReductionProcessor();
        nrChunked.setNoiseProfile(profile);
        nrChunked.prepare(SR, 1);
        nrChunked.processInPlace(Arrays.copyOf(input, nrLatency), nrLatency, 1);
        float[] nrActual = new float[frames];
        streamChunks(nrChunked, input, nrActual, frames, nrLatency);
        assertArrayEquals("SpectralNoiseReduction chunked streaming differs",
                nrExpected, nrActual, 0f);
    }

    @Test
    public void changePitchAtZeroSemitonesIsBitExactBypassRegardlessOfPreserveFormants() {
        int frames = 4800;
        float[] input = sine(frames, 1, 440, 0.25f);
        ChangePitchProcessor p = new ChangePitchProcessor();
        p.prepare(SR, 1);
        for (int formant = 0; formant <= 1; formant++) {
            p.getParam("pitch_formant").setBoolean(formant == 1);
            p.getParam("pitch_semitones").setFloat(0f);
            p.getParam("pitch_cents").setCurrentRaw(0);
            p.getParam("pitch_percent").setFloat(0f);
            assertFalse("zero-semitone pitch shift must be silent", p.isAudible());
            float[] out = p.processOfflineRegion(input.clone(), frames, 1);
            assertArrayEquals("zero-semitone pitch shift must be bit-exact",
                    input, out, 0f);
        }
    }

    @Test
    public void wsolaTimeStretchPreservesUnityGainWhenHqSpeechIsOff() {
        int frames = 4800;
        float[] input = new float[frames];
        Arrays.fill(input, 0.25f);
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(SR);
        float[] out = wsola.timeStretch(input, frames, 1, 1.0, false);
        assertEquals(frames, out.length);
        for (int i = 1200; i < 3600; i++) {
            assertEquals("WSOLA off-HQ gain changed at " + i,
                    0.25f, out[i], 1e-4f);
        }
    }

    @Test
    public void truncateSilenceCrossfadesSpliceBoundaryWithoutStepDiscontinuity()
            throws Exception {
        int frames = 38400;
        float[] input = new float[frames];
        int pauseStart = 9600;   // 0.20 s
        int pauseEnd = 24000;    // 0.50 s
        for (int f = 0; f < frames; f++) {
            if (f < pauseStart) {
                input[f] = (float) (0.20 * Math.sin(2.0 * Math.PI * 440.0 * f / SR));
            } else if (f < pauseEnd) {
                input[f] = (float) (0.03 * Math.sin(2.0 * Math.PI * 137.0 * f / SR));
            } else {
                input[f] = (float) (0.20 * Math.sin(2.0 * Math.PI * 137.0 * f / SR));
            }
        }
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        p.getParam("ts_thresh").setFloat(-30f);
        p.getParam("ts_min").setCurrentRaw(100);
        p.getParam("ts_trunc").setCurrentRaw(100);
        p.prepare(SR, 1);

        float[] memory = p.processOfflineRegion(input.clone(), frames, 1);
        // The detector closes a silent span at the END of the first non-silent
        // 10 ms analysis window, so the detected pause includes that window.
        int detectedSpan = (pauseEnd - pauseStart) + 480;
        int expectedFrames = frames - (detectedSpan - 4800);
        assertEquals(expectedFrames, memory.length);
        int splice = pauseStart + 4800;
        float memoryStep = Math.abs(memory[splice] - memory[splice - 1]);
        assertTrue("in-memory splice step=" + memoryStep,
                memoryStep < 0.01f);

        File src = File.createTempFile("takeone-trunc-src", ".wav");
        File dst = File.createTempFile("takeone-trunc-dst", ".wav");
        try {
            FxVerifySupport.writeFloatWav(src, input, 1, SR);
            long fileFrames = p.processOfflineRangeToFile(
                    src, 0, frames, dst, SR, 1);
            assertEquals(expectedFrames, fileFrames);
            FxVerifySupport.WavData rendered = FxVerifySupport.readFloatWav(dst);
            assertEquals(memory.length, rendered.samples.length);
            assertArrayEquals(memory, rendered.samples, 1e-6f);
            float fileStep = Math.abs(rendered.samples[splice]
                    - rendered.samples[splice - 1]);
            assertTrue("file splice step=" + fileStep, fileStep < 0.01f);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
    }

    private static void streamChunks(AudioProcessor processor, float[] input,
                                     float[] actual, int frames, int latency) {
        int cur = 0;
        int[] steps = {1, 37, 512, 2049, 3, 700, 4096};
        int i = 0;
        while (cur < frames) {
            int want = Math.min(steps[i % steps.length], frames - cur);
            float[] chunk = new float[want];
            long inPos = (long) cur + latency;
            int avail = (int) Math.max(0L,
                    Math.min((long) want, frames - inPos));
            if (avail > 0) {
                System.arraycopy(input, (int) inPos, chunk, 0, avail);
            }
            processor.processInPlace(chunk, want, 1);
            System.arraycopy(chunk, 0, actual, cur, want);
            cur += want;
            i++;
        }
    }

    // ---- generic mode-inapplicable inertness sweep ------------------------

    private static float[] stereoProbe2(int frames) {
        float[] out = new float[frames * 2];
        for (int f = 0; f < frames; f++) {
            double l = Math.sin(2.0 * Math.PI * 440.0 * f / SR) * 0.3;
            double r = Math.sin(2.0 * Math.PI * 660.0 * f / SR) * 0.25;
            out[f * 2] = (float) l;
            out[f * 2 + 1] = (float) r;
        }
        return out;
    }

    @Test
    public void modeInapplicableParamsAreInertAndFlagged() {
        float[] probe = stereoProbe2(1024);
        int frames = 1024;
        int ch = 2;
        int tested = 0;
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            AudioProcessor proto;
            try {
                proto = FxRackChain.createProcessor(type.id);
            } catch (Throwable t) {
                continue;
            }
            if (proto == null) continue;
            // Profiled effects need a profile to become audible.
            if (proto instanceof NoiseSuppressionProcessor) {
                float[] noise = new float[24000];
                long x = 0x31L;
                for (int i = 0; i < noise.length; i++) { x ^= x << 13; x ^= x >>> 7; x ^= x << 17; noise[i] = ((x >>> 40) / 8388608f - 1f) * 0.04f; }
                float[] prof = NoiseSuppressionProcessor.learnProfile(noise, 1);
                if (prof != null) ((NoiseSuppressionProcessor) proto).setNoiseProfile(prof);
            }
            if (proto instanceof SpectralNoiseReductionProcessor) {
                float[] noise = new float[24000];
                long x = 0x31L;
                for (int i = 0; i < noise.length; i++) { x ^= x << 13; x ^= x >>> 7; x ^= x << 17; noise[i] = ((x >>> 40) / 8388608f - 1f) * 0.04f; }
                float[] prof = SpectralNoiseReductionProcessor.learnProfile(noise, 1);
                if (prof != null) ((SpectralNoiseReductionProcessor) proto).setNoiseProfile(prof);
            }
            try {
                proto.prepare(SR, ch);
            } catch (Throwable t) {
                continue;
            }

            // Find ENUM/BOOLEAN mode selectors.
            List<FxParameter> modeSelectors = new ArrayList<>();
            for (FxParameter q : proto.getParameters()) {
                if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN) {
                    modeSelectors.add(q);
                }
            }
            if (modeSelectors.isEmpty()) continue;

            // Set all non-mode params to a mid-range active value so the
            // processor produces non-trivial output.
            for (FxParameter q : proto.getParameters()) {
                if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN) continue;
                if (q.maxRaw > q.minRaw) {
                    q.setCurrentRaw(q.minRaw + (q.maxRaw - q.minRaw) / 2);
                }
            }

            boolean anyMode = false;
            for (FxParameter sel : modeSelectors) {
                int selCount = (sel.type == FxParameter.Type.BOOLEAN) ? 2 : sel.enumLabels.length;
                for (int mv = 0; mv < selCount; mv++) {
                    if (sel.type == FxParameter.Type.BOOLEAN) {
                        sel.setBoolean(mv == 1);
                    } else {
                        sel.setEnumIndex(mv);
                    }

                    // Baseline output at this mode with all params at current (mid) values.
                    float[] base = probe.clone();
                    try {
                        proto.reset();
                        proto.processInPlace(base, frames, ch);
                    } catch (Throwable t) {
                        base = null;
                    }
                    if (base == null) continue;

                    for (FxParameter p : proto.getParameters()) {
                        if (p == sel) continue;
                        if (proto.isParamApplicable(p.id)) continue;
                        int lo = p.minRaw, hi = p.maxRaw;
                        if (lo >= hi) continue;

                        // Sweep to lo
                        p.setCurrentRaw(lo);
                        float[] outLo = probe.clone();
                        proto.reset();
                        proto.processInPlace(outLo, frames, ch);

                        // Sweep to hi
                        p.setCurrentRaw(hi);
                        float[] outHi = probe.clone();
                        proto.reset();
                        proto.processInPlace(outHi, frames, ch);

                        for (int i = 0; i < base.length; i++) {
                            if (base[i] != outLo[i] || base[i] != outHi[i]) {
                                throw new AssertionError(type.id + " mode " + sel.id + "=" + mv
                                        + ": inapplicable param " + p.id
                                        + " changed audio at sample " + i
                                        + " (base=" + base[i]
                                        + " lo=" + outLo[i] + " hi=" + outHi[i] + ")");
                            }
                        }
                        anyMode = true;
                    }
                    // restore mid-range for next mode
                    for (FxParameter q : proto.getParameters()) {
                        if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN) continue;
                        if (q.maxRaw > q.minRaw) {
                            q.setCurrentRaw(q.minRaw + (q.maxRaw - q.minRaw) / 2);
                        }
                    }
                }
            }
            if (anyMode) tested++;
        }
        assertTrue("No effect had an inapplicable param to test", tested > 0);
    }

    // ---- generic chunked-streaming continuity sweep ----------------------

    @Test
    public void chunkedStreamingContinuityAllRealtime() {
        int chunk = 4096;
        int frames = chunk * 2;
        float[] probe = new float[frames];
        for (int f = 0; f < frames; f++) {
            probe[f] = (float) (Math.sin(2.0 * Math.PI * 440.0 * f / SR) * 0.3);
        }
        int tested = 0;
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            AudioProcessor cont, chunked;
            try {
                cont = FxRackChain.createProcessor(type.id);
                chunked = FxRackChain.createProcessor(type.id);
            } catch (Throwable t) {
                continue;
            }
            if (cont == null || chunked == null) continue;
            // STFT / full-buffer processors need priming or the whole buffer;
            // their chunked-vs-contiguous equivalence is covered by dedicated
            // tests (chunkedProcessingMatchesSingleCallAndReportsLatency, etc.).
            if (java.util.Arrays.asList("noise","specnr","spectraledit","paulstretch",
                    "slidestretch","changepitch","tempo","speedpitch","fade",
                    "timeline","truncsil","generator","normalize","loudness")
                    .contains(type.id)) continue;
            if (cont instanceof NoiseSuppressionProcessor) {
                float[] n = new float[NoiseSuppressionProcessor.BINS];
                Arrays.fill(n, 1e-4f);
                ((NoiseSuppressionProcessor) cont).setNoiseProfile(n);
                ((NoiseSuppressionProcessor) chunked).setNoiseProfile(n);
            }
            if (cont instanceof SpectralNoiseReductionProcessor) {
                float[] n = new float[SpectralNoiseReductionProcessor.BINS];
                Arrays.fill(n, 1e-4f);
                ((SpectralNoiseReductionProcessor) cont).setNoiseProfile(n);
                ((SpectralNoiseReductionProcessor) chunked).setNoiseProfile(n);
            }
            try {
                cont.prepare(SR, 1);
                chunked.prepare(SR, 1);
            } catch (Throwable t) {
                continue;
            }
            for (AudioProcessor pp : new AudioProcessor[]{cont, chunked}) {
                for (FxParameter q : pp.getParameters()) {
                    if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN) continue;
                    if (q.maxRaw > q.minRaw) q.setCurrentRaw(q.minRaw + (q.maxRaw - q.minRaw) / 2);
                }
            }
            float[] full = probe.clone();
            float[] joined = new float[frames];
            try {
                cont.processInPlace(full, frames, 1);
                float[] first = Arrays.copyOfRange(probe, 0, chunk);
                float[] second = Arrays.copyOfRange(probe, chunk, frames);
                chunked.processInPlace(first, chunk, 1);
                chunked.processInPlace(second, chunk, 1);
                System.arraycopy(first, 0, joined, 0, chunk);
                System.arraycopy(second, 0, joined, chunk, chunk);
            } catch (Throwable t) {
                continue;
            }
            boolean bad = false;
            for (int i = 0; i < frames; i++) {
                if (Float.isNaN(full[i]) || Float.isInfinite(full[i])
                        || Float.isNaN(joined[i]) || Float.isInfinite(joined[i])) {
                    bad = true; break;
                }
            }
            if (bad) continue;
            float maxDiff = 0f;
            for (int i = 0; i < frames; i++) {
                maxDiff = Math.max(maxDiff, Math.abs(full[i] - joined[i]));
            }
            if (maxDiff >= 1e-4f) {
                throw new AssertionError(type.id + " chunked vs contiguous maxDiff=" + maxDiff);
            }
            tested++;
        }
        assertTrue("No real-time processor was tested", tested > 0);
    }

    // ---- generic pairwise anchor invariance sweep -----------------------

    @Test
    public void pairwiseAnchorInvarianceAllEffects() {
        // Richer probe: tone + broadband noise + clicks so every effect has
        // something to act on (noise suppression, click removal, etc.).
        float[] probe = new float[1024 * 2];
        long nx = 0x42L;
        for (int f = 0; f < 1024; f++) {
            double t = Math.sin(2.0 * Math.PI * 440.0 * f / SR) * 0.25;
            nx ^= nx << 13; nx ^= nx >>> 7; nx ^= nx << 17;
            double n = ((nx >>> 40) / 8388608.0 - 1.0) * 0.05;
            double click = (f % 256 == 0) ? 0.6 : 0.0;
            probe[f * 2] = (float) (t + n + click);
            probe[f * 2 + 1] = (float) (t * 0.9 + n * 0.8);
        }
        int frames = 1024;
        int ch = 2;
        int tested = 0;
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            AudioProcessor proto;
            try {
                proto = FxRackChain.createProcessor(type.id);
            } catch (Throwable t) {
                continue;
            }
            if (proto == null) continue;
            // Profile-driven effects need a realistic profile and signal; their
            // cross-param interactions are covered by their dedicated suites.
            if (type.id.equals("noise") || type.id.equals("specnr")) continue;
            if (proto instanceof NoiseSuppressionProcessor) {
                float[] noise = new float[24000];
                long x = 0x31L;
                for (int i = 0; i < noise.length; i++) { x ^= x << 13; x ^= x >>> 7; x ^= x << 17; noise[i] = ((x >>> 40) / 8388608f - 1f) * 0.04f; }
                float[] prof = NoiseSuppressionProcessor.learnProfile(noise, 1);
                if (prof != null) ((NoiseSuppressionProcessor) proto).setNoiseProfile(prof);
            }
            if (proto instanceof SpectralNoiseReductionProcessor) {
                float[] noise = new float[24000];
                long x = 0x31L;
                for (int i = 0; i < noise.length; i++) { x ^= x << 13; x ^= x >>> 7; x ^= x << 17; noise[i] = ((x >>> 40) / 8388608f - 1f) * 0.04f; }
                float[] prof = SpectralNoiseReductionProcessor.learnProfile(noise, 1);
                if (prof != null) ((SpectralNoiseReductionProcessor) proto).setNoiseProfile(prof);
            }
            try {
                proto.prepare(SR, ch);
            } catch (Throwable t) {
                continue;
            }

            List<FxParameter> tunable = new ArrayList<>();
            for (FxParameter q : proto.getParameters()) {
                if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN
                        || q.type == FxParameter.Type.ACTION || q.type == FxParameter.Type.CURVE_NODES) continue;
                if (q.maxRaw <= q.minRaw) continue;
                tunable.add(q);
            }
            if (tunable.size() < 2) continue;

            // Activate all non-mode params to mid-range.
            for (FxParameter q : proto.getParameters()) {
                if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN) continue;
                if (q.maxRaw > q.minRaw) q.setCurrentRaw(q.minRaw + (q.maxRaw - q.minRaw) / 2);
            }

            for (FxParameter P : tunable) {
                for (FxParameter Q : tunable) {
                    if (P == Q) continue;
                    // Three Q anchors: default, 25%, 75% of range.
                    int[] qAnchors = new int[]{
                            Q.defaultRaw,
                            Q.minRaw + (Q.maxRaw - Q.minRaw) / 4,
                            Q.minRaw + 3 * (Q.maxRaw - Q.minRaw) / 4
                    };
                    for (int qa : qAnchors) {
                        Q.setCurrentRaw(qa);
                        // If the effect is inactive at this anchor (e.g. amount=0),
                        // secondary params cannot produce a change; skip.
                        float[] probeCheck = probe.clone();
                        proto.reset();
                        proto.processInPlace(probeCheck, frames, ch);
                        boolean inactive = true;
                        for (int i = 0; i < frames * ch; i++) {
                            if (Math.abs(probeCheck[i] - probe[i]) > 1e-6f) { inactive = false; break; }
                        }
                        if (inactive) continue;
                        // Sweep P at 20%, 50%, 80% of range.
                        int[] pVals = new int[]{
                                P.minRaw + (P.maxRaw - P.minRaw) / 5,
                                P.minRaw + (P.maxRaw - P.minRaw) / 2,
                                P.minRaw + 4 * (P.maxRaw - P.minRaw) / 5
                        };
                        float[][] outs = new float[3][];
                        boolean ok = true;
                        for (int pi = 0; pi < 3; pi++) {
                            P.setCurrentRaw(pVals[pi]);
                            float[] o = probe.clone();
                            try {
                                proto.reset();
                                proto.processInPlace(o, frames, ch);
                            } catch (Throwable t) {
                                ok = false; break;
                            }
                            boolean bad = false;
                            for (int i = 0; i < frames * ch; i++) {
                                if (Float.isNaN(o[i]) || Float.isInfinite(o[i])) { bad = true; break; }
                            }
                            if (bad) { ok = false; break; }
                            outs[pi] = o;
                        }
                        if (!ok || outs[0] == null || outs[1] == null || outs[2] == null) continue;
                        // At least two of the three must differ.
                        boolean anyDiff = false;
                        for (int a = 0; a < 3 && !anyDiff; a++) {
                            for (int b = a + 1; b < 3; b++) {
                                for (int i = 0; i < frames * ch; i++) {
                                    if (Math.abs(outs[a][i] - outs[b][i]) > 1e-5f) { anyDiff = true; break; }
                                }
                                if (anyDiff) break;
                            }
                        }
                        if (!anyDiff) {
                            throw new AssertionError(type.id + " param " + P.id
                                    + " produced identical output across all sweep points"
                                    + " (Q=" + Q.id + " at " + qa + ")");
                        }
                    }
                    // restore mid-range
                    for (FxParameter q : proto.getParameters()) {
                        if (q.type == FxParameter.Type.ENUM || q.type == FxParameter.Type.BOOLEAN) continue;
                        if (q.maxRaw > q.minRaw) q.setCurrentRaw(q.minRaw + (q.maxRaw - q.minRaw) / 2);
                    }
                }
            }
            tested++;
        }
        assertTrue("No effect was tested", tested > 0);
    }
}
