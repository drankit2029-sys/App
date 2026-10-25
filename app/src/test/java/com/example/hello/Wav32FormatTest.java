package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/** Pure-JUnit coverage for the raw 32-bit float save path. */
public class Wav32FormatTest {

    @Test
    public void toWav32CopiesTheFloatPayloadByteForByte() throws Exception {
        File src = File.createTempFile("raw32", ".wav");
        File dst = File.createTempFile("copy32", ".wav");
        try {
            int frames = 480;
            byte[] payload = new byte[frames * 2 * 4];
            for (int i = 0; i < payload.length; i += 4) {
                float v = (float) Math.sin(i * 0.01) * 0.5f;
                int bits = Float.floatToIntBits(v);
                payload[i] = (byte) bits;
                payload[i + 1] = (byte) (bits >>> 8);
                payload[i + 2] = (byte) (bits >>> 16);
                payload[i + 3] = (byte) (bits >>> 24);
            }
            RandomAccessFile w = new RandomAccessFile(src, "rw");
            try {
                w.setLength(0);
                w.write(Dsp.wavFloatHeader(2, 48000, payload.length));
                w.write(payload);
            } finally {
                w.close();
            }

            FormatConverter.toWav32(src, dst, null);
            FormatConverter.validateFinal(dst, SaveFormat.WAV32);
            assertEquals(src.length(), dst.length());

            RandomAccessFile r = new RandomAccessFile(dst, "r");
            try {
                r.seek(44);
                byte[] copied = new byte[payload.length];
                r.readFully(copied);
                assertTrue("float payload changed",
                        java.util.Arrays.equals(payload, copied));
            } finally {
                r.close();
            }
            assertEquals(10L, Dsp.wavDurationMs(dst));
        } finally {
            // noinspection ResultOfMethodCallIgnored
            src.delete();
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
    }

    @Test
    public void validateFinalRejectsIntegerPcmLabelledWav32() throws Exception {
        File f = File.createTempFile("not32", ".wav");
        try {
            byte[] hdr = Dsp.wav16Header(1, 48000, 200);
            byte[] all = new byte[hdr.length + 200];
            System.arraycopy(hdr, 0, all, 0, hdr.length);
            FileOutputStream w = new FileOutputStream(f);
            try {
                w.write(all);
            } finally {
                w.close();
            }
            boolean threw = false;
            try {
                FormatConverter.validateFinal(f, SaveFormat.WAV32);
            } catch (IllegalStateException expected) {
                threw = true;
            }
            assertTrue("integer PCM accepted as WAV32", threw);
        } finally {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
