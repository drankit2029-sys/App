package com.example.hello;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes a library take (float/16-bit WAV, M4A/AAC, …) into a fresh 32-bit
 * float WAV session file so the studio can punch in from its end. The
 * original file is never touched.
 */
public final class AudioImporter {

    public interface Progress {
        void onProgress(int percent);
    }

    private AudioImporter() {}

    /**
     * @param dstParent   directory for the new session file (takes dir).
     * @param baseName    sanitized take title used for the session file name.
     * @return the new float WAV session file (without a pending marker —
     * callers attach it via the engine, which writes the marker).
     */
    public static File takeToFloatWav(File src, File dstParent, String baseName,
                                      Progress cb) throws Exception {
        String lower = src.getName().toLowerCase();
        File dst = uniqueSession(dstParent, baseName);
        if (lower.endsWith(".wav")) {
            wavToFloatWav(src, dst, cb);
        } else {
            compressedToFloatWav(src, dst, cb);
        }
        // The engine always streams stereo sessions; importer output matches.
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(dst);
        if (info.channels != 2 || info.sampleRate != RecordingEngine.SAMPLE_RATE) {
            throw new IllegalStateException("bad import channels/rate");
        }
        if (info.dataBytes <= 0) throw new IllegalStateException("empty import");
        if (cb != null) cb.onProgress(100);
        return dst;
    }

    private static File uniqueSession(File dir, String base) {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        File c = new File(dir, base + "_" + stamp + ".wav");
        if (!c.exists()) return c;
        int i = 2;
        while (true) {
            File n = new File(dir, base + "_" + stamp + " (" + i + ").wav");
            if (!n.exists()) return n;
            i++;
        }
    }

    // ---- WAV sources ----

    private static void wavToFloatWav(File src, File dst, Progress cb) throws Exception {
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
            long onDisk = Math.max(0, r.length() - 44);
            if (ch < 1 || ch > 2 || sr != RecordingEngine.SAMPLE_RATE) {
                throw new IllegalStateException("unsupported wav (need 48k mono/stereo)");
            }
            long totalSamples;
            if (audioFormat == 3 && bits == 32) {
                totalSamples = onDisk / 4;
            } else if (audioFormat == 1 && bits == 16) {
                totalSamples = onDisk / 2;
            } else if (audioFormat == 1 && bits == 24) {
                // 24-bit sample source: 3 bytes, assembled byte-wise below.
                totalSamples = onDisk / 3;
            } else {
                throw new IllegalStateException("unsupported wav encoding");
            }
            RandomAccessFile w = new RandomAccessFile(dst, "rw");
            try {
                w.setLength(0);
                w.write(RecordingEngine.wavHeader(2, 0));
                byte[] in = new byte[32768 * 4];
                byte[] out = new byte[32768 * 2 * 4];
                ByteBuffer ib = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
                long done = 0;
                int lastPct = -1;
                while (done < totalSamples) {
                    int want = (int) Math.min(32768, totalSamples - done);
                    if (audioFormat == 3) {
                        r.readFully(in, 0, want * 4);
                        ib.clear();
                        ib.limit(want * 4);
                        ob.clear();
                        for (int i = 0; i < want; i++) {
                            float s = ib.getFloat();
                            if (ch == 2) {
                                ob.putFloat(s);
                            } else {
                                ob.putFloat(s);
                                ob.putFloat(s);
                            }
                        }
                        // Mono float source: each sample duplicated above, so
                        // output frames are want/1 -> want stereo frames.
                        w.write(out, 0, ch == 2 ? want * 4 : want * 8);
                    } else {
                        if (bits == 24) {
                            r.readFully(in, 0, want * 3);
                            ib.clear();
                            ib.limit(want * 3);
                            ob.clear();
                            for (int i = 0; i < want; i++) {
                                int v = (ib.get() & 0xFF)
                                        | ((ib.get() & 0xFF) << 8)
                                        | (ib.get() << 16);
                                // Sign-extend the 24-bit value, then scale.
                                if ((v & 0x800000) != 0) v |= 0xFF000000;
                                float s = v / 8388608f;
                                ob.putFloat(s);
                                if (ch == 1) ob.putFloat(s);
                            }
                        } else {
                            r.readFully(in, 0, want * 2);
                            ib.clear();
                            ib.limit(want * 2);
                            ob.clear();
                            for (int i = 0; i < want; i++) {
                                float s = ib.getShort() / 32768f;
                                if (ch == 2) {
                                    ob.putFloat(s);
                                } else {
                                    ob.putFloat(s);
                                    ob.putFloat(s);
                                }
                            }
                        }
                        w.write(out, 0, ch == 2 ? want * 4 : want * 8);
                    }
                    done += want;
                    if (cb != null) {
                        int pct = (int) (done * 100 / Math.max(1, totalSamples));
                        if (pct != lastPct) {
                            lastPct = pct;
                            cb.onProgress(pct);
                        }
                    }
                }
                // Stereo float payload bytes actually written.
                long frames = ch == 2 ? totalSamples / 2 : totalSamples;
                w.seek(0);
                w.write(RecordingEngine.wavHeader(2, frames * 8));
            } finally {
                try {
                    w.close();
                } catch (Exception ignored) {}
            }
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {}
        }
    }

    // ---- compressed sources (M4A/AAC/MP3/…) via platform decoder ----

    private static void compressedToFloatWav(File src, File dst, Progress cb)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(src.getAbsolutePath());
            int track = -1;
            MediaFormat trackFmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    trackFmt = f;
                    break;
                }
            }
            if (track < 0 || trackFmt == null) {
                throw new IllegalStateException("no audio track");
            }
            int srcCh = 2;
            try {
                srcCh = trackFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } catch (Exception ignored) {}
            long durationUs = 0;
            try {
                durationUs = trackFmt.getLong(MediaFormat.KEY_DURATION);
            } catch (Exception ignored) {}
            String mime = trackFmt.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(track);

            MediaCodec dec = MediaCodec.createDecoderByType(mime);
            dec.configure(trackFmt, null, null, 0);
            dec.start();
            RandomAccessFile w = new RandomAccessFile(dst, "rw");
            boolean muxed = false;
            try {
                w.setLength(0);
                w.write(RecordingEngine.wavHeader(2, 0));
                ByteBuffer outBuf = ByteBuffer.allocate(65536 * 4)
                        .order(ByteOrder.LITTLE_ENDIAN);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inEos = false;
                boolean outEos = false;
                long payloadBytes = 0;
                int lastPct = -1;
                while (!outEos) {
                    if (!inEos) {
                        int idx = dec.dequeueInputBuffer(5000);
                        if (idx >= 0) {
                            ByteBuffer ib = dec.getInputBuffer(idx);
                            int n = ex.readSampleData(ib, 0);
                            if (n < 0) {
                                dec.queueInputBuffer(idx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inEos = true;
                            } else {
                                dec.queueInputBuffer(idx, 0, n,
                                        ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int oidx = dec.dequeueOutputBuffer(bi, 5000);
                    if (oidx >= 0) {
                        if (bi.size > 0) {
                            ByteBuffer ob = dec.getOutputBuffer(oidx);
                            ob.position(bi.offset);
                            ob.limit(bi.offset + bi.size);
                            // Decoder PCM is 16-bit: convert to stereo float.
                            int shorts = bi.size / 2;
                            int frames = shorts / Math.max(1, srcCh);
                            outBuf.clear();
                            for (int f = 0; f < frames; f++) {
                                float s0 = ob.getShort() / 32768f;
                                float s1 = srcCh > 1
                                        ? ob.getShort() / 32768f : s0;
                                // Skip any extra channels beyond stereo.
                                for (int c = 2; c < srcCh; c++) ob.getShort();
                                outBuf.putFloat(s0);
                                outBuf.putFloat(s1);
                            }
                            outBuf.flip();
                            byte[] arr = outBuf.array();
                            w.write(arr, 0, outBuf.remaining());
                            payloadBytes += frames * 8L;
                            if (cb != null && durationUs > 0) {
                                int pct = (int) (bi.presentationTimeUs * 100
                                        / Math.max(1, durationUs));
                                pct = Math.max(0, Math.min(99, pct));
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    cb.onProgress(pct);
                                }
                            }
                        }
                        dec.releaseOutputBuffer(oidx, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outEos = true;
                        }
                    } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        try {
                            MediaFormat of = dec.getOutputFormat();
                            srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if (srcCh < 1) srcCh = 2;
                            if (srcCh > 8) srcCh = 2;
                        } catch (Exception ignored) {}
                    }
                    muxed = true;
                }
                if (!muxed || payloadBytes <= 0) {
                    throw new IllegalStateException("decode produced no audio");
                }
                w.seek(0);
                w.write(RecordingEngine.wavHeader(2, payloadBytes));
            } finally {
                try {
                    w.close();
                } catch (Exception ignored) {}
            }
            try {
                dec.stop();
            } catch (Exception ignored) {}
            try {
                dec.release();
            } catch (Exception ignored) {}
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {}
        }
    }
}
