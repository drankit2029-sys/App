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
 * Professional FX suite: response checks for the HPF, de-esser, gate, tone,
 * compressor and limiter, plus latency, preset-safety and parameter
 * persistence contracts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProFxTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static float rms(float[] buf, int from, int to, int ch) {
        double sum = 0;
        int n = 0;
        for (int f = from; f < to; f++) {
            for (int c = 0; c < ch; c++) {
                float v = buf[f * ch + c];
                sum += v * v;
                n++;
            }
        }
        return n == 0 ? 0f : (float) Math.sqrt(sum / n);
    }

    private static double goertzel(float[] buf, int from, int len, int ch,
                                   int chan, double hz) {
        double w = 2.0 * Math.PI * hz / SR;
        double coeff = 2.0 * Math.cos(w);
        double s0 = 0, s1 = 0, s2 = 0;
        for (int i = from; i < from + len; i++) {
            s0 = buf[i * ch + chan] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(w);
        double imag = s2 * Math.sin(w);
        return Math.hypot(real, imag) / (len / 2.0);
    }

    @Test
    public void hpfProOffersSteeperSlopeAndPassesTheBand() {
        int frames = 8192;
        float[] rumble12 = sine(frames, 1, 30, 0.5f);
        float[] rumble24 = rumble12.clone();
        ProHpfProcessor p12 = new ProHpfProcessor();
        p12.getParam("cutoff").setCurrentRaw(80);
        p12.getParam("slope").setCurrentRaw(0);
        p12.prepare(SR, 1);
        p12.processInPlace(rumble12, frames, 1);

        ProHpfProcessor p24 = new ProHpfProcessor();
        p24.getParam("cutoff").setCurrentRaw(80);
        p24.getParam("slope").setCurrentRaw(1);
        p24.prepare(SR, 1);
        p24.processInPlace(rumble24, frames, 1);

        float a12 = rms(rumble12, 4096, frames, 1);
        float a24 = rms(rumble24, 4096, frames, 1);
        assertTrue("24 dB slope should reject 30 Hz much harder",
                a24 < a12 * 0.45f);

        float[] voice = sine(frames, 1, 1000, 0.5f);
        p24.reset();
        p24.processInPlace(voice, frames, 1);
        assertTrue("1 kHz should pass the 80 Hz low cut",
                rms(voice, 4096, frames, 1) > 0.30f);
    }

    @Test
    public void deEsserProCutsSibilanceAndKeepsTheLowBand() {
        int frames = 12000;
        int ch = 1;
        float[] in = new float[frames];
        for (int f = 0; f < frames; f++) {
            in[f] = (float) (0.30 * Math.sin(2 * Math.PI * 500 * f / SR)
                    + 0.30 * Math.sin(2 * Math.PI * 7000 * f / SR));
        }
        float[] out = in.clone();
        ProDeEsserProcessor deess = new ProDeEsserProcessor();
        deess.setLegacyAmount(100);
        deess.prepare(SR, ch);
        deess.processInPlace(out, frames, ch);

        int from = frames - 4096;
        double inHigh = goertzel(in, from, 4096, ch, 0, 7000);
        double outHigh = goertzel(out, from, 4096, ch, 0, 7000);
        double inLow = goertzel(in, from, 4096, ch, 0, 500);
        double outLow = goertzel(out, from, 4096, ch, 0, 500);

        assertTrue("7 kHz sibilance should drop substantially",
                outHigh < inHigh * 0.45);
        assertTrue("500 Hz body should survive the split-band de-esser",
                outLow > inLow * 0.90);
    }

    @Test
    public void gateProClosesAfterTheHoldAndReopens() {
        int ch = 1;
        int toneLen = 4800;
        int silenceLen = 12000;
        int frames = toneLen * 2 + silenceLen;
        float[] buf = new float[frames];
        for (int f = 0; f < toneLen; f++) {
            buf[f] = (float) Math.sin(2 * Math.PI * 440 * f / SR) * 0.4f;
        }
        for (int f = toneLen; f < toneLen + silenceLen; f++) {
            buf[f] = (float) Math.sin(2 * Math.PI * 440 * f / SR) * 0.01f;
        }
        for (int f = toneLen + silenceLen; f < frames; f++) {
            buf[f] = (float) Math.sin(2 * Math.PI * 440 * f / SR) * 0.4f;
        }
        ProGateProcessor gate = new ProGateProcessor();
        gate.setLegacyAmount(70);
        gate.getParam("gate_range").setCurrentRaw(30);
        gate.getParam("gate_hold").setCurrentRaw(40);
        gate.getParam("gate_release").setCurrentRaw(160);
        gate.prepare(SR, ch);
        gate.processInPlace(buf, frames, ch);

        float duringTone = rms(buf, toneLen - 2048, toneLen, ch);
        float lateSilence = rms(buf, toneLen + silenceLen - 2048,
                toneLen + silenceLen, ch);
        float reopened = rms(buf, frames - 2048, frames, ch);
        assertTrue("gate should pass the opening phrase", duringTone > 0.20f);
        assertTrue("gate should close on the silent gap",
                lateSilence < 0.02f);
        assertTrue("gate should reopen for the next phrase", reopened > 0.20f);
    }

    @Test
    public void toneProShelvesTargetTheirOwnBand() {
        int frames = 8192;
        int ch = 1;

        float[] warm = sine(frames, ch, 120, 0.4f);
        float[] warmRef = warm.clone();
        ProToneProcessor warmProc = new ProToneProcessor();
        warmProc.setLegacyAmounts(100, 0);
        warmProc.prepare(SR, ch);
        warmProc.processInPlace(warm, frames, ch);

        float[] pres = sine(frames, ch, 5500, 0.4f);
        float[] presRef = pres.clone();
        ProToneProcessor presProc = new ProToneProcessor();
        presProc.setLegacyAmounts(0, 100);
        presProc.prepare(SR, ch);
        presProc.processInPlace(pres, frames, ch);

        float warmRatio = rms(warm, 4096, frames, ch)
                / rms(warmRef, 4096, frames, ch);
        float presRatio = rms(pres, 4096, frames, ch)
                / rms(presRef, 4096, frames, ch);

        assertTrue("warmth shelf should lift 120 Hz, ratio=" + warmRatio,
                warmRatio > 1.45f && warmRatio < 3.2f);
        assertTrue("presence shelf should lift 5.5 kHz, ratio=" + presRatio,
                presRatio > 1.45f && presRatio < 3.6f);
    }

    @Test
    public void compressorProNarrowsDynamicRange() {
        int frames = 12000;
        int ch = 1;
        ProCompressorProcessor comp = new ProCompressorProcessor();
        comp.setLegacyAmount(80);
        comp.getParam("comp_ratio").setCurrentRaw(600);
        comp.prepare(SR, ch);

        float[] loud = sine(frames, ch, 440, 0.8f);
        comp.processInPlace(loud, frames, ch);
        comp.reset();
        float[] quiet = sine(frames, ch, 440, 0.05f);
        comp.processInPlace(quiet, frames, ch);

        float loudGain = rms(loud, 4096, frames, ch) / 0.5657f;
        float quietGain = rms(quiet, 4096, frames, ch) / 0.03536f;
        assertTrue("loud gain should be lower than quiet gain ("
                        + loudGain + " vs " + quietGain + ")",
                loudGain < quietGain * 0.60f);
        assertTrue("compressor output should stay finite and bounded",
                rms(loud, 4096, frames, ch) < 1.0f);
    }

    @Test
    public void limiterProHoldsItsCeilingAndAddsDrive() {
        int frames = 8192;
        int ch = 1;
        float[] lowDrive = sine(frames, ch, 220, 0.5f);
        float[] highDrive = lowDrive.clone();

        ProLimiterProcessor lim50 = new ProLimiterProcessor();
        lim50.setLegacyState(50, false);
        lim50.prepare(SR, ch);
        lim50.processInPlace(lowDrive, frames, ch);

        ProLimiterProcessor lim90 = new ProLimiterProcessor();
        lim90.setLegacyState(90, false);
        lim90.prepare(SR, ch);
        lim90.processInPlace(highDrive, frames, ch);

        float[] dry = sine(frames, ch, 220, 0.5f);
        ProLimiterProcessor limDry = new ProLimiterProcessor();
        limDry.setLegacyState(0, false);
        limDry.prepare(SR, ch);
        limDry.processInPlace(dry, frames, ch);

        for (int i = 0; i < frames; i++) {
            assertTrue("limiter exceeded the ceiling",
                    Math.abs(lowDrive[i]) <= 0.98f);
            assertTrue("limiter exceeded the ceiling",
                    Math.abs(highDrive[i]) <= 0.98f);
        }
        assertTrue("drive should raise the output level above the dry signal",
                rms(highDrive, 4096, frames, ch)
                        > rms(dry, 4096, frames, ch) * 1.4f);

        float[] quiet = sine(frames, ch, 220, 0.5f);
        ProLimiterProcessor limCeiling = new ProLimiterProcessor();
        limCeiling.setLegacyState(100, false);
        limCeiling.getParam("limiter_ceiling").setCurrentRaw(-600);
        limCeiling.prepare(SR, ch);
        limCeiling.processInPlace(quiet, frames, ch);
        for (int i = 0; i < frames; i++) {
            assertTrue("custom -6 dB ceiling was exceeded",
                    Math.abs(quiet[i]) <= 0.505f);
        }
    }

    @Test
    public void proStagesAreLatencyFree() {
        AudioProcessor[] stages = {
                new ProHpfProcessor(), new ProDeEsserProcessor(),
                new ProGateProcessor(), new ProToneProcessor(),
                new ProCompressorProcessor(), new ProLimiterProcessor()
        };
        for (AudioProcessor p : stages) {
            p.prepare(SR, 2);
            assertEquals(p.getId() + " should be latency free",
                    0, p.getLatencyFrames());
        }
    }

    @Test
    public void secondaryControlsPersistThroughRackJson() throws Exception {
        FxRackChain rack = FxRackChain.fromLegacyConfig(2,
                DspChain.Config.voicePolish());
        rack.getProcessor(rack.indexOf("hpf"))
                .getParam("slope").setCurrentRaw(0);
        rack.getProcessor(rack.indexOf("gate"))
                .getParam("gate_hold").setCurrentRaw(120);
        rack.getProcessor(rack.indexOf("tone"))
                .getParam("warmth_freq").setCurrentRaw(220);
        rack.getProcessor(rack.indexOf("deess"))
                .getParam("deess_mode").setCurrentRaw(1);
        rack.getProcessor(rack.indexOf("comp"))
                .getParam("comp_ratio").setCurrentRaw(350);
        rack.getProcessor(rack.indexOf("limiter"))
                .getParam("limiter_ceiling").setCurrentRaw(-300);

        JSONObject json = rack.toJson();
        FxRackChain back = FxRackChain.fromJson(2, json);
        assertEquals(0, back.getProcessor(back.indexOf("hpf"))
                .getParam("slope").getCurrentRaw());
        assertEquals(120, back.getProcessor(back.indexOf("gate"))
                .getParam("gate_hold").getCurrentRaw());
        assertEquals(220, back.getProcessor(back.indexOf("tone"))
                .getParam("warmth_freq").getCurrentRaw());
        assertEquals(1, back.getProcessor(back.indexOf("deess"))
                .getParam("deess_mode").getCurrentRaw());
        assertEquals(350, back.getProcessor(back.indexOf("comp"))
                .getParam("comp_ratio").getCurrentRaw());
        assertEquals(-300, back.getProcessor(back.indexOf("limiter"))
                .getParam("limiter_ceiling").getCurrentRaw());
    }

    @Test
    public void everyPresetStaysFiniteAndBounded() {
        for (DspChain.Preset preset : DspChain.Preset.values()) {
            DspChain.Config cfg = new DspChain.Config();
            cfg.applyPreset(preset);
            DspChain chain = new DspChain(2, cfg);
            int frames = 4800;
            float[] buf = new float[frames * 2];
            long seed = 12345;
            for (int f = 0; f < frames; f++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                float noise = ((seed >>> 40) / (float) (1 << 24) - 0.5f) * 0.3f;
                float s = (float) (0.4 * Math.sin(f * 0.05)
                        + 0.25 * Math.sin(f * 0.21) + noise);
                buf[f * 2] = s;
                buf[f * 2 + 1] = -s;
            }
            chain.processInPlace(buf, frames);
            for (int i = 0; i < buf.length; i++) {
                assertTrue(preset + " produced a non-finite sample",
                        Float.isFinite(buf[i]));
                assertTrue(preset + " exceeded the ceiling @" + i,
                        Math.abs(buf[i]) <= 0.99f);
            }
        }
    }

    @Test
    public void rackLatencyStillCountsOnlyTheSpectralStage() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        cfg.addOrFocusSlot(DspChain.EffectType.NOISE_SUPPRESSION);
        cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        cfg.addOrFocusSlot(DspChain.EffectType.BRICKWALL_LIMITER);
        DspChain chain = new DspChain(1, cfg);
        assertEquals(new NoiseSuppressionProcessor().getLatencyFrames(),
                chain.getLatencyFrames());
        assertTrue(chain.getLatencyFrames() > 0);
        assertNotNull(chain.getRack());
    }
}
