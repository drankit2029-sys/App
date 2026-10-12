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
        // blockAlign = channels * bytesPerSample
        assertEquals(2, u16(h, 32));
    }

    @Test
    public void wav24HeaderFields() {
        byte[] h = Dsp.wav24Header(2, 48000, 900);
        assertEquals('R', h[0]);
        assertEquals('W', h[8]);
        assertEquals('d', h[36]);
        assertEquals(1, u16(h, 20)); // integer PCM, not float
        assertEquals(2, u16(h, 22));
        assertEquals(48000, s32(h, 24));
        assertEquals(24, u16(h, 34));
        // 2 channels * 3 bytes = 6 byte frames; byte rate = rate * align.
        assertEquals(6, u16(h, 32));
        assertEquals(48000 * 6, s32(h, 28));
        assertEquals(900, s32(h, 40));
        assertEquals(44, h.length);
    }

    @Test
    public void pcmHeaderRejectsOddDepth() {
        // Only 16 and 24 are writable; anything else would produce a header
        // no editor could open.
        try {
            Dsp.wavPcmHeader(1, 48000, 20, 100);
            org.junit.Assert.fail("expected rejection of 20-bit PCM");
        } catch (IllegalArgumentException expected) {
            // correct
        }
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
    public void wavDurationPcm24() throws Exception {
        // 24-bit must resolve to the same duration as the equivalent file,
        // or every WAV24 export would show a wrong length in the library.
        File f = tmp.newFile("c24.wav");
        int frames = 24000; // 0.5s @ 48 kHz stereo
        byte[] h = Dsp.wav24Header(2, 48000, (long) frames * 2 * 3);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(h);
            w.write(new byte[frames * 2 * 3]);
        } finally {
            w.close();
        }
        assertEquals(500, Dsp.wavDurationMs(f));
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

    // ---- trash ----

    private File writeBytes(File f, int len) throws Exception {
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        w.write(new byte[len]);
        w.close();
        return f;
    }

    @Test
    public void trashRoundTrip() throws Exception {
        File takes = tmp.newFolder("takes");
        File src = writeBytes(new File(takes, "song.wav"), 100);
        File trashed = Dsp.moveToTrash(takes, src);
        assertTrue(trashed != null && trashed.exists());
        assertTrue(!src.exists());
        assertEquals(100, trashed.length());
        File back = new File(takes, "song.wav");
        assertTrue(Dsp.restoreFromTrash(trashed, back));
        assertTrue(back.exists() && !trashed.exists());
    }

    @Test
    public void trashUniquifies() throws Exception {
        File takes = tmp.newFolder("takes2");
        File a = writeBytes(new File(takes, "same.wav"), 10);
        File t1 = Dsp.moveToTrash(takes, a);
        File b = writeBytes(new File(takes, "same.wav"), 10);
        File t2 = Dsp.moveToTrash(takes, b);
        assertTrue(t1 != null && t2 != null && !t1.equals(t2));
    }

    @Test
    public void trashMoveMissingIsNull() throws Exception {
        File takes = tmp.newFolder("takes3");
        assertTrue(Dsp.moveToTrash(takes, new File(takes, "ghost.wav")) == null);
        assertTrue(Dsp.moveToTrash(takes, null) == null);
        assertTrue(!Dsp.restoreFromTrash(new File(takes, "ghost.wav"),
                new File(takes, "x.wav")));
    }

    @Test
    public void pruneTrashKeepsFreshKillsOld() throws Exception {
        File trash = tmp.newFolder("trash");
        File fresh = writeBytes(new File(trash, "fresh.wav"), 1000);
        File old = writeBytes(new File(trash, "old.wav"), 2000);
        assertTrue(old.setLastModified(System.currentTimeMillis() - 8L * 24 * 3600_000L));
        long freed = Dsp.pruneTrash(trash, 7L * 24 * 3600_000L);
        assertEquals(2000, freed);
        assertTrue(fresh.exists());
        assertTrue(!old.exists());
        assertEquals(0, Dsp.pruneTrash(null, 1));
    }
}
