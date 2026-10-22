package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A rack with two copies of the same effect must survive a save and load
 * with both entries intact, their identities distinct and their parameters
 * unmixed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxRackDuplicatesPersistenceTest {

    @Test
    public void duplicateCopiesSurviveASaveAndLoadRoundTrip() throws Exception {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot first =
                cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        DspChain.EffectSlot second =
                cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        first.value = 30;
        second.value = 80;
        first.extraParams.put("rv_decay", 20);
        second.extraParams.put("rv_decay", 90);

        // Round-trip through the persisted JSON form.
        JSONArray arr = new JSONArray();
        for (DspChain.EffectSlot s : cfg.slots) {
            JSONObject o = new JSONObject();
            o.put("id", s.type.id);
            o.put("iid", s.instanceId);
            o.put("en", s.enabled);
            o.put("val", s.value);
            o.put("mix", (double) s.wetDry);
            JSONObject p = new JSONObject();
            for (java.util.Map.Entry<String, Integer> e : s.extraParams.entrySet()) {
                p.put(e.getKey(), e.getValue());
            }
            o.put("params", p);
            arr.put(o);
        }

        DspChain.Config loaded = new DspChain.Config();
        loaded.slots.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            DspChain.EffectType t = DspChain.EffectType.byId(
                    o.optString("id", "noise"));
            DspChain.EffectSlot s = new DspChain.EffectSlot(t,
                    o.optBoolean("en", true), o.optInt("val", t.defaultVal),
                    false, o.optLong("iid", 0L));
            JSONObject p = o.optJSONObject("params");
            if (p != null) {
                java.util.Iterator<String> keys = p.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    s.extraParams.put(k, p.optInt(k));
                }
            }
            loaded.slots.add(s);
        }
        assertEquals("Both copies survive the round trip", 2, loaded.slots.size());
        assertNotEquals("Identities stay distinct",
                loaded.slots.get(0).instanceId, loaded.slots.get(1).instanceId);
        assertEquals(30, loaded.slots.get(0).value);
        assertEquals(80, loaded.slots.get(1).value);
        assertEquals(Integer.valueOf(20),
                loaded.slots.get(0).extraParams.get("rv_decay"));
        assertEquals(Integer.valueOf(90),
                loaded.slots.get(1).extraParams.get("rv_decay"));
    }

    @Test
    public void aRestoredRackRebuildsOneProcessorPerCopy() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot first =
                cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        first.value = 30;

        // A restored rack is rebuilt from the persisted JSON, so the copy
        // identities have to survive for the processors to stay separate.
        DspChain.Config restored = new DspChain.Config();
        restored.slots.clear();
        for (DspChain.EffectSlot s : cfg.slots) {
            restored.slots.add(new DspChain.EffectSlot(s.type, s.enabled,
                    s.value, s.expanded, s.instanceId));
        }
        DspChain chain = new DspChain(1, restored);
        int reverbs = 0;
        for (AudioProcessor p : chain.getRack().processors()) {
            if ("reverb".equals(p.getId())) reverbs++;
        }
        assertEquals("Each copy gets its own processor", 2, reverbs);
    }
}
