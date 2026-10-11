package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Pure DSP + file-math helpers. Zero Android imports on purpose so this
 * class (and the audio math that ships inside user files) is unit-testable
 * on plain JUnit.
 */
public final class Dsp {

    private Dsp() {}

    public static String sanitize(String name) {
        if (name == null) return "Take";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "Take";
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s;
    }

    /**
     * Library title from a session file name: strips the extension and the
     * trailing _yyyyMMdd_HHmmss stamp (plus any " (n)" uniquifier), turns
     * underscores into spaces. "Take_My_Song_20261011_101500.wav" -> "Take My Song".
     */
    public static String prettyFileTitle(String fileName) {
        if (fileName == null) return "Take";
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.replaceFirst("\\s*\\(\\d+\\)\\s*$", "");
        s = s.replaceFirst("_\\d{8}_\\d{6}$", "");
        s = s.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "Take";
        return s;
    }

    /** Matches the library's historical size rendering. */
    public static String sizeLabel(long length) {
        long mbTenths = Math.max(1, length / 102400);
        return String.format(Locale.US, "%.1f MB", mbTenths / 10f);
    }

    /** 44-byte IEEE-float WAV header (audioFormat 3, 32-bit). */
    public static byte[] wavFloatHeader(int ch, int sampleRate, long bytes) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, 36 + bytes));
        b.put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 3); // IEEE float
        b.putShort((short) ch);
        b.putInt(sampleRate);
        b.putInt(sampleRate * ch * 4);
        b.putShort((short) (ch * 4));
        b.putShort((short) 32);
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, bytes));
        return b.array();
    }

    /** 44-byte PCM-16 WAV header (audioFormat 1). */
    public static byte[] wav16Header(int ch, int sr, long pcmBytes) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, 36 + pcmBytes));
        b.put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) ch);
        b.putInt(sr);
        b.putInt(sr * ch * 2);
        b.putShort((short) (ch * 2));
        b.putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, pcmBytes));
        return b.array();
    }

    /** Hamming-windowed sinc lowpass; cutoff in cycles/sample (0..0.5). */
    public static float[] firLowpass(double cutoffNorm, int taps) {
        float[] h = new float[taps];
        // Center as a double: integer (taps-1)/2 truncates for even tap
        // counts and silently breaks symmetry (caught by unit test).
        double m = (taps - 1) / 2.0;
        double sum = 0;
        for (int n = 0; n < taps; n++) {
            double x = n - m;
            double sinc = x == 0 ? 2 * Math.PI * cutoffNorm
                    : Math.sin(2 * Math.PI * cutoffNorm * x) / x;
            double win = 0.54 - 0.46 * Math.cos(2 * Math.PI * n / (taps - 1));
            h[n] = (float) (sinc * win);
            sum += h[n];
        }
        for (int n = 0; n < taps; n++) h[n] /= sum;
        return h;
    }

    /**
     * Duration of a float32/PCM16 WAV in ms, read from its own header.
     *
     * @return ms, or -1 when the file is not a readable WAV (callers fall
     * back to MediaMetadataRetriever for compressed containers).
     */
    public static long wavDurationMs(File f) {
        RandomAccessFile r = null;
        try {
            r = new RandomAccessFile(f, "r");
            if (r.length() < 44) return -1;
            byte[] h = new byte[44];
            r.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int audioFormat = b.getShort(20) & 0xFFFF;
            int ch = b.getShort(22) & 0xFFFF;
            int sr = b.getInt(24);
            int bits = b.getShort(34) & 0xFFFF;
            long dataBytes = b.getInt(40) & 0xFFFFFFFFL;
            long onDisk = Math.max(0, r.length() - 44);
            if (dataBytes <= 0 || dataBytes > onDisk) dataBytes = onDisk;
            int bytesPerSample;
            if (audioFormat == 3 && bits == 32) bytesPerSample = 4;
            else if (audioFormat == 1 && bits == 16) bytesPerSample = 2;
            else return -1;
            if (ch < 1 || sr <= 0) return -1;
            long frames = dataBytes / bytesPerSample / ch;
            return frames * 1000L / sr;
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
