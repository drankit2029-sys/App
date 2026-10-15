package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * NEW-01 regression on plain JUnit/Robolectric (no MediaCodec needed for the
 * WAV path): stereo imports must not crash, must honour the data chunk
 * offset, and must preserve geometry and pitch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AudioImportFixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static android.content.Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static File makeWav(File f, int ch, int rate, int frames,
                                int format, int bits) throws Exception {
        float[] s = new float[frames * ch];
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.4 * Math.sin(i * 0.05));
            for (int c = 0; c < ch; c++) s[i * ch + c] = v;
        }
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            if (format == 3) {
                w.write(Dsp.wavFloatHeader(ch, rate,
                        (long) frames * ch * 4));
                Dsp.writeFloatsLE(w, s, 0, s.length);
            } else {
                w.write(Dsp.wavPcmHeader(ch, rate, bits,
                        (long) frames * ch * bits / 8));
                byte[] raw = new byte[frames * ch * bits / 8];
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (float v : s) {
                    if (bits == 24) {
                        int q = Math.round(v * 8388607f);
                        bb.put((byte) (q & 0xFF));
                        bb.put((byte) ((q >> 8) & 0xFF));
                        bb.put((byte) ((q >> 16) & 0xFF));
                    } else {
                        bb.putShort((short) Math.round(v * 32767f));
                    }
                }
                w.write(raw);
            }
        } finally {
            w.close();
        }
        return f;
    }

    private static double pitchHz(File wav) throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(wav);
        RandomAccessFile r = new RandomAccessFile(wav, "r");
        try {
            int ch = info.channels;
            long frames = info.dataBytes / 4 / ch;
            int n = (int) Math.min(frames, 48000);
            byte[] raw = new byte[n * ch * 4];
            r.seek(info.dataStart);
            r.readFully(raw);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int crossings = 0;
            float prev = fb.get(0);
            for (int i = 1; i < n; i++) {
                float cur = fb.get(i * ch);
                if ((prev <= 0f && cur > 0f) || (prev >= 0f && cur < 0f)) {
                    crossings++;
                }
                prev = cur;
            }
            return crossings / 2.0 / (n / (double) info.sampleRate);
        } finally {
            r.close();
        }
    }

    @Test
    public void payloadBytesOfSkipsLeadingChunks() throws Exception {
        File f = new File(tmp.getRoot(), "pay.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            java.nio.ByteBuffer le = java.nio.ByteBuffer.allocate(8)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            w.write(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0,
                    'W', 'A', 'V', 'E'});
            w.write(new byte[]{'J', 'U', 'N', 'K'});
            le.clear();
            le.putInt(8);
            w.write(le.array(), 0, 4);
            w.write(new byte[8]);
            w.write(new byte[]{'f', 'm', 't', ' ', 16, 0, 0, 0});
            java.nio.ByteBuffer fmt = java.nio.ByteBuffer.allocate(16)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            fmt.putShort((short) 3);
            fmt.putShort((short) 1);
            fmt.putInt(48000);
            fmt.putInt(48000 * 4);
            fmt.putShort((short) 4);
            fmt.putShort((short) 32);
            w.write(fmt.array());
            w.write(new byte[]{'d', 'a', 't', 'a'});
            le.clear();
            le.putInt(100 * 4);
            w.write(le.array(), 0, 4);
            Dsp.writeFloatsLE(w, new float[100], 0, 100);
            // Patch the RIFF size.
            w.seek(4);
            le.clear();
            le.putInt((int) (w.length() - 8));
            w.write(le.array(), 0, 4);
        } finally {
            w.close();
        }
        // 12 (riff) + 16 (junk) + 24 (fmt) + 8 (data hdr) + 400 (payload).
        assertEquals(f.length() - 60, RecordingEngine.payloadBytesOf(f));
        assertEquals(0, RecordingEngine.payloadBytesOf(
                new File(tmp.getRoot(), "missing.wav")));
    }

    @Test
    public void stereoFloatWavImportsWithoutUnderflow() throws Exception {
        File src = makeWav(new File(tmp.getRoot(), "st.wav"), 2, 48000,
                48000, 3, 32);
        File out = AudioImporter.takeToFloatWav(src,
                RecordingEngine.takesDir(ctx()), "Stereo", null);
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(out);
            assertEquals(2, info.channels);
            assertEquals(48000, info.sampleRate);
            assertEquals(48000L * 2 * 4, info.dataBytes);
            // ~382 Hz sine (0.05 rad/sample @48k); must survive intact.
            assertEquals(382.0, pitchHz(out), 6.0);
        } finally {
            out.delete();
        }
    }

    @Test
    public void stereoPcm16WavImportsWithRightScale() throws Exception {
        File src = makeWav(new File(tmp.getRoot(), "st16.wav"), 2, 48000,
                24000, 1, 16);
        File out = AudioImporter.takeToFloatWav(src,
                RecordingEngine.takesDir(ctx()), "Stereo16", null);
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(out);
            assertEquals(2, info.channels);
            assertEquals(24000L * 2 * 4, info.dataBytes);
            assertEquals(382.0, pitchHz(out), 6.0);
        } finally {
            out.delete();
        }
    }

    @Test
    public void wavWithLeadingJunkChunkImports() throws Exception {
        // data chunk sits past a JUNK chunk: the old code read chunk bytes
        // as audio (and never sought to dataStart).
        File f = new File(tmp.getRoot(), "junky.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            java.nio.ByteBuffer le = java.nio.ByteBuffer.allocate(8)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            w.write(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0,
                    'W', 'A', 'V', 'E'});
            w.write(new byte[]{'J', 'U', 'N', 'K'});
            le.clear();
            le.putInt(16);
            w.write(le.array(), 0, 4);
            w.write(new byte[16]);
            w.write(new byte[]{'f', 'm', 't', ' ', 16, 0, 0, 0});
            java.nio.ByteBuffer fmt = java.nio.ByteBuffer.allocate(16)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            fmt.putShort((short) 3);
            fmt.putShort((short) 1);
            fmt.putInt(48000);
            fmt.putInt(48000 * 4);
            fmt.putShort((short) 4);
            fmt.putShort((short) 32);
            w.write(fmt.array());
            w.write(new byte[]{'d', 'a', 't', 'a'});
            le.clear();
            le.putInt(4800 * 4);
            w.write(le.array(), 0, 4);
            float[] s = new float[4800];
            for (int i = 0; i < 4800; i++) {
                s[i] = (float) (0.4 * Math.sin(i * 0.05));
            }
            Dsp.writeFloatsLE(w, s, 0, s.length);
            // Patch the RIFF size.
            w.seek(4);
            le.clear();
            le.putInt((int) (w.length() - 8));
            w.write(le.array(), 0, 4);
        } finally {
            w.close();
        }
        File out = AudioImporter.takeToFloatWav(f,
                RecordingEngine.takesDir(ctx()), "Junky", null);
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(out);
            assertEquals(1, info.channels);
            assertEquals(4800L * 4, info.dataBytes);
            assertEquals(382.0, pitchHz(out), 8.0);
        } finally {
            out.delete();
        }
    }
}
