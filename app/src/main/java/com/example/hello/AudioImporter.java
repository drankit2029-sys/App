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

    /** Bound on draining a decoder that never signals end of stream. */
    private static final long DRAIN_TIMEOUT_MS = 10_000L;

    private AudioImporter() {}

    /**
     * True when {@code src} is already a 48 kHz 32-bit IEEE float mono/stereo
     * WAV, so preparing an edit session is a straight byte copy rather than a
     * format conversion.
     */
    public static boolean isNativeFloatWav(File src) {
        if (src == null || !src.exists()) return false;
        try (RandomAccessFile r = new RandomAccessFile(src, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            return h.audioFormat == 3 && h.bits == 32
                    && h.sampleRate == RecordingEngine.SAMPLE_RATE
                    && (h.channels == 1 || h.channels == 2)
                    && Math.max(0, r.length() - h.dataStart) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param dstParent   directory for the new session file (takes dir).
     * @param baseName    sanitized take title used for the session file name.
     * @return the new float WAV session file (without a pending marker —
     * callers attach it via the engine, which writes the marker).
     */
    public static File takeToFloatWav(File src, File dstParent, String baseName,
                                      Progress cb) throws Exception {
        String lower = src.getName().toLowerCase(java.util.Locale.US);
        File dst = uniqueSession(dstParent, baseName);
        try {
            if (lower.endsWith(".wav")) {
                wavToFloatWav(src, dst, cb);
            } else {
                compressedToFloatWav(src, dst, cb);
            }
        } catch (Exception | Error e) {
            // A4-05: the session file lives in takes/ and starts with a
            // header. If decoding throws, a truncated float WAV would be
            // sitting there with no .pending marker, and the next launch
            // would adopt it into the library as a take full of noise.
            try {
                if (dst.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    dst.delete();
                }
            } catch (Exception ignored) {}
            throw e;
        }
        // FMT-02d: validation lives inside the try so a malformed import
        // deletes its output too; otherwise the orphan is adopted on the
        // next launch by reconcile().
        try {
            // Importer output preserves the source geometry (mono stays
            // mono); callers attach the session with these exact channels.
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(dst);
            if (info.channels < 1 || info.channels > 2
                    || info.sampleRate != RecordingEngine.SAMPLE_RATE) {
                throw new IllegalStateException("bad import channels/rate");
            }
            if (info.dataBytes <= 0) {
                throw new IllegalStateException("empty import");
            }
        } catch (Exception | Error e) {
            try {
                if (dst.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    dst.delete();
                }
            } catch (Exception ignored) {}
            throw e;
        }
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
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            int ch = h.channels;
            int sr = h.sampleRate;
            if (ch < 1 || ch > 2) {
                throw new IllegalStateException("unsupported wav (need mono/stereo)");
            }
            int bytesPerSample;
            if (h.audioFormat == 3 && h.bits == 32) bytesPerSample = 4;
            else if (h.audioFormat == 1 && h.bits == 16) bytesPerSample = 2;
            else if (h.audioFormat == 1 && h.bits == 24) bytesPerSample = 3;
            else throw new IllegalStateException("unsupported wav encoding");
            long onDisk = Math.max(0, r.length() - h.dataStart);
            long dataBytes = h.dataBytes;
            if (dataBytes <= 0 || dataBytes > onDisk) dataBytes = onDisk;
            long totalSrcFrames = dataBytes / bytesPerSample / ch;
            if (totalSrcFrames <= 0) throw new IllegalStateException("empty wav");
            // Fast path: source is already 48 kHz 32-bit IEEE float WAV.
            // Copy raw payload bytes directly without unpacking floats or
            // running Dsp.Resampler.
            if (h.audioFormat == 3 && h.bits == 32
                    && sr == RecordingEngine.SAMPLE_RATE) {
                long payloadBytes = totalSrcFrames * ch * 4L;
                try (RandomAccessFile w = new RandomAccessFile(dst, "rw")) {
                    w.setLength(0);
                    w.write(RecordingEngine.wavHeader(ch, payloadBytes));
                    r.seek(h.dataStart);
                    byte[] buf = new byte[65536];
                    long left = payloadBytes;
                    while (left > 0) {
                        int n = r.read(buf, 0,
                                (int) Math.min(buf.length, left));
                        if (n <= 0) break;
                        w.write(buf, 0, n);
                        left -= n;
                    }
                    long actual = Math.max(0L, w.length() - 44L);
                    if (actual != payloadBytes) {
                        w.seek(0);
                        w.write(RecordingEngine.wavHeader(ch, actual));
                    }
                }
                return;
            }
            // Mono stays mono; foreign rates are resampled to the session
            // rate instead of being stamped with a lying header.
            Dsp.Resampler resampler =
                    new Dsp.Resampler(sr, RecordingEngine.SAMPLE_RATE, ch);
            RandomAccessFile w = new RandomAccessFile(dst, "rw");
            try {
                w.setLength(0);
                w.write(RecordingEngine.wavHeader(ch, 0));
                // Seek explicitly to the parsed data chunk start (NEW-01).
                r.seek(h.dataStart);
                byte[] in = new byte[32768 * 2 * 4];
                ByteBuffer ib = ByteBuffer.wrap(in)
                        .order(ByteOrder.LITTLE_ENDIAN);
                float[] fbuf = new float[32768 * 2];
                long done = 0;
                long payloadBytes = 0;
                int lastPct = -1;
                while (done < totalSrcFrames) {
                    int want = (int) Math.min(32768, totalSrcFrames - done);
                    // NEW-01 fix: `want` is in frames, so read `want * ch`
                    // samples (`samplesToRead * bytesPerSample` bytes).
                    int samplesToRead = want * ch;
                    float[] samples;
                    if (h.audioFormat == 3) {
                        r.readFully(in, 0, samplesToRead * 4);
                        ib.clear();
                        ib.limit(samplesToRead * 4);
                        for (int i = 0; i < samplesToRead; i++) {
                            fbuf[i] = ib.getFloat();
                        }
                        samples = resampler.push(fbuf, want);
                    } else if (h.bits == 24) {
                        r.readFully(in, 0, samplesToRead * 3);
                        ib.clear();
                        ib.limit(samplesToRead * 3);
                        for (int i = 0; i < samplesToRead; i++) {
                            int v = (ib.get() & 0xFF)
                                    | ((ib.get() & 0xFF) << 8)
                                    | (ib.get() << 16);
                            if ((v & 0x800000) != 0) v |= 0xFF000000;
                            fbuf[i] = v / 8388608f;
                        }
                        samples = resampler.push(fbuf, want);
                    } else {
                        r.readFully(in, 0, samplesToRead * 2);
                        ib.clear();
                        ib.limit(samplesToRead * 2);
                        for (int i = 0; i < samplesToRead; i++) {
                            fbuf[i] = ib.getShort() / 32768f;
                        }
                        samples = resampler.push(fbuf, want);
                    }
                    int outFrames = samples.length / ch;
                    if (outFrames > 0) {
                        Dsp.writeFloatsLE(w, samples, 0, samples.length);
                        payloadBytes += (long) outFrames * ch * 4;
                    }
                    done += want;
                    if (cb != null) {
                        int pct = (int) (done * 100
                                / Math.max(1, totalSrcFrames));
                        if (pct != lastPct) {
                            lastPct = pct;
                            cb.onProgress(pct);
                        }
                    }
                }
                float[] tail = resampler.flush();
                if (tail.length > 0) {
                    Dsp.writeFloatsLE(w, tail, 0, tail.length);
                    payloadBytes += tail.length * 4L;
                }
                w.seek(0);
                w.write(RecordingEngine.wavHeader(ch, payloadBytes));
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
            int srcRate = RecordingEngine.SAMPLE_RATE;
            try {
                srcRate = trackFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            } catch (Exception ignored) {}
            if (srcRate <= 0) srcRate = RecordingEngine.SAMPLE_RATE;
            long durationUs = 0;
            try {
                durationUs = trackFmt.getLong(MediaFormat.KEY_DURATION);
            } catch (Exception ignored) {}
            String mime = trackFmt.getString(MediaFormat.KEY_MIME);
            ex.selectTrack(track);

            // Mono stays mono (downmix only beyond stereo); the rate is
            // resampled to the session rate instead of being stamped with a
            // lying 48 kHz header.
            int outCh = Math.max(1, Math.min(2, srcCh));
            Dsp.Resampler resampler =
                    new Dsp.Resampler(srcRate, RecordingEngine.SAMPLE_RATE,
                            outCh);

            MediaCodec dec = MediaCodec.createDecoderByType(mime);
            // A4-05/FMT-02a: pin the decoder output to 16-bit PCM. Set it on
            // the track format directly: MediaFormat's copy constructor only
            // exists from API 29, so `new MediaFormat(trackFmt)` threw
            // NoSuchMethodError (an Error, not caught below) on API 24-28.
            try {
                trackFmt.setInteger(MediaFormat.KEY_PCM_ENCODING,
                        android.media.AudioFormat.ENCODING_PCM_16BIT);
            } catch (Exception ignored) {}
            dec.configure(trackFmt, null, null, 0);
            dec.start();
            RandomAccessFile w = new RandomAccessFile(dst, "rw");
            boolean muxed = false;
            try {
                w.setLength(0);
                w.write(RecordingEngine.wavHeader(outCh, 0));
                float[] fbuf = new float[16384 * 2];
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inEos = false;
                boolean outEos = false;
                long payloadBytes = 0;
                int lastPct = -1;
                // FMT-02c: bound the post-EOS drain. An OEM decoder that
                // never emits END_OF_STREAM otherwise hangs the import
                // dialog forever.
                int pcmEncoding = android.media.AudioFormat
                        .ENCODING_PCM_16BIT;
                long drainDeadline = 0;
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
                                drainDeadline = android.os.SystemClock
                                        .elapsedRealtime() + DRAIN_TIMEOUT_MS;
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
                            // NEW-02 fix: MediaCodec output ByteBuffers
                            // default to BIG_ENDIAN; set LITTLE_ENDIAN so
                            // getShort() does not byte-swap PCM samples.
                            ob.order(ByteOrder.LITTLE_ENDIAN);
                            ob.position(bi.offset);
                            ob.limit(bi.offset + bi.size);
                            // Decoder PCM is 16-bit: convert to float, then
                            // resample onto the session grid.
                            // FMT-02b: KEY_PCM_ENCODING is only a hint. If
                            // the decoder still reports float output, read
                            // 4-byte samples; reading them as shorts byte-swaps
                            // and doubles the apparent duration.
                            final int pcmEnc = pcmEncoding;
                            final boolean pcmFloat = pcmEnc
                                    == android.media.AudioFormat
                                    .ENCODING_PCM_FLOAT;
                            final int bytesPerSample = pcmFloat ? 4 : 2;
                            int samples = bi.size / bytesPerSample;
                            int frames = samples / Math.max(1, srcCh);
                            int fpos = 0;
                            for (int f = 0; f < frames; f++) {
                                float s0 = pcmFloat
                                        ? ob.getFloat() : ob.getShort() / 32768f;
                                float s1 = srcCh > 1
                                        ? (pcmFloat ? ob.getFloat()
                                        : ob.getShort() / 32768f) : s0;
                                // Skip any extra channels beyond stereo.
                                for (int c = 2; c < srcCh; c++) {
                                    if (pcmFloat) ob.getFloat();
                                    else ob.getShort();
                                }
                                if (outCh == 1) {
                                    fbuf[fpos++] = srcCh > 1
                                            ? (s0 + s1) * 0.5f : s0;
                                } else {
                                    fbuf[fpos++] = s0;
                                    fbuf[fpos++] = s1;
                                }
                                if (fpos + 2 > fbuf.length) {
                                    float[] conv = resampler.push(fbuf,
                                            fpos / outCh);
                                    if (conv.length > 0) {
                                        Dsp.writeFloatsLE(w, conv, 0,
                                                conv.length);
                                        payloadBytes += conv.length * 4L;
                                    }
                                    fpos = 0;
                                }
                            }
                            if (fpos > 0) {
                                float[] conv = resampler.push(fbuf,
                                        fpos / outCh);
                                if (conv.length > 0) {
                                    Dsp.writeFloatsLE(w, conv, 0, conv.length);
                                    payloadBytes += conv.length * 4L;
                                }
                            }
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
                    } else if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inEos && android.os.SystemClock.elapsedRealtime()
                                > drainDeadline) {
                            throw new IllegalStateException(
                                    "decoder never finished");
                        }
                    } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        try {
                            MediaFormat of = dec.getOutputFormat();
                            try {
                                pcmEncoding = of.getInteger(
                                        MediaFormat.KEY_PCM_ENCODING);
                            } catch (Exception ignored) {}
                            int newCh = of.getInteger(
                                    MediaFormat.KEY_CHANNEL_COUNT);
                            int newRate = srcRate;
                            try {
                                newRate = of.getInteger(
                                        MediaFormat.KEY_SAMPLE_RATE);
                            } catch (Exception ignored) {}
                            if (newCh >= 1 && newCh <= 8
                                    && newRate > 0) {
                                if (payloadBytes > 0
                                        && (newCh != srcCh
                                        || newRate != srcRate)) {
                                    throw new IllegalStateException(
                                            "decoder geometry changed mid-stream");
                                }
                                srcCh = newCh;
                                srcRate = newRate;
                                outCh = Math.max(1, Math.min(2, srcCh));
                                resampler = new Dsp.Resampler(srcRate,
                                        RecordingEngine.SAMPLE_RATE, outCh);
                            }
                        } catch (IllegalStateException ise) {
                            throw ise;
                        } catch (Exception ignored) {}
                    }
                    muxed = true;
                }
                float[] tail = resampler.flush();
                if (tail.length > 0) {
                    Dsp.writeFloatsLE(w, tail, 0, tail.length);
                    payloadBytes += tail.length * 4L;
                }
                if (!muxed || payloadBytes <= 0) {
                    throw new IllegalStateException("decode produced no audio");
                }
                w.seek(0);
                w.write(RecordingEngine.wavHeader(outCh, payloadBytes));
            } finally {
                try {
                    w.close();
                } catch (Exception ignored) {}
                // Stop/release the decoder HERE, not after this block: any
                // throw inside the loop above must not skip them and leak
                // the native MediaCodec instance.
                try {
                    dec.stop();
                } catch (Exception ignored) {}
                try {
                    dec.release();
                } catch (Exception ignored) {}
            }
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) {}
        }
    }
}