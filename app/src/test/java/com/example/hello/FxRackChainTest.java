package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Modular-rack contract: ordering, bypass, wet/dry blending, safety limiting
 * and JSON/SharedPreferences persistence must behave as documented.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxRackChainTest {

    private static float[] tone(int frames, int ch, float amp, float step) {
        float[] buf = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) Math.sin(f * step) * amp;
            for (int c = 0; c < ch; c++) buf[f * ch + c] = s;
        }
        return buf;
    }

    private static float[] sineAt(int frames, float amp, double hz) {
        return tone(frames, 1, amp, (float) (2 * Math.PI * hz / 48000.0));
    }

    private static double rmsTail(float[] buf) {
        int from = buf.length / 2;
        double sum = 0;
        for (int i = from; i < buf.length; i++) sum += buf[i] * buf[i];
        return Math.sqrt(sum / Math.max(1, buf.length - from));
    }

    // -------------------------------------------------------- rack mechanics

    @Test
    public void inactiveLegacyConfigLeavesBufferUntouched() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain chain = new DspChain(2, cfg);
        FxRackChain rack = chain.getRack();
        assertNotNull(rack);
        assertFalse(rack.isActive());
        float[] buf = tone(777, 2, 0.9f, 0.05f);
        float[] before = buf.clone();
        rack.processInPlace(buf, 777);
        for (int i = 0; i < buf.length; i++) {
            assertEquals(before[i], buf[i], 0.0f);
        }
    }

    // -------------------------------------------------------- rack mechanics

    @Test
    public void moveProcessorReordersRackAndGuardsBounds() {
        FxRackChain rack = new FxRackChain(2);
        rack.addProcessor(new ProHpfProcessor());
        rack.addProcessor(new NoiseSuppressionProcessor());
        rack.addProcessor(new ProGateProcessor());
        assertEquals("hpf", rack.getProcessor(0).getId());
        assertEquals("noise", rack.getProcessor(1).getId());
        assertEquals("gate", rack.getProcessor(2).getId());

        assertTrue(rack.moveProcessor(0, 2));
        assertEquals("noise", rack.getProcessor(0).getId());
        assertEquals("gate", rack.getProcessor(1).getId());
        assertEquals("hpf", rack.getProcessor(2).getId());
        assertEquals(0, rack.indexOf("noise"));
        assertEquals(2, rack.indexOf("hpf"));

        assertFalse(rack.moveProcessor(0, 0));
        assertFalse(rack.moveProcessor(-1, 0));
        assertFalse(rack.moveProcessor(0, 99));
        assertFalse(rack.moveProcessor(2, 99));
        assertEquals(3, rack.size());

        assertEquals("hpf", rack.removeProcessor(2).getId());
        assertEquals(2, rack.size());
        assertNull(rack.removeProcessor(5));
    }

    @Test
    public void bypassedProcessorLeavesSignalBitIdentical() {
        final int frames = 512;
        float[] input = tone(frames, 2, 0.8f, 0.04f);

        NoiseSuppressionProcessor noise = new NoiseSuppressionProcessor();
        noise.setLegacyAmount(80);
        assertTrue(noise.isAudible());

        FxRackChain rack = new FxRackChain(2);
        rack.addProcessor(noise);
        assertTrue(rack.isActive());

        float[] wet = input.clone();
        rack.processInPlace(wet, frames);
        boolean changed = false;
        for (int i = 0; i < wet.length; i++) {
            if (Float.compare(wet[i], input[i]) != 0) {
                changed = true;
                break;
            }
        }
        assertTrue("enabled noise stage did nothing", changed);

        noise.setEnabled(false);
        assertFalse(noise.isAudible());
        assertFalse(rack.isActive());
        float[] dry = input.clone();
        rack.processInPlace(dry, frames);
        for (int i = 0; i < dry.length; i++) {
            assertEquals("bypassed stage touched @" + i,
                    Float.floatToRawIntBits(input[i]),
                    Float.floatToRawIntBits(dry[i]));
        }
    }

    @Test
    public void wetDryMixBlendsExactlyHalfAndHalf() {
        final int frames = 256;
        float[] input = tone(frames, 2, 0.9f, 0.037f);

        ProToneProcessor wetProc = new ProToneProcessor();
        wetProc.setLegacyAmounts(70, 60);
        FxRackChain wetRack = new FxRackChain(2);
        wetRack.addProcessor(wetProc);
        float[] wet = input.clone();
        wetRack.processInPlace(wet, frames);

        ProToneProcessor halfProc = new ProToneProcessor();
        halfProc.setLegacyAmounts(70, 60);
        halfProc.setWetDryMix(0.5f);
        FxRackChain halfRack = new FxRackChain(2);
        halfRack.addProcessor(halfProc);
        float[] half = input.clone();
        halfRack.processInPlace(half, frames);

        for (int i = 0; i < half.length; i++) {
            float expected = input[i] * 0.5f + wet[i] * 0.5f;
            assertEquals("blend mismatch @" + i,
                    Float.floatToRawIntBits(expected),
                    Float.floatToRawIntBits(half[i]));
        }

        // A zero mix is a pure dry pass.
        ProToneProcessor offProc = new ProToneProcessor();
        offProc.setLegacyAmounts(70, 60);
        offProc.setWetDryMix(0f);
        FxRackChain offRack = new FxRackChain(2);
        offRack.addProcessor(offProc);
        float[] off = input.clone();
        offRack.processInPlace(off, frames);
        for (int i = 0; i < off.length; i++) {
            assertEquals("zero mix touched @" + i,
                    Float.floatToRawIntBits(input[i]),
                    Float.floatToRawIntBits(off[i]));
        }
    }

    // ------------------------------------------------------- serialization

    @Test
    public void jsonRoundTripPreservesOrderEnableMixAndParams() throws Exception {
        FxRackChain rack = FxRackChain.fromLegacyConfig(2,
                DspChain.Config.voicePolish());
        AudioProcessor hpf = rack.getProcessor(rack.indexOf("hpf"));
        AudioProcessor noise = rack.getProcessor(rack.indexOf("noise"));
        assertNotNull(hpf);
        assertNotNull(noise);
        hpf.setWetDryMix(0.25f);
        noise.setEnabled(false);
        noise.getParam("amt").setCurrentRaw(77);
        rack.addProcessor(FxRackChain.createProcessor("limiter"));

        JSONObject json = rack.toJson();
        FxRackChain back = FxRackChain.fromJson(2, json);

        assertEquals(rack.size(), back.size());
        for (int i = 0; i < rack.size(); i++) {
            AudioProcessor a = rack.getProcessor(i);
            AudioProcessor b = back.getProcessor(i);
            assertNotNull("missing " + a.getId(), b);
            assertEquals(a.getId(), b.getId());
            assertEquals("enabled " + a.getId(),
                    a.isEnabled(), b.isEnabled());
            assertEquals("mix " + a.getId(),
                    Float.floatToRawIntBits(a.getWetDryMix()),
                    Float.floatToRawIntBits(b.getWetDryMix()));
            for (FxParameter pa : a.getParameters()) {
                FxParameter pb = b.getParam(pa.id);
                assertNotNull("missing param " + pa.id, pb);
                assertEquals("raw " + pa.id,
                        pa.getCurrentRaw(), pb.getCurrentRaw());
            }
        }
        assertEquals(0.25f,
                back.getProcessor(back.indexOf("hpf")).getWetDryMix(), 0.0f);
        assertFalse(back.getProcessor(back.indexOf("noise")).isEnabled());
        assertEquals(77, back.getProcessor(back.indexOf("noise"))
                .getParam("amt").getCurrentRaw());
    }

    @Test
    public void unknownProcessorIdsAreSkippedOnLoad() throws Exception {
        JSONObject json = new JSONObject();
        json.put("channels", 2);
        org.json.JSONArray arr = new org.json.JSONArray();
        JSONObject ghost = new JSONObject();
        ghost.put("id", "this_effect_does_not_exist");
        arr.put(ghost);
        JSONObject real = new JSONObject();
        real.put("id", "hpf");
        arr.put(real);
        json.put("processors", arr);

        FxRackChain rack = FxRackChain.fromJson(2, json);
        assertEquals(1, rack.size());
        assertEquals("hpf", rack.getProcessor(0).getId());
    }

    @Test
    public void registryCoversEveryLegacyStageId() {
        for (String id : new String[]{"hpf", "noise", "gate", "tone",
                "deess", "comp", "limiter"}) {
            AudioProcessor p = FxRackChain.createProcessor(id);
            assertNotNull("registry missing " + id, p);
            assertEquals(id, p.getId());
            assertNotNull(p.getTitle());
            assertNotNull(p.getCategory());
        }
        assertNull(FxRackChain.createProcessor("nope"));
    }

    // ------------------------------------------------- slot persistence

    @Test
    public void slotWetDryAndExtraParamsPersist() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        DspChain.EffectSlot noise = null;
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.NOISE_SUPPRESSION) noise = s;
        }
        assertNotNull(noise);
        noise.wetDry = 0.35f;
        noise.extraParams.put("amt", 61);
        cfg.save(ctx);

        DspChain.Config back = DspChain.Config.load(ctx);
        DspChain.EffectSlot loaded = null;
        for (DspChain.EffectSlot s : back.slots) {
            if (s.type == DspChain.EffectType.NOISE_SUPPRESSION) loaded = s;
        }
        assertNotNull(loaded);
        assertEquals(0.35f, loaded.wetDry, 0.0f);
        assertEquals(61, (int) loaded.extraParams.get("amt"));
    }

    @Test
    public void slotWetDryDrivesProcessorMixThroughUpdateConfig() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.NOISE_SUPPRESSION) {
                s.wetDry = 0.25f;
            }
        }
        DspChain chain = new DspChain(2, cfg);
        AudioProcessor noise =
                chain.getRack().getProcessor(chain.getRack().indexOf("noise"));
        assertEquals(0.25f, noise.getWetDryMix(), 0.0f);

        // Live edit path: the FX inspector mutates the slot and calls
        // updateConfig, which must re-push the mix.
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.NOISE_SUPPRESSION) {
                s.wetDry = 0.75f;
            }
        }
        chain.updateConfig(cfg);
        assertEquals(0.75f, noise.getWetDryMix(), 0.0f);
    }

    @Test
    public void warmthAndPresenceShareOneToneProcessorWithoutSlotMix() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.WARM_VOCAL);
        cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        DspChain chain = new DspChain(2, cfg);
        FxRackChain rack = chain.getRack();

        // Both slots map to the single combined tone stage, which stays fully
        // wet regardless of slot mixes.
        for (DspChain.EffectSlot s : cfg.slots) {
            s.wetDry = 0.1f;
        }
        chain.updateConfig(cfg);
        AudioProcessor toneProc = rack.getProcessor(rack.indexOf("tone"));
        assertEquals(1.0f, toneProc.getWetDryMix(), 0.0f);
        assertTrue(toneProc.isAudible());
    }

    @Test
    public void limiterActsAsSafetyCeilingForGainStagesAlone() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        DspChain chain = new DspChain(1, cfg);
        FxRackChain rack = chain.getRack();
        AudioProcessor limiter =
                rack.getProcessor(rack.indexOf("limiter"));
        assertNotNull(limiter);
        // Compressor alone must arm the safety limiter even at drive 0.
        assertTrue(limiter.isEnabled());
        assertTrue(limiter.isAudible());

        // A loud tone is soft-clipped by the 0.82 ceiling after makeup gain.
        float[] buf = new float[960];
        for (int f = 0; f < buf.length; f++) {
            buf[f] = 0.95f * (float) Math.sin(f * 0.11);
        }
        chain.processInPlace(buf, 960);
        for (float v : buf) {
            assertTrue(Float.isFinite(v));
            assertTrue("unbounded " + v, Math.abs(v) < 1.02f);
        }
    }

    // ------------------------------------------------------ live state

    @Test
    public void liveConfigUpdatePreservesFilterState() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        DspChain chain = new DspChain(1, cfg);
        float[] warm = tone(2048, 1, 0.8f, 0.043f);
        chain.processInPlace(warm, 2048);

        // Change the HPF cutoff live: coefficients update, but the biquad's
        // z-history must survive (the original engine never reset state).
        DspChain.Config next = new DspChain.Config();
        next.applyPreset(DspChain.Preset.CLEAR_ALL);
        next.addOrFocusSlot(DspChain.EffectType.LOW_CUT_HPF);
        for (DspChain.EffectSlot s : next.slots) {
            if (s.type == DspChain.EffectType.LOW_CUT_HPF) s.value = 150;
        }
        chain.updateConfig(next);

        float[] hot = tone(256, 1, 0.8f, 0.043f);
        float[] carried = hot.clone();
        chain.processInPlace(carried, 256);

        DspChain fresh = new DspChain(1, next);
        float[] cold = hot.clone();
        fresh.processInPlace(cold, 256);

        // Fresh chain starts from zero z-state, so a live update that kept
        // state MUST differ (unless the signal is silent, which it is not).
        boolean anyDiff = false;
        for (int i = 0; i < carried.length; i++) {
            if (Float.compare(carried[i], cold[i]) != 0) {
                anyDiff = true;
                break;
            }
        }
        assertTrue("live update reset the filter state", anyDiff);
        for (float v : carried) assertTrue(Float.isFinite(v));
    }


    // ----------------------------------------- Stage 2 audit regression tests

    @Test
    public void slotReorderingChangesRackExecutionOrderAndAudioOutput() {
        // BUG-01 regression: reordering cfg.slots must reorder rack processors
        // and produce distinct audio when nonlinear stages change order.
        DspChain.Config cfgA = new DspChain.Config();
        cfgA.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfgA.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        cfgA.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        for (DspChain.EffectSlot s : cfgA.slots) {
            if (s.type == DspChain.EffectType.WARMTH_BODY) s.value = 85;
            if (s.type == DspChain.EffectType.COMPRESSOR) s.value = 85;
        }
        DspChain chainA = new DspChain(1, cfgA);
        assertTrue(chainA.getRack().indexOf("tone")
                < chainA.getRack().indexOf("comp"));

        // Swap slot order in-place (Compressor BEFORE Warmth) and call updateConfig
        DspChain.Config cfgB = new DspChain.Config();
        cfgB.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfgB.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        cfgB.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        for (DspChain.EffectSlot s : cfgB.slots) {
            if (s.type == DspChain.EffectType.WARMTH_BODY) s.value = 85;
            if (s.type == DspChain.EffectType.COMPRESSOR) s.value = 85;
        }
        DspChain chainB = new DspChain(1, cfgB);
        assertTrue(chainB.getRack().indexOf("comp")
                < chainB.getRack().indexOf("tone"));

        float[] sigA = tone(1024, 1, 0.6f, 0.05f);
        float[] sigB = sigA.clone();
        chainA.processInPlace(sigA, 1024);
        chainB.processInPlace(sigB, 1024);

        boolean diff = false;
        for (int i = 0; i < sigA.length; i++) {
            if (Math.abs(sigA[i] - sigB[i]) > 1e-4f) {
                diff = true;
                break;
            }
        }
        assertTrue("Reordering compressor before tone had no effect on audio", diff);
    }

    @Test
    public void primarySlotParamCannotBeOverwrittenByStaleExtraParams() {
        // BUG-02 regression: the limiter's primary "drive" param
        // must be driven by slot.value and never clobbered by slot.extraParams.
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot limSlot =
                cfg.addOrFocusSlot(DspChain.EffectType.BRICKWALL_LIMITER);
        limSlot.value = 80;
        limSlot.extraParams.put("drive", 10);

        DspChain chain = new DspChain(1, cfg);
        AudioProcessor lim = chain.getRack().getProcessor(
                chain.getRack().indexOf("limiter"));
        assertNotNull(lim);
        assertEquals(80, lim.getParam("drive").getCurrentRaw());
    }

    @Test
    public void safetyLimiterCeilingHoldsEvenWhenLimiterSlotMixIsZeroOrUnchecked() {
        // BUG-03 regression: upstream gain stages (Warmth + Presence + Comp)
        // must never clip past the brickwall safety ceiling even if a
        // BRICKWALL_LIMITER slot has wetDry < 1.0f or is unchecked.
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot w = cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        DspChain.EffectSlot p = cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        DspChain.EffectSlot c = cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        DspChain.EffectSlot l = cfg.addOrFocusSlot(DspChain.EffectType.BRICKWALL_LIMITER);
        w.value = 100;
        p.value = 100;
        c.value = 100;
        l.value = 50;
        l.wetDry = 0.0f; // 0% drive mix on limiter slot

        DspChain chain = new DspChain(1, cfg);
        float[] hot = tone(1024, 1, 0.95f, 0.09f);
        chain.processInPlace(hot, 1024);
        for (int i = 0; i < hot.length; i++) {
            assertTrue("Sample clipped past safety ceiling @" + i + ": " + hot[i],
                    Math.abs(hot[i]) <= 0.99f);
        }
    }

    @Test
    public void removingSlotResetsProcessorWetDryMixToDefault() {
        // BUG-04 regression: removing a slot with wetDry = 0.2f must reset the
        // backing processor's wetDryMix back to 1.0f on updateConfig.
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        DspChain.EffectSlot noiseSlot = null;
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.NOISE_SUPPRESSION) noiseSlot = s;
        }
        assertNotNull(noiseSlot);
        noiseSlot.wetDry = 0.2f;

        DspChain chain = new DspChain(2, cfg);
        AudioProcessor noiseProc = chain.getRack().getProcessor(
                chain.getRack().indexOf("noise"));
        assertEquals(0.2f, noiseProc.getWetDryMix(), 0.0f);

        cfg.removeSlot(noiseSlot);
        chain.updateConfig(cfg);
        assertEquals(1.0f, noiseProc.getWetDryMix(), 0.0f);
    }

    @Test
    public void manualAdjustmentPreservesSlotWetDryAndOrder() {
        // BUG-05 regression: calling onManualAdjustment must not wipe out
        // existing slots' wetDry mix, extraParams, or custom slot order.
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        // Move COMPRESSOR to index 0
        DspChain.EffectSlot compSlot = cfg.slots.remove(3);
        cfg.slots.add(0, compSlot);
        compSlot.wetDry = 0.4f;
        compSlot.extraParams.put("custom_knob", 42);

        cfg.noisePct = 65;
        cfg.onManualAdjustment();

        assertEquals(DspChain.EffectType.COMPRESSOR, cfg.slots.get(0).type);
        assertEquals(0.4f, cfg.slots.get(0).wetDry, 0.0f);
        assertEquals(Integer.valueOf(42),
                cfg.slots.get(0).extraParams.get("custom_knob"));
    }

    @Test
    public void fxParameterClampRawRoundsToNearestStepAndReachesMax() {
        // BUG-07 regression: clampRaw must round to nearest step and allow maxRaw.
        FxParameter p = FxParameter.intParam("test", "Test", "%", 0, 100, 5, 50);
        assertEquals(50, p.clampRaw(48));
        assertEquals(45, p.clampRaw(47));
        assertEquals(100, p.clampRaw(100));

        FxParameter unaligned = FxParameter.intParam("u", "U", "", 0, 12, 5, 0);
        assertEquals(12, unaligned.clampRaw(12));
    }

    @Test
    public void setSampleRateUpdatesBiquadFilterResponse() {
        // Sample-rate changes must retune the biquad stages.
        // recompute coefficients when FxRackChain.setSampleRate is called.
        FxRackChain rack48 = new FxRackChain(1);
        ProHpfProcessor hpf48 = new ProHpfProcessor();
        hpf48.setLegacyState(true, 150);
        rack48.addProcessor(hpf48);
        rack48.setSampleRate(48000);

        FxRackChain rack96 = new FxRackChain(1);
        ProHpfProcessor hpf96 = new ProHpfProcessor();
        hpf96.setLegacyState(true, 150);
        rack96.addProcessor(hpf96);
        rack96.setSampleRate(96000);

        float[] a = tone(256, 1, 0.8f, 0.02f);
        float[] b = a.clone();
        rack48.processInPlace(a, 256);
        rack96.processInPlace(b, 256);

        boolean diff = false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-4f) {
                diff = true;
                break;
            }
        }
        assertTrue("setSampleRate had no effect on HPF biquad coefficients", diff);
    }

    @Test
    public void singleToneSlotSupportsWetDryMixWhileDualToneStaysFullWet() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CRISP_CLARITY);
        assertFalse(cfg.hasBothToneSlotsEnabled());
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.VOCAL_PRESENCE) {
                s.wetDry = 0.45f;
            }
        }
        DspChain chain = new DspChain(2, cfg);
        AudioProcessor toneProc = chain.getRack().getProcessor(
                chain.getRack().indexOf("tone"));
        assertEquals(0.45f, toneProc.getWetDryMix(), 0.0f);

        // Enabling Warmth alongside Presence switches to the combined pass (1.0f)
        cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        assertTrue(cfg.hasBothToneSlotsEnabled());
        chain.updateConfig(cfg);
        assertEquals(1.0f, toneProc.getWetDryMix(), 0.0f);
    }

    @Test
    public void separatingWarmthAndPresenceAroundCompressorChangesAudioOutput() {
        // Order 1: [WARMTH, PRESENCE, COMPRESSOR] (both before Compressor)
        DspChain.Config cfg1 = new DspChain.Config();
        cfg1.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot w1 = cfg1.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        DspChain.EffectSlot p1 = cfg1.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        DspChain.EffectSlot c1 = cfg1.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        w1.value = 85;
        p1.value = 85;
        c1.value = 85;
        cfg1.syncLegacyFieldsFromSlots();

        // Order 2: [WARMTH, COMPRESSOR, PRESENCE] (Presence moved after Compressor)
        DspChain.Config cfg2 = new DspChain.Config();
        cfg2.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot w2 = cfg2.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        DspChain.EffectSlot c2 = cfg2.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        DspChain.EffectSlot p2 = cfg2.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        w2.value = 85;
        p2.value = 85;
        c2.value = 85;
        cfg2.syncLegacyFieldsFromSlots();

        float[] sig1 = tone(1024, 1, 0.55f, 0.06f);
        for (int i = 0; i < sig1.length; i += 5) {
            sig1[i] += 0.35f * (float) Math.sin(i * 0.65);
        }
        float[] sig2 = sig1.clone();

        new DspChain(1, cfg1).processInPlace(sig1, 1024);
        new DspChain(1, cfg2).processInPlace(sig2, 1024);

        boolean diff = false;
        for (int i = 0; i < sig1.length; i++) {
            if (Math.abs(sig1[i] - sig2[i]) > 1e-4f) {
                diff = true;
                break;
            }
        }
        assertTrue("Moving Presence after Compressor had no effect on audio", diff);
    }

    @Test
    public void disabledToneSlotDoesNotTrapEnabledSiblingPosition() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot w = cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY);
        DspChain.EffectSlot c = cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        DspChain.EffectSlot p = cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        w.enabled = false;
        c.enabled = true;
        p.enabled = true;
        cfg.syncLegacyFieldsFromSlots();

        DspChain chain = new DspChain(1, cfg);
        assertTrue("Disabled Warmth at slot 0 must not trap enabled Presence before Compressor",
                chain.getRack().indexOf("comp") < chain.getRack().indexOf("tone"));
    }

    @Test
    public void syncSlotsFromLegacyFieldsPreservesDisabledSlotsAndTheirSettings() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.PODCAST_BROADCAST);
        DspChain.EffectSlot gateSlot = null;
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.ROOM_GATE) gateSlot = s;
        }
        assertNotNull(gateSlot);
        gateSlot.enabled = false;
        gateSlot.value = 65;
        gateSlot.wetDry = 0.4f;
        gateSlot.extraParams.put("hold_ms", 120);
        cfg.syncLegacyFieldsFromSlots();

        cfg.noisePct = 70;
        cfg.onManualAdjustment();

        DspChain.EffectSlot preserved = null;
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s.type == DspChain.EffectType.ROOM_GATE) preserved = s;
        }
        assertNotNull("Disabled ROOM_GATE slot was dropped by syncSlotsFromLegacyFields",
                preserved);
        assertFalse(preserved.enabled);
        assertEquals(65, preserved.value);
        assertEquals(0.4f, preserved.wetDry, 0.0f);
        assertEquals(Integer.valueOf(120), preserved.extraParams.get("hold_ms"));
    }

    @Test
    public void setSampleRateRetunesToneStage() {
        FxRackChain tone48 = new FxRackChain(1);
        ProToneProcessor tp48 = new ProToneProcessor();
        tp48.setLegacyAmounts(70, 70);
        tone48.addProcessor(tp48);
        tone48.setSampleRate(48000);

        FxRackChain tone96 = new FxRackChain(1);
        ProToneProcessor tp96 = new ProToneProcessor();
        tp96.setLegacyAmounts(70, 70);
        tone96.addProcessor(tp96);
        tone96.setSampleRate(96000);

        float[] tA = tone(256, 1, 0.6f, 0.04f);
        float[] tB = tA.clone();
        tone48.processInPlace(tA, 256);
        tone96.processInPlace(tB, 256);
        boolean toneDiff = false;
        for (int i = 0; i < tA.length; i++) {
            if (Math.abs(tA[i] - tB[i]) > 1e-4f) {
                toneDiff = true;
                break;
            }
        }
        assertTrue("ProToneProcessor ignored sample rate change", toneDiff);

    }

    @Test
    public void prototypeProcessorCacheAndSlotSubtitleFormattingWork() {
        AudioProcessor p1 = FxRackChain.getPrototypeProcessor("noise");
        AudioProcessor p2 = FxRackChain.getPrototypeProcessor("noise");
        assertNotNull(p1);
        assertTrue("getPrototypeProcessor should cache instances", p1 == p2);

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        DspChain.EffectSlot slot = cfg.slots.get(1); // NOISE_SUPPRESSION
        assertEquals("Enabled • Intensity: 50%  ▾",
                StudioFragment.formatSlotSubtitle(slot, cfg));
        slot.wetDry = 0.5f;
        assertEquals("Enabled • Intensity: 50% • Mix 50%  ▾",
                StudioFragment.formatSlotSubtitle(slot, cfg));
    }

    @Test
    public void decibelParameterFormatDisplayPreservesHundredthsPrecision() {
        FxParameter db = FxParameter.dbParam("gain", "Gain", -24f, 24f, 0f);
        db.setCurrentRaw(-350);
        assertEquals("-3.5 dB", db.formatDisplay());
        db.setCurrentRaw(-5);
        assertEquals("-0.05 dB", db.formatDisplay());
        db.setCurrentRaw(125);
        assertEquals("+1.25 dB", db.formatDisplay());
    }

    @Test
    public void toneTargetsFollowLegacyAmountsChangedAfterPrepare() {
        ProToneProcessor tone = new ProToneProcessor();
        tone.prepare(48000, 1);
        tone.setLegacyAmounts(0, 90);

        float[] high = sineAt(4096, 0.2f, 5000.0);
        double before = rmsTail(high);
        tone.processInPlace(high, 4096, 1);
        double after = rmsTail(high);
        assertTrue("post-prepare presence change was ignored: "
                        + before + " -> " + after,
                after > before * 1.8);
    }

    @Test
    public void splitPresenceStillRunsWhenMiddleEqIsInaudible() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY).value = 40;
        DspChain.EffectSlot eq =
                cfg.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6);
        eq.value = 0;
        cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE).value = 90;
        cfg.syncLegacyFieldsFromSlots();

        DspChain chain = new DspChain(1, cfg);
        assertEquals("eq6", chain.getSplitToneAfterId());
        ParametricEq6BandProcessor eqProc =
                (ParametricEq6BandProcessor) chain.getRack().getProcessor(
                        chain.getRack().indexOf("eq6"));
        assertNotNull(eqProc);
        eqProc.getParam("b1f").setCurrentRaw(20);
        assertFalse("test must exercise the inaudible anchor path",
                eqProc.isAudible());

        float[] high = sineAt(2048, 0.2f, 5000.0);
        double before = rmsTail(high);
        chain.getRack().processInPlace(high, 2048);
        double after = rmsTail(high);
        assertTrue("second tone stage skipped across an inaudible eq6: "
                        + before + " -> " + after,
                after > before * 1.15);
    }

    @Test
    public void splitPresenceAfterLimiterStillHonoursSafetyCeiling() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.addOrFocusSlot(DspChain.EffectType.WARMTH_BODY).value = 100;
        cfg.addOrFocusSlot(DspChain.EffectType.BRICKWALL_LIMITER).value = 50;
        cfg.addOrFocusSlot(DspChain.EffectType.PARAMETRIC_EQ6).value = 0;
        cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE).value = 100;
        cfg.syncLegacyFieldsFromSlots();

        DspChain chain = new DspChain(1, cfg);
        assertEquals("eq6", chain.getSplitToneAfterId());
        ParametricEq6BandProcessor eqProc =
                (ParametricEq6BandProcessor) chain.getRack().getProcessor(
                        chain.getRack().indexOf("eq6"));
        assertNotNull(eqProc);
        eqProc.getParam("b1f").setCurrentRaw(20);
        assertFalse(eqProc.isAudible());

        float[] hot = sineAt(2048, 0.95f, 5000.0);
        chain.getRack().processInPlace(hot, 2048);
        for (int i = 0; i < hot.length; i++) {
            assertTrue("split second stage bypassed safety ceiling at "
                            + i + ": " + hot[i],
                    Math.abs(hot[i]) < 0.99f);
        }
    }
}
