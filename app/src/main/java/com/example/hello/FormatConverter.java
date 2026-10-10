package com.example.hello;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

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
        WavInfo info = readFloatWav(src);
        final int ch = info.channels;
        final int sr = info.sampleRate;
        final long totalSamples = info.dataBytes / 4; // interleaved samples
        final long totalFrames = totalSamples / Math.max(1, ch);

        MediaFormat fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);

        MediaCodec enc = MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer muxer = new MediaMuxer(
                dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean muxStarted = false;
        int track = -1;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            r.seek(info.dataStart);
            byte[] in = new byte[16384 * 4];
            ByteBuffer ib = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
            long framesDone = 0;
            boolean inputEos = false;
            boolean outEos = false;
            int lastPct = -1;

            while (!outEos) {
                if (!inputEos) {
                    int idx = enc.dequeueInputBuffer(10000);
                    if (idx >= 0) {
                        ByteBuffer dstBuf = enc.getInputBuffer(idx);
                        dstBuf.clear();
                        long framesLeft = totalFrames - framesDone;
                        // Never read more file bytes than the codec buffer fits.
                        int maxByBuf = dstBuf.remaining() / 2 / Math.max(1, ch);
                        int wantFrames = (int) Math.min(4096,
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
                                int pct = (int) (framesDone * 90
                                        / Math.max(1, totalFrames));
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    cb.onProgress(pct);
                                }
                            }
                        }
                    }
                }
                int oidx = enc.dequeueOutputBuffer(bi, 10000);
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
        if (dst.length() < 1024) throw new IllegalStateException("encode failed");
    }

    private static byte[] wav16Header(int ch, int sr, long pcmBytes) {
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
}
