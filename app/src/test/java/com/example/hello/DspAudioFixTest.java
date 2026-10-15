package com.example.hello;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Regression tests for the audit fixes: RIFF chunk scanning (BUG-06),
 * streaming resampling (BUG-01), bit-exact chunked decimation (BUG-03),
 * little-endian sample I/O (BUG-09) and waveform peak loading (BUG-04).
 */
public class DspAudioFixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- BUG-06: chunk walker ----

    private static void writeChunk(RandomAccessFile w, String id, byte[] payload)
            throws Exception {
        w.write(new byte[]{(byte) id.charAt(0), (byte) id.charAt(1),
                (byte) id.charAt(2), (byte) id.charAt(3)});
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(payload.length);
        w.write(b.array());
        w.write(payload);
        if ((payload.length & 1) == 1) w.write(0); // word alignment
    }

    @Test
    public void parseWavHeaderFindsDataAfterJunk() throws Exception {
        File f = tmp.newFile("junky.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0,
                    'W', 'A', 'V', 'E'});
            writeChunk(w, "JUNK", new byte[16]);
            ByteBuffer fmt = ByteBuffer.allocate(16)
                    .order(ByteOrder.LITTLE_ENDIAN);
            fmt.putShort((short) 3);
            fmt.putShort((short) 1);
            fmt.putInt(48000);
            fmt.putInt(48000 * 4);
            fmt.putShort((short) 4);
            fmt.putShort((short) 32);
            writeChunk(w, "fmt ", fmt.array());
            writeChunk(w, "LIST", new byte[12]);
            byte[] pcm = new byte[480 * 4];
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().put(new float[480]);
            writeChunk(w, "data", pcm);
        } finally {
            w.close();
        }
        RandomAccessFile r = new RandomAccessFile(f, "r");
        Dsp.WavHeader h;
        try {
            h = Dsp.parseWavHeader(r);
        } finally {
            r.close();
        }
        assertEquals(3, h.audioFormat);
        assertEquals(1, h.channels);
        assertEquals(48000, h.sampleRate);
        assertEquals(32, h.bits);
        // 12 (riff) + 24 (junk) + 24 (fmt) + 20 (list) + 8 (data hdr).
        assertEquals(88, h.dataStart);
        assertEquals(480 * 4L, h.dataBytes);
        // Duration must agree through the scanner too.
        assertEquals(10L, Dsp.wavDurationMs(f));
    }

    @Test
    public void parseWavHeaderRejectsNonWav() throws Exception {
        File f = tmp.newFile("nope.wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(new byte[100]);
        } finally {
            w.close();
        }
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            Dsp.parseWavHeader(r);
            fail("non-wav parsed");
        } catch (IllegalStateException expected) {
        } finally {
            r.close();
        }
    }

    // ---- BUG-01: resampler ----

    private static float[] sine(int frames, int ch, int rate, double freq,
                                float amp) {
        float[] s = new float[frames * ch];
        for (int f = 0; f < frames; f++) {
            float v = (float) (amp * Math.sin(2 * Math.PI * freq * f / rate));
            for (int c = 0; c < ch; c++) s[f * ch + c] = v;
        }
        return s;
    }

    private static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int p = 0;
        for (float[] part : parts) {
            System.arraycopy(part, 0, out, p, part.length);
            p += part.length;
        }
        return out;
    }

    /** Zero-crossing pitch estimate on channel 0. */
    private static double pitchHz(float[] s, int ch, int rate) {
        int crossings = 0;
        int n = s.length / ch;
        float prev = s[0];
        for (int f = 1; f < n; f++) {
            float cur = s[f * ch];
            if ((prev <= 0f && cur > 0f) || (prev >= 0f && cur < 0f)) {
                crossings++;
            }
            prev = cur;
        }
        double seconds = (double) n / rate;
        return crossings / 2.0 / seconds;
    }

    @Test
    public void resamplerUpsamplePreservesDurationAndPitch() {
        // 8 kHz AMR-geometry mono -> 48 kHz session, fed in uneven chunks.
        float[] in = sine(8000, 1, 8000, 440.0, 0.5f);
        Dsp.Resampler r = new Dsp.Resampler(8000, 48000, 1);
        float[] a = r.push(in, 1000);
        float[] b = r.push(java.util.Arrays.copyOfRange(in, 1000, 4000), 3000);
        float[] c = r.push(java.util.Arrays.copyOfRange(in, 4000, 8000), 4000);
        float[] tail = r.flush();
        float[] out = concat(a, b, c, tail);
        assertEquals(48000, out.length, 4);
        assertEquals(440.0, pitchHz(out, 1, 48000), 3.0);
        float peak = 0f;
        for (float v : out) {
            if (Float.isNaN(v)) fail("NaN in resampled output");
            peak = Math.max(peak, Math.abs(v));
        }
        assertEquals(0.5f, peak, 0.06f);
    }

    @Test
    public void resamplerChunkingMatchesOneShot() {
        float[] in = sine(4410, 2, 44100, 440.0, 0.4f);
        Dsp.Resampler one = new Dsp.Resampler(44100, 48000, 2);
        float[] o1 = concat(one.push(in, 4410), one.flush());
        Dsp.Resampler two = new Dsp.Resampler(44100, 48000, 2);
        float[] p1 = two.push(java.util.Arrays.copyOf(in, 2000), 1000);
        float[] p2 = two.push(
                java.util.Arrays.copyOfRange(in, 2000, 8820), 3410);
        float[] o2 = concat(p1, p2, two.flush());
        assertArrayEquals(o1, o2, 0f);
    }

    @Test
    public void resamplerDownsampleKillsAliases() {
        // 96 kHz stereo: 440 Hz tone + 30 kHz tone above the 24 kHz Nyquist.
        int frames = 9600;
        float[] in = new float[frames * 2];
        for (int f = 0; f < frames; f++) {
            float v = (float) (0.4 * Math.sin(2 * Math.PI * 440 * f / 96000)
                    + 0.4 * Math.sin(2 * Math.PI * 30000 * f / 96000));
            in[f * 2] = v;
            in[f * 2 + 1] = v;
        }
        Dsp.Resampler r = new Dsp.Resampler(96000, 48000, 2);
        float[] out = concat(r.push(in, frames), r.flush());
        int outFrames = out.length / 2;
        assertEquals(4800, outFrames, 4);
        // Skip the FIR leading-edge transient (first 128 frames): any
        // finite filter rings while its delay line fills, which is normal
        // and inaudible, but it would pollute a crossing count.
        int skip = 128;
        float[] steady = java.util.Arrays.copyOfRange(out,
                skip * 2, out.length);
        int steadyFrames = outFrames - skip;
        assertEquals(440.0, pitchHz(steady, 2, 48000), 5.0);
        // Fit the 440 Hz amplitude delay-independently (any FIR delays by
        // its group delay): quadrature correlation gives the true passband
        // gain, and the residual proves the 30 kHz alias is (nearly) gone.
        double ss = 0, sc = 0, cc = 0, ys = 0, yc = 0;
        for (int f = 0; f < steadyFrames; f++) {
            double ph = 2 * Math.PI * 440 * (f + skip) / 48000;
            double s = Math.sin(ph);
            double c = Math.cos(ph);
            double y = steady[f * 2];
            ss += s * s;
            sc += s * c;
            cc += c * c;
            ys += y * s;
            yc += y * c;
        }
        double det = ss * cc - sc * sc;
        double ampA = (ys * cc - yc * sc) / det;
        double ampB = (ss * yc - sc * ys) / det;
        double amp = Math.sqrt(ampA * ampA + ampB * ampB);
        assertEquals(0.4, amp, 0.05);
        double resid = 0;
        for (int f = 0; f < steadyFrames; f++) {
            double ph = 2 * Math.PI * 440 * (f + skip) / 48000;
            double d = steady[f * 2]
                    - (ampA * Math.sin(ph) + ampB * Math.cos(ph));
            resid += d * d;
        }
        assertTrue("alias leaked, residual rms="
                        + Math.sqrt(resid / steadyFrames),
                Math.sqrt(resid / steadyFrames) < 0.06);
    }

    @Test
    public void resamplerPassthroughIsIdentity() {
        float[] in = sine(1000, 2, 48000, 440.0, 0.3f);
        Dsp.Resampler r = new Dsp.Resampler(48000, 48000, 2);
        assertArrayEquals(in, r.push(in, 1000), 0f);
        assertEquals(0, r.flush().length);
    }

    // ---- BUG-03: chunked decimation is bit-exact vs batch ----

    @Test
    public void firDecimatorMatchesBatchExactly() {
        Random rnd = new Random(1234);
        int frames = 4800;
        int decim = 6;
        int taps = 64;
        float[] fir = Dsp.firLowpass(3400.0 / 48000, taps);
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) {
            mono[i] = rnd.nextFloat() * 2f - 1f;
        }
        // Batch reference: the old whole-file loop, verbatim math.
        int delay = taps / 2;
        int half = taps / 2;
        int outLen = frames / decim;
        short[] expect = new short[outLen];
        for (int o = 0; o < outLen; o++) {
            float acc = 0f;
            int center = o * decim - delay;
            for (int k = 0; k < half; k++) {
                int i1 = center + k;
                int i2 = center + taps - 1 - k;
                float s1 = (i1 >= 0 && i1 < frames) ? mono[i1] : 0f;
                float s2 = (i2 >= 0 && i2 < frames) ? mono[i2] : 0f;
                acc += s1 * fir[k] + s2 * fir[taps - 1 - k];
            }
            if (acc > 1f) acc = 1f;
            else if (acc < -1f) acc = -1f;
            expect[o] = (short) (acc * 32767f);
        }
        // Streamed: odd-sized pushes plus a flush.
        Dsp.FirDecimator dec = new Dsp.FirDecimator(decim, fir);
        short[] got = new short[outLen + 64];
        int p = 0;
        p += dec.push(mono, 1000, got, p);
        float[] rest = java.util.Arrays.copyOfRange(mono, 1000, frames);
        p += dec.push(rest, frames - 1000, got, p);
        p += dec.flush(frames, got, p);
        assertEquals("output count " + p + " != batch " + outLen,
                outLen, p);
        assertArrayEquals(expect, java.util.Arrays.copyOf(got, p));
    }

    @Test
    public void firDecimatorOutputCountMatchesBatchRule() {
        // Non-multiple length: total outputs must be totalIn/decim.
        Dsp.FirDecimator dec =
                new Dsp.FirDecimator(6, Dsp.firLowpass(3400.0 / 48000, 64));
        int total = 25 * 6 + 3;
        float[] zeros = new float[total];
        short[] out = new short[total];
        int p = dec.push(zeros, total, out, 0);
        p += dec.flush(total, out, p);
        assertEquals(total / 6, p);
    }

    // ---- BUG-09: little-endian I/O ----

    @Test
    public void writeFloatsLERoundTrips() throws Exception {
        File f = tmp.newFile("le.wav");
        float[] vals = {0f, 1f, -1f, 0.5f, -0.25f, 1e-7f, 3.14159f, -2.5f};
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            Dsp.writeFloatsLE(w, vals, 0, vals.length);
        } finally {
            w.close();
        }
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] raw = new byte[vals.length * 4];
            r.readFully(raw);
            java.nio.FloatBuffer fb = ByteBuffer.wrap(raw)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            for (int i = 0; i < vals.length; i++) {
                assertEquals("sample " + i, vals[i], fb.get(), 0f);
            }
        } finally {
            r.close();
        }
    }

    // ---- BUG-04: peak loading ----

    @Test
    public void loadPeaksReflectsEnvelope() throws Exception {
        File f = tmp.newFile("env.wav");
        int frames = 48000;
        float[] s = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float amp = i < frames / 2 ? 0.5f : 0.1f;
            float v = amp * (float) Math.sin(i * 0.05);
            s[i * 2] = v;
            s[i * 2 + 1] = v * 0.5f;
        }
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            Dsp.writeFloatsLE(w, s, 0, s.length);
        } finally {
            w.close();
        }
        float[] peaks = Dsp.loadPeaks(f, 8192);
        assertTrue("no peaks", peaks.length > 10);
        float first = 0f, second = 0f;
        for (int i = 0; i < peaks.length / 2; i++) {
            first = Math.max(first, peaks[i]);
        }
        for (int i = peaks.length / 2; i < peaks.length; i++) {
            second = Math.max(second, peaks[i]);
        }
        assertEquals(0.5f, first, 0.06f);
        assertEquals(0.1f, second, 0.03f);
    }
}
