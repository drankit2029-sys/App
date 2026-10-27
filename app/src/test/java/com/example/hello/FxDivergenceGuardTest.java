package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the three processors that diverged at settings inside their own ranges,
 * as found by the parameter matrix: a chorus feedback loop that compounded past
 * unity, a multi-tap delay whose feedback sum exceeded unity, and an upward
 * compressor whose gain sign boosted quiet material by the full ratio.
 *
 * <p>Each case renders to the end of the buffer and asserts the render stays
 * finite and bounded, so a regression shows up here instead of in a 5.8e11 peak.
 */
public class FxDivergenceGuardTest {

    private static final int SR = 48000;

    private static float[] tone(int frames, double hz, float amp) {
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            out[f] = (float) (Math.sin(2 * Math.PI * hz * f / SR) * amp);
        }
        return out;
    }

    private static float[] impulses(int frames) {
        float[] out = new float[frames];
        for (int at = 2400; at < frames; at += 24000) {
            out[at] = 0.8f;
        }
        return out;
    }

    private static void assertFiniteAndBounded(String label, float[] buf,
                                               float bound) {
        float peak = 0f;
        int bad = 0;
        for (float v : buf) {
            if (!Float.isFinite(v)) {
                bad++;
                continue;
            }
            peak = Math.max(peak, Math.abs(v));
        }
        assertEquals(label + " produced non-finite samples", 0, bad);
        assertTrue(label + " peak " + peak + " exceeded " + bound,
                peak <= bound);
    }

    @Test
    public void chorusFeedbackStaysStableAtBothExtremes() {
        for (int fbRaw : new int[]{90, -90}) {
            ChorusFlangerProcessor chorus = new ChorusFlangerProcessor();
            chorus.getParam("ch_fb").setCurrentRaw(fbRaw);
            chorus.getParam("ch_mix").setCurrentRaw(100);
            chorus.getParam("ch_depth").setCurrentRaw(100);
            float[] buf = tone(SR, 220.0, 0.4f);
            chorus.prepare(SR, 1);
            chorus.processInPlace(buf, SR, 1);
            assertFiniteAndBounded("chorus fb=" + fbRaw, buf, 10f);
        }
    }

    @Test
    public void multiDelayFeedbackSumCannotExceedUnity() {
        for (int type : new int[]{0, 1, 2}) {
            MultiDelayProcessor delay = new MultiDelayProcessor();
            delay.getParam("md_type").setEnumIndex(type);
            delay.getParam("md_level").setFloat(1f);      // the +1 dB maximum
            delay.getParam("md_echoes").setCurrentRaw(30);
            delay.getParam("md_time").setCurrentRaw(10);
            float[] buf = impulses(SR * 2);
            delay.prepare(SR, 1);
            delay.processInPlace(buf, SR * 2, 1);
            assertFiniteAndBounded("multidelay type=" + type, buf, 200f);
        }
    }

    @Test
    public void upwardCompressionAttenuatesInsteadOfBoosting() {
        CompressorProcessor comp = new CompressorProcessor();
        comp.getParam("dyncomp_upward").setBoolean(true);
        comp.getParam("dyncomp_thresh").setFloat(-6f);
        comp.getParam("dyncomp_ratio").setFloat(20f);
        comp.getParam("dyncomp_makeup").setBoolean(false);
        float[] quiet = tone(SR, 300.0, 0.01f);
        float[] before = quiet.clone();
        comp.prepare(SR, 1);
        comp.processInPlace(quiet, SR, 1);
        assertFiniteAndBounded("dyncomp upward", quiet, 1f);

        double inRms = rms(before);
        double outRms = rms(quiet);
        assertTrue("upward expansion must widen the range downward, not boost "
                        + "quiet material (in=" + inRms + ", out=" + outRms + ")",
                outRms < inRms);
    }

    private static double rms(float[] v) {
        double sum = 0;
        for (float s : v) sum += (double) s * s;
        return Math.sqrt(sum / Math.max(1, v.length));
    }
}
