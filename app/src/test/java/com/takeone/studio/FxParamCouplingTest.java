package com.takeone.studio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Direct tests for the redundant-control UI coupling math. */
public class FxParamCouplingTest {

    @Test
    public void tempoPercentAndBpmRemainConsistent() {
        DspChain.EffectSlot slot = new DspChain.EffectSlot(
                DspChain.EffectType.CHANGE_TEMPO, false, 1000, false);
        slot.extraParams.put("tempo_bpmfrom", 120);
        slot.extraParams.put("tempo_bpmto", 120);

        FxParamCoupling.sync(slot, "tempo_percent");
        assertEquals(132, (int) slot.extraParams.get("tempo_bpmto"));

        slot.extraParams.put("tempo_bpmto", 60);
        FxParamCoupling.sync(slot, "tempo_bpmto");
        assertEquals(-5000, slot.value);
    }

    @Test
    public void speedAndPercentRemainConsistent() {
        DspChain.EffectSlot slot = new DspChain.EffectSlot(
                DspChain.EffectType.CHANGE_SPEED_AND_PITCH, false, 200, false);

        FxParamCoupling.sync(slot, "speed_mult");
        assertEquals(10000, (int) slot.extraParams.get("speed_percent"));

        slot.extraParams.put("speed_percent", -2500);
        FxParamCoupling.sync(slot, "speed_percent");
        assertEquals(75, slot.value);
    }
}
