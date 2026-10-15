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
            dst[i] = v > 8388607 ? 8388607
                    : (v < -8388608 ? -8388608 : v);
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
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = h.dataBytes;
            // Crash-safe: trust the bytes actually on disk over the header.
            if (dataBytes <= 0 || dataBytes > onDisk) dataBytes = onDisk;
            if (h.audioFormat != 3 || h.bits != 32 || h.channels < 1
                    || h.sampleRate <= 0) {
                throw new IllegalStateException("not 32-bit float wav");
            }
            return new WavInfo(h.channels, h.sampleRate, dataBytes,
                    h.dataStart);
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
                    // The EBML magic is written before any audio is muxed, so
                    // it alone would accept a truncated file with zero
                    // clusters. Require at least one Cluster element.
                    if (!containsBox(r, new byte[]{0x1F, 0x43,
                            (byte) 0xB6, 0x75})) {
                        throw new IllegalStateException(
                                "webm has no clusters (truncated)");
                    }
                    break;
                case FLAC: {
                    // Magic alone is not enough: a wrong metadata block type
                    // (0x90) or a 0 Hz sample rate produces a file every
                    // player rejects.
                    byte[] fh = new byte[42];
                    r.seek(0);
                    r.readFully(fh);
                    if (!FlacHeader.validHead(fh)) {
                        throw new IllegalStateException(
                                "flac streaminfo invalid");
                    }
                    break;
                }
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
                    // A header with a zero data size is a conversion that
                    // died before writing any payload.
                    if (Dsp.wavDurationMs(dst) <= 0) {
                        throw new IllegalStateException("wav has no audio");
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
     *
     * <p>Fully streaming: a bounded chunk plus the decimator's ~17k-sample
     * window is all that is ever retained, so takes of any length convert
     * with constant memory and no duration cap.
     */
    public static void toAmr(File src, File dst, boolean wideband, Progress cb)
            throws Exception {
        WavInfo info = readFloatWav(src);
        final int outRate = wideband ? 16000 : 8000;
        final int decim = Math.max(1, info.sampleRate / outRate);
        final double cutoff = (wideband ? 7000.0 : 3400.0) / info.sampleRate;
        final int taps = wideband ? 48 : 64;
        Dsp.FirDecimator decimator =
                new Dsp.FirDecimator(decim, Dsp.firLowpass(cutoff, taps));

        final int ch = info.channels;
        final long totalFrames = info.dataBytes / 4 / Math.max(1, ch);
        final long expectOut = totalFrames / decim;
        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            r.seek(info.dataStart);
            String mime = wideband ? "audio/amr-wb" : "audio/3gpp";
            int bitrate = wideband ? 23850 : 12200;
            MediaFormat fmt = MediaFormat.createAudioFormat(mime, outRate, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
            MediaCodec enc = MediaCodec.createEncoderByType(mime);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            MediaMuxer muxer = new MediaMuxer(
                    dst.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP);
            boolean muxStarted = false;
            int samplesWritten = 0;
            int track = -1;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            byte[] in = new byte[CONV_CHUNK * ch * 4];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] mono = new float[CONV_CHUNK];
            // NEW-08 fix: removed unused `short[] decOut` allocation.
            // Encoder backlog: decimated shorts awaiting codec room.
            short[] pend = new short[32768];
            int pendLen = 0;
            long readFrames = 0;
            long fed = 0;
            boolean tailFlushed = false;
            boolean inEos = false;
            boolean outEos = false;
            int lastPct = -1;
            long drainDeadline = 0;
            try {
                while (!outEos) {
                    if (!inEos) {
                        // 1) Produce more decimated samples while the backlog
                        // has room and the file has frames.
                        if (pendLen < pend.length / 2
                                && readFrames < totalFrames) {
                            int want = (int) Math.min(CONV_CHUNK,
                                    totalFrames - readFrames);
                            r.readFully(in, 0, want * ch * 4);
                            fb.clear();
                            fb.limit(want * ch);
                            if (ch > 1) {
                                for (int f = 0; f < want; f++) {
                                    mono[f] = (fb.get(f * 2)
                                            + fb.get(f * 2 + 1)) * 0.5f;
                                }
                            } else {
                                for (int f = 0; f < want; f++) {
                                    mono[f] = fb.get(f);
                                }
                            }
                            int need = pendLen + want / decim + taps + 16;
                            if (need > pend.length) {
                                short[] bigger = new short[Math.max(
                                        pend.length * 2, need)];
                                System.arraycopy(pend, 0, bigger, 0, pendLen);
                                pend = bigger;
                            }
                            pendLen += decimator.push(mono, want, pend,
                                    pendLen);
                            readFrames += want;
                        } else if (readFrames >= totalFrames
                                && !tailFlushed) {
                            int need = pendLen + taps + 16;
                            if (need > pend.length) {
                                short[] bigger = new short[Math.max(
                                        pend.length * 2, need)];
                                System.arraycopy(pend, 0, bigger, 0, pendLen);
                                pend = bigger;
                            }
                            pendLen += decimator.flush(totalFrames, pend,
                                    pendLen);
                            tailFlushed = true;
                        }
                        // 2) Feed the encoder from the backlog.
                        int idx = enc.dequeueInputBuffer(5000);
                        if (idx >= 0) {
                            if (pendLen > 0) {
                                ByteBuffer dstBuf = enc.getInputBuffer(idx);
                                dstBuf.clear();
                                int room = dstBuf.remaining() / 2;
                                int want = Math.min(room, pendLen);
                                if (want <= 0) {
                                    enc.queueInputBuffer(idx, 0, 0, 0, 0);
                                } else {
                                    java.nio.ShortBuffer sb = dstBuf
                                            .order(ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer();
                                    sb.put(pend, 0, want);
                                    dstBuf.position(0);
                                    dstBuf.limit(want * 2);
                                    long pts = fed * 1_000_000L / outRate;
                                    enc.queueInputBuffer(idx, 0, want * 2,
                                            pts, 0);
                                    fed += want;
                                    System.arraycopy(pend, want, pend, 0,
                                            pendLen - want);
                                    pendLen -= want;
                                    if (cb != null) {
                                        int pct = (int) (fed * 100 / Math.max(
                                                1, expectOut));
                                        if (pct != lastPct) {
                                            lastPct = pct;
                                            cb.onProgress(
                                                    Math.min(100, pct));
                                        }
                                    }
                                }
                            } else if (tailFlushed) {
                                enc.queueInputBuffer(idx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inEos = true;
                                // Progress is already 100%; bound the drain.
                                drainDeadline = SystemClock.elapsedRealtime()
                                        + DRAIN_TIMEOUT_MS;
                            }
                            // Else: backlog momentarily empty but more file
                            // remains — loop back to produce more.
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
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * FLAC stream-header writer, shared by the batch converter and the
     * incremental encoder so the two can never disagree on the layout.
     *
     * <p>Three defects lived here before: the metadata block header byte was
     * written as {@code 0x90} (type 16 = reserved, which makes Android's
     * FLACExtractor reject every saved file); the encoder's csd-0 buffer was
     * copied from offset 0 even though Android's FLAC encoder prefixes
     * STREAMINFO with {@code "fLaC"} and a block header, so the payload
     * contained a duplicated magic and a 0 Hz sample rate; and
     * {@code total_samples} stayed 0, breaking duration probing.
     */
    public static final class FlacHeader {

        public static final int STREAMINFO_LEN = 34;
        /** Byte offset of the 20-bit sample rate inside STREAMINFO. */
        private static final int SI_SAMPLE_RATE = 10;
        /** First byte of the 36-bit total-samples field (spans 13..17). */
        private static final int SI_TOTAL_SAMPLES = 13;

        private FlacHeader() {}

        /**
         * Pulls the 34-byte STREAMINFO out of an encoder csd-0 buffer,
         * tolerating the three shapes Android ships: bare STREAMINFO,
         * block-header-prefixed, or {@code "fLaC"}+block-header-prefixed.
         *
         * @return the 34 bytes, or null when the buffer holds none.
         */
        public static byte[] streamInfoFromCsd(ByteBuffer csd) {
            if (csd == null || csd.remaining() < STREAMINFO_LEN) return null;
            int start = csd.position();
            int skip = 0;
            if (csd.remaining() >= 42) {
                int p = csd.position();
                if (csd.get(p) == 'f' && csd.get(p + 1) == 'L'
                        && csd.get(p + 2) == 'a' && csd.get(p + 3) == 'C') {
                    // "fLaC" + [0x80 0x00 0x00 0x22] + STREAMINFO
                    skip = 8;
                }
            }
            if (skip == 0 && csd.remaining() >= 38
                    && (csd.get(csd.position()) & 0x7F) == 0
                    && csd.get(csd.position() + 1) == 0
                    && csd.get(csd.position() + 2) == 0
                    && (csd.get(csd.position() + 3) & 0xFF)
                    == STREAMINFO_LEN) {
                // block header + STREAMINFO
                skip = 4;
            }
            if (csd.remaining() - skip < STREAMINFO_LEN) return null;
            byte[] si = new byte[STREAMINFO_LEN];
            ByteBuffer view = csd.duplicate();
            view.position(view.position() + skip);
            view.get(si);
            csd.position(start);
            return si;
        }

        /** Writes {@code fLaC} + the last-STREAMINFO block header + payload. */
        public static void write(java.io.OutputStream out, byte[] si)
                throws Exception {
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            // bit 7 = last-metadata-block, bits 6..0 = type 0 (STREAMINFO).
            out.write(0x80);
            out.write(0x00);
            out.write(0x00);
            out.write(STREAMINFO_LEN);
            out.write(si);
        }

        /**
         * Patches the 36-bit total-samples field so players can report a
         * duration. The encoder streams, so it leaves this at 0.
         *
         * <p>Layout: low 4 bits of STREAMINFO byte 13, then bytes 14..17.
         */
        public static void patchTotalSamples(RandomAccessFile r,
                                              long totalSamples)
                throws Exception {
            if (totalSamples <= 0 || totalSamples >= (1L << 36)) return;
            byte[] si = new byte[STREAMINFO_LEN];
            r.seek(8 + SI_TOTAL_SAMPLES - 1); // byte 12: keep its high bits
            r.readFully(si, SI_TOTAL_SAMPLES - 1, 5);
            si[SI_TOTAL_SAMPLES] = (byte) ((si[SI_TOTAL_SAMPLES] & 0xF0)
                    | ((totalSamples >>> 32) & 0x0F));
            si[SI_TOTAL_SAMPLES + 1] = (byte) (totalSamples >>> 24);
            si[SI_TOTAL_SAMPLES + 2] = (byte) (totalSamples >>> 16);
            si[SI_TOTAL_SAMPLES + 3] = (byte) (totalSamples >>> 8);
            si[SI_TOTAL_SAMPLES + 4] = (byte) totalSamples;
            r.seek(8 + SI_TOTAL_SAMPLES - 1);
            r.write(si, SI_TOTAL_SAMPLES - 1, 5);
        }

        /** Sample rate in Hz from a 34-byte STREAMINFO. */
        public static int sampleRate(byte[] si) {
            if (si == null || si.length < 21) return 0;
            return ((si[SI_SAMPLE_RATE] & 0xFF) << 12)
                    | ((si[SI_SAMPLE_RATE + 1] & 0xFF) << 4)
                    | ((si[SI_SAMPLE_RATE + 2] & 0xF0) >>> 4);
        }

        /**
         * Structural check on the first 42 bytes of a FLAC file: magic,
         * a STREAMINFO last-block header with the right length, no nested
         * magic, and a non-zero sample rate.
         */
        public static boolean validHead(byte[] head) {
            if (head == null || head.length < 42) return false;
            if (head[0] != 'f' || head[1] != 'L' || head[2] != 'a'
                    || head[3] != 'C') return false;
            if ((head[4] & 0x7F) != 0) return false;   // type must be 0
            if ((head[5] & 0xFF) != 0 || (head[6] & 0xFF) != 0
                    || (head[7] & 0xFF) != STREAMINFO_LEN) return false;
            if (head[8] == 'f' && head[9] == 'L' && head[10] == 'a'
                    && head[11] == 'C') return false;   // duplicated magic
            int rate = ((head[8 + SI_SAMPLE_RATE] & 0xFF) << 12)
                    | ((head[8 + SI_SAMPLE_RATE + 1] & 0xFF) << 4)
                    | ((head[8 + SI_SAMPLE_RATE + 2] & 0xF0) >>> 4);
            return rate > 0;
        }
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
            // No magic here: FlacHeader.write() emits "fLaC" with the
            // STREAMINFO block once the encoder publishes its config.
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
                            if (!wroteInfo) {
                                byte[] si = FlacHeader.streamInfoFromCsd(
                                        of.getByteBuffer("csd-0"));
                                if (si != null) {
                                    FlacHeader.write(out, si);
                                    wroteInfo = true;
                                    if (pending.size() > 0) {
                                        pending.writeTo(out);
                                        pending.reset();
                                    }
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
            // The encoder streams, so STREAMINFO's total_samples is 0; patch
            // it now that the frame count is known.
            try {
                RandomAccessFile rr = new RandomAccessFile(dst, "rw");
                try {
                    FlacHeader.patchTotalSamples(rr, totalFrames);
                } finally {
                    rr.close();
                }
            } catch (Exception ignored) {}
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
