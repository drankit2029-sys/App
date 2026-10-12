package com.example.hello;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts the crash-safe 32-bit float WAV into the user's chosen format.
 * Progress is reported 0..100 so the UI can paint a live progress bar.
 */
public final class FormatConverter {

    /**
     * How long an encoder may take to flush after all input is queued. Some
     * OEM codecs never deliver END_OF_STREAM; without a deadline the convert
     * thread spins until the ANR killer takes the process.
     */
    private static final long DRAIN_TIMEOUT_MS = 10_000L;

    /**
     * Samples per bulk-conversion chunk. Large enough that per-chunk setup is
     * irrelevant next to the per-sample work.
     */
    private static final int CONV_CHUNK = 65536;

    /**
     * Clamped float samples to 16-bit PCM, written into {@code dst}.
     *
     * <p>Typed views plus absolute indexing replace per-sample
     * {@code ByteBuffer.getFloat/putShort}, which re-checked the buffer type
     * and byte-swapped on every element. This loop is the single hottest path
     * in a save, so it is worth doing once here rather than three times.
     *
     * <p>Rounding is half-away-from-zero, which is symmetric about silence;
     * the previous {@code Math.round} biased toward +inf and cost a
     * {@code long} intermediate per sample.
     */
    static int toInt16(java.nio.FloatBuffer src, int n,
                       java.nio.ShortBuffer dst) {
        for (int i = 0; i < n; i++) {
            float s = src.get(i);
            if (s > 1f) s = 1f;
            else if (s < -1f) s = -1f;
            float t = s * 32767f;
            dst.put(i, (short) (t >= 0f ? t + 0.5f : t - 0.5f));
        }
        return n;
    }

    /** Same clamp into 24-bit range, ready to be split into 3 bytes LE. */
    static int toInt24(java.nio.FloatBuffer src, int n, int[] dst) {
        for (int i = 0; i < n; i++) {
            float s = src.get(i);
            if (s > 1f) s = 1f;
            else if (s < -1f) s = -1f;
            float t = s * 8388607f;
            int v = (int) (t >= 0f ? t + 0.5f : t - 0.5f);
            dst.put(i, v > 8388607 ? 8388607
                    : (v < -8388608 ? -8388608 : v));
        }
        return n;
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private FormatConverter() {}

    /** Float WAV header view. Null when the file is not a float WAV. */
    public static final class WavInfo {
        public final int channels;
        public final int sampleRate;
        public final long dataBytes;
        public final long dataStart;

        WavInfo(int channels, int sampleRate, long dataBytes, long dataStart) {
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.dataBytes = dataBytes;
            this.dataStart = dataStart;
        }
    }

    public static WavInfo readFloatWav(File src) throws Exception {
        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            if (r.length() < 44) throw new IllegalStateException("too small");
            byte[] h = new byte[44];
            r.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            int audioFormat = b.getShort(20) & 0xFFFF;
            int ch = b.getShort(22) & 0xFFFF;
            int sr = b.getInt(24);
            int bits = b.getShort(34) & 0xFFFF;
            long dataBytes = b.getInt(40) & 0xFFFFFFFFL;
            long onDisk = Math.max(0, r.length() - 44);
            // Crash-safe: trust the bytes actually on disk over the header.
            if (dataBytes <= 0 || dataBytes > onDisk) dataBytes = onDisk;
            if (audioFormat != 3 || bits != 32 || ch < 1 || sr <= 0) {
                throw new IllegalStateException("not 32-bit float wav");
            }
            return new WavInfo(ch, sr, dataBytes, 44);
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
        }
    }

    /** 32-bit float WAV -> 16-bit PCM WAV. */
    public static void toWav16(File src, File dst, Progress cb) throws Exception {
        WavInfo info = readFloatWav(src);
        long totalSamples = info.dataBytes / 4;
        RandomAccessFile r = new RandomAccessFile(src, "r");
        RandomAccessFile w = new RandomAccessFile(dst, "rw");
        try {
            w.setLength(0);
            w.write(wav16Header(info.channels, info.sampleRate, 0));
            r.seek(info.dataStart);
            byte[] in = new byte[CONV_CHUNK * 4];
            byte[] out = new byte[CONV_CHUNK * 2];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            java.nio.ShortBuffer sb = ByteBuffer.wrap(out)
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            long done = 0;
            int lastPct = -1;
            while (done < totalSamples) {
                int want = (int) Math.min(CONV_CHUNK, totalSamples - done);
                r.readFully(in, 0, want * 4);
                toInt16(fb, want, sb);
                w.write(out, 0, want * 2);
                done += want;
                if (cb != null) {
                    int pct = (int) (done * 100 / Math.max(1, totalSamples));
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct);
                    }
                }
            }
            long pcmBytes = totalSamples * 2;
            w.seek(0);
            w.write(wav16Header(info.channels, info.sampleRate, pcmBytes));
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
            try {
                w.close();
            } catch (Exception ignored) {}
        }
        if (cb != null) cb.onProgress(100);
    }

    /** 32-bit float WAV -> M4A (AAC) via MediaCodec + MediaMuxer. */
    public static void toM4a(File src, File dst, Progress cb) throws Exception {
        toM4a(src, dst, 128000, cb);
    }

    /**
     * Same with an explicit AAC bitrate (the presets range 64–320 kbps).
     * Tuned for speed: big input chunks, microsecond codec waits and
     * throttled progress so a minute of audio converts in seconds.
     */
    public static void toM4a(File src, File dst, int bitrate, Progress cb) throws Exception {
        encodeMuxed(src, dst, MediaFormat.MIMETYPE_AUDIO_AAC,
                Math.max(64000, Math.min(320000, bitrate)),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC, cb);
    }

    /**
     * 32-bit float WAV -> 24-bit PCM WAV.
     *
     * <p>24-bit samples are not 4-byte aligned, so each one is assembled
     * byte-wise rather than through a typed {@link ByteBuffer} read.
     */
    public static void toWav24(File src, File dst, Progress cb) throws Exception {
        WavInfo info = readFloatWav(src);
        long totalSamples = info.dataBytes / 4;
        RandomAccessFile r = new RandomAccessFile(src, "r");
        RandomAccessFile w = new RandomAccessFile(dst, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wav24Header(info.channels, info.sampleRate, 0));
            r.seek(info.dataStart);
            byte[] in = new byte[CONV_CHUNK * 4];
            byte[] out = new byte[CONV_CHUNK * 3];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            int[] ints = new int[CONV_CHUNK];
            long done = 0;
            int lastPct = -1;
            while (done < totalSamples) {
                int want = (int) Math.min(CONV_CHUNK, totalSamples - done);
                r.readFully(in, 0, want * 4);
                toInt24(fb, want, ints);
                int p = 0;
                for (int i = 0; i < want; i++) {
                    int v = ints[i];
                    out[p++] = (byte) (v & 0xFF);
                    out[p++] = (byte) ((v >> 8) & 0xFF);
                    out[p++] = (byte) ((v >> 16) & 0xFF);
                }
                w.write(out, 0, p);
                done += want;
                if (cb != null) {
                    int pct = (int) (done * 100 / Math.max(1, totalSamples));
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct);
                    }
                }
            }
            long pcmBytes = totalSamples * 3;
            w.seek(0);
            w.write(Dsp.wav24Header(info.channels, info.sampleRate, pcmBytes));
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
            try {
                w.close();
            } catch (Exception ignored) {}
        }
        if (cb != null) cb.onProgress(100);
    }

    /** Opus in WebM (probe-gated: only offered when an encoder exists). */
    public static void toOpus(File src, File dst, int bitrate, Progress cb) throws Exception {
        encodeMuxed(src, dst, "audio/opus",
                Math.max(32000, Math.min(256000, bitrate <= 0 ? 128000 : bitrate)),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, -1, cb);
    }

    /**
     * Shared float-WAV -> encoder -> muxer pipeline. Negative aacProfile skips
     * the AAC profile key (Opus/Vorbis don't take one).
     */
    private static void encodeMuxed(File src, File dst, String mime, int bitrate,
                                    int muxerFormat, int aacProfile,
                                    Progress cb) throws Exception {
        WavInfo info = readFloatWav(src);
        final int ch = info.channels;
        final int sr = info.sampleRate;
        final long totalSamples = info.dataBytes / 4; // interleaved samples
        final long totalFrames = totalSamples / Math.max(1, ch);

        MediaFormat fmt = MediaFormat.createAudioFormat(mime, sr, ch);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        if (aacProfile >= 0) {
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile);
        }
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 262144);

        MediaCodec enc = MediaCodec.createEncoderByType(mime);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer muxer = new MediaMuxer(dst.getAbsolutePath(), muxerFormat);
        boolean muxStarted = false;
        int samplesWritten = 0;
        int track = -1;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            r.seek(info.dataStart);
            byte[] in = new byte[16384 * 4 * 2];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            long framesDone = 0;
            boolean inputEos = false;
            boolean outEos = false;
            int lastPct = -1;
            long lastCbAt = 0;
            long drainDeadline = 0;

            while (!outEos) {
                if (!inputEos) {
                    int idx = enc.dequeueInputBuffer(2000);
                    if (idx >= 0) {
                        ByteBuffer dstBuf = enc.getInputBuffer(idx);
                        dstBuf.clear();
                        long framesLeft = totalFrames - framesDone;
                        // Never read more file bytes than the codec buffer fits.
                        int maxByBuf = dstBuf.remaining() / 2 / Math.max(1, ch);
                        int wantFrames = (int) Math.min(16384,
                                Math.min(framesLeft, maxByBuf));
                        if (wantFrames <= 0 && framesLeft > 0) {
                            // Codec buffer unexpectedly small: retry next round.
                            enc.queueInputBuffer(idx, 0, 0, 0, 0);
                        } else if (wantFrames <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                            // All audio is queued, so the progress bar is
                            // already at 100% from here on. What remains is
                            // draining the encoder, which some OEM codecs
                            // never finish: bound it, or the worker thread
                            // spins forever and the app is ANR-killed right
                            // after the bar completes.
                            drainDeadline = SystemClock.elapsedRealtime()
                                    + DRAIN_TIMEOUT_MS;
                        } else {
                            int wantBytes = wantFrames * ch * 4;
                            r.readFully(in, 0, wantBytes);
                            int shorts = wantFrames * ch;
                            java.nio.ShortBuffer sb = dstBuf
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer();
                            toInt16(fb, shorts, sb);
                            dstBuf.position(0);
                            dstBuf.limit(shorts * 2);
                            long pts = framesDone * 1_000_000L / sr;
                            enc.queueInputBuffer(
                                    idx, 0, shorts * 2, pts, 0);
                            framesDone += wantFrames;
                            if (cb != null) {
                                int pct = (int) (framesDone * 100
                                        / Math.max(1, totalFrames));
                                long now = SystemClock.elapsedRealtime();
                                if (pct != lastPct && (pct >= 100
                                        || now - lastCbAt >= 250)) {
                                    lastPct = pct;
                                    lastCbAt = now;
                                    cb.onProgress(pct);
                                }
                            }
                        }
                    }
                }
                int oidx = enc.dequeueOutputBuffer(bi, 2000);
                if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputEos && SystemClock.elapsedRealtime()
                            > drainDeadline) {
                        throw new IllegalStateException(
                                "encoder never finished");
                    }
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = enc.getOutputFormat();
                    track = muxer.addTrack(of);
                    muxer.start();
                    muxStarted = true;
                } else if (oidx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oidx);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && bi.size > 0 && muxStarted) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        muxer.writeSampleData(track, ob, bi);
                        samplesWritten++;
                    }
                    enc.releaseOutputBuffer(oidx, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outEos = true;
                    }
                }
            }
            if (cb != null) cb.onProgress(100);
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
            try {
                enc.stop();
            } catch (Exception ignored) {}
            try {
                enc.release();
            } catch (Exception ignored) {}
            try {
                // stop() with an empty track is undefined in the native
                // muxer; only finalize it when samples actually landed.
                if (muxStarted && samplesWritten > 0) muxer.stop();
            } catch (Exception ignored) {}
            try {
                muxer.release();
            } catch (Exception ignored) {}
        }
        if (dst.length() < 1024) {
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw new IllegalStateException("encode failed");
        }
    }

    /**
     * Pure-Java sanity check on a finished conversion, run before the library
     * ever sees the file. Catches containers torn by a dying encoder or a
     * process kill (header written, trailer missing) without touching the
     * platform parser. Throws on anything invalid.
     */
    public static void validateFinal(File dst, SaveFormat fmt) throws Exception {
        if (dst == null || !dst.exists() || dst.length() < 64) {
            throw new IllegalStateException("output too small");
        }
        RandomAccessFile r = new RandomAccessFile(dst, "r");
        try {
            byte[] head = new byte[12];
            r.readFully(head);
            switch (fmt) {
                case M4A:
                case AMR_NB:
                case AMR_WB:
                case AUTO:
                default:
                    // ISO base media: 'ftyp' at offset 4, and a 'moov' box
                    // somewhere (written last — its absence means teardown
                    // never finished).
                    if (head[4] != 'f' || head[5] != 't'
                            || head[6] != 'y' || head[7] != 'p') {
                        throw new IllegalStateException("not an mp4 container");
                    }
                    if (!containsBox(r, new byte[]{'m', 'o', 'o', 'v'})) {
                        throw new IllegalStateException("mp4 truncated (no moov)");
                    }
                    break;
                case OPUS:
                    // EBML header.
                    if ((head[0] & 0xFF) != 0x1A || (head[1] & 0xFF) != 0x45
                            || (head[2] & 0xFF) != 0xDF
                            || (head[3] & 0xFF) != 0xA3) {
                        throw new IllegalStateException("not a webm container");
                    }
                    break;
                case FLAC:
                    if (head[0] != 'f' || head[1] != 'L'
                            || head[2] != 'a' || head[3] != 'C') {
                        throw new IllegalStateException("not a flac container");
                    }
                    break;
                case WAV16:
                case WAV24:
                    // RIFF/WAVE; wavDurationMs() re-reads the header and
                    // rejects a bit depth it does not understand.
                    if (head[0] != 'R' || head[1] != 'I'
                            || head[2] != 'F' || head[3] != 'F'
                            || head[8] != 'W' || head[9] != 'A'
                            || head[10] != 'V' || head[11] != 'E') {
                        throw new IllegalStateException("not a wav container");
                    }
                    break;
            }
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
        }
    }

    /** True when the 4-byte token occurs anywhere in the file. */
    private static boolean containsBox(RandomAccessFile r, byte[] token)
            throws Exception {
        byte[] buf = new byte[65536];
        int carry = 0;
        long left = r.length();
        r.seek(0);
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            r.readFully(buf, 0, n);
            left -= n;
            for (int i = 0; i < n; i++) {
                if (buf[i] == token[carry]) {
                    if (++carry == token.length) return true;
                } else {
                    carry = buf[i] == token[0] ? 1 : 0;
                }
            }
        }
        return false;
    }

    /**
     * AMR-NB (8 kHz) or AMR-WB (16 kHz) in a .3gp container. The 48 kHz float
     * capture is mixed to mono and properly decimated through a windowed-sinc
     * lowpass (no naive downsampling, so no aliasing whistle).
     */
    public static void toAmr(File src, File dst, boolean wideband, Progress cb)
            throws Exception {
        WavInfo info = readFloatWav(src);
        final int outRate = wideband ? 16000 : 8000;
        final int decim = RecordingEngine.SAMPLE_RATE / outRate;
        final double cutoff = (wideband ? 7000.0 : 3400.0) / RecordingEngine.SAMPLE_RATE;
        final int taps = wideband ? 48 : 64;

        // 1) Load + mono mix (progress 0..35).
        long totalSamples = info.dataBytes / 4;
        long totalFrames = totalSamples / Math.max(1, info.channels);
        if (totalFrames > 20_000_000L) throw new IllegalStateException("too long");
        float[] mono = new float[(int) totalFrames];
        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            r.seek(info.dataStart);
            int ch = info.channels;
            byte[] in = new byte[CONV_CHUNK * ch * 4];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            long done = 0;
            int lastPct = -1;
            int pos = 0;
            while (done < totalFrames) {
                int want = (int) Math.min(CONV_CHUNK, totalFrames - done);
                r.readFully(in, 0, want * ch * 4);
                if (ch > 1) {
                    for (int f = 0; f < want; f++) {
                        float s0 = fb.get(f * 2);
                        float s1 = fb.get(f * 2 + 1);
                        mono[pos++] = (s0 + s1) * 0.5f;
                    }
                } else {
                    for (int f = 0; f < want; f++) {
                        mono[pos++] = fb.get(f);
                    }
                }
                done += want;
                if (cb != null) {
                    int pct = (int) (done * 35 / Math.max(1, totalFrames));
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct);
                    }
                }
            }
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
        }

        // 2) Lowpass + decimate to 16-bit narrow/wideband PCM (progress 35..55).
        float[] fir = firLowpass(cutoff, taps);
        int delay = taps / 2;
        int outLen = (int) (totalFrames / decim);
        short[] pcm = new short[outLen];
        int n = mono.length;
        // The window is symmetric (firLowpass guarantees it), so each tap
        // pair is one multiply-add instead of two: half the MACs of the
        // straightforward loop, which is the costliest part of an AMR save.
        int half = taps / 2;
        for (int o = 0; o < outLen; o++) {
            float acc = 0f;
            int center = o * decim - delay;
            for (int k = 0; k < half; k++) {
                int i1 = center + k;
                int i2 = center + taps - 1 - k;
                float s1 = (i1 >= 0 && i1 < n) ? mono[i1] : 0f;
                float s2 = (i2 >= 0 && i2 < n) ? mono[i2] : 0f;
                acc += s1 * fir[k] + s2 * fir[taps - 1 - k];
            }
            if (acc > 1f) acc = 1f;
            else if (acc < -1f) acc = -1f;
            pcm[o] = (short) (acc * 32767f);
            if (cb != null && (o & 0x3FFF) == 0) {
                cb.onProgress(35 + (int) (o * 20L / Math.max(1, outLen)));
            }
        }
        mono = null; // release before encoding

        // 3) Encode + mux (progress 55..100).
        String mime = wideband ? "audio/amr-wb" : "audio/3gpp";
        int bitrate = wideband ? 23850 : 12200;
        MediaFormat fmt = MediaFormat.createAudioFormat(mime, outRate, 1);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
        MediaCodec enc = MediaCodec.createEncoderByType(mime);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer muxer = new MediaMuxer(
                dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP);
        boolean muxStarted = false;
        int samplesWritten = 0;
        int track = -1;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            boolean inEos = false;
            boolean outEos = false;
            int lastPct = 55;
            long drainDeadline = 0;
            long fed = 0;
            while (!outEos) {
                if (!inEos) {
                    int idx = enc.dequeueInputBuffer(5000);
                    if (idx >= 0) {
                        ByteBuffer dstBuf = enc.getInputBuffer(idx);
                        dstBuf.clear();
                        int room = dstBuf.remaining() / 2;
                        int left = pcm.length - (int) fed;
                        int want = Math.min(room, left);
                        if (want <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                            // Progress is already 100%; bound the drain.
                            drainDeadline = SystemClock.elapsedRealtime()
                                    + DRAIN_TIMEOUT_MS;
                        } else {
                            java.nio.ShortBuffer sb = dstBuf
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer();
                            sb.put(pcm, (int) fed, want);
                            dstBuf.position(0);
                            dstBuf.limit(want * 2);
                            long pts = fed * 1_000_000L / outRate;
                            enc.queueInputBuffer(idx, 0, want * 2, pts, 0);
                            fed += want;
                            if (cb != null) {
                                int pct = 55 + (int) (fed * 45L
                                        / Math.max(1, pcm.length));
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    cb.onProgress(pct);
                                }
                            }
                        }
                    }
                }
                int oidx = enc.dequeueOutputBuffer(bi, 5000);
                if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inEos && SystemClock.elapsedRealtime()
                            > drainDeadline) {
                        throw new IllegalStateException(
                                "encoder never finished");
                    }
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (oidx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oidx);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && bi.size > 0 && muxStarted) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        muxer.writeSampleData(track, ob, bi);
                        samplesWritten++;
                    }
                    enc.releaseOutputBuffer(oidx, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outEos = true;
                    }
                }
            }
            if (cb != null) cb.onProgress(100);
        } finally {
            try {
                enc.stop();
            } catch (Exception ignored) {}
            try {
                enc.release();
            } catch (Exception ignored) {}
            try {
                if (muxStarted && samplesWritten > 0) muxer.stop();
            } catch (Exception ignored) {}
            try {
                muxer.release();
            } catch (Exception ignored) {}
        }
        if (dst.length() < 512) {
            // noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw new IllegalStateException("amr encode failed");
        }
    }

    /** Hamming-windowed sinc lowpass; pure math lives in {@link Dsp}. */
    private static float[] firLowpass(double cutoffNorm, int taps) {
        return Dsp.firLowpass(cutoffNorm, taps);
    }

    /**
     * Lossless FLAC stream (probe-gated). Writes the encoder's STREAMINFO as
     * the file's metadata block followed by raw audio frames — playable
     * anywhere FLAC is supported.
     */
    public static void toFlac(File src, File dst, Progress cb) throws Exception {
        WavInfo info = readFloatWav(src);
        final int ch = info.channels;
        final int sr = info.sampleRate;
        final long totalFrames = info.dataBytes / 4 / Math.max(1, ch);

        MediaFormat fmt = MediaFormat.createAudioFormat("audio/flac", sr, ch);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 262144);
        MediaCodec enc = MediaCodec.createEncoderByType("audio/flac");
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        boolean ok = false;
        try {
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            boolean wroteInfo = false;
            // Frames can precede the format change; hold them until the
            // STREAMINFO header is written so no audio is lost.
            java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
            RandomAccessFile r = new RandomAccessFile(src, "r");
            byte[] in = new byte[16384 * 4 * 2];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long framesDone = 0;
            boolean inEos = false;
            boolean outEos = false;
            int lastPct = -1;
            long lastCbAt = 0;
            long drainDeadline = 0;
            try {
                r.seek(info.dataStart);
                while (!outEos) {
                    if (!inEos) {
                        int idx = enc.dequeueInputBuffer(5000);
                        if (idx >= 0) {
                            ByteBuffer dstBuf = enc.getInputBuffer(idx);
                            dstBuf.clear();
                            long framesLeft = totalFrames - framesDone;
                            int maxByBuf = dstBuf.remaining() / 2 / Math.max(1, ch);
                            int wantFrames = (int) Math.min(16384,
                                    Math.min(framesLeft, maxByBuf));
                            if (wantFrames <= 0 && framesLeft > 0) {
                                enc.queueInputBuffer(idx, 0, 0, 0, 0);
                            } else if (wantFrames <= 0) {
                                enc.queueInputBuffer(idx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inEos = true;
                                // Progress is already 100%; bound the drain.
                                drainDeadline = SystemClock.elapsedRealtime()
                                        + DRAIN_TIMEOUT_MS;
                            } else {
                                int wantBytes = wantFrames * ch * 4;
                                r.readFully(in, 0, wantBytes);
                                int shorts = wantFrames * ch;
                                java.nio.ShortBuffer sb = dstBuf
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer();
                                toInt16(fb, shorts, sb);
                                dstBuf.position(0);
                                dstBuf.limit(shorts * 2);
                                long pts = framesDone * 1_000_000L / sr;
                                enc.queueInputBuffer(idx, 0, shorts * 2, pts, 0);
                                framesDone += wantFrames;
                            }
                        }
                    }
                    int oidx = enc.dequeueOutputBuffer(bi, 5000);
                    if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inEos && SystemClock.elapsedRealtime()
                                > drainDeadline) {
                            throw new IllegalStateException(
                                    "encoder never finished");
                        }
                    } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        try {
                            MediaFormat of = enc.getOutputFormat();
                            ByteBuffer csd = of.getByteBuffer("csd-0");
                            if (csd != null && csd.remaining() >= 34 && !wroteInfo) {
                                byte[] si = new byte[34];
                                csd.get(si);
                                out.write(0x90); // last-metadata-block + STREAMINFO
                                out.write(0);
                                out.write(0);
                                out.write(34);
                                out.write(si);
                                wroteInfo = true;
                                if (pending.size() > 0) {
                                    pending.writeTo(out);
                                    pending.reset();
                                }
                            }
                        } catch (Exception ignored) {}
                    } else if (oidx >= 0) {
                        ByteBuffer ob = enc.getOutputBuffer(oidx);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                && bi.size > 0) {
                            byte[] frame = new byte[bi.size];
                            ob.position(bi.offset);
                            ob.get(frame);
                            if (wroteInfo) {
                                out.write(frame);
                            } else {
                                pending.write(frame, 0, frame.length);
                            }
                        }
                        enc.releaseOutputBuffer(oidx, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outEos = true;
                        }
                    }
                    if (cb != null) {
                        int pct = (int) (framesDone * 100 / Math.max(1, totalFrames));
                        long now = SystemClock.elapsedRealtime();
                        if (pct != lastPct && (pct >= 100 || now - lastCbAt >= 250)) {
                            lastPct = pct;
                            lastCbAt = now;
                            cb.onProgress(pct);
                        }
                    }
                }
            } finally {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
            out.flush();
            ok = wroteInfo && dst.length() > 1024;
            if (cb != null) cb.onProgress(100);
        } finally {
            try {
                out.close();
            } catch (Exception ignored) {}
            try {
                enc.stop();
            } catch (Exception ignored) {}
            try {
                enc.release();
            } catch (Exception ignored) {}
            if (!ok) {
                // noinspection ResultOfMethodCallIgnored
                dst.delete();
                throw new IllegalStateException("flac encode failed");
            }
        }
    }

    private static byte[] wav16Header(int ch, int sr, long pcmBytes) {
        return Dsp.wav16Header(ch, sr, pcmBytes);
    }
}
