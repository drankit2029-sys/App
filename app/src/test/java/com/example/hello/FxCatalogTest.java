package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * Cross-stage catalogue contract: every effect in the "+ Add Effect" list
 * must have a registered processor, a primary parameter that matches the
 * slot's dial range, a category, and must survive a JSON round-trip.
 */
public class FxCatalogTest {

    @Test
    public void everyEffectTypeHasARegisteredProcessor() {
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            AudioProcessor p = FxRackChain.createProcessor(t.id);
            assertNotNull("No processor registered for " + t.id + " ("
                    + t.title + ")", p);
        }
    }

    @Test
    public void everyNonLegacyEffectHasAPrimaryParameterInRange() {
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            if (t.isLegacyStage()) continue;
            String primary = DspChain.primaryParamId(t);
            assertNotNull("No primary param for " + t.id, primary);
            AudioProcessor p = FxRackChain.createProcessor(t.id);
            FxParameter fp = p.getParam(primary);
            assertNotNull("Primary param " + primary + " missing on " + t.id,
                    fp);
            assertEquals("Dial range mismatch for " + t.id,
                    fp.minRaw, t.minVal);
            assertEquals("Dial range mismatch for " + t.id,
                    fp.maxRaw, t.maxVal);
            assertEquals("Default mismatch for " + t.id,
                    fp.defaultRaw, t.defaultVal);
        }
    }

    @Test
    public void everyEffectBelongsToACategory() {
        Set<FxCategory> seen = new HashSet<>();
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            FxCategory c = DspChain.categoryOf(t);
            assertNotNull("No category for " + t.id, c);
            seen.add(c);
        }
        // The whole catalog must cover the eight families.
        assertEquals("Not every category is represented", 8, seen.size());
    }

    @Test
    public void effectIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            assertTrue("Duplicate effect id " + t.id, ids.add(t.id));
        }
    }

    @Test
    public void newEffectsJoinTheRack() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.DYNAMIC_COMPRESSOR, true, -1800, true));
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.FREEVERB_REVERB, true, 55, true));
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.CHANGE_TEMPO, true, 5000, true));

        DspChain chain = new DspChain(2, cfg);
        // The rack always carries the seven legacy stages plus one
        // processor per non-legacy slot.
        assertEquals("Wrong rack size", 7 + 3, chain.getRack().size());
        assertTrue("Compressor missing from the rack",
                chain.getRack().indexOf("dyncomp") >= 0);
        assertTrue("Reverb missing from the rack",
                chain.getRack().indexOf("reverb") >= 0);
        assertTrue("Tempo processor missing from the rack",
                chain.getRack().indexOf("tempo") >= 0);
    }

    @Test
    public void neutralEffectsDoNotCountAsAnActiveRack() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        // Amplify at 0 dB does nothing.
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.AMPLIFY, true, 0, false));
        assertFalse("A neutral effect must not arm the rack", cfg.isActive());

        // The same effect at +6 dB does.
        cfg.slots.get(0).value = 600;
        assertTrue("An engaged effect must arm the rack", cfg.isActive());
    }

    @Test
    public void offlineEffectsAreSeparatedFromTheRealtimeChain() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.CHANGE_TEMPO, true, 5000, false));
        DspChain chain = new DspChain(2, cfg);
        assertEquals("Offline processors not detected",
                1, chain.offlineProcessors().size());
        assertFalse("Offline-only chain must not report in-place processors",
                chain.hasInPlaceProcessors());
        chain.clearOfflineProcessors();
        assertEquals("Offline processors not cleared",
                0, chain.offlineProcessors().size());
    }
}
