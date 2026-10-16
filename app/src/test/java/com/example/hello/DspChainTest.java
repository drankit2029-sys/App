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
 * Roadmap Phase 2 DSP chain: fast-math accuracy, inactive no-op guarantee,
 * bounded active processing, badge text and persisted configuration.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DspChainTest {

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
        assertEquals("FX • 1 On",
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
}
