package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Stage 3 EQ suite: verifies the analog prototypes really land on their
 * textbook cutoffs after bilinear transform (edge attenuation, ripple bounds,
 * stopband depth), checks the RBJ biquads, and smoke-tests every processor in
 * the suite plus its DspChain integration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EqSuiteTest {

    private static final int SR = 48000;

    private static double mag(float[][] sections, double f) {
        return Dsp.cascadeMagDb(sections, f, SR);
    }

    private static float[] sine(int frames, int ch, double freq, float amp) {
        float[] buf = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) Math.sin(2 * Math.PI * freq * f / SR) * amp;
            for (int c = 0; c < ch; c++) buf[f * ch + c] = s;
        }
        return buf;
    }

    private static void assertFiniteBounded(float[] buf, float bound) {
        for (int i = 0; i < buf.length; i++) {
            assertTrue("non-finite @" + i, Float.isFinite(buf[i]));
            assertTrue("unbounded @" + i + " = " + buf[i],
                    Math.abs(buf[i]) <= bound);
        }
    }

    // ------------------------------------------------------- prototypes

    @Test
    public void butterworthEdgeIsMinusThreeDbBothDirections() {
        for (int order : new int[]{1, 2, 3, 4, 6, 8}) {
            assertEquals("LP order " + order, -3.01,
                    mag(Dsp.butterworth(order, SR, 1000, false), 1000), 0.2);
            assertEquals("HP order " + order, -3.01,
                    mag(Dsp.butterworth(order, SR, 1000, true), 1000), 0.2);
        }
        assertEquals(0.0, mag(Dsp.butterworth(4, SR, 1000, false), 100), 0.1);
        assertEquals(0.0, mag(Dsp.butterworth(4, SR, 1000, true), 10000), 0.1);
        assertTrue(mag(Dsp.butterworth(8, SR, 1000, false), 10000) < -100);
    }

    @Test
    public void chebyshev1RippleAndEdge() {
        for (double ripple : new double[]{0.5, 1, 3}) {
            for (int order : new int[]{1, 2, 3, 4, 5, 8}) {
                float[][] lp = Dsp.chebyshev1(order, ripple, SR, 1000, false);
                float[][] hp = Dsp.chebyshev1(order, ripple, SR, 1000, true);
                assertEquals("LP n" + order + " r" + ripple, -ripple,
                        mag(lp, 1000), 0.35);
                assertEquals("HP n" + order + " r" + ripple, -ripple,
                        mag(hp, 1000), 0.35);
                double lo = 1e9, hi = -1e9;
                for (int i = 0; i <= 240; i++) {
                    double f = 20 * Math.pow(50.0, i / 240.0);
                    double g = mag(lp, f);
                    lo = Math.min(lo, g);
                    hi = Math.max(hi, g);
                }
                assertTrue("ripple peak too high: " + hi, hi <= 0.15);
                assertTrue("ripple floor too low: " + lo, lo >= -ripple - 0.3);
            }
        }
    }

    @Test
    public void chebyshev2EdgeAndStopband() {
        for (double ripple : new double[]{1, 3}) {
            for (int order : new int[]{2, 3, 4, 5, 8}) {
                float[][] lp = Dsp.chebyshev2(order, ripple, SR, 1000, false);
                float[][] hp = Dsp.chebyshev2(order, ripple, SR, 1000, true);
                assertEquals("LP n" + order + " r" + ripple, -ripple,
                        mag(lp, 1000), 0.35);
                assertEquals("HP n" + order + " r" + ripple, -ripple,
                        mag(hp, 1000), 0.35);
                assertEquals("LP passband flat", 0.0, mag(lp, 100), 0.3);
                assertEquals("HP passband flat", 0.0, mag(hp, 10000), 0.3);
            }
        }
        // Type II stopbands are equiripple at -r dB, but its transmission
        // zeros sit exactly on the jw axis: check the notches directly.
        float[][] lp = Dsp.chebyshev2(4, 1.0, SR, 1000, false);
        float[][] hp = Dsp.chebyshev2(4, 1.0, SR, 1000, true);
        double w0 = 2.0 * SR * Math.tan(Math.PI * 1000.0 / SR);
        double lpNotch = invPrewarp(w0 / Math.cos(Math.PI / 8));
        double hpNotch = invPrewarp(w0 * Math.cos(Math.PI / 8));
        assertTrue("LP notch " + mag(lp, lpNotch), mag(lp, lpNotch) < -40);
        assertTrue("HP notch " + mag(hp, hpNotch), mag(hp, hpNotch) < -40);
    }

    private static double invPrewarp(double omega) {
        return Math.atan(omega / (2.0 * SR)) * SR / Math.PI;
    }

    @Test
    public void besselEdgeIsMinusThreeDb() {
        for (int order = 1; order <= 10; order++) {
            assertEquals("LP order " + order, -3.01,
                    mag(Dsp.bessel(order, SR, 1000, false), 1000), 0.45);
            assertEquals("HP order " + order, -3.01,
                    mag(Dsp.bessel(order, SR, 1000, true), 1000), 0.45);
        }
    }

    @Test
    public void rbjBiquadsHitTheirTargets() {
        float[][] peak = {Dsp.rbjPeaking(SR, 1000, 1.0, 6.0)};
        assertEquals(6.0, mag(peak, 1000), 0.05);
        assertEquals(0.0, mag(peak, 50), 0.05);
        float[][] notch = {Dsp.rbjNotch(SR, 1000, 8.0)};
        assertTrue("notch depth " + mag(notch, 1000), mag(notch, 1000) < -40);
        assertEquals(0.0, mag(notch, 50), 0.05);
        float[][] lowShelf = {Dsp.rbjLowShelf(SR, 250, 0.707, 6.0)};
        assertEquals(6.0, mag(lowShelf, 40), 0.1);
        assertEquals(0.0, mag(lowShelf, 12000), 0.15);
        float[][] highShelf = {Dsp.rbjHighShelf(SR, 3500, 0.707, -6.0)};
        assertEquals(-6.0, mag(highShelf, 22000), 0.1);
        assertEquals(0.0, mag(highShelf, 60), 0.15);
    }

    // ------------------------------------------------------- processors

    @Test
    public void parametricEq6RunsAndResponds() {
        ParametricEq6BandProcessor p = (ParametricEq6BandProcessor)
                FxRackChain.createProcessor("eq6");
        p.prepare(SR, 2);
        p.getParam("b3g").setCurrentRaw(600);
        p.getParam("b3f").setCurrentRaw(1000);
        assertEquals(6.0, mag(p.buildSections(), 1000), 0.3);
        // Far from every band (only untouched 0 dB bells remain there).
        assertEquals(0.0, mag(p.buildSections(), 12000), 0.2);
        float[] buf = sine(4800, 2, 1000, 0.5f);
        p.processInPlace(buf, 4800, 2);
        assertFiniteBounded(buf, 8f);
    }

    @Test
    public void graphicEqBandsShape() {
        GraphicEqProcessor p = (GraphicEqProcessor)
                FxRackChain.createProcessor("geq");
        p.prepare(SR, 2);
        p.getParam("g3").setCurrentRaw(-600);
        assertEquals(-6.0, mag(p.buildSections(), 250), 0.6);
        assertEquals(0.0, mag(p.buildSections(), 4000), 0.3);
        float[] buf = sine(4800, 2, 250, 0.5f);
        p.processInPlace(buf, 4800, 2);
        assertFiniteBounded(buf, 8f);
    }

    @Test
    public void filterCurveEqDesignsAndConvolves() {
        FilterCurveEqProcessor p = (FilterCurveEqProcessor)
                FxRackChain.createProcessor("fcurve");
        p.prepare(SR, 2);
        p.getParam("pg0").setCurrentRaw(1200);
        float[] buf = sine(4096, 2, 100, 0.5f);
        float peak = 0f;
        p.processInPlace(buf, 4096, 2);
        for (float v : buf) {
            assertTrue(Float.isFinite(v));
            peak = Math.max(peak, Math.abs(v));
        }
        assertTrue("filter-curve EQ silenced the signal", peak > 0.2f);
        assertTrue("filter-curve EQ blew up: " + peak, peak < 4f);
    }

    @Test
    public void slopeFilterOrderMatchesSlope() {
        SlopeFilterProcessor hp = new SlopeFilterProcessor(true);
        hp.prepare(SR, 2);
        hp.getParam("freq").setCurrentRaw(1000);
        hp.getParam("slope").setEnumIndex(4);
        float[][] sections = hp.buildSections();
        assertEquals(3, sections.length);
        assertEquals(-3.01, mag(sections, 1000), 0.3);
        // Butterworth is -3 dB at fc and rolls off *below* fc for a HPF.
        assertTrue(mag(sections, 500) < -25);
    }

    @Test
    public void notchFilterKillsHum() {
        NotchFilterProcessor p = (NotchFilterProcessor)
                FxRackChain.createProcessor("notch");
        p.prepare(SR, 2);
        assertTrue(mag(p.buildSections(), 60) < -30);
        assertEquals(0.0, mag(p.buildSections(), 1000), 0.2);
    }

    @Test
    public void shelfAndBassTrebleRun() {
        ShelfFilterProcessor s = (ShelfFilterProcessor)
                FxRackChain.createProcessor("shelf");
        s.prepare(SR, 2);
        s.getParam("gain").setCurrentRaw(600);
        assertEquals(6.0, mag(s.buildSections(), 30), 0.5);

        BassAndTrebleProcessor bt = (BassAndTrebleProcessor)
                FxRackChain.createProcessor("basstreble");
        bt.prepare(SR, 2);
        bt.getParam("bass").setCurrentRaw(600);
        bt.getParam("treble").setCurrentRaw(-600);
        assertEquals(6.0, mag(bt.buildSections(), 30), 0.4);
        assertTrue(mag(bt.buildSections(), 15000) < -4);
        float[] buf = sine(4800, 2, 1000, 0.5f);
        bt.processInPlace(buf, 4800, 2);
        assertFiniteBounded(buf, 8f);
    }

    @Test
    public void classicFiltersServeEveryFamily() {
        ClassicFiltersProcessor p = (ClassicFiltersProcessor)
                FxRackChain.createProcessor("classic");
        p.prepare(SR, 2);
        p.getParam("freq").setCurrentRaw(1000);
        p.getParam("order").setCurrentRaw(4);
        for (int type = 0; type <= 3; type++) {
            p.getParam("type").setEnumIndex(type);
            double edge = type == 0 || type == 3 ? -3.01 : -1.0;
            assertEquals("type " + type, edge, mag(p.buildSections(), 1000),
                    type == 3 ? 0.45 : 0.35);
        }
        p.getParam("type").setEnumIndex(0);
        p.getParam("mode").setEnumIndex(1);
        assertEquals("HP mode", -3.01, mag(p.buildSections(), 1000), 0.3);
        assertTrue(mag(p.buildSections(), 250) < -20);
    }

    // ------------------------------------------------------- chain wiring

    @Test
    public void enabledSuiteSlotActivatesChain() {
        DspChain.Config cfg = new DspChain.Config();
        assertTrue(!cfg.isActive());
        DspChain.EffectSlot slot =
                cfg.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6);
        slot.enabled = true;
        slot.value = 300;
        assertTrue(cfg.isActive());
        DspChain chain = new DspChain(2, cfg);
        assertTrue(chain.isActive());
        float[] buf = sine(4800, 2, 440, 0.5f);
        chain.processInPlace(buf, 4800);
        assertFiniteBounded(buf, 8f);
    }

    @Test
    public void disabledSuiteSlotsStayInactive() {
        DspChain.Config cfg = new DspChain.Config();
        DspChain.EffectSlot slot =
                cfg.addOrFocusSlot(DspChain.EffectType.GRAPHIC_EQ);
        slot.enabled = false;
        assertNotNull(slot);
        assertTrue(!cfg.isActive());
        DspChain chain = new DspChain(2, cfg);
        float[] buf = sine(1024, 2, 440, 0.5f);
        float[] before = buf.clone();
        chain.processInPlace(buf, 1024);
        for (int i = 0; i < buf.length; i++) {
            assertEquals("touched @" + i, before[i], buf[i], 0f);
        }
    }

    @Test
    public void suitePrimaryParamsHaveDisplayMetadata() {
        String[] ids = {"eq6", "geq", "fcurve", "basstreble", "highpass",
                "lowpass", "notch", "shelf", "classic"};
        for (String id : ids) {
            AudioProcessor p = FxRackChain.getPrototypeProcessor(id);
            assertNotNull(id, p);
            assertNotNull(id + " has no processor instance",
                    FxRackChain.createProcessor(id));
        }
        assertEquals("out",
                DspChain.primaryParamId(DspChain.EffectType.PARAMETRIC_EQ6));
        assertEquals("bass",
                DspChain.primaryParamId(DspChain.EffectType.BASS_TREBLE));
        assertEquals("freq",
                DspChain.primaryParamId(DspChain.EffectType.NOTCH_FILTER));
        assertEquals("gain",
                DspChain.primaryParamId(DspChain.EffectType.SHELF_FILTER));
    }
}
