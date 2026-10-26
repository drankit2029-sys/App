package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Device-side helpers for the FX verification harness. The unit-test copy of
 * these lives in {@code app/src/test}; instrumentation tests cannot see it, so
 * the few pieces the device tier needs are duplicated here.
 */
public final class FxVerifySupportDevice {

    private FxVerifySupportDevice() {}

    /** Geometry of a 32-bit float WAV, read straight off the header. */
    public static final class WavInfo {
        public final int channels;
        public final int sampleRate;
        public final int bits;
        public final int audioFormat;
        public final long frames;

        WavInfo(int channels, int sampleRate, int bits, int audioFormat, long frames) {
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bits = bits;
            this.audioFormat = audioFormat;
            this.frames = frames;
        }
    }

    public static WavInfo readInfo(File f) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long pos = 12;
            int channels = 1, sampleRate = 48000, bits = 0, format = 0;
            long dataBytes = 0;
            while (pos + 8 <= len) {
                r.seek(pos);
                byte[] head = new byte[8];
                r.readFully(head);
                String tag = new String(head, 0, 4, "US-ASCII");
                long size = Integer.toUnsignedLong(
                        ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                if ("fmt ".equals(tag)) {
                    byte[] fmt = new byte[(int) Math.min(size, 40)];
                    r.readFully(fmt);
                    ByteBuffer b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    // Absolute offsets: bitsPerSample sits at 14, behind
                    // byteRate and blockAlign.
                    format = b.getShort(0) & 0xFFFF;
                    channels = b.getShort(2) & 0xFFFF;
                    sampleRate = b.getInt(4);
                    bits = b.getShort(14) & 0xFFFF;
                } else if ("data".equals(tag)) {
                    dataBytes = Math.min(size, len - (pos + 8));
                }
                pos += 8 + size + (size % 2);
            }
            long frames = channels > 0 ? dataBytes / (4L * channels) : 0;
            return new WavInfo(channels, sampleRate, bits, format, frames);
        }
    }

    /** The dial the FX card drives; {@code DspChain.primaryParamId} is private. */
    public static String primaryParamId(DspChain.EffectType type) {
        try {
            Method m = DspChain.class.getDeclaredMethod("primaryParamId",
                    DspChain.EffectType.class);
            m.setAccessible(true);
            Object v = m.invoke(null, type);
            return v == null ? null : (String) v;
        } catch (Throwable t) {
            return null;
        }
    }
}
