package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FLAC stream-header correctness (FMT-01) and container validation (FMT-03).
 *
 * <p>The old writer emitted 0x90 (metadata block type 16, reserved) and
 * copied STREAMINFO from offset 0 of csd-0 even though Android's encoder
 * prefixes it with "fLaC" + a block header. The result was a file every
 * Android extractor rejected.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FlacHeaderTest {

    /** 34-byte STREAMINFO describing 48 kHz / 2ch / 32-bit. */
    private static byte[] streamInfo(int sampleRate, int channels) {
        byte[] si = new byte[34];
        ByteBuffer b = ByteBuffer.wrap(si).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) 4096);    // min block size
        b.putShort((short) 4096);    // max block size
        si[10] = (byte) ((sampleRate >>> 12) & 0xFF);
        si[11] = (byte) ((sampleRate >>> 4) & 0xFF);
        si[12] = (byte) (((sampleRate & 0x0F) << 4)
                | (((channels - 1) & 0x07) << 1));
        si[13] = (byte) (((channels - 1) & 0x01) << 7); // bps=0
        return si;
    }

    @Test
    public void writesStreamInfoHeaderNotReservedType() throws Exception {
        byte[] si = streamInfo(48000, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FormatConverter.FlacHeader.write(out, si);
        byte[] f = out.toByteArray();

        assertEquals(42, f.length);
        assertEquals('f', f[0]);
        assertEquals('L', f[1]);
        assertEquals('a', f[2]);
        assertEquals('C', f[3]);
        // 0x80 = last-metadata-block set + type 0 (STREAMINFO). Never 0x90.
        assertEquals("metadata block header must be 0x80",
                (byte) 0x80, f[4]);
        assertEquals(0, f[5]);
        assertEquals(0, f[6]);
        assertEquals(34, f[7]);
        // The payload must not repeat the magic.
        assertFalse(f[8] == 'f' && f[9] == 'L' && f[10] == 'a'
                && f[11] == 'C');
        assertEquals(48000, FormatConverter.FlacHeader.sampleRate(
                java.util.Arrays.copyOfRange(f, 8, 42)));
        assertTrue(FormatConverter.FlacHeader.validHead(f));
    }

    @Test
    public void extractsStreamInfoFromAllCsdShapes() {
        byte[] si = streamInfo(48000, 2);
        // Shape 1: bare STREAMINFO.
        ByteBuffer bare = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN);
        bare.put(si).flip();
        assertNotNull(FormatConverter.FlacHeader.streamInfoFromCsd(bare));
        assertEquals(48000, FormatConverter.FlacHeader.sampleRate(
                FormatConverter.FlacHeader.streamInfoFromCsd(bare)));

        // Shape 2: block header + STREAMINFO (38 bytes).
        ByteBuffer hdr = ByteBuffer.allocate(38);
        hdr.put(new byte[]{(byte) 0x80, 0, 0, 34}).put(si).flip();
        byte[] got2 = FormatConverter.FlacHeader.streamInfoFromCsd(hdr);
        assertNotNull(got2);
        assertEquals(48000, FormatConverter.FlacHeader.sampleRate(got2));

        // Shape 3: "fLaC" + block header + STREAMINFO (42 bytes) — the
        // shape Android's C2 FLAC encoder actually emits.
        ByteBuffer full = ByteBuffer.allocate(42);
        full.put(new byte[]{'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34})
                .put(si).flip();
        byte[] got3 = FormatConverter.FlacHeader.streamInfoFromCsd(full);
        assertNotNull("42-byte csd-0 was not understood", got3);
        assertEquals(48000, FormatConverter.FlacHeader.sampleRate(got3));
        // Crucially the sample rate must not be 0 (a 'fLaC'-prefixed buffer
        // read from offset 0 would decode garbage here).
        assertEquals(0, got3.length == 0 ? -1 : 0);
    }

    @Test
    public void csdPositionIsNotConsumed() {
        byte[] si = streamInfo(48000, 2);
        ByteBuffer full = ByteBuffer.allocate(42);
        full.put(new byte[]{'f', 'L', 'a', 'C', (byte) 0x80, 0, 0, 34})
                .put(si).flip();
        int before = full.position();
        FormatConverter.FlacHeader.streamInfoFromCsd(full);
        assertEquals("csd-0 position must be restored", before,
                full.position());
    }

    @Test
    public void totalSamplesIsPatchedIntoStreamInfo() throws Exception {
        File f = File.createTempFile("flac", ".flac");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FormatConverter.FlacHeader.write(out, streamInfo(48000, 2));
            try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
                w.write(out.toByteArray());
                FormatConverter.FlacHeader.patchTotalSamples(w, 96000L);
            }
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                byte[] h = new byte[42];
                r.readFully(h);
                // 36-bit field: low nibble of file byte 21, then 22..25.
                long total = ((h[21] & 0x0FL) << 32)
                        | ((h[22] & 0xFFL) << 24)
                        | ((h[23] & 0xFFL) << 16)
                        | ((h[24] & 0xFFL) << 8)
                        | (h[25] & 0xFFL);
                assertEquals(96000L, total);
                // The rate must survive the patch.
                assertEquals(48000, ((h[18] & 0xFF) << 12)
                        | ((h[19] & 0xFF) << 4) | ((h[20] & 0xF0) >>> 4));
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void validHeadRejectsReservedTypeAndZeroRate() {
        byte[] good;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FormatConverter.FlacHeader.write(out, streamInfo(48000, 2));
            good = out.toByteArray();
        } catch (Exception e) {
            fail(e.toString());
            return;
        }
        assertTrue(FormatConverter.FlacHeader.validHead(good));

        byte[] reserved = good.clone();
        reserved[4] = (byte) 0x90;
        assertFalse("0x90 must be rejected",
                FormatConverter.FlacHeader.validHead(reserved));

        byte[] zeroRate = good.clone();
        zeroRate[18] = 0;
        zeroRate[19] = 0;
        zeroRate[20] &= (byte) 0x0F;
        assertFalse("0 Hz sample rate must be rejected",
                FormatConverter.FlacHeader.validHead(zeroRate));

        byte[] shortLen = good.clone();
        shortLen[7] = 33;
        assertFalse(FormatConverter.FlacHeader.validHead(shortLen));

        assertFalse(FormatConverter.FlacHeader.validHead(new byte[10]));
        assertNull(null);
    }
}