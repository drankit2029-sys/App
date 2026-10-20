package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * End-to-end checks for the persisted FX rack used by StudioFragment:
 * every legacy effect must survive save/load, produce an audible change
 * through DspChain, and disappear cleanly from a live chain when Apply resets
 * the rack.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxPipelineTest {

    private static float[] probeTone(DspChain.EffectType type) {
        int frames = 4800;
        float[] buf = new float[frames * 2];
        float amp = type == DspChain.EffectType.ROOM_GATE ? 0.003f : 0.5f;
        double hz = 229.0;
        if (type == DspChain.EffectType.DE_ESSER) hz = 6000.0;
        if (type == DspChain.EffectType.LOW_CUT_HPF) hz = 60.0;
        for (int f = 0; f < frames; f++) {
            float s = (float) (amp * Math.sin(2.0 * Math.PI * hz * f / 48000.0));
            buf[f * 2] = s;
            buf[f * 2 + 1] = s;
        }
        return buf;
    }

    private static boolean differs(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-4f) return true;
        }
        return false;
    }

    private static boolean finite(float[] a) {
        for (float v : a) {
            if (!Float.isFinite(v)) return false;
        }
        return true;
    }

    @Test
    public void everyLegacyEffectSurvivesSaveLoadAndProcessesAudio() {
        Context ctx = ApplicationProvider.getApplicationContext();
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            if (!type.isLegacyStage()) continue;
            DspChain.Config cfg = new DspChain.Config();
            cfg.slots.clear();
            cfg.slots.add(new DspChain.EffectSlot(type, true,
                    type.defaultVal, false));
            cfg.syncLegacyFieldsFromSlots();
            cfg.save(ctx);
            DspChain.Config loaded = DspChain.Config.load(ctx);
            assertTrue("Loaded rack lost " + type.id, loaded.isActive());

            DspChain chain = new DspChain(2, loaded);
            assertTrue("Chain inactive for " + type.id, chain.isActive());
            float[] wet = probeTone(type);
            float[] dry = wet.clone();
            chain.processInPlace(wet, 4800);
            assertTrue("Non-finite output from " + type.id, finite(wet));
            assertTrue("Persisted " + type.id + " produced no audio change",
                    differs(wet, dry));
        }
    }

    @Test
    public void clearingTheRackMakesALiveChainDryAgain() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.BRICKWALL_LIMITER, true, 100, false));
        cfg.syncLegacyFieldsFromSlots();
        DspChain chain = new DspChain(2, cfg);
        assertTrue(chain.isActive());

        float[] first = probeTone(DspChain.EffectType.BRICKWALL_LIMITER);
        chain.processInPlace(first, 4800);
        assertTrue(chain.isActive());

        cfg.clearRack();
        assertEquals(0, cfg.slots.size());
        assertFalse(cfg.isActive());
        assertEquals(DspChain.Config.MODE_OFF, cfg.mode);
        assertEquals(DspChain.Preset.CLEAR_ALL.id, cfg.activePresetId);

        chain.updateConfig(cfg);
        assertFalse(chain.isActive());
        float[] second = probeTone(DspChain.EffectType.BRICKWALL_LIMITER);
        float[] before = second.clone();
        chain.processInPlace(second, 4800);
        for (int i = 0; i < second.length; i++) {
            assertEquals("cleared chain touched sample " + i,
                    before[i], second[i], 0.0f);
        }
    }

    @Test
    public void secondaryParametersSurviveSaveLoad() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        DspChain.EffectSlot deess = new DspChain.EffectSlot(
                DspChain.EffectType.DE_ESSER, true, 65, true);
        deess.extraParams.put("deess_freq", 7200);
        deess.extraParams.put("deess_width", 150);
        deess.extraParams.put("deess_mode", 1);
        cfg.slots.add(deess);
        cfg.syncLegacyFieldsFromSlots();
        cfg.save(ctx);

        DspChain.Config loaded = DspChain.Config.load(ctx);
        assertEquals(1, loaded.slots.size());
        DspChain.EffectSlot back = loaded.slots.get(0);
        assertEquals(Integer.valueOf(7200),
                back.extraParams.get("deess_freq"));
        assertEquals(Integer.valueOf(150),
                back.extraParams.get("deess_width"));
        assertEquals(Integer.valueOf(1), back.extraParams.get("deess_mode"));

        DspChain chain = new DspChain(2, loaded);
        ProDeEsserProcessor p = (ProDeEsserProcessor) chain.getRack()
                .getProcessor(chain.getRack().indexOf("deess"));
        assertEquals(7200, p.getParam("deess_freq").getCurrentRaw());
        assertEquals(150, p.getParam("deess_width").getCurrentRaw());
        assertEquals(1, p.getParam("deess_mode").getCurrentRaw());
    }

    @Test
    public void previewConfigSeparatesWetAndDryPlayback() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.slots.clear();
        cfg.slots.add(new DspChain.EffectSlot(
                DspChain.EffectType.COMPRESSOR, true, 40, false));
        cfg.syncLegacyFieldsFromSlots();
        assertTrue(cfg.isActive());

        assertTrue("Play/Pause must stay dry",
                !StudioFragment.previewConfigFor(false,
                        false, false, false, cfg).isActive());
        assertTrue("FX Preview must run the live rack",
                StudioFragment.previewConfigFor(true,
                        false, false, false, cfg).isActive());
        assertTrue("A baked take must not be processed twice",
                !StudioFragment.previewConfigFor(true,
                        true, false, false, cfg).isActive());
        assertTrue("A fresh selection may preview after a sub-range bake",
                StudioFragment.previewConfigFor(true,
                        true, true, true, cfg).isActive());
        assertTrue("A selection is required after a sub-range bake",
                !StudioFragment.previewConfigFor(true,
                        true, true, false, cfg).isActive());
    }
}
