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
            byte[] in = new byte[32768 * 4];
            byte[] out = new byte[32768 * 2];
            ByteBuffer ib = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
            long done = 0;
            int lastPct = -1;
            while (done < totalSamples) {
                int want = (int) Math.min(32768, totalSamples - done);
                int bytes = want * 4;
                r.readFully(in, 0, bytes);
                ib.clear();
                ib.limit(bytes);
                ob.clear();
                for (int i = 0; i < want; i++) {
                    float s = ib.getFloat();
                    if (s > 1f) s = 1f;
                    else if (s < -1f) s = -1f;
                    ob.putShort((short) Math.round(s * 32767f));
                }
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

    /** Opus in WebM (probe-gated: only offered when an encoder exists). */
    public static void toOpus(File src, File dst, int bitrate, Progress cb) throws Exception {
        encodeMuxed(src, dst, "audio/opus",
                Math.max(32000, Math.min(256000, bitrate <= 0 ? 128000 : bitrate)),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, -1, cb);
    }

    /** Vorbis in WebM (probe-gated). */
    public static void toVorbis(File src, File dst, int bitrate, Progress cb) throws Exception {
        encodeMuxed(src, dst, "audio/vorbis",
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
        int track = -1;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            r.seek(info.dataStart);
            byte[] in = new byte[16384 * 4 * 2];
            ByteBuffer ib = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            long framesDone = 0;
            boolean inputEos = false;
            boolean outEos = false;
            int lastPct = -1;
            long lastCbAt = 0;

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
                        } else {
                            int wantBytes = wantFrames * ch * 4;
                            r.readFully(in, 0, wantBytes);
                            ib.clear();
                            ib.limit(wantBytes);
                            int shorts = wantFrames * ch;
                            for (int i = 0; i < shorts; i++) {
                                float s = ib.getFloat();
                                if (s > 1f) s = 1f;
                                else if (s < -1f) s = -1f;
                                dstBuf.putShort((short) Math.round(s * 32767f));
                            }
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
                    if (inputEos && !muxStarted) {
                        // fall through; keep draining
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
                if (muxStarted) muxer.stop();
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
                case VORBIS:
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
            byte[] in = new byte[32768 * info.channels * 4];
            ByteBuffer ib = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            long done = 0;
            int lastPct = -1;
            int pos = 0;
            while (done < totalFrames) {
                int want = (int) Math.min(32768, totalFrames - done);
                r.readFully(in, 0, want * info.channels * 4);
                ib.clear();
                ib.limit(want * info.channels * 4);
                for (int f = 0; f < want; f++) {
                    float s0 = ib.getFloat();
                    float s1 = info.channels > 1 ? ib.getFloat() : s0;
                    mono[pos++] = (s0 + s1) * 0.5f;
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
        for (int n = 0; n < outLen; n++) {
            double acc = 0;
            int center = n * decim;
            for (int k = 0; k < taps; k++) {
                int idx = center - delay + k;
                float s = (idx < 0 || idx >= mono.length) ? 0f : mono[idx];
                acc += s * fir[k];
            }
            if (acc > 1) acc = 1;
            else if (acc < -1) acc = -1;
            pcm[n] = (short) Math.round(acc * 32767f);
            if (cb != null && (n & 0x3FFF) == 0) {
                cb.onProgress(35 + (int) (n * 20L / Math.max(1, outLen)));
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
        int track = -1;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            long fed = 0;
            boolean inEos = false;
            boolean outEos = false;
            int lastPct = 55;
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
                        } else {
                            for (int i = 0; i < want; i++) {
                                dstBuf.putShort(pcm[(int) fed + i]);
                            }
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
                if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
                if (muxStarted) muxer.stop();
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
            ByteBuffer ib = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long framesDone = 0;
            boolean inEos = false;
            boolean outEos = false;
            int lastPct = -1;
            long lastCbAt = 0;
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
                            } else {
                                int wantBytes = wantFrames * ch * 4;
                                r.readFully(in, 0, wantBytes);
                                ib.clear();
                                ib.limit(wantBytes);
                                int shorts = wantFrames * ch;
                                for (int i = 0; i < shorts; i++) {
                                    float s = ib.getFloat();
                                    if (s > 1f) s = 1f;
                                    else if (s < -1f) s = -1f;
                                    dstBuf.putShort((short) Math.round(s * 32767f));
                                }
                                long pts = framesDone * 1_000_000L / sr;
                                enc.queueInputBuffer(idx, 0, shorts * 2, pts, 0);
                                framesDone += wantFrames;
                            }
                        }
                    }
                    int oidx = enc.dequeueOutputBuffer(bi, 5000);
                    if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
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
