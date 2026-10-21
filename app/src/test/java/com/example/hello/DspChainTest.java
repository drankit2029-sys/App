package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Roadmap Phase 2 DSP chain: fast-math accuracy, inactive no-op guarantee,
 * bounded active processing, badge text and persisted configuration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DspChainTest {

    @After
    public void clearTakeonePrefs() {
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("takeone", Context.MODE_PRIVATE)
                .edit().clear().commit();
        TeleSettings.invalidateCache();
    }


    private static float[] sine(int frames, int ch, float amp) {
        float[] buf = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) Math.sin(f * 0.03) * amp;
            for (int c = 0; c < ch; c++) buf[f * ch + c] = s;
        }
        return buf;
    }

    @Test
    public void fastMathSanity() {
        assertEquals(0.0f, DspChain.fastLinToDb(1.0f), 0.5f);
        assertEquals(-20.0f, DspChain.fastLinToDb(0.1f), 1.0f);
        assertEquals(1.0f, DspChain.fastDbToLin(0f), 0.05f);
        assertEquals(0.0f, DspChain.fastDbToLin(-140f), 0.0f);
        float rt = DspChain.fastDbToLin(DspChain.fastLinToDb(0.25f));
        assertEquals(0.25f, rt, 0.03f);
    }

    @Test
    public void inactiveChainIsBitIdenticalNoOp() {
        DspChain chain = new DspChain(2, new DspChain.Config());
        assertFalse(chain.isActive());
        float[] buf = sine(1024, 2, 0.7f);
        float[] before = buf.clone();
        chain.processInPlace(buf, 1024);
        for (int i = 0; i < buf.length; i++) {
            assertEquals("touched @" + i, before[i], buf[i], 0.0f);
        }
    }

    @Test
    public void polishChainStaysBoundedAndAudible() {
        DspChain chain = new DspChain(2, DspChain.Config.voicePolish());
        assertTrue(chain.isActive());
        float[] buf = sine(4800, 2, 0.9f);
        chain.processInPlace(buf, 4800);
        float peak = 0f;
        boolean changed = false;
        float[] orig = sine(4800, 2, 0.9f);
        for (int i = 0; i < buf.length; i++) {
            assertTrue("non-finite @" + i, Float.isFinite(buf[i]));
            peak = Math.max(peak, Math.abs(buf[i]));
            if (Math.abs(buf[i] - orig[i]) > 1e-4f) changed = true;
        }
        assertTrue("silenced, peak=" + peak, peak > 0.05f);
        assertTrue("unbounded, peak=" + peak, peak <= 1.0f);
        assertTrue("chain did nothing", changed);
    }

    @Test
    public void monoChainWorks() {
        DspChain chain = new DspChain(1, DspChain.Config.voicePolish());
        float[] buf = sine(512, 1, 0.5f);
        chain.processInPlace(buf, 512);
        for (float v : buf) assertTrue(Float.isFinite(v));
    }

    @Test
    public void badgeSummaries() {
        assertEquals("FX Off", new DspChain.Config().badgeSummary());
        assertEquals("FX • Polish", DspChain.Config.voicePolish()
                .badgeSummary());
        assertEquals("FX • Custom",
                new DspChain.Config(false, 30, 0, 0).badgeSummary());
    }

    @Test
    public void configPersists() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DspChain.Config c = new DspChain.Config(true, 50, 45, 40);
        c.save(ctx);
        DspChain.Config back = DspChain.Config.load(ctx);
        assertTrue(back.hpfEnabled);
        assertEquals(50, back.noisePct);
        assertEquals(45, back.deEssPct);
        assertEquals(40, back.compPct);
    }

    @Test
    public void threeModesSwitchInterchangeably() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DspChain.Config c = new DspChain.Config();
        assertEquals(DspChain.Config.MODE_OFF, c.mode);
        assertFalse(c.isActive());

        // 1) Off -> Noise Suppression
        c.applyMode(DspChain.Config.MODE_NOISE_SUPPRESSION);
        assertEquals(DspChain.Config.MODE_NOISE_SUPPRESSION, c.mode);
        assertTrue(c.isActive());
        assertEquals(50, c.noisePct);
        assertEquals(45, c.deEssPct);
        assertEquals(40, c.compPct);

        // 2) Noise Suppression -> Custom (previously broken!)
        c.applyMode(DspChain.Config.MODE_CUSTOM);
        assertEquals(DspChain.Config.MODE_CUSTOM, c.mode);
        assertTrue(c.isActive());
        assertEquals("FX • Custom", c.badgeSummary());

        // Adjust a custom slider and switch back to Noise Suppression, then Custom
        c.noisePct = 75;
        c.onManualAdjustment();
        assertEquals(DspChain.Config.MODE_CUSTOM, c.mode);

        c.applyMode(DspChain.Config.MODE_NOISE_SUPPRESSION);
        assertEquals(DspChain.Config.MODE_NOISE_SUPPRESSION, c.mode);
        assertEquals(50, c.noisePct);

        c.applyMode(DspChain.Config.MODE_CUSTOM);
        assertEquals(DspChain.Config.MODE_CUSTOM, c.mode);
        assertEquals(75, c.noisePct);

        // 3) Custom -> Off -> Custom
        c.applyMode(DspChain.Config.MODE_OFF);
        assertEquals(DspChain.Config.MODE_OFF, c.mode);
        assertFalse(c.isActive());

        c.applyMode(DspChain.Config.MODE_CUSTOM);
        assertEquals(DspChain.Config.MODE_CUSTOM, c.mode);
        assertEquals(75, c.noisePct);

        c.save(ctx);
        DspChain.Config loaded = DspChain.Config.load(ctx);
        assertEquals(DspChain.Config.MODE_CUSTOM, loaded.mode);
        assertEquals(75, loaded.noisePct);
    }

    @Test
    public void proFxRackPresetsCheckmarksAndDeletionWork() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.PODCAST_BROADCAST);
        assertTrue(cfg.isActive());
        assertTrue(cfg.enabledSlotCount() >= 5);

        // Unchecking all slots disables processing
        for (DspChain.EffectSlot s : cfg.slots) {
            s.enabled = false;
        }
        cfg.syncLegacyFieldsFromSlots();
        assertFalse(cfg.isActive());

        // Adding a single slot enables it
        DspChain.EffectSlot added = cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        assertTrue(added.enabled);
        assertTrue(cfg.isActive());

        // Deleting the slot removes it from the rack
        cfg.removeSlot(added);
        assertFalse(cfg.isActive());
    }

    @Test
    public void allEightEffectsModifySignalWhenEnabled() {
        for (DspChain.EffectType type : DspChain.EffectType.values()) {
            DspChain.Config cfg = new DspChain.Config();
            cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
            DspChain.EffectSlot slot = cfg.addOrFocusSlot(type);
            if (!type.isLegacyStage()) {
                // The Stage 3 suite defaults to a flat response; dial in a
                // setting that must move the ~229 Hz probe tone. A flat slot
                // is intentionally NOT active (it must not arm save-time
                // baking or push undo state), so assert only after dialing.
                String pid = DspChain.primaryParamId(type);
                if ("out".equals(pid) || "bass".equals(pid)) {
                    slot.value = 300;
                } else if ("gain".equals(pid)) {
                    slot.value = 600;
                } else {
                    slot.value = 229;
                }
            }
            assertTrue("Effect " + type.id + " should be active", cfg.isActive());

            DspChain chain = new DspChain(2, cfg);
            // A loud sustained tone keeps the physical noise gate fully open
            // (envelope >> threshold), which is gate-correct — feed the gate
            // a quiet tone below its threshold instead.
            float amp = (type == DspChain.EffectType.ROOM_GATE) ? 0.003f : 0.5f;
            // The de-esser only processes the 4.5–9.5 kHz sibilance band BY
            // DESIGN, so a low tone never moves it — feed it a bright 6 kHz
            // sibilant tone instead.
            float[] buf;
            if (type == DspChain.EffectType.DE_ESSER) {
                buf = new float[4800 * 2];
                for (int f = 0; f < 4800; f++) {
                    float s = (float) Math.sin(2.0 * Math.PI * 6000.0
                            * f / 48000.0) * amp;
                    buf[f * 2] = s;
                    buf[f * 2 + 1] = s;
                }
            } else {
                buf = sine(4800, 2, amp);
            }
            float[] orig = buf.clone();
            chain.processInPlace(buf, 4800);
            boolean modified = false;
            for (int i = 0; i < buf.length; i++) {
                if (Math.abs(buf[i] - orig[i]) > 1e-4f) {
                    modified = true;
                    break;
                }
            }
            assertTrue("Effect " + type.id + " produced no audio change", modified);
        }
    }
}
