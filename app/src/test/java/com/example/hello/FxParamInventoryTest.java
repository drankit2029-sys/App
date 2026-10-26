package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Tier 0 of the FX verification harness: the runtime parameter inventory.
 *
 * <p>Walks the effect catalog exactly as the "+ Add Effect" dialog does and
 * records every parameter each processor actually exposes, so the test matrix
 * is derived from the code instead of a hand-maintained list. The workflow
 * compares this output against its own matrix and fails on drift.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxParamInventoryTest {

    @Test
    public void everyEffectExposesItsParameters() throws Exception {
        File out = FxVerifySupport.outRoot("params");
        JSONArray effects = new JSONArray();
        Set<String> ids = new HashSet<>();
        StringBuilder idList = new StringBuilder();

        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            AudioProcessor proto = FxRackChain.createProcessor(type.id);
            assertNotNull("No processor registered for " + type.id, proto);
            assertTrue("Duplicate effect id " + type.id, ids.add(type.id));

            String primary = FxVerifySupport.primaryParamId(type);
            JSONArray params = new JSONArray();
            boolean sawPrimary = primary == null;
            for (FxParameter p : proto.getParameters()) {
                JSONObject o = p.toJson();
                o.put("primary", p.id.equals(primary));
                p.resetToDefault();
                o.put("display", p.formatDisplay());
                params.put(o);
                if (p.id.equals(primary)) sawPrimary = true;
            }
            if (!sawPrimary) {
                fail("Effect " + type.id + " has no primary parameter '"
                        + primary + "' in its own parameter list");
            }
            assertTrue("Effect " + type.id + " exposes no parameters",
                    params.length() > 0);

            JSONObject e = new JSONObject();
            e.put("id", type.id);
            e.put("title", type.title);
            e.put("description", type.description);
            e.put("paramLabel", type.paramLabel);
            e.put("dialMin", type.minVal);
            e.put("dialMax", type.maxVal);
            e.put("dialStep", type.stepVal);
            e.put("dialDefault", type.defaultVal);
            FxCategory cat = DspChain.categoryOf(type);
            e.put("category", cat == null ? "" : cat.name());
            e.put("offlineOnly", proto.isOfflineOnly());
            e.put("handlesOwnWetDryMix", proto.handlesOwnWetDryMix());
            e.put("requiresLearnedProfile", type.requiresLearnedProfile());
            e.put("primaryParam", primary == null ? "" : primary);
            e.put("paramCount", params.length());
            e.put("params", params);
            effects.put(e);

            idList.append(type.id).append('\n');
        }

        JSONObject root = new JSONObject();
        root.put("effectCount", effects.length());
        root.put("effects", effects);

        write(new File(out, "params.json"), root.toString(2));
        write(new File(out, "effect_ids.txt"), idList.toString());
        System.out.println("FX inventory: " + effects.length() + " effects, "
                + countParams(effects) + " parameters");
    }

    private static int countParams(JSONArray effects) throws Exception {
        int n = 0;
        for (int i = 0; i < effects.length(); i++) {
            n += effects.getJSONObject(i).getInt("paramCount");
        }
        return n;
    }

    private static void write(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f),
                StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }
}
