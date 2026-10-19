package com.example.hello;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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

    /** 44-byte PCM WAV header. bits must be 16 or 24. */
    public static byte[] wavPcmHeader(int ch, int sr, int bits, long pcmBytes) {
        if (bits != 16 && bits != 24) {
            throw new IllegalArgumentException("unsupported pcm depth: " + bits);
        }
        int bytesPerSample = bits / 8;
        int blockAlign = ch * bytesPerSample;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, 36 + pcmBytes));
        b.put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '});
        b.putInt(16);
        b.putShort((short) 1); // integer PCM
        b.putShort((short) ch);
        b.putInt(sr);
        b.putInt(sr * blockAlign);
        b.putShort((short) blockAlign);
        b.putShort((short) bits);
        b.put(new byte[]{'d', 'a', 't', 'a'});
        b.putInt((int) Math.min(Integer.MAX_VALUE, pcmBytes));
        return b.array();
    }

    /** 44-byte PCM-16 WAV header (audioFormat 1). */
    public static byte[] wav16Header(int ch, int sr, long pcmBytes) {
        return wavPcmHeader(ch, sr, 16, pcmBytes);
    }

    /** 44-byte PCM-24 WAV header (audioFormat 1, 3 bytes per sample). */
    public static byte[] wav24Header(int ch, int sr, long pcmBytes) {
        return wavPcmHeader(ch, sr, 24, pcmBytes);
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
     * Duration of a float32 / PCM16 / PCM24 WAV in ms, read from its own
     * header (located through the chunk scanner, so extra RIFF chunks do
     * not confuse it).
     *
     * @return ms, or -1 when the file is not a readable WAV (callers fall
     * back to MediaMetadataRetriever for compressed containers).
     */    public static long wavDurationMs(File f) {
        RandomAccessFile r = null;
        try {
            r = new RandomAccessFile(f, "r");
            WavHeader h;
            try {
                h = parseWavHeader(r);
            } catch (Exception e) {
                return -1;
            }
            int bytesPerSample;
            if (h.audioFormat == 3 && h.bits == 32) bytesPerSample = 4;
            else if (h.audioFormat == 1 && h.bits == 16) bytesPerSample = 2;
            else if (h.audioFormat == 1 && h.bits == 24) bytesPerSample = 3;
            else return -1;
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = h.dataBytes;
            if (dataBytes <= 0 || dataBytes > onDisk) dataBytes = onDisk;
            long frames = dataBytes / bytesPerSample / h.channels;
            return frames * 1000L / h.sampleRate;
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

    // ---- RIFF/WAV chunk scanner (BUG-06) ----

    /** Parsed WAV header: format geometry plus the real data-chunk location. */
    public static final class WavHeader {
        public final int audioFormat;
        public final int channels;
        public final int sampleRate;
        public final int bits;
        public final long dataStart;
        public final long dataBytes;

        WavHeader(int audioFormat, int channels, int sampleRate, int bits,
                  long dataStart, long dataBytes) {
            this.audioFormat = audioFormat;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.bits = bits;
            this.dataStart = dataStart;
            this.dataBytes = dataBytes;
        }
    }

    /**
     * Locates the {@code fmt } and {@code data} chunks by walking RIFF
     * sub-chunks ({@code id + size + payload}, word-aligned) instead of
     * assuming the payload starts at byte 44. TakeOne's own writer emits a
     * minimal 44-byte header, but external files (and anything with
     * {@code fact}, {@code LIST}, {@code JUNK} or {@code bext} chunks) place
     * {@code data} later — a hardcoded offset decodes chunk garbage as audio.
     */
    public static WavHeader parseWavHeader(RandomAccessFile r)
            throws Exception {
        long len = r.length();
        if (len < 44) throw new IllegalStateException("too small");
        byte[] riff = new byte[12];
        r.seek(0);
        r.readFully(riff);
        if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F'
                || riff[8] != 'W' || riff[9] != 'A'
                || riff[10] != 'V' || riff[11] != 'E') {
            throw new IllegalStateException("not wav");
        }
        int audioFormat = -1, ch = -1, sr = -1, bits = -1;
        long dataStart = -1, dataBytes = 0;
        byte[] hdr = new byte[8];
        long pos = 12, scanned = 0;
        while (pos + 8 <= len && scanned < 1048576L) {
            r.seek(pos);
            r.readFully(hdr);
            long size = ByteBuffer.wrap(hdr, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            long payload = pos + 8;
            if (hdr[0] == 'f' && hdr[1] == 'm'
                    && hdr[2] == 't' && hdr[3] == ' ') {
                if (size < 16) throw new IllegalStateException("bad fmt");
                byte[] fmt = new byte[(int) Math.min(size, 40)];
                r.seek(payload);
                r.readFully(fmt);
                ByteBuffer b = ByteBuffer.wrap(fmt)
                        .order(ByteOrder.LITTLE_ENDIAN);
                audioFormat = b.getShort(0) & 0xFFFF;
                ch = b.getShort(2) & 0xFFFF;
                sr = b.getInt(4);
                bits = b.getShort(14) & 0xFFFF;
            } else if (hdr[0] == 'd' && hdr[1] == 'a'
                    && hdr[2] == 't' && hdr[3] == 'a') {
                dataStart = payload;
                dataBytes = Math.min(size, Math.max(0, len - payload));
                if (audioFormat != -1) break;
            }
            long step = 8 + size + (size & 1L);
            if (step <= 8) break;
            pos += step;
            scanned += step;
            if (audioFormat != -1 && dataStart != -1) break;
        }
        if (audioFormat == -1 || ch < 1 || sr <= 0 || bits <= 0
                || dataStart < 0) {
            throw new IllegalStateException("bad wav header");
        }
        return new WavHeader(audioFormat, ch, sr, bits, dataStart, dataBytes);
    }

    /**
     * Writes floats little-endian in bounded chunks. {@link RandomAccessFile}
     * {@code writeFloat} is big-endian, which silently byte-swaps every
     * sample of a WAV payload.
     */
    public static void writeFloatsLE(RandomAccessFile w, float[] v, int off,
                                     int n) throws Exception {
        byte[] tmp = new byte[8192 * 4];
        ByteBuffer bb = ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN);
        int done = 0;
        while (done < n) {
            int want = Math.min(8192, n - done);
            bb.clear();
            for (int i = 0; i < want; i++) bb.putFloat(v[off + done + i]);
            w.write(tmp, 0, want * 4);
            done += want;
        }
    }

    /** 240 frames @ 48 kHz = 5.0 ms raised-cosine crossfade window. */
    public static final int CROSSFADE_FRAMES = 240;
    private static final float[] COS_FADE_IN = new float[CROSSFADE_FRAMES];
    static {
        for (int i = 0; i < CROSSFADE_FRAMES; i++) {
            COS_FADE_IN[i] = 0.5f * (1.0f - (float) Math.cos(
                    Math.PI * (i + 0.5) / CROSSFADE_FRAMES));
        }
    }

    /**
     * Blends `frames` of `outgoing` (fading 1 -> 0) with `incoming` (fading 0 -> 1)
     * into `dst`. Uses precomputed LUT when frames == CROSSFADE_FRAMES for zero
     * runtime trig overhead on low-end CPUs.
     */
    public static void crossfadeFrames(float[] outgoing, int outFrameOff,
                                       float[] incoming, int inFrameOff,
                                       float[] dst, int dstFrameOff,
                                       int frames, int ch) {
        if (frames <= 0 || ch < 1) return;
        boolean useLut = (frames == CROSSFADE_FRAMES);
        for (int f = 0; f < frames; f++) {
            float wIn = useLut ? COS_FADE_IN[f]
                    : 0.5f * (1.0f - (float) Math.cos(Math.PI * (f + 0.5) / frames));
            float wOut = 1.0f - wIn;
            int oIdx = (outFrameOff + f) * ch;
            int iIdx = (inFrameOff + f) * ch;
            int dIdx = (dstFrameOff + f) * ch;
            for (int c = 0; c < ch; c++) {
                dst[dIdx + c] = outgoing[oIdx + c] * wOut
                        + incoming[iIdx + c] * wIn;
            }
        }
    }

    /**
     * Applies a short raised-cosine fade-in or fade-out in-place to `buf`
     * (interleaved `frames * ch` floats).
     */
    public static void applyFadeInPlace(float[] buf, int frameOff,
                                        int frames, int ch, boolean fadeIn) {
        if (frames <= 0 || ch < 1) return;
        boolean useLut = (frames == CROSSFADE_FRAMES);
        for (int f = 0; f < frames; f++) {
            float w = useLut ? COS_FADE_IN[f]
                    : 0.5f * (1.0f - (float) Math.cos(Math.PI * (f + 0.5) / frames));
            if (!fadeIn) w = 1.0f - w;
            int idx = (frameOff + f) * ch;
            for (int c = 0; c < ch; c++) {
                buf[idx + c] *= w;
            }
        }
    }

    /**
     * Scans within +/- `radiusFrames` (default 240 = +/- 5ms) around `targetFrame`
     * in a 32-bit float WAV to find the frame with minimum absolute energy
     * (preferring rising zero-crossings on channel 0), eliminating splice clicks.
     */
    public static long snapToZeroCrossing(RandomAccessFile r, long dataStart,
                                          int ch, long targetFrame,
                                          long totalFrames, int radiusFrames) {
        if (totalFrames <= 0 || targetFrame <= 0) return 0;
        if (targetFrame >= totalFrames) return totalFrames;
        long lo = Math.max(0, targetFrame - radiusFrames);
        long hi = Math.min(totalFrames - 1, targetFrame + radiusFrames);
        int span = (int) (hi - lo + 1);
        if (span <= 1) return targetFrame;
        try {
            byte[] raw = new byte[span * ch * 4];
            r.seek(dataStart + lo * ch * 4L);
            r.readFully(raw);
            java.nio.FloatBuffer fb = ByteBuffer.wrap(raw)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            long bestFrame = targetFrame;
            float bestScore = Float.MAX_VALUE;
            float prev0 = fb.get(0);
            for (int f = 1; f < span; f++) {
                float s0 = fb.get(f * ch);
                float energy = Math.abs(s0);
                if (ch > 1) energy += Math.abs(fb.get(f * ch + 1));
                boolean zeroCross = (prev0 <= 0f && s0 >= 0f)
                        || (prev0 >= 0f && s0 <= 0f);
                // Bias strongly toward true zero crossings near targetFrame.
                float distPenalty = Math.abs((lo + f) - targetFrame) * 0.0002f;
                float score = (zeroCross ? energy * 0.1f : energy) + distPenalty;
                if (score < bestScore) {
                    bestScore = score;
                    bestFrame = lo + f;
                }
                prev0 = s0;
            }
            return bestFrame;
        } catch (Exception e) {
            return targetFrame;
        }
    }

    /**
     * Peak envelope of a 32-bit float WAV for waveform display: at most
     * {@code maxBuckets} columns, each the max absolute sample across all
     * channels in its span. Pure I/O + math so it can run on any thread.
     */
    public static float[] loadPeaks(File wav, int maxBuckets) throws Exception {
        RandomAccessFile r = new RandomAccessFile(wav, "r");
        try {
            WavHeader h = parseWavHeader(r);
            if (h.audioFormat != 3 || h.bits != 32 || h.channels < 1) {
                throw new IllegalStateException("need 32-bit float wav");
            }
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = h.dataBytes;
            if (dataBytes <= 0 || dataBytes > onDisk) dataBytes = onDisk;
            long totalFrames = dataBytes / 4 / h.channels;
            if (totalFrames <= 0) return new float[0];
            int buckets = (int) Math.min(Math.max(1, maxBuckets),
                    Math.max(1, totalFrames / 120));
            float[] peaks = new float[buckets];
            int framesPerRead = Math.max(1, 65536 / 4 / h.channels);
            byte[] in = new byte[framesPerRead * h.channels * 4];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            r.seek(h.dataStart);
            long frame = 0;
            while (frame < totalFrames) {
                int want = (int) Math.min(framesPerRead, totalFrames - frame);
                r.readFully(in, 0, want * h.channels * 4);
                fb.clear();
                fb.limit(want * h.channels);
                for (int f = 0; f < want; f++) {
                    float peak = 0f;
                    for (int c = 0; c < h.channels; c++) {
                        float s = fb.get(f * h.channels + c);
                        float a = s < 0f ? -s : s;
                        if (a > peak) peak = a;
                    }
                    int b = (int) ((frame + f) * buckets / totalFrames);
                    if (b >= buckets) b = buckets - 1;
                    if (peak > peaks[b]) peaks[b] = peak;
                }
                frame += want;
            }
            return peaks;
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Streaming sample-rate converter for import duty: arbitrary rates to the
     * fixed 48 kHz session format with bounded memory (a few KB of overlap
     * state, independent of duration).
     *
     * <p>Upsampling uses linear interpolation between adjacent frames.
     * Downsampling first band-limits with a streaming 64-tap windowed-sinc
     * lowpass at 80% of the destination Nyquist, then interpolates — so no
     * aliasing folds back into the session.
     */
    public static final class Resampler {
        private final int srcRate;
        private final int dstRate;
        private final int ch;
        private final boolean downsample;
        private final float[] fir;
        private final int taps;
        /** Overlap for the prefilter: last taps-1 raw frames (zero-padded). */
        private final float[] fCarry;
        /** Last postfilter frame per channel, for interpolation. */
        private final float[] prev;
        private boolean havePrev;
        /** Absolute position (postfilter-stream frames) of the next output. */
        private double nextPos;
        private long inFrames;
        private long outFrames;

        public Resampler(int srcRate, int dstRate, int channels) {
            if (srcRate <= 0 || dstRate <= 0 || channels < 1) {
                throw new IllegalArgumentException("bad resample geometry");
            }
            this.srcRate = srcRate;
            this.dstRate = dstRate;
            this.ch = channels;
            this.downsample = srcRate > dstRate;
            if (downsample) {
                this.taps = 64;
                this.fir = firLowpass(0.8 * (dstRate / 2.0) / srcRate, taps);
            } else {
                this.taps = 0;
                this.fir = null;
            }
            this.fCarry = downsample ? new float[(taps - 1) * channels] : null;
            this.prev = new float[channels];
            this.havePrev = srcRate == dstRate;
            this.nextPos = 0.0;
        }

        /**
         * Converts one chunk; returns the destination-rate frames produced
         * (possibly empty). Input is interleaved source-rate frames.
         */
        public float[] push(float[] in, int frames) {
            if (frames <= 0) return new float[0];
            if (srcRate == dstRate) {
                float[] out = new float[frames * ch];
                System.arraycopy(in, 0, out, 0, frames * ch);
                inFrames += frames;
                outFrames += frames;
                return out;
            }
            // Stage 1: optional streaming lowpass at the source rate.
            float[] stream;
            int streamFrames;
            if (downsample) {
                int ext = (taps - 1) + frames;
                float[] x = new float[ext * ch];
                System.arraycopy(fCarry, 0, x, 0, (taps - 1) * ch);
                System.arraycopy(in, 0, x, (taps - 1) * ch, frames * ch);
                stream = new float[frames * ch];
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < ch; c++) {
                        float acc = 0f;
                        for (int k = 0; k < taps; k++) {
                            acc += x[(f + k) * ch + c] * fir[taps - 1 - k];
                        }
                        stream[f * ch + c] = acc;
                    }
                }
                System.arraycopy(x, frames * ch, fCarry, 0, (taps - 1) * ch);
                streamFrames = frames;
            } else {
                stream = in;
                streamFrames = frames;
            }
            // Stage 2: linear interpolation onto the destination grid using a
            // primitive float[] buffer (no ArrayList<Float> boxing).
            double step = (double) srcRate / dstRate;
            long base = inFrames;
            double span = (base + streamFrames - 1) - nextPos;
            int estFrames = span >= 0 ? (int) Math.floor(span / step) + 2 : 1;
            float[] out = new float[Math.max(4, estFrames) * ch];
            int outPos = 0;
            while (true) {
                long i0 = (long) Math.floor(nextPos) - base;
                double frac = nextPos - Math.floor(nextPos);
                if (i0 + 1 >= streamFrames) break;
                if (outPos + ch > out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                for (int c = 0; c < ch; c++) {
                    float s0 = i0 < 0
                            ? (havePrev ? prev[c] : stream[c])
                            : stream[(int) i0 * ch + c];
                    float s1 = stream[((int) i0 + 1) * ch + c];
                    out[outPos++] = (float) (s0 + (s1 - s0) * frac);
                }
                nextPos += step;
                outFrames++;
            }
            // Retain the last postfilter frame for the next chunk.
            for (int c = 0; c < ch; c++) {
                prev[c] = stream[(streamFrames - 1) * ch + c];
            }
            havePrev = true;
            inFrames += frames;
            return outPos == out.length ? out : Arrays.copyOf(out, outPos);
        }

        /**
         * Drains the tail: fades the last frame out so edge samples still
         * produce output, keeping the total duration exact to within a frame.
         */
        public float[] flush() {
            if (srcRate == dstRate) return new float[0];
            double step = (double) srcRate / dstRate;
            long target = (long) Math.floor(
                    (double) inFrames * dstRate / srcRate);
            int rem = (int) Math.max(0, target - outFrames);
            float[] out = new float[rem * ch];
            int outPos = 0;
            while (outFrames < target) {
                double frac = nextPos - Math.floor(nextPos);
                for (int c = 0; c < ch; c++) {
                    float s0 = havePrev ? prev[c] : 0f;
                    out[outPos++] = (float) (s0 * (1.0 - frac));
                }
                nextPos += step;
                outFrames++;
            }
            return outPos == out.length ? out : Arrays.copyOf(out, outPos);
        }
    }

    /**
     * Streaming symmetric-FIR decimator with the exact indexing of the old
     * batch AMR path (output {@code o} centres on input
     * {@code o * decim - taps / 2}, zero-padded outside the stream), so a
     * chunked conversion is bit-identical to a whole-file one — but the
     * retained window stays under ~17k samples no matter how long the take.
     */
    public static final class FirDecimator {
        private final int decim;
        private final int taps;
        private final int delay;
        private final int half;
        private final float[] fir;
        private float[] buf;
        private int bufLen;
        private long baseAbs;
        private long inAbs;
        private long outCount;

        public FirDecimator(int decim, float[] firTaps) {
            if (decim < 1 || firTaps == null || firTaps.length < 2) {
                throw new IllegalArgumentException("bad decimator");
            }
            this.decim = decim;
            this.fir = firTaps.clone();
            this.taps = firTaps.length;
            this.delay = taps / 2;
            this.half = taps / 2;
            this.buf = new float[16384 + taps];
        }

        private void ensure(int need) {
            if (bufLen + need <= buf.length) return;
            int cap = Math.max(buf.length * 2, bufLen + need);
            float[] bigger = new float[cap];
            System.arraycopy(buf, 0, bigger, 0, bufLen);
            buf = bigger;
        }

        private short emit(long o) {
            long center = o * decim - delay;
            float acc = 0f;
            for (int k = 0; k < half; k++) {
                long i1 = center + k;
                long i2 = center + taps - 1 - k;
                // Only retained samples are real; anything compacted away or
                // past the buffered tail reads as zero (leading/trailing pad).
                float s1 = (i1 >= baseAbs && i1 < baseAbs + bufLen)
                        ? buf[(int) (i1 - baseAbs)] : 0f;
                float s2 = (i2 >= baseAbs && i2 < baseAbs + bufLen)
                        ? buf[(int) (i2 - baseAbs)] : 0f;
                acc += s1 * fir[k] + s2 * fir[taps - 1 - k];
            }
            if (acc > 1f) acc = 1f;
            else if (acc < -1f) acc = -1f;
            return (short) (acc * 32767f);
        }

        private void compact() {
            long needFrom = outCount * decim - delay;
            if (needFrom > baseAbs) {
                int drop = (int) Math.min(bufLen, needFrom - baseAbs);
                System.arraycopy(buf, drop, buf, 0, bufLen - drop);
                bufLen -= drop;
                baseAbs += drop;
            }
        }

        /**
         * Appends mono samples; emits every output whose filter window is
         * fully buffered. Returns shorts appended at {@code out[outPos..]}.
         */
        public int push(float[] in, int n, short[] out, int outPos) {
            if (n <= 0) return 0;
            ensure(n);
            System.arraycopy(in, 0, buf, bufLen, n);
            bufLen += n;
            inAbs += n;
            int made = 0;
            while (true) {
                long center = outCount * decim - delay;
                if (center + taps > baseAbs + bufLen) break;
                out[outPos + made++] = emit(outCount);
                outCount++;
            }
            compact();
            return made;
        }

        /**
         * Emits the tail (zero-padded past the end) so the total output count
         * is exactly {@code totalIn / decim}, matching a batch conversion.
         */
        public int flush(long totalIn, short[] out, int outPos) {
            long want = totalIn / decim;
            int made = 0;
            while (outCount < want) {
                out[outPos + made++] = emit(outCount);
                outCount++;
            }
            return made;
        }
    }

    // ---- trash dir (delete-undo safety net) ----

    /** Subdirectory of the takes dir holding soft-deleted audio. */
    public static File trashDir(File takesDir) {
        File t = new File(takesDir, ".trash");
        // noinspection ResultOfMethodCallIgnored
        t.mkdirs();
        return t;
    }

    /**
     * Moves a backing file into the trash instead of deleting it, so
     * delete-undo can bring the audio back too — not just the entry.
     *
     * @return the trash location, or null when the move failed (caller then
     * falls back to a straight delete).
     */
    public static File moveToTrash(File takesDir, File target) {
        if (target == null || !target.exists()) return null;
        try {
            File trash = trashDir(takesDir);
            File dest = uniqueIn(trash, target.getName());
            if (target.renameTo(dest)) return dest;
            // Cross-volume fallback: copy + delete.
            java.io.FileInputStream in = new java.io.FileInputStream(target);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.getFD().sync();
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {}
                try {
                    out.close();
                } catch (Exception ignored) {}
            }
            if (dest.exists() && dest.length() == target.length()
                    && target.delete()) {
                return dest;
            }
            // noinspection ResultOfMethodCallIgnored
            dest.delete();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Moves a trashed file back to its recorded location.
     *
     * <p>A4-09: returns the file that actually landed. The destination is
     * uniquified when it is taken, so the caller must persist the real path
     * — the old boolean gave no way to learn it and the library row kept
     * pointing at a name that no longer exists. Falls back to copy+delete
     * across volumes, mirroring {@link #moveToTrash(File, File)}.
     *
     * @return the restored file, or null when the move failed.
     */
    public static File restoreFromTrash(File trashFile, File dest) {
        if (trashFile == null || dest == null || !trashFile.exists()) {
            return null;
        }
        try {
            if (dest.exists()) {
                dest = uniqueIn(dest.getParentFile(), dest.getName());
            }
            if (trashFile.renameTo(dest)) return dest;
            File in = new File(trashFile.getAbsolutePath());
            File out = new File(dest.getAbsolutePath());
            java.io.FileInputStream fis = new java.io.FileInputStream(in);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                fos.getFD().sync();
            } finally {
                try {
                    fis.close();
                } catch (Exception ignored) {}
                try {
                    fos.close();
                } catch (Exception ignored) {}
            }
            if (out.exists() && out.length() == in.length() && in.delete()) {
                return out;
            }
            // noinspection ResultOfMethodCallIgnored
            out.delete();
        } catch (Exception ignored) {}
        return null;
    }

    /** Boolean form for callers that only care whether it came back. */
    public static boolean restoreFromTrashOk(File trashFile, File dest) {
        return restoreFromTrash(trashFile, dest) != null;
    }

    private static File uniqueIn(File dir, String name) {
        File c = new File(dir, name);
        if (!c.exists()) return c;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 2;
        while (true) {
            File n = new File(dir, base + " (" + i + ")" + ext);
            if (!n.exists()) return n;
            i++;
        }
    }

    // ---- Stage 3: biquad engine (RBJ + classic analog prototypes) ----

    /**
     * RBJ cookbook peaking EQ biquad. Returns {b0,b1,b2,a1,a2} with a0
     * normalized out. Coefficients are exact at the requested gain; gainDb
     * of 0 degenerates to unity.
     */
    public static float[] rbjPeaking(double sr, double f0, double q,
                                     double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double b0 = 1 + alpha * a, b1 = -2 * cs, b2 = 1 - alpha * a;
        double a0 = 1 + alpha / a, a1 = -2 * cs, a2 = 1 - alpha / a;
        return normBiquad(b0, b1, b2, a0, a1, a2);
    }

    /** RBJ low-shelf biquad (S = shelf slope; 1.0 is steepest without overshoot). */
    public static float[] rbjLowShelf(double sr, double f0, double s,
                                      double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / 2.0 * Math.sqrt((a + 1 / a) * (1 / s - 1) + 2);
        double ap1 = a + 1, am1 = a - 1;
        double sq2sa = 2 * Math.sqrt(a) * alpha;
        double b0 = a * (ap1 - am1 * cs + sq2sa);
        double b1 = 2 * a * (am1 - ap1 * cs);
        double b2 = a * (ap1 - am1 * cs - sq2sa);
        double a0 = ap1 + am1 * cs + sq2sa;
        double a1 = -2 * (am1 + ap1 * cs);
        double a2 = ap1 + am1 * cs - sq2sa;
        return normBiquad(b0, b1, b2, a0, a1, a2);
    }

    /** RBJ high-shelf biquad. */
    public static float[] rbjHighShelf(double sr, double f0, double s,
                                       double gainDb) {
        double a = Math.pow(10, gainDb / 40.0);
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / 2.0 * Math.sqrt((a + 1 / a) * (1 / s - 1) + 2);
        double ap1 = a + 1, am1 = a - 1;
        double sq2sa = 2 * Math.sqrt(a) * alpha;
        double b0 = a * (ap1 + am1 * cs + sq2sa);
        double b1 = -2 * a * (am1 + ap1 * cs);
        double b2 = a * (ap1 + am1 * cs - sq2sa);
        double a0 = ap1 - am1 * cs + sq2sa;
        double a1 = 2 * (am1 - ap1 * cs);
        double a2 = ap1 - am1 * cs - sq2sa;
        return normBiquad(b0, b1, b2, a0, a1, a2);
    }

    /** RBJ notch biquad: unity elsewhere, deep null at f0. */
    public static float[] rbjNotch(double sr, double f0, double q) {
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        return normBiquad(1, -2 * cs, 1, 1 + alpha, -2 * cs, 1 - alpha);
    }

    /** RBJ band-pass biquad (constant 0 dB peak gain). */
    public static float[] rbjBandPass(double sr, double f0, double q) {
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        return normBiquad(alpha, 0, -alpha, 1 + alpha, -2 * cs, 1 - alpha);
    }

    /** RBJ low-pass biquad (Butterworth Q = 0.7071 gives the maximally flat 2nd-order). */
    public static float[] rbjLowPass(double sr, double f0, double q) {
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double b1 = 1 - cs;
        return normBiquad(b1 / 2, b1, b1 / 2, 1 + alpha, -2 * cs, 1 - alpha);
    }

    /** RBJ high-pass biquad. */
    public static float[] rbjHighPass(double sr, double f0, double q) {
        double w = 2.0 * Math.PI * f0 / sr;
        double cs = Math.cos(w), sn = Math.sin(w);
        double alpha = sn / (2.0 * q);
        double b0 = (1 + cs) / 2;
        return normBiquad(b0, -(1 + cs), b0, 1 + alpha, -2 * cs, 1 - alpha);
    }

    private static float[] normBiquad(double b0, double b1, double b2,
                                      double a0, double a1, double a2) {
        return new float[]{
                (float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0),
                (float) (a1 / a0), (float) (a2 / a0)
        };
    }

    /** Identity biquad carrying a plain gain: {g,0,0,0,0}. */
    public static float[] gainBiquad(double gain) {
        return new float[]{(float) gain, 0f, 0f, 0f, 0f};
    }

    /**
     * Analog prototype pole tables for the 3 dB-normalized Bessel filter,
     * orders 1..10 ({sigma, omega} with sigma negative; omega 0 = real pole).
     * Generated offline from the reverse Bessel polynomials and verified to
     * give -3.01 dB at the cutoff for every order (unit test).
     */
    private static final double[][][] BESSEL_POLES = {
            {},
            {{-1.0, 0.0}},
            {{-1.10160133, 0.63600982}},
            {{-1.04740916, 0.99926444}, {-1.32267580, 0.0}},
            {{-1.37006783, 0.41024972}, {-0.99520876, 1.25710574}},
            {{-1.38087733, 0.71790959}, {-0.95767655, 1.47112432},
                    {-1.50231627, 0.0}},
            {{-1.57149040, 0.32089637}, {-1.38185810, 0.97147189},
                    {-0.93065652, 1.66186327}},
            {{-1.61203877, 0.58924451}, {-1.37890322, 1.19156678},
                    {-0.90986778, 1.83645135}, {-1.68436818, 0.0}},
            {{-1.75740840, 0.27286758}, {-1.63693942, 0.82279563},
                    {-1.37384122, 1.38835658}, {-0.89286972, 1.99832584}},
            {{-1.80717053, 0.51238373}, {-1.65239648, 1.03138957},
                    {-1.36758831, 1.56773371}, {-0.87839928, 2.14980052},
                    {-1.85660050, 0.0}},
            {{-1.92761969, 0.24162347}, {-1.84219624, 0.72725760},
                    {-1.66181024, 1.22110022}, {-1.36069228, 1.73350574},
                    {-0.86575690, 2.29260483}}
    };

    public static int maxBesselOrder() {
        return BESSEL_POLES.length - 1;
    }

    /**
     * Bilinear-transformed Butterworth cascade. Pole angles
     * {@code pi/2 + (2k+1)pi/(2N)}; -3.01 dB exactly at f0 for every order.
     */
    public static float[][] butterworth(int order, double sr, double f0,
                                        boolean highpass) {
        order = Math.max(1, Math.min(10, order));
        double k = 2.0 * sr;
        double w0 = k * Math.tan(Math.PI * f0 / sr);
        float[][] out = new float[order / 2 + (order % 2)][];
        int idx = 0;
        for (int i = 0; i < order / 2; i++) {
            double th = Math.PI / 2 + (2 * i + 1) * Math.PI / (2 * order);
            double sigma = w0 * Math.cos(th), omega = w0 * Math.sin(th);
            double pS = sigma, pW = omega;
            if (highpass) {
                double r2 = sigma * sigma + omega * omega;
                pS = w0 * w0 * sigma / r2;
                pW = w0 * w0 * omega / r2;
                pW = Math.abs(pW);
            }
            out[idx++] = secPair(pS, pW, k, highpass, 0);
        }
        if (order % 2 == 1) {
            double a = w0;
            if (highpass) a = w0;
            out[idx++] = secReal(a, k, highpass);
        }
        return out;
    }

    /**
     * Chebyshev Type I cascade. Passband ripple r dB between -r and 0,
     * edge attenuation -r dB (equal-ripple poles; even orders are scaled by
     * 1/sqrt(1+eps^2) so the passband never exceeds 0 dB).
     */
    public static float[][] chebyshev1(int order, double rippleDb,
                                       double sr, double f0,
                                       boolean highpass) {
        order = Math.max(1, Math.min(10, order));
        double eps = Math.sqrt(Math.pow(10, rippleDb / 10.0) - 1);
        double xi = Math.asinh(1.0 / eps) / order;
        double k = 2.0 * sr;
        double w0 = k * Math.tan(Math.PI * f0 / sr);
        float[][] out = new float[order / 2 + (order % 2)][];
        int idx = 0;
        for (int i = 0; i < (order + 1) / 2; i++) {
            double th = (2 * i + 1) * Math.PI / (2 * order);
            double sigma = -Math.sinh(xi) * Math.sin(th) * w0;
            double omega = Math.cosh(xi) * Math.cos(th) * w0;
            if (Math.abs(omega) < 1e-9) {
                double a = -sigma;
                if (highpass) a = w0 * w0 / a;
                out[idx++] = secReal(a, k, highpass);
            } else {
                double pS = sigma, pW = Math.abs(omega);
                if (highpass) {
                    double r2 = sigma * sigma + omega * omega;
                    pS = w0 * w0 * sigma / r2;
                    pW = w0 * w0 * Math.abs(omega) / r2;
                }
                out[idx++] = secPair(pS, pW, k, highpass, 0);
            }
        }
        if (order % 2 == 0) {
            double s = 1.0 / Math.sqrt(1 + eps * eps);
            scaleSection(out[0], s);
        }
        return out;
    }

    /**
     * Chebyshev Type II (inverse Chebyshev) cascade. Flat passband, stopband
     * ripple, edge attenuation exactly -rippleDb; notches at
     * 1/cos(theta) (LP) / cos(theta) (HP) relative to the prewarped cutoff.
     * Normalized to unity DC (LP) / Nyquist (HP).
     */
    public static float[][] chebyshev2(int order, double rippleDb,
                                       double sr, double f0,
                                       boolean highpass) {
        order = Math.max(1, Math.min(10, order));
        double eps = Math.sqrt(Math.pow(10, rippleDb / 10.0) - 1);
        double mu = Math.asinh(eps) / order;
        double k = 2.0 * sr;
        double w0 = k * Math.tan(Math.PI * f0 / sr);
        float[][] out = new float[order / 2 + (order % 2)][];
        int idx = 0;
        for (int i = 0; i < (order + 1) / 2; i++) {
            double th = (2 * i + 1) * Math.PI / (2 * order);
            double cth = Math.cos(th), sth = Math.sin(th);
            double pr = -Math.sinh(mu) * sth, pi = Math.cosh(mu) * cth;
            double d = pr * pr + pi * pi;
            double sigma = pr / d * w0, omega = -pi / d * w0;
            double zb = Math.abs(cth) > 1e-12 ? 1.0 / Math.abs(cth) : 0.0;
            if (Math.abs(omega) < 1e-9 || zb == 0.0) {
                if (Math.abs(omega) < 1e-9) {
                    double a = -sigma;
                    if (highpass) a = w0 * w0 / a;
                    out[idx++] = secReal(a, k, highpass);
                }
            } else {
                double pS = sigma, pW = Math.abs(omega), zeta;
                if (highpass) {
                    double r2 = sigma * sigma + omega * omega;
                    pS = w0 * w0 * sigma / r2;
                    pW = w0 * w0 * Math.abs(omega) / r2;
                    zeta = w0 / zb;
                } else {
                    zeta = w0 * zb;
                }
                out[idx++] = secPair(pS, pW, k, highpass, zeta);
            }
        }
        double g = Math.abs(gainAtZ(out, highpass ? -1.0 : 1.0));
        if (g > 1e-12) scaleSection(out[0], 1.0 / g);
        return out;
    }

    /** 3 dB-normalized Bessel cascade from the pole tables. */
    public static float[][] bessel(int order, double sr, double f0,
                                   boolean highpass) {
        order = Math.max(1, Math.min(maxBesselOrder(), order));
        double k = 2.0 * sr;
        double w0 = k * Math.tan(Math.PI * f0 / sr);
        double[][] poles = BESSEL_POLES[order];
        float[][] out = new float[poles.length][];
        int idx = 0;
        for (double[] pole : poles) {
            double sigma = pole[0] * w0, omega = pole[1] * w0;
            if (omega == 0.0) {
                double a = -sigma;
                if (highpass) a = w0 * w0 / a;
                out[idx++] = secReal(a, k, highpass);
            } else {
                double pS = sigma, pW = omega;
                if (highpass) {
                    double r2 = sigma * sigma + omega * omega;
                    pS = w0 * w0 * sigma / r2;
                    pW = w0 * w0 * omega / r2;
                }
                out[idx++] = secPair(pS, pW, k, highpass, 0);
            }
        }
        return out;
    }

    /**
     * One analog pole pair (or conjugate pair) through the bilinear
     * transform. {@code zb} > 0 adds a zero pair at +-j*zb (inverse
     * Chebyshev); 0 keeps the plain LP/HP numerator.
     */
    private static float[] secPair(double sigma, double omega, double k,
                                   boolean highpass, double zb) {
        double r2 = sigma * sigma + omega * omega;
        double a0 = k * k - 2 * sigma * k + r2;
        double a1 = 2 * (r2 - k * k);
        double a2 = k * k + 2 * sigma * k + r2;
        double b0, b1, b2;
        if (zb > 0) {
            double zs = zb * zb;
            b0 = k * k + zs;
            b1 = 2 * (zs - k * k);
            b2 = k * k + zs;
        } else if (highpass) {
            b0 = k * k;
            b1 = -2 * k * k;
            b2 = k * k;
        } else {
            b0 = r2;
            b1 = 2 * r2;
            b2 = r2;
        }
        return new float[]{(float) (b0 / a0), (float) (b1 / a0),
                (float) (b2 / a0), (float) (a1 / a0), (float) (a2 / a0)};
    }

    /** First-order section from a real analog pole at s = -a. */
    private static float[] secReal(double a, double k, boolean highpass) {
        double a0 = k + a;
        double a1 = a - k;
        if (highpass) {
            return new float[]{(float) (k / a0), (float) (-k / a0), 0f,
                    (float) (a1 / a0), 0f};
        }
        return new float[]{(float) (a / a0), (float) (a / a0), 0f,
                (float) (a1 / a0), 0f};
    }

    private static void scaleSection(float[] sec, double s) {
        sec[0] *= (float) s;
        sec[1] *= (float) s;
        sec[2] *= (float) s;
    }

    /** Cascade gain at z = +1 (DC) or z = -1 (Nyquist). */
    private static double gainAtZ(float[][] sections, double z) {
        double g = 1.0;
        for (float[] c : sections) {
            double num = c[0] + c[1] * z + c[2] * z * z;
            double den = 1.0 + c[3] * z + c[4] * z * z;
            g *= num / den;
        }
        return g;
    }

    /** Magnitude of a biquad cascade at freqHz, in dB (for tests/graphs). */
    public static double cascadeMagDb(float[][] sections, double freqHz,
                                      double sr) {
        double w = 2.0 * Math.PI * freqHz / sr;
        double cw = Math.cos(w), sw = Math.sin(w);
        double c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
        double g = 1.0;
        for (float[] c : sections) {
            double nr = c[0] + c[1] * cw + c[2] * c2;
            double ni = -(c[1] * sw + c[2] * s2);
            double dr = 1 + c[3] * cw + c[4] * c2;
            double di = -(c[3] * sw + c[4] * s2);
            double n2 = nr * nr + ni * ni;
            double d2 = dr * dr + di * di;
            g *= Math.sqrt(n2 / Math.max(1e-30, d2));
        }
        return 20.0 * Math.log10(Math.max(1e-12, g));
    }

    /** dB to linear amplitude, clamped to a sane floor. */
    public static float dbToLin(double db) {
        return (float) Math.pow(10, db / 20.0);
    }

    /** Linear amplitude to dB with a -140 floor. */
    public static float linToDb(double lin) {
        double a = Math.abs(lin);
        return a < 1e-7 ? -140f : (float) (20.0 * Math.log10(a));
    }

    // ---- FFT (iterative radix-2, in place) ----

    /** In-place complex FFT; {@code n} must be a power of two. */
    public static void fft(float[] re, float[] im, boolean inverse) {
        int n = re.length;
        if (n <= 1) return;
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("fft size must be power of 2");
        }
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = i + j + len / 2;
                    float vr = re[v] * cr - im[v] * ci;
                    float vi = re[v] * ci + im[v] * cr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
        if (inverse) {
            float inv = 1f / n;
            for (int i = 0; i < n; i++) {
                re[i] *= inv;
                im[i] *= inv;
            }
        }
    }

    /** Blackman window of length n (for FIR truncation). */
    public static float[] blackmanWindow(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1))
                    + 0.08 * Math.cos(4 * Math.PI * i / (n - 1)));
        }
        return w;
    }

    /**
     * Deletes trash files older than maxAgeMs (storage hygiene for
     * long-forgotten deletes).
     *
     * @return bytes reclaimed.
     */
    public static long pruneTrash(File trashDir, long maxAgeMs) {
        long freed = 0;
        if (trashDir == null) return 0;
        File[] files;
        try {
            files = trashDir.listFiles();
        } catch (Exception e) {
            return 0;
        }
        if (files == null) return 0;
        long now = System.currentTimeMillis();
        for (File f : files) {
            try {
                if (f.isFile() && now - f.lastModified() > maxAgeMs) {
                    freed += f.length();
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
        return freed;
    }
}
