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

    @Test
    public void amplifyGainAndNewPeakStayConsistent() {
        DspChain.EffectSlot slot = new DspChain.EffectSlot(
                DspChain.EffectType.AMPLIFY, false, 600, false);
        slot.extraParams.put("amp_peak", -1200);

        // +6 dB on a -12 dBFS source targets -6 dBFS.
        FxParamCoupling.sync(slot, "amp_gain", -12.0f);
        assertEquals(-600, (int) slot.extraParams.get("amp_peak"));

        // The other direction: -6 dBFS target on a -12 dBFS source is +6 dB.
        slot.value = 0;
        FxParamCoupling.sync(slot, "amp_peak", -12.0f);
        assertEquals(600, slot.value);

        // Nothing happens before the source peak is measured.
        slot.value = 300;
        FxParamCoupling.sync(slot, "amp_gain", Float.NaN);
        assertEquals(-600, (int) slot.extraParams.get("amp_peak"));

        // Nor while Allow Clipping makes New Peak inapplicable.
        slot.extraParams.put("amp_clip", 1);
        FxParamCoupling.sync(slot, "amp_gain", -12.0f);
        assertEquals(-600, (int) slot.extraParams.get("amp_peak"));
    }
}
