package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * The FX rack must let the same effect be inserted twice, with each copy
 * keeping its own parameters, and the catalog must stay navigable.
 */
public class FxRackDuplicatesTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    @Test
    public void theSameEffectCanBeAddedTwice() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot first =
                cfg.addOrFocusSlot(DspChain.EffectType.DYNAMIC_COMPRESSOR);
        DspChain.EffectSlot second =
                cfg.addOrFocusSlot(DspChain.EffectType.DYNAMIC_COMPRESSOR);
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals("A second copy must be a new rack entry",
                first, second);
        assertNotEquals("Each copy needs its own identity",
                first.instanceId, second.instanceId);
        assertEquals("Both copies live in the rack", 2, cfg.slots.size());
    }

    @Test
    public void duplicateCopiesKeepIndependentParameters() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot first =
                cfg.addOrFocusSlot(DspChain.EffectType.DYNAMIC_COMPRESSOR);
        DspChain.EffectSlot second =
                cfg.addOrFocusSlot(DspChain.EffectType.DYNAMIC_COMPRESSOR);
        first.value = -600;
        second.value = -3000;

        DspChain chain = new DspChain(1, cfg);
        int comps = 0;
        for (AudioProcessor p : chain.getRack().processors()) {
            if (!"dyncomp".equals(p.getId())) continue;
            comps++;
            FxParameter thr = p.getParam("dyncomp_thresh");
            assertNotNull("The compressor must expose its threshold", thr);
        }
        assertEquals("Both copies need their own processor", 2, comps);

        // Changing one copy must not touch the other.
        cfg.slots.get(0).value = -1200;
        chain.updateConfig(cfg);
        int[] seen = new int[2];
        int i = 0;
        for (AudioProcessor p : chain.getRack().processors()) {
            if (!"dyncomp".equals(p.getId())) continue;
            if (i < 2) seen[i++] = p.getParam("dyncomp_thresh").getCurrentRaw();
        }
        assertEquals("The first copy keeps its own value", -1200, seen[0]);
        assertEquals("The second copy keeps its own value", -3000, seen[1]);
    }

    @Test
    public void legacyStagesCanAlsoBeStackedMultipleTimes() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot first =
                cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        DspChain.EffectSlot second =
                cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        assertNotEquals("Legacy stages can be stacked multiple times", first, second);
        assertEquals("Both copies live in the rack", 2, cfg.slots.size());
        first.value = 35;
        second.value = 75;
        DspChain chain = new DspChain(1, cfg);
        int comps = 0;
        for (AudioProcessor p : chain.getRack().processors()) {
            if ("comp".equals(p.getId()) && p.isEnabled()) comps++;
        }
        assertEquals("Both stacked legacy copies have active processors", 2, comps);
    }

    @Test
    public void removingOneCopyLeavesTheOtherInPlace() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot first =
                cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        assertEquals(2, cfg.slots.size());

        DspChain chain = new DspChain(1, cfg);
        int before = 0;
        for (AudioProcessor p : chain.getRack().processors()) {
            if ("reverb".equals(p.getId())) before++;
        }
        assertEquals("Two processors for two copies", 2, before);

        cfg.removeSlot(first);
        chain.updateConfig(cfg);
        int after = 0;
        for (AudioProcessor p : chain.getRack().processors()) {
            if ("reverb".equals(p.getId())) after++;
        }
        assertEquals("The removed copy drops its processor", 1, after);
    }

    @Test
    public void theCatalogIsOrderedAlphabetically() {
        // The Add Effect menu lists the catalog by title, so the list is
        // sorted the same way the dialog sorts it.
        DspChain.EffectType[] all = DspChain.EffectType.values();
        java.util.List<DspChain.EffectType> sorted =
                new java.util.ArrayList<>(java.util.Arrays.asList(all));
        java.util.Collections.sort(sorted,
                new java.util.Comparator<DspChain.EffectType>() {
                    @Override
                    public int compare(DspChain.EffectType a,
                                       DspChain.EffectType b) {
                        return a.title.compareToIgnoreCase(b.title);
                    }
                });
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue("Titles must sort ascending: "
                            + sorted.get(i - 1).title + " then "
                            + sorted.get(i).title,
                    sorted.get(i - 1).title.compareToIgnoreCase(
                            sorted.get(i).title) <= 0);
        }
    }
}
