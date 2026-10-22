package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Effects mode renders only the target region and learns noise profiles from
 * the audio as it enters the profiler, i.e. after every effect above it.
 */
public class FxLivePreviewTest {

    private static File writeSine(File dir, String name, int frames, int ch,
                                  float amp) throws Exception {
        File f = new File(dir, name);
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000,
                    (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float s = (float) (Math.sin(
                        i * 2 * Math.PI * 440 / 48000) * amp);
                for (int c = 0; c < ch; c++) buf[i * ch + c] = s;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        }
        return f;
    }

    private static DspChain.Config shelfRack() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf =
                cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        shelf.value = 600;
        shelf.extraParams.put("freq", 20000);
        return cfg;
    }

    @Test
    public void prefixContainsOnlyTheEnabledEffectsAboveTheTarget() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot eq =
                cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        eq.value = 300;
        eq.wetDry = 0.5f;
        eq.extraParams.put("freq", 8000);
        DspChain.EffectSlot comp =
                cfg.addOrFocusSlot(DspChain.EffectType.COMPRESSOR);
        comp.enabled = false;
        DspChain.EffectSlot target =
                cfg.addOrFocusSlot(DspChain.EffectType.NOISE_SUPPRESSION);

        DspChain.Config prefix =
                StudioFragment.prefixConfigFor(cfg, target);

        assertEquals("Only the enabled effect above the target belongs "
                + "in the learning prefix", 1, prefix.slots.size());
        DspChain.EffectSlot copy = prefix.slots.get(0);
        assertEquals(DspChain.EffectType.SHELF_FILTER, copy.type);
        assertEquals(300, copy.value);
        assertEquals(0.5f, copy.wetDry, 1e-6f);
        assertEquals(Integer.valueOf(8000),
                copy.extraParams.get("freq"));
        assertNotSame("The prefix must be a snapshot, not the live slot",
                eq, copy);
    }

    @Test
    public void theLearningPrefixProcessesTheAudioLikeTheRack()
            throws Exception {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot amp =
                cfg.addOrFocusSlot(DspChain.EffectType.AMPLIFY);
        amp.value = 600;
        DspChain.EffectSlot target =
                cfg.addOrFocusSlot(DspChain.EffectType.NOISE_SUPPRESSION);

        DspChain prefix = new DspChain(1,
                StudioFragment.prefixConfigFor(cfg, target));
        assertTrue("The prefix must run the earlier effect", prefix.isActive());
        float[] buf = new float[4800];
        java.util.Arrays.fill(buf, 0.2f);
        prefix.prepareOffline(null, 0, buf.length, 48000, 1);
        prefix.reset();
        prefix.processInPlace(buf, buf.length);
        assertEquals("The profiler must see +6 dB from the effect above it",
                0.4f, buf[buf.length - 1], 0.02f);
    }

    @Test
    public void regionRenderFillsOnlyTheTargetBuckets() throws Exception {
        int frames = 4800;
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "takeone-fx-live-tests");
        dir.mkdirs();
        File raw = writeSine(dir, "region_render.wav", frames, 1, 0.2f);

        DspChain chain = new DspChain(1, shelfRack());
        float[] peaks = FxPreviewPeaks.renderRegion(raw, chain, 100,
                48000, 0.5f, 1f);
        assertTrue("The region render must produce buckets", peaks != null);
        assertEquals(100, peaks.length);
        for (int i = 0; i < 50; i++) {
            assertEquals("Bucket " + i + " is outside the target",
                    0f, peaks[i], 1e-6f);
        }
        float wetPeak = 0f;
        for (int i = 50; i < 100; i++) {
            wetPeak = Math.max(wetPeak, peaks[i]);
        }
        assertEquals("The target region must carry the +6 dB effect",
                0.4f, wetPeak, 0.05f);
    }

    @Test
    public void auditionStartsAtThePlayheadWhenItIsInsideTheRange() {
        assertEquals(3000L,
                StudioFragment.auditionStartFrame(1000L, 5000L, 3000L));
        assertEquals("Playhead on the start keeps the range start",
                1000L, StudioFragment.auditionStartFrame(
                        1000L, 5000L, 1000L));
        assertEquals("Playhead on the end restarts the range",
                1000L, StudioFragment.auditionStartFrame(
                        1000L, 5000L, 5000L));
        assertEquals("Playhead before the range starts at its beginning",
                1000L, StudioFragment.auditionStartFrame(
                        1000L, 5000L, 200L));
        assertEquals("Playhead after the range starts at its beginning",
                1000L, StudioFragment.auditionStartFrame(
                        1000L, 5000L, 9000L));
    }

    @Test
    public void regionRenderRejectsAnEmptyRange() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "takeone-fx-live-tests");
        dir.mkdirs();
        File raw = writeSine(dir, "empty_range.wav", 4800, 1, 0.2f);
        DspChain chain = new DspChain(1, shelfRack());
        assertNull(FxPreviewPeaks.renderRegion(raw, chain, 100,
                48000, 0.5f, 0.5f));
    }
}
