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
 * Stage 2 modular-rack contract: the refactored {@link FxRackChain} must
 * reproduce the legacy {@code DspChain} engine bit-for-bit, reorder/bypass/wet
 * -dry must behave exactly as documented, and multi-parameter state must
 * survive JSON and SharedPreferences round-trips.
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

    // ---------------------------------------------------------------- parity

    @Test
    public void rackIsBitIdenticalToLegacyEngineOnEveryStage() {
        // Podcast preset exercises all 8 effect types (warmth+presence share
        // the tone stage), so every legacy stage runs in one pass.
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.PODCAST_BROADCAST);
        cfg.addOrFocusSlot(DspChain.EffectType.VOCAL_PRESENCE);
        // 4801 frames is not divisible by the 16-frame stage block, so the
        // final partial block (1 frame) is compared too.
        final int frames = 4801;
        final int ch = 2;

        LegacyRef ref = new LegacyRef();
        ref.updateConfig(cfg);
        float[] a = tone(frames, ch, 0.85f, 0.017f);
        // Sprinkle sibilance so the de-esser sidechain actually engages.
        for (int f = 0; f < frames; f += 7) {
            float s = (float) Math.sin(f * 1.9) * 0.6f;
            a[f * ch] = s;
            a[f * ch + 1] = s;
        }
        float[] b = a.clone();
        ref.process(a, frames, ch);

        DspChain chain = new DspChain(ch, cfg);
        assertTrue(chain.isActive());
        chain.processInPlace(b, frames);

        assertEquals("different length", a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals("bit divergence @" + i,
                    Float.floatToRawIntBits(a[i]),
                    Float.floatToRawIntBits(b[i]));
        }
    }

    @Test
    public void rackMatchesLegacyAfterLiveConfigUpdate() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.VOICE_POLISH);
        final int frames = 1023;
        final int ch = 2;

        LegacyRef ref = new LegacyRef();
        ref.updateConfig(cfg);
        DspChain chain = new DspChain(ch, cfg);

        float[] a = tone(frames, ch, 0.7f, 0.031f);
        float[] b = a.clone();
        ref.process(a, frames, ch);
        chain.processInPlace(b, frames);
        for (int i = 0; i < a.length; i++) {
            assertEquals("block 1 diverged @" + i,
                    Float.floatToRawIntBits(a[i]),
                    Float.floatToRawIntBits(b[i]));
        }

        // Live edit: push a new cutoff/amounts into BOTH engines mid-stream
        // (this is what the FX panel's TeleValue scrub does).
        DspChain.Config live = new DspChain.Config();
        live.applyPreset(DspChain.Preset.NOISY_ROOM);
        ref.updateConfig(live);
        chain.updateConfig(live);

        float[] c = tone(frames, ch, 0.4f, 0.021f);
        float[] d = c.clone();
        ref.process(c, frames, ch);
        chain.processInPlace(d, frames);
        for (int i = 0; i < c.length; i++) {
            assertEquals("block 2 diverged @" + i,
                    Float.floatToRawIntBits(c[i]),
                    Float.floatToRawIntBits(d[i]));
        }
    }

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
        rack.addProcessor(new LegacyHpfProcessor());
        rack.addProcessor(new LegacyNoiseProcessor());
        rack.addProcessor(new LegacyGateProcessor());
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

        LegacyNoiseProcessor noise = new LegacyNoiseProcessor();
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

        LegacyToneProcessor wetProc = new LegacyToneProcessor();
        wetProc.setLegacyAmounts(70, 60);
        FxRackChain wetRack = new FxRackChain(2);
        wetRack.addProcessor(wetProc);
        float[] wet = input.clone();
        wetRack.processInPlace(wet, frames);

        LegacyToneProcessor halfProc = new LegacyToneProcessor();
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
        LegacyToneProcessor offProc = new LegacyToneProcessor();
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

    // ----------------------------------------------------- legacy reference

    /** Standalone copy of the pre-Stage-2 engine, for bit-parity checks. */
    private static final class LegacyRef {

// ==== VERBATIM COPY of the pre-Stage-2 DspChain engine (commit 2c98087) ==== // ==== kept in the test only so rack output is proven bit-identical. ====

    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;

    private volatile boolean hpfOn;
    private volatile int currentHpfHz = -1;
    private volatile float noiseAmt;
    private volatile float deEssAmt;
    private volatile float compAmt;
    private volatile float presenceAmt;
    private volatile float warmthAmt;
    private volatile float gateAmt;
    private volatile float limiterAmt;

    // Stage 1: Dynamic Butterworth High-Pass Filter (40..160 Hz)
    private volatile float hpfB0, hpfB1, hpfB2, hpfA1, hpfA2;
    private final float[] hpfZ1 = new float[2];
    private final float[] hpfZ2 = new float[2];

    // Stage 2: 3-Band Minimum-Statistics Adaptive Noise Suppressor state
    private static final float NS_ALPHA_LOW = 0.02839f; // 220 Hz 1-pole crossover
    private static final float NS_ALPHA_HIGH = 0.3919f; // 3800 Hz 1-pole crossover
    private final float[] nsLp1 = new float[2];
    private final float[] nsLp2 = new float[2];
    private final float[] nsEnv = {1e-5f, 1e-5f, 1e-5f};
    private final float[] nsFloor = {1e-4f, 1e-4f, 1e-4f};
    private boolean nsSeeded = false;
    private final float[] nsGain = {1.0f, 1.0f, 1.0f};
    private final float[] nsTargetGain = {1.0f, 1.0f, 1.0f};
    private final float[] nsBandAcc = new float[3];
    private final float[] ScratchLow = new float[DECIM * 2];
    private final float[] ScratchMid = new float[DECIM * 2];
    private final float[] ScratchHigh = new float[DECIM * 2];

    // Stage 3: Dedicated Downward Expander / Room Gate state
    private float gateEnv = 0.0f;
    private float gateGainCur = 1.0f;
    private float gateGainTarget = 1.0f;

    // Stage 4: Split-Band Relative Sidechain De-Esser (6800 Hz bandpass, Q=1.5)
    private final float deB0, deB1, deB2, deA1, deA2;
    private final float[] deZ1 = new float[2];
    private final float[] deZ2 = new float[2];
    private float deSibEnv = 1e-7f;
    private float deFullEnv = 1e-6f;
    private float deCutCur = 0.0f;
    private float deCutTarget = 0.0f;
    private final float[] ScratchSib = new float[DECIM * 2];

    // Stage 5: Warmth (180 Hz) & Vocal Presence (3500 Hz) state
    private final float[] warmLp = new float[2];
    private final float[] presLp = new float[2];
    private static final float WARM_ALPHA = 0.0233f;  // ~180 Hz
    private static final float PRES_ALPHA = 0.3678f;  // ~3500 Hz

    // Stage 6: Soft-Knee Compressor + Auto-Makeup
    private float compEnvDb = -60.0f;
    private float compGainCur = 1.0f;
    private float compGainTarget = 1.0f;

    LegacyRef() {
        computeHpfCoefficients(80);

        double dw0 = 2.0 * Math.PI * 6800.0 / DspChain.SAMPLE_RATE;
        double dCos = Math.cos(dw0);
        double dAlpha = Math.sin(dw0) / (2.0 * 1.5);
        double da0Inv = 1.0 / (1.0 + dAlpha);
        this.deB0 = (float) (dAlpha * da0Inv);
        this.deB1 = 0.0f;
        this.deB2 = (float) (-dAlpha * da0Inv);
        this.deA1 = (float) (-2.0 * dCos * da0Inv);
        this.deA2 = (float) ((1.0 - dAlpha) * da0Inv);
    }

    private void computeHpfCoefficients(int cutoffHz) {
        int hz = Math.max(40, Math.min(160, cutoffHz));
        if (hz == this.currentHpfHz) return;
        this.currentHpfHz = hz;
        double w0 = 2.0 * Math.PI * hz / DspChain.SAMPLE_RATE;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * 0.70710678);
        double a0Inv = 1.0 / (1.0 + alpha);
        this.hpfB0 = (float) ((1.0 + cosW0) * 0.5 * a0Inv);
        this.hpfB1 = (float) (-(1.0 + cosW0) * a0Inv);
        this.hpfB2 = this.hpfB0;
        this.hpfA1 = (float) (-2.0 * cosW0 * a0Inv);
        this.hpfA2 = (float) ((1.0 - alpha) * a0Inv);
    }

    public void updateConfig(DspChain.Config cfg) {
        if (cfg == null) {
            this.hpfOn = false;
            this.noiseAmt = 0f;
            this.deEssAmt = 0f;
            this.compAmt = 0f;
            this.presenceAmt = 0f;
            this.warmthAmt = 0f;
            this.gateAmt = 0f;
            this.limiterAmt = 0f;
            return;
        }
        cfg.syncLegacyFieldsFromSlots();
        this.hpfOn = cfg.hpfEnabled;
        computeHpfCoefficients(cfg.hpfCutoffHz);
        this.noiseAmt = cfg.noisePct / 100.0f;
        this.deEssAmt = cfg.deEssPct / 100.0f;
        this.compAmt = cfg.compPct / 100.0f;
        this.presenceAmt = cfg.presencePct / 100.0f;
        this.warmthAmt = cfg.warmthPct / 100.0f;
        this.gateAmt = cfg.gatePct / 100.0f;
        this.limiterAmt = cfg.limiterPct / 100.0f;
    }

    public boolean isActive() {
        return hpfOn || noiseAmt > 0.001f || deEssAmt > 0.001f || compAmt > 0.001f
                || presenceAmt > 0.001f || warmthAmt > 0.001f
                || gateAmt > 0.001f || limiterAmt > 0.001f;
    }

    public void reset() {
        for (int c = 0; c < 2; c++) {
            hpfZ1[c] = hpfZ2[c] = 0f;
            nsLp1[c] = nsLp2[c] = 0f;
            deZ1[c] = deZ2[c] = 0f;
            warmLp[c] = presLp[c] = 0f;
        }
        for (int b = 0; b < 3; b++) {
            nsEnv[b] = 1e-5f;
            nsFloor[b] = 1e-4f;
            nsGain[b] = nsTargetGain[b] = 1.0f;
        }
        nsSeeded = false;
        gateEnv = 0f;
        gateGainCur = gateGainTarget = 1.0f;
        deSibEnv = 1e-7f;
        deFullEnv = 1e-6f;
        deCutCur = deCutTarget = 0f;
        compEnvDb = -60.0f;
        compGainCur = compGainTarget = 1.0f;
    }

    void process(float[] buf, int frames, int channels) {
        final int ch = channels;
        final boolean doHpf = this.hpfOn;
        final float nAmt = this.noiseAmt;
        final float gAmt = this.gateAmt;
        final float dAmt = this.deEssAmt;
        final float cAmt = this.compAmt;
        final float wAmt = this.warmthAmt;
        final float pAmt = this.presenceAmt;
        final float lAmt = this.limiterAmt;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 1: Dynamic Butterworth High-Pass Filter (40..160 Hz)
            if (doHpf) {
                final float b0 = this.hpfB0, b1 = this.hpfB1, b2 = this.hpfB2;
                final float a1 = this.hpfA1, a2 = this.hpfA2;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        float y = b0 * x + hpfZ1[c];
                        hpfZ1[c] = b1 * x - a1 * y + hpfZ2[c];
                        hpfZ2[c] = b2 * x - a2 * y;
                        buf[idx + c] = y;
                    }
                }
            }

            // Stage 2: 3-Band Adaptive Noise Suppressor
            if (nAmt > 0.001f) {
                nsBandAcc[0] = nsBandAcc[1] = nsBandAcc[2] = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        nsLp1[c] += NS_ALPHA_LOW * (x - nsLp1[c]);
                        nsLp2[c] += NS_ALPHA_HIGH * (x - nsLp2[c]);
                        float bLow = nsLp1[c];
                        float bMid = nsLp2[c] - bLow;
                        float bHigh = x - nsLp2[c];
                        ScratchLow[sIdx + c] = bLow;
                        ScratchMid[sIdx + c] = bMid;
                        ScratchHigh[sIdx + c] = bHigh;
                        nsBandAcc[0] += bLow * bLow;
                        nsBandAcc[1] += bMid * bMid;
                        nsBandAcc[2] += bHigh * bHigh;
                    }
                }
                float invSamples = 1.0f / (blk * ch);
                if (!nsSeeded) {
                    nsSeeded = true;
                    for (int b = 0; b < 3; b++) {
                        float e = Math.max(1e-7f, nsBandAcc[b] * invSamples);
                        nsEnv[b] = e;
                        nsFloor[b] = Math.max(1e-6f, e * 0.35f);
                    }
                }
                for (int b = 0; b < 3; b++) {
                    float e = nsBandAcc[b] * invSamples;
                    nsEnv[b] = 0.60f * nsEnv[b] + 0.40f * e;
                    if (nsEnv[b] < nsFloor[b]) {
                        nsFloor[b] = 0.75f * nsFloor[b] + 0.25f * nsEnv[b];
                    } else {
                        nsFloor[b] = nsFloor[b] * 1.0006f + 1e-9f;
                    }
                    float snr = nsEnv[b] / (nsFloor[b] + 1e-10f);
                    float overSub = 1.0f + 3.8f * nAmt;
                    float g = (snr - overSub) / Math.max(snr, 1e-6f);
                    float floorG = (b == 1)
                            ? (1.0f - 0.85f * nAmt)
                            : (1.0f - 0.94f * nAmt);
                    g = Math.max(floorG, Math.min(1.0f, g));
                    float smooth = g > nsTargetGain[b] ? 0.40f
                            : (b == 2 ? 0.10f : 0.045f);
                    nsTargetGain[b] += smooth * (g - nsTargetGain[b]);
                }
                float dG0 = (nsTargetGain[0] - nsGain[0]) * INV_DECIM;
                float dG1 = (nsTargetGain[1] - nsGain[1]) * INV_DECIM;
                float dG2 = (nsTargetGain[2] - nsGain[2]) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    nsGain[0] += dG0;
                    nsGain[1] += dG1;
                    nsGain[2] += dG2;
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] = nsGain[0] * ScratchLow[sIdx + c]
                                + nsGain[1] * ScratchMid[sIdx + c]
                                + nsGain[2] * ScratchHigh[sIdx + c];
                    }
                }
            }

            // Stage 3: Dedicated Downward Expander / Room Gate
            if (gAmt > 0.001f) {
                float blkPeak = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(buf[idx + c]);
                        if (a > blkPeak) blkPeak = a;
                    }
                }
                float alphaG = blkPeak > gateEnv ? 0.35f : 0.015f;
                gateEnv += alphaG * (blkPeak - gateEnv);
                // Threshold ranges from -52 dBFS (0.0025) to -18 dBFS (0.125)
                float threshLin = DspChain.fastDbToLin(-52.0f + 34.0f * gAmt);
                if (gateEnv >= threshLin) {
                    gateGainTarget = 1.0f;
                } else {
                    float ratio = gateEnv / Math.max(1e-6f, threshLin);
                    float minFloor = 1.0f - 0.92f * gAmt;
                    gateGainTarget = Math.max(minFloor, ratio * ratio);
                }
                float dGate = (gateGainTarget - gateGainCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    gateGainCur += dGate;
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] *= gateGainCur;
                    }
                }
            }

            // Stage 4: Warmth (180 Hz Chest Body) & Vocal Presence (3.5 kHz Air)
            if (wAmt > 0.001f || pAmt > 0.001f) {
                float warmGain = wAmt * 1.45f; // up to +9 dB low-mid body
                float presGain = pAmt * 1.65f; // up to +10 dB air & articulation
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        warmLp[c] += WARM_ALPHA * (x - warmLp[c]);
                        presLp[c] += PRES_ALPHA * (x - presLp[c]);
                        float hi = x - presLp[c];
                        buf[idx + c] = x + warmGain * warmLp[c] + presGain * hi;
                    }
                }
            }

            // Stage 5: Split-Band Relative Sidechain De-Esser
            if (dAmt > 0.001f) {
                float sibEnergy = 0f;
                float fullEnergy = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        float sib = deB0 * x + deZ1[c];
                        deZ1[c] = deB1 * x - deA1 * sib + deZ2[c];
                        deZ2[c] = deB2 * x - deA2 * sib;
                        ScratchSib[sIdx + c] = sib;
                        sibEnergy += sib * sib;
                        fullEnergy += x * x;
                    }
                }
                float invN = 1.0f / (blk * ch);
                deSibEnv = 0.50f * deSibEnv + 0.50f * (sibEnergy * invN);
                deFullEnv = 0.70f * deFullEnv + 0.30f * (fullEnergy * invN);
                float ratioDb = DspChain.fastLinToDb(deSibEnv + 1e-10f) * 0.5f
                        - DspChain.fastLinToDb(deFullEnv + 1e-9f) * 0.5f;
                float threshDb = 1.0f - 11.5f * dAmt;
                float wantCut = 0f;
                if (ratioDb > threshDb && deFullEnv > 1e-7f) {
                    float overDb = Math.min(14.0f, (ratioDb - threshDb) * 1.4f);
                    wantCut = 1.0f - DspChain.fastDbToLin(-overDb);
                }
                float alphaDe = wantCut > deCutTarget ? 0.50f : 0.12f;
                deCutTarget += alphaDe * (wantCut - deCutTarget);
                float dCut = (deCutTarget - deCutCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    deCutCur += dCut;
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] -= deCutCur * ScratchSib[sIdx + c];
                    }
                }
            }

            // Stage 6: Soft-Knee Compressor + Auto-Makeup
            if (cAmt > 0.001f) {
                float peak = 0f;
                float sumSq = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(buf[idx + c]);
                        if (a > peak) peak = a;
                        sumSq += a * a;
                    }
                }
                float rms = (float) Math.sqrt(sumSq / (blk * ch));
                float det = Math.max(1e-6f, 0.7f * peak + 0.3f * rms);
                float inDb = DspChain.fastLinToDb(det);
                float alphaC = inDb > compEnvDb ? 0.10f : 0.004f;
                compEnvDb += alphaC * (inDb - compEnvDb);

                float threshDb = -10.0f - 18.0f * cAmt; // -10 .. -28 dBFS
                float ratio = 1.0f + 5.0f * cAmt;       // 1:1 .. 6:1
                float slope = 1.0f - (1.0f / ratio);
                float knee = 6.0f;
                float over = compEnvDb - threshDb;
                float grDb;
                if (2.0f * over < -knee) {
                    grDb = 0.0f;
                } else if (2.0f * Math.abs(over) <= knee) {
                    float t = over + knee * 0.5f;
                    grDb = -slope * (t * t) / (2.0f * knee);
                } else {
                    grDb = -slope * over;
                }
                float makeupDb = (-threshDb * slope) * 0.55f;
                compGainTarget = DspChain.fastDbToLin(grDb + makeupDb);
                float dG = (compGainTarget - compGainCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    compGainCur += dG;
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] *= compGainCur;
                    }
                }
            }

            // Stage 7: Dedicated Brickwall Ceiling Limiter (or safety limiter when active)
            if (lAmt > 0.001f || cAmt > 0.001f || wAmt > 0.001f || pAmt > 0.001f) {
                float driveLin = lAmt > 0.001f ? DspChain.fastDbToLin(lAmt * 10.5f) : 1.0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float y = buf[idx + c] * driveLin;
                        float ay = Math.abs(y);
                        if (ay > 0.82f) {
                            float d = ay - 0.82f;
                            float lim = 0.82f + 0.165f * (d / (0.165f + d));
                            y = y >= 0f ? lim : -lim;
                        }
                        buf[idx + c] = y;
                    }
                }
            }

            offset += blk;
        }
    }
    }
}
