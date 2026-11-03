package com.takeone.studio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Mode-aware parameter applicability used by the rack and the exhaustive harness. */
public class FxParamRelevanceTest {

    @Test
    public void truncateModeDisablesCompressTo() {
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        Map<String, Integer> values = new HashMap<>();
        values.put("ts_action", 0);
        assertTrue(p.isParamApplicable("ts_thresh", values));
        assertTrue(p.isParamApplicable("ts_min", values));
        assertTrue(p.isParamApplicable("ts_trunc", values));
        assertFalse(p.isParamApplicable("ts_compress", values));
    }

    @Test
    public void compressModeDisablesTruncateTo() {
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        Map<String, Integer> values = new HashMap<>();
        values.put("ts_action", 1);
        assertFalse(p.isParamApplicable("ts_trunc", values));
        assertTrue(p.isParamApplicable("ts_compress", values));
        assertTrue(p.isParamApplicable("ts_thresh", values));
    }

    @Test
    public void missingActionUsesTheDefaultMode() {
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        assertTrue(p.isParamApplicable("ts_trunc", null));
        assertFalse(p.isParamApplicable("ts_compress", new HashMap<>()));
    }

    @Test
    public void generatorApplicabilityStillWorks() {
        AudioGeneratorTool g = new AudioGeneratorTool();
        Map<String, Integer> silence = new HashMap<>();
        silence.put("gen_type", 2);
        assertFalse(g.isParamApplicable("gen_start", silence));
        assertFalse(g.isParamApplicable("gen_wave", silence));
        assertTrue(g.isParamApplicable("gen_dur", silence));
        assertTrue(g.isParamApplicable("gen_type", silence));

        Map<String, Integer> tone = new HashMap<>();
        tone.put("gen_type", 0);
        assertTrue(g.isParamApplicable("gen_start", tone));
        assertFalse(g.isParamApplicable("gen_bpm", tone));
    }
}
