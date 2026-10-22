package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A one-decimal control must move by a visible amount per drag tick.
 *
 * <p>Fixed-point parameters are stored in raw counts, so their storage
 * granularity is far finer than anything the label can show. Dragging with
 * the storage granularity made controls like a Q of 25.0 or a 0.1 dB
 * threshold crawl: the value changed, but the displayed number barely moved.
 */
public class FxParameterStepTest {

    @Test
    public void aOneDecimalControlStepsByATenthPerTick() {
        FxParameter q = FxParameter.floatParam("q", "Q", "", 0.5f, 12f, 2.5f);
        // 0.01 displayed units per tick is invisible; 0.1 is a decimal place.
        assertEquals("The drag step must show a decimal place",
                10, q.interactionStepRaw());
        assertEquals("One step is 0.1 in display units",
                0.1f, q.interactionStepRaw() / (float) q.scaleDivisor, 0.0001f);
    }

    @Test
    public void aDecibelControlStepsByATenthPerTick() {
        FxParameter thr = FxParameter.dbParam("t", "Threshold",
                -60f, -1f, -18f);
        assertEquals("A dB control moves in 0.1 dB steps",
                10, thr.interactionStepRaw());
    }

    @Test
    public void anIntegerControlKeepsItsOwnStep() {
        FxParameter bands = FxParameter.intParam("b", "Bands", "",
                0, 12, 1, 3);
        assertEquals("An integer control steps by its own step",
                1, bands.interactionStepRaw());
        FxParameter time = FxParameter.intParam("t", "Time", " ms",
                10, 3000, 10, 300);
        assertEquals("A stepped integer control keeps its step",
                10, time.interactionStepRaw());
    }

    @Test
    public void theStepNeverCoarsensBeyondTheParameterRange() {
        FxParameter tiny = FxParameter.floatParam("t", "Tiny", "",
                0f, 0.05f, 0.01f);
        assertTrue("The step must stay inside the range",
                tiny.interactionStepRaw() <= tiny.maxRaw - tiny.minRaw);
    }
}
