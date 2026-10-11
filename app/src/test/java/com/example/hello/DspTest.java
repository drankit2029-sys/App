package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public class DspTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- sanitize ----

    @Test
    public void sanitizeBasics() {
        assertEquals("Take", Dsp.sanitize(null));
        assertEquals("Take", Dsp.sanitize("   "));
        // Every illegal char becomes its own underscore (no collapsing).
        assertEquals("a_b_c_______", Dsp.sanitize("a/b\\c:*?\"<>|"));
        assertEquals("Hello World", Dsp.sanitize("  Hello   World  "));
    }

    @Test
    public void sanitizeTruncates() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        assertTrue(Dsp.sanitize(sb.toString()).length() <= 60);
    }

    // ---- prettyFileTitle ----

    @Test
    public void prettyStripsStampAndExt() {
        assertEquals("Take My Song",
                Dsp.prettyFileTitle("Take_My_Song_20261011_101500.wav"));
        assertEquals("Take My Song",
                Dsp.prettyFileTitle("Take_My_Song_20261011_101500 (2).m4a"));
        assertEquals("Voice Memo", Dsp.prettyFileTitle("Voice_Memo.m4a"));
        assertEquals("Take", Dsp.prettyFileTitle(null));
    }

    // ---- sizeLabel ----

    @Test
    public void sizeLabelMatchesHistory() {
        assertEquals("0.1 MB", Dsp.sizeLabel(0));
        assertEquals("4.1 MB", Dsp.sizeLabel((long) (4.1 * 1024 * 1024)));
    }

    // ---- WAV headers ----

    private static int u16(byte[] h, int off) {
        return ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getShort(off) & 0xFFFF;
    }

    private static int s32(byte[] h, int off) {
        return ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt(off);
    }

    @Test
    public void floatHeaderFields() {
        byte[] h = Dsp.wavFloatHeader(2, 48000, 1000);
        assertEquals('R', h[0]);
        assertEquals('W', h[8]);
        assertEquals('d', h[36]);
        assertEquals(3, u16(h, 20)); // IEEE float
        assertEquals(2, u16(h, 22));
        assertEquals(48000, s32(h, 24));
        assertEquals(32, u16(h, 34));
        assertEquals(1000, s32(h, 40));
        assertEquals(44, h.length);
    }

    @Test
    public void wav16HeaderFields() {
        byte[] h = Dsp.wav16Header(1, 16000, 500);
        assertEquals(1, u16(h, 20)); // PCM
        assertEquals(1, u16(h, 22));
        assertEquals(16000, s32(h, 24));
        assertEquals(16, u16(h, 34));
        assertEquals(500, s32(h, 40));
    }

    // ---- FIR ----

    @Test
    public void firUnityGainAndSymmetric() {
        float[] h = Dsp.firLowpass(3400.0 / 48000.0, 64);
        assertEquals(64, h.length);
        double sum = 0;
        for (float v : h) sum += v;
        assertEquals(1.0, sum, 1e-6);
        for (int i = 0; i < h.length / 2; i++) {
            assertEquals(h[i], h[h.length - 1 - i], 1e-6f);
        }
    }

    // ---- wavDurationMs ----

    private File writeWav(boolean isFloat, int ch, int sr, int frames) throws Exception {
        File f = tmp.newFile(isFloat ? "a.wav" : "b.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        byte[] hdr = isFloat ? Dsp.wavFloatHeader(ch, sr, (long) frames * ch * 4)
                : Dsp.wav16Header(ch, sr, (long) frames * ch * 2);
        w.write(hdr);
        w.write(new byte[frames * ch * (isFloat ? 4 : 2)]);
        w.close();
        return f;
    }

    @Test
    public void wavDurationFloatStereo() throws Exception {
        // 48000 frames stereo @48k = exactly 1 second.
        assertEquals(1000, Dsp.wavDurationMs(writeWav(true, 2, 48000, 48000)));
    }

    @Test
    public void wavDurationPcm16Mono8k() throws Exception {
        assertEquals(2000, Dsp.wavDurationMs(writeWav(false, 1, 8000, 16000)));
    }

    @Test
    public void wavDurationRejectsGarbage() throws Exception {
        File f = tmp.newFile("x.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        w.write(new byte[10]);
        w.close();
        assertEquals(-1, Dsp.wavDurationMs(f));
        assertEquals(-1, Dsp.wavDurationMs(new File(tmp.getRoot(), "nope.wav")));
    }
}
