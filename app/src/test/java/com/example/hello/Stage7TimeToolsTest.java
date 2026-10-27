package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Stage 7 suite: WSOLA duration scaling without pitch drift, pitch shifting
 * without a duration change, silence truncation, fades, the timeline tools,
 * the generator and the analysers.
 */
public class Stage7TimeToolsTest {

    private static final int SR = 48000;

    private static float[] sine(int frames, int ch, double hz, float amp) {
        float[] out = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float s = (float) (Math.sin(2.0 * Math.PI * hz * f / SR) * amp);
            for (int c = 0; c < ch; c++) out[f * ch + c] = s;
        }
        return out;
    }

    private static double goertzel(float[] buf, int from, int len, double hz) {
        double w = 2.0 * Math.PI * hz / SR;
        double coeff = 2.0 * Math.cos(w);
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        for (int i = from; i < from + len && i < buf.length; i++) {
            s0 = buf[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(w);
        double imag = s2 * Math.sin(w);
        return Math.hypot(real, imag) / (len / 2.0);
    }

    @Test
    public void wsolaChangesDurationWithoutDriftingThePitch() {
        int frames = SR / 2;
        float[] buf = sine(frames, 1, 440, 0.5f);
        WsolaTimePitchProcessor wsola = new WsolaTimePitchProcessor(SR);
        float[] stretched = wsola.timeStretch(buf, frames, 1, 2.0);
        int outFrames = stretched.length;
        assertTrue("Duration did not double: " + outFrames,
                Math.abs(outFrames - frames * 2) < frames / 10);
        // The fundamental must still be at 440 Hz.
        double fundamental = goertzel(stretched, 2000, 8000, 440);
        double neighbour = goertzel(stretched, 2000, 8000, 880);
        assertTrue("Pitch drifted: 440 Hz at " + fundamental
                + " vs 880 Hz at " + neighbour, fundamental > neighbour * 2.0);
    }

    @Test
    public void changePitchShiftsTheFundamentalAndKeepsTheDuration() {
        int frames = SR / 2;
        float[] buf = sine(frames, 1, 440, 0.5f);
        ChangePitchProcessor p = new ChangePitchProcessor();
        p.getParam("pitch_semitones").setFloat(12f); // one octave up
        p.prepare(SR, 1);
        float[] out = p.processOfflineRegion(buf, frames, 1);
        assertEquals("Duration changed", frames, out.length);
        double up = goertzel(out, 2000, 8000, 880);
        double original = goertzel(out, 2000, 8000, 440);
        assertTrue("Pitch did not shift: 880 Hz at " + up
                + " vs 440 Hz at " + original, up > original * 2.0);
    }

    @Test
    public void changeTempoScalesTheDuration() {
        int frames = SR / 4;
        float[] buf = sine(frames, 1, 440, 0.5f);
        ChangeTempoProcessor p = new ChangeTempoProcessor();
        p.getParam("tempo_percent").setFloat(100f); // twice as fast -> half as long
        p.prepare(SR, 1);
        float[] out = p.processOfflineRegion(buf, frames, 1);
        int outFrames = out.length;
        assertTrue("Duration did not halve: " + outFrames,
                Math.abs(outFrames - frames / 2) < frames / 10);
    }

    @Test
    public void changeSpeedAndPitchChangesBoth() {
        int frames = SR / 4;
        float[] buf = sine(frames, 1, 440, 0.5f);
        ChangeSpeedAndPitchProcessor p = new ChangeSpeedAndPitchProcessor();
        p.getParam("speed_mult").setFloat(2f);
        p.prepare(SR, 1);
        float[] out = p.processOfflineRegion(buf, frames, 1);
        int outFrames = out.length;
        assertTrue("Duration did not halve: " + outFrames,
                Math.abs(outFrames - frames / 2) < frames / 10);
        double up = goertzel(out, 2000, 8000, 880);
        double original = goertzel(out, 2000, 8000, 440);
        assertTrue("Pitch did not shift", up > original * 2.0);
    }

    @Test
    public void truncateSilenceRemovesThePause() {
        int frames = SR;
        float[] buf = new float[frames];
        // 0.5 s of tone, 0.5 s of silence.
        for (int f = 0; f < SR / 2; f++) {
            buf[f] = (float) (Math.sin(2.0 * Math.PI * 440 * f / SR) * 0.5);
        }
        TruncateSilenceProcessor p = new TruncateSilenceProcessor();
        p.getParam("ts_thresh").setFloat(-40f);
        p.getParam("ts_min").setCurrentRaw(400);
        p.getParam("ts_action").setEnumIndex(0);
        p.getParam("ts_trunc").setCurrentRaw(100);
        p.prepare(SR, 1);
        float[] out = p.processOfflineRegion(buf, frames, 1);
        int outFrames = out.length;
        assertTrue("Silence was not truncated: " + outFrames,
                outFrames < frames * 0.9);
        assertTrue("Too much was removed: " + outFrames,
                outFrames > frames * 0.5);
    }

    @Test
    public void fadeInRampsFromSilenceToFullLevel() {
        int frames = 4800;
        float[] buf = sine(frames, 1, 440, 0.8f);
        FadeProcessor p = new FadeProcessor();
        p.getParam("fade_type").setEnumIndex(0); // Fade In
        p.getParam("fade_len").setCurrentRaw(50);
        p.prepare(SR, 1);
        float[] out = p.processOfflineRegion(buf, frames, 1);
        assertTrue("Fade did not start at silence",
                Math.abs(out[0]) < 0.01f);
        // The fade covers the first half of the region, so the level must
        // reach full strength by the end of the fade window.
        int fadeEnd = frames / 2;
        float peakAtFadeEnd = 0f;
        for (int f = fadeEnd - 200; f < fadeEnd; f++) {
            peakAtFadeEnd = Math.max(peakAtFadeEnd, Math.abs(out[f]));
        }
        assertTrue("Fade did not reach full level: " + peakAtFadeEnd,
                peakAtFadeEnd > 0.7f);
    }

    @Test
    public void timelineUtilityInvertsReversesAndRepeats() {
        int frames = 1000;
        float[] buf = new float[frames];
        for (int f = 0; f < frames; f++) buf[f] = f / (float) frames;

        TimelineUtilityProcessor p = new TimelineUtilityProcessor();
        p.getParam("tl_action").setEnumIndex(0); // Invert
        p.prepare(SR, 1);
        float[] inverted = p.processOfflineRegion(buf, frames, 1);
        assertEquals(-buf[0], inverted[0], 0.0001f);

        p.getParam("tl_action").setEnumIndex(1); // Reverse
        float[] reversed = p.processOfflineRegion(buf, frames, 1);
        assertEquals(buf[0], reversed[frames - 1], 0.0001f);

        p.getParam("tl_action").setEnumIndex(2); // Repeat
        p.getParam("tl_repeats").setCurrentRaw(3);
        float[] repeated = p.processOfflineRegion(buf, frames, 1);
        assertEquals(frames * 3, repeated.length);
        assertEquals(buf[10], repeated[frames + 10], 0.0001f);
    }

    @Test
    public void generatorRendersAToneIntoTheRegion() {
        int frames = 24000;
        float[] buf = new float[frames];
        AudioGeneratorTool p = new AudioGeneratorTool();
        p.getParam("gen_type").setEnumIndex(0);
        p.getParam("gen_wave").setEnumIndex(0);
        p.getParam("gen_start").setCurrentRaw(1000);
        p.getParam("gen_end").setCurrentRaw(1000);
        p.getParam("gen_amp").setCurrentRaw(50);
        p.prepare(SR, 1);
        float[] out = p.processOfflineRegion(buf, frames, 1);
        double tone = goertzel(out, 4000, 16000, 1000);
        assertTrue("Generator did not render the tone: " + tone, tone > 0.1);
    }

    @Test
    public void plotSpectrumFindsThePeakBinAndNote() {
        int frames = 16384;
        float[] buf = sine(frames, 1, 440, 0.5f);
        PlotSpectrumDialog.Result r =
                PlotSpectrumDialog.analyze(buf, frames, 1, SR, 4096);
        assertTrue("Peak frequency off: " + r.peakHz,
                Math.abs(r.peakHz - 440f) < 20f);
        assertEquals("A4", r.peakNote);
        assertTrue("Note should be close", r.noteIsClose);
    }

    @Test
    public void diagnosticsReportPeakRmsAndClipping() {
        // The loudness integration needs at least one 400 ms block.
        int frames = SR * 2;
        float[] buf = sine(frames, 1, 1000, 0.5f);
        // A run of four clipped samples.
        for (int i = 100; i < 104; i++) buf[i] = 1.0f;
        AudioDiagnosticsTool.Report rep =
                AudioDiagnosticsTool.analyze(buf, frames, 1, SR, 3);
        assertTrue("Peak not reported", rep.peakDbfs > -1f);
        assertTrue("RMS not reported", rep.rmsDbfs < 0f);
        assertTrue("Clipped run not detected", rep.clippedRuns >= 1);
        assertTrue("LUFS not reported", rep.integratedLufs > -70f);
        assertNotNull(rep.summary());
    }

    @Test
    public void everyStage7EffectIsRegisteredAndHasItsPrimaryParam() {
        DspChain.EffectType[] types = {
                DspChain.EffectType.CHANGE_PITCH,
                DspChain.EffectType.CHANGE_TEMPO,
                DspChain.EffectType.CHANGE_SPEED_AND_PITCH,
                DspChain.EffectType.SLIDING_STRETCH,
                DspChain.EffectType.PAULSTRETCH,
                DspChain.EffectType.FADE,
                DspChain.EffectType.TIMELINE_UTILITY,
                DspChain.EffectType.TRUNCATE_SILENCE,
                DspChain.EffectType.SPECTRAL_EDIT,
                DspChain.EffectType.AUDIO_GENERATOR};
        for (DspChain.EffectType t : types) {
            AudioProcessor p = FxRackChain.createProcessor(t.id);
            assertNotNull("Processor missing for " + t.id, p);
            String primary = DspChain.primaryParamId(t);
            assertNotNull("No primary param for " + t.id, primary);
            assertNotNull("Primary param " + primary + " missing on " + t.id,
                    p.getParam(primary));
            assertEquals("Wrong category for " + t.id,
                    p.getCategory(), DspChain.categoryOf(t));
        }
    }

    @Test
    public void offlineEffectsAreMarkedOfflineOnly() {
        String[] offlineIds = {"changepitch", "tempo", "speedpitch",
                "slidestretch", "paulstretch", "fade", "timeline", "truncsil",
                "generator"};
        for (String id : offlineIds) {
            AudioProcessor p = FxRackChain.createProcessor(id);
            assertNotNull(id, p);
            assertTrue(id + " must be offline-only", p.isOfflineOnly());
            assertNotNull(id + " must render offline",
                    p.processOfflineRegion(new float[16], 4, 1));
        }
    }
}
