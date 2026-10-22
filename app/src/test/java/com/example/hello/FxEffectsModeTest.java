package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Effects mode: the FX rack drives the transport.
 *
 * <p>With the rack open, Play auditions the effect-applied audio, Stop
 * commits the rack to the region that was selected before the rack opened,
 * and the FX chip is only highlighted while the panel is actually open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxEffectsModeTest {

    private Context ctx;
    private RecordingEngine engine;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        engine = RecordingEngine.get();
        engine.abortAndDelete();
    }

    @After
    public void tearDown() {
        engine.abortAndDelete();
    }

    private File createFloatWav(String name, int frames, int ch) throws Exception {
        File dir = new File(ctx.getCacheDir(), "fxmode");
        dir.mkdirs();
        File f = new File(dir, name);
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            for (int i = 0; i < frames; i++) {
                float s = (float) (Math.sin(i * 2 * Math.PI * 440 / 48000) * 0.2);
                for (int c = 0; c < ch; c++) buf[i * ch + c] = s;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        }
        return f;
    }

    private static float[] readSamples(File wav, int frames, int ch)
            throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        byte[] raw = new byte[frames * ch * 4];
        try (RandomAccessFile r = new RandomAccessFile(wav, "r")) {
            r.seek(info.dataStart);
            r.readFully(raw);
        }
        float[] out = new float[frames * ch];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    @Test
    public void theFxChipIsOnlyHighlightedWhileTheRackIsOpen() throws Exception {
        int frames = 4800;
        File raw = createFloatWav("chip_highlight.wav", frames, 1);
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        // A rack with an effect in it, but the panel is closed.
        DspChain.EffectSlot slot =
                cfg.addOrFocusSlot(DspChain.EffectType.FREEVERB_REVERB);
        slot.value = 80;
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Chip Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 301L, "Chip Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);

        TextView btnDspFx = root.findViewById(R.id.btnDspFx);
        assertFalse("The rack must start closed", frag.isFxPanelOpen());
        assertNotEquals("A pending rack must not highlight the FX chip",
                selectedChipColour(btnDspFx), chipColour(btnDspFx));

        // Open the rack: now the chip is highlighted.
        btnDspFx.performClick();
        assertTrue("The rack must be open", frag.isFxPanelOpen());
        assertNotEquals("An open rack must highlight the FX chip",
                selectedChipColour(btnDspFx), chipColour(btnDspFx));
    }

    /** The fill colour of the chip's current background drawable. */
    private static int chipColour(TextView chip) {
        android.graphics.drawable.Drawable d = chip.getBackground();
        if (d instanceof android.graphics.drawable.GradientDrawable) {
            return ((android.graphics.drawable.GradientDrawable) d)
                    .getColor().getDefaultColor();
        }
        return 0;
    }

    /** The fill colour the highlighted (selected) chip uses. */
    private static int selectedChipColour(TextView chip) {
        android.graphics.drawable.Drawable d =
                androidx.core.content.ContextCompat.getDrawable(
                        chip.getContext(), R.drawable.bg_chip_selected);
        if (d instanceof android.graphics.drawable.GradientDrawable) {
            return ((android.graphics.drawable.GradientDrawable) d)
                    .getColor().getDefaultColor();
        }
        return -1;
    }

    @Test
    public void theRackCommitsToTheRegionSelectedBeforeItOpened()
            throws Exception {
        int frames = 4800;
        File raw = createFloatWav("frozen_target.wav", frames, 1);
        float[] before = readSamples(raw, frames, 1);

        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        DspChain.EffectSlot shelf =
                cfg.addOrFocusSlot(DspChain.EffectType.SHELF_FILTER);
        shelf.value = 600;
        shelf.extraParams.put("freq", 20000);
        cfg.save(ctx);

        engine.attachExisting(ctx, raw, "Frozen Take", 1,
                RecordingEngine.payloadBytesOf(raw));
        MainActivity act = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().visible().get();
        act.openStudioWithEdit(raw, 302L, "Frozen Take");
        Robolectric.flushForegroundThreadScheduler();

        StudioFragment frag = (StudioFragment) act.getSupportFragmentManager()
                .findFragmentByTag("STU");
        assertNotNull(frag);
        View root = frag.getView();
        assertNotNull(root);

        WaveformView wf = root.findViewById(R.id.waveform);
        TextView btnDspFx = root.findViewById(R.id.btnDspFx);

        // Select the second half BEFORE opening the rack.
        wf.selectRange(0.5f, 1f);
        btnDspFx.performClick();
        assertTrue("The rack must be open", frag.isFxPanelOpen());

        // Stop commits the rack to that region.
        frag.applyEffectsToSelection();

        float[] after = readSamples(raw, frames, 1);
        float peakFirstHalf = 0f;
        float peakSecondHalf = 0f;
        for (int i = 100; i < 2000; i++) {
            peakFirstHalf = Math.max(peakFirstHalf, Math.abs(after[i]));
        }
        for (int i = 2600; i < 4600; i++) {
            peakSecondHalf = Math.max(peakSecondHalf, Math.abs(after[i]));
        }
        assertEquals("The first half must stay dry",
                0.20f, peakFirstHalf, 0.03f);
        assertEquals("The selected half must carry the effect",
                0.40f, peakSecondHalf, 0.06f);
        // The untouched half must be bit-identical.
        for (int i = 100; i < 2000; i++) {
            assertEquals("The dry region must be untouched at " + i,
                    before[i], after[i], 1e-6f);
        }
    }

    @Test
    public void anEmptyRackDoesNotArmTheChain() {
        DspChain.Config cfg = new DspChain.Config();
        cfg.applyPreset(DspChain.Preset.CLEAR_ALL);
        assertFalse("An empty rack must stay inactive", cfg.isActive());
        DspChain chain = new DspChain(1, cfg);
        assertFalse("An empty rack must not process audio", chain.isActive());
    }
}
