package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Persistence contract for the Stage 4-7 effects: a rack that carries the
 * new dynamics, spatial and time tools must survive a JSON round-trip with
 * every processor and every parameter intact.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class Stage4to7PersistenceTest {

    @Test
    public void newEffectsRoundTripThroughTheRackJson() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.DYNAMIC_COMPRESSOR, true, -1800, true));
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.FREEVERB_REVERB, true, 55, true));
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.CHANGE_TEMPO, true, 5000, true));

        DspChain chain = new DspChain(2, cfg);
        assertEquals("Wrong rack size", 7 + 3, chain.getRack().size());

        JSONObject json = chain.getRack().toJson();
        assertTrue("Rack JSON is empty", json.length() > 0);
        FxRackChain restored = FxRackChain.fromJson(2, json);
        assertEquals("Round trip lost processors",
                chain.getRack().size(), restored.size());

        AudioProcessor comp = restored.getProcessor(restored.indexOf("dyncomp"));
        assertNotNull("Compressor lost", comp);
        assertEquals("Compressor threshold lost", -1800,
                comp.getParam("dyncomp_thresh").getCurrentRaw());
    }

    @Test
    public void stage7ParametersSurviveTheRoundTrip() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.CHANGE_PITCH, true, 1200, true));
        cfg.slots.get(0).extraParams.put("pitch_cents", 25);
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.TRUNCATE_SILENCE, true, -4000, true));
        cfg.slots.get(1).extraParams.put("ts_min", 250);

        DspChain chain = new DspChain(2, cfg);
        FxRackChain restored = FxRackChain.fromJson(2, chain.getRack().toJson());
        AudioProcessor pitch =
                restored.getProcessor(restored.indexOf("changepitch"));
        assertNotNull("Pitch processor lost", pitch);
        assertEquals("Semitones lost", 1200,
                pitch.getParam("pitch_semitones").getCurrentRaw());
        assertEquals("Cents lost", 25,
                pitch.getParam("pitch_cents").getCurrentRaw());
    }
}
