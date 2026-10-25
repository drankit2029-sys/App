package com.example.hello;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Incremental encoder that follows the crash-safe raw capture file while a
 * take is being recorded.
 *
 * <p>The raw 32-bit float WAV is append-only: every byte past the 44-byte
 * header is immutable once written. A pump thread converts the newest
 * immutable prefix in chunks, so by the time the user hits save most of the
 * take is already encoded and stopping only drains the tail. Anything the
 * pump cannot do — encoder missing, mid-stream failure, process death — falls
 * back to a full post-stop conversion from the raw, which always remains the
 * source of truth. Capture itself never touches this class except through
 * {@link RecordingEngine}, and the pump only ever <em>reads</em> the raw.
 *
 * <p>WAV16/WAV24 output is byte-appended and exactly resumable; codec output
 * (AAC/M4A, Opus, FLAC, AMR) is fed with contiguous presentation timestamps
 * derived from the encoded frame count, so pause/resume capture just looks
 * like a quiet pump.
 */
public final class StreamingEncoder {

    /** Frames converted per pump iteration; matches the batch chunk scale. */
    /** How long abort()/timed-out finish() waits for the pump to die. */
    private static final long PUMP_STOP_JOIN_MS = 500L;

    private static final int PUMP_FRAMES = 16384;
    /** How long the pump parks when it has caught up with capture. */
    private static final long PARK_MS = 80L;
    /** Bound on flushing a dead OEM encoder, same as the batch path. */
    private static final long DRAIN_TIMEOUT_MS = 10_000L;

    public interface Progress {
        void onProgress(int percent);
    }

    private final RecordPreset preset;
    private final SaveFormat format;
    private final int channels;
    private final int sampleRate;
    private final int bitrate;
    private final File rawFile;
    private final File partFile;
    private final long dataStart;

    private final Thread pump;
    private volatile boolean stopRequested;
    private volatile boolean finishing;
    private volatile boolean failed;
    private volatile String failure;
    private volatile long framesEncoded;
    private volatile long framesSeen;
    private volatile boolean finishedOk;

    private StreamingEncoder(RecordPreset preset, SaveFormat format,
                             int channels, File rawFile, File partFile,
                             long dataStart) {
        this.preset = preset;
        this.format = format;
        this.channels = channels;
        this.sampleRate = RecordingEngine.SAMPLE_RATE;
        this.bitrate = bitrateFor(preset, format);
        this.rawFile = rawFile;
        this.partFile = partFile;
        this.dataStart = dataStart;
        this.pump = new Thread(this::pumpLoop, "takeone-stream-enc");
    }

    private static int bitrateFor(RecordPreset preset, SaveFormat format) {
        int br = preset != null ? preset.aacBitrate : 128000;
        switch (format) {
            case M4A:
            case AUTO:
                return Math.max(64000, Math.min(320000, br));
            case OPUS:
                return Math.max(32000, Math.min(256000,
                        br > 0 ? br : 128000));
            default:
                return br;
        }
    }

    /**
     * Starts incremental encoding of {@code rawFile} into {@code partFile}.
     * Throws when no streaming path exists (missing encoder, I/O error) — the
     * caller must treat that as "convert fully at save time".
     */
    public static StreamingEncoder start(RecordPreset preset, SaveFormat format,
                                         int channels, File rawFile)
            throws Exception {
        if (rawFile == null) throw new IllegalStateException("no raw");
        SaveFormat eff = format.effective(preset);
        if (eff == SaveFormat.AUTO) eff = SaveFormat.M4A;
        File part = new File(rawFile.getAbsolutePath() + ".part");
        // noinspection ResultOfMethodCallIgnored
        part.delete();
        long dataStart = 44;
        try {
            FormatConverter.WavInfo info =
                    FormatConverter.readFloatWav(rawFile);
            if (info.dataStart > 0) dataStart = info.dataStart;
        } catch (Exception ignored) {}
        StreamingEncoder enc = new StreamingEncoder(preset, eff, channels,
                rawFile, part, dataStart);
        enc.pump.start();
        return enc;
    }

    public boolean healthy() {
        return !failed && !stopRequested;
    }

    public boolean matches(RecordPreset p, SaveFormat s) {
        if (p == null || s == null) return false;
        SaveFormat eff = s.effective(p);
        if (eff == SaveFormat.AUTO) eff = SaveFormat.M4A;
        return eff == format
                && p.id.equals(preset.id)
                && bitrateFor(p, eff) == bitrate;
    }

    public long framesEncoded() {
        return framesEncoded;
    }

    public long framesSeen() {
        return framesSeen;
    }

    /**
     * Frames the pump still has to encode before the part file is complete.
     *
     * <p>A freshly restarted encoder reports {@code framesEncoded() > 0} after
     * its first chunk even though nearly the whole take is still pending, so
     * this is the honest measure of how much work an inline drain would cost.
     */
    public long unencodedFrames() {
        long avail = availableFrames();
        return Math.max(0L, avail - framesEncoded);
    }

    public File partFile() {
        return partFile;
    }

    public SaveFormat format() {
        return format;
    }

    public String failure() {
        return failure;
    }

    private void fail(String why) {
        if (!failed) {
            failed = true;
            failure = why;
        }
    }

    /**
     * Stops the pump after the tail is encoded: signals end-of-stream,
     * drains, and finalizes the part file. Fast when the pump kept up —
     * usually just the last partial chunk plus the encoder drain.
     */
    public void finish() {
        finish(30_000);
    }

    /**
     * Finish with a deadline.
     *
     * <p>A save must not park the studio while a lagging pump encodes its
     * tail: the caller gets {@code false} back once the budget runs out and
     * can hand the take to the background worker instead.
     *
     * @return true when the pump finished inside the budget.
     */
    public boolean finish(long timeoutMs) {
        finishing = true;
        try {
            pump.join(Math.max(1, timeoutMs));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (!pump.isAlive()) return true;
        // Budget expired. The pump must NOT stay alive: it would keep writing
        // this encoder's part file while the conversion worker starts its own
        // output on the same path. Stop it before returning.
        stopRequested = true;
        finishing = false;
        pump.interrupt();
        try {
            pump.join(PUMP_STOP_JOIN_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return !pump.isAlive();
    }

    public boolean finishedOk() {
        return finishedOk;
    }

    /** Releases everything and deletes the partial output. */
    public void abort() {
        stopRequested = true;
        finishing = false;
        pump.interrupt();
        try {
            pump.join(PUMP_STOP_JOIN_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // The pump only ever writes through the one file descriptor opened
        // when it started, so unlinking here means even a slow-dying pump
        // cannot write into a part file the worker creates afterwards.
        // noinspection ResultOfMethodCallIgnored
        partFile.delete();
    }

    // ---- pump ----

    private void pumpLoop() {
        try {
            switch (format) {
                case WAV16:
                    pumpWav(false);
                    break;
                case WAV24:
                    pumpWav(true);
                    break;
                case WAV32:
                    pumpWav32();
                    break;
                case AMR_NB:
                    pumpAmr(false);
                    break;
                case AMR_WB:
                    pumpAmr(true);
                    break;
                case FLAC:
                    pumpFlac();
                    break;
                case OPUS:
                    int opusMuxFmt = android.os.Build.VERSION.SDK_INT >= 29
                            ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                            : MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
                    pumpMuxed("audio/opus", bitrate, opusMuxFmt, -1);
                    break;
                case M4A:
                default:
                    pumpMuxed(MediaFormat.MIMETYPE_AUDIO_AAC, bitrate,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                            android.media.MediaCodecInfo
                                    .CodecProfileLevel.AACObjectLC);
                    break;
            }
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    /** Frames currently immutable in the raw file (past the WAV header). */
    private long availableFrames() {
        if (!rawFile.exists()) {
            // The raw was renamed or deleted under this encoder (e.g. a
            // Studio rename moves the file but the pump still points at the
            // old path). Fail loudly so healthy() goes false and an orphaned
            // encoder can never be adopted inline.
            fail("raw file moved or deleted");
            return 0;
        }
        long len = rawFile.length();
        if (len <= dataStart) return 0;
        return (len - dataStart) / 4 / Math.max(1, channels);
    }

    private void parkForMore() {
        try {
            Thread.sleep(PARK_MS);
        } catch (InterruptedException ignored) {
            // Woken by finish()/abort(): the loop condition re-checks
            // stopRequested/finishing. Never surface as an encoder failure.
        }
    }

    // ---- WAV (byte-exact, resumable) ----

    private void pumpWav(boolean w24) throws Exception {
        int frameBytes = channels * (w24 ? 3 : 2);
        int pumpFrames = MemoryBudget.chunkFrames(PUMP_FRAMES, channels);
        RandomAccessFile w = new RandomAccessFile(partFile, "rw");
        try {
            w.setLength(0);
            w.write(w24 ? Dsp.wav24Header(channels, sampleRate, 0)
                    : Dsp.wav16Header(channels, sampleRate, 0));
            RandomAccessFile r = new RandomAccessFile(rawFile, "r");
            try {
                byte[] in = new byte[pumpFrames * channels * 4];
                byte[] out = new byte[pumpFrames * frameBytes];
                java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                int[] ints = w24 ? new int[pumpFrames * channels] : null;
                java.nio.ShortBuffer sb = w24 ? null : ByteBuffer.wrap(out)
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                long done = 0;
                while (!stopRequested) {
                    long avail = availableFrames();
                    framesSeen = Math.max(framesSeen, avail);
                    if (done >= avail) {
                        if (finishing) break;
                        parkForMore();
                        continue;
                    }
                    int want = (int) Math.min(pumpFrames, avail - done);
                    r.seek(dataStart + done * channels * 4L);
                    r.readFully(in, 0, want * channels * 4);
                    int samples = want * channels;
                    if (w24) {
                        FormatConverter.toInt24(fb, samples, ints);
                        int p = 0;
                        for (int i = 0; i < samples; i++) {
                            int v = ints[i];
                            out[p++] = (byte) (v & 0xFF);
                            out[p++] = (byte) ((v >> 8) & 0xFF);
                            out[p++] = (byte) ((v >> 16) & 0xFF);
                        }
                        w.write(out, 0, samples * 3);
                    } else {
                        FormatConverter.toInt16(fb, samples, sb);
                        w.write(out, 0, samples * 2);
                    }
                    done += want;
                    framesEncoded = done;
                }
                if (stopRequested && !finishing) return;
                w.seek(0);
                w.write(w24 ? Dsp.wav24Header(channels, sampleRate,
                                done * channels * 3L)
                        : Dsp.wav16Header(channels, sampleRate,
                                done * channels * 2L));
                finishedOk = !failed && done > 0;
            } finally {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Raw 32-bit float WAV: copy the immutable float payload byte-for-byte
     * behind a fresh header. This is the "no conversion" save path; the pump
     * still follows capture so Save only has to drain the tail.
     */
    private void pumpWav32() throws Exception {
        int pumpFrames = MemoryBudget.chunkFrames(PUMP_FRAMES, channels);
        RandomAccessFile w = new RandomAccessFile(partFile, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(channels, sampleRate, 0));
            RandomAccessFile r = new RandomAccessFile(rawFile, "r");
            try {
                byte[] buf = new byte[pumpFrames * channels * 4];
                long done = 0;
                while (!stopRequested) {
                    long avail = availableFrames();
                    framesSeen = Math.max(framesSeen, avail);
                    if (done >= avail) {
                        if (finishing) break;
                        parkForMore();
                        continue;
                    }
                    int want = (int) Math.min(pumpFrames, avail - done);
                    r.seek(dataStart + done * channels * 4L);
                    r.readFully(buf, 0, want * channels * 4);
                    w.write(buf, 0, want * channels * 4);
                    done += want;
                    framesEncoded = done;
                }
                if (stopRequested && !finishing) return;
                w.seek(0);
                w.write(Dsp.wavFloatHeader(channels, sampleRate,
                        done * channels * 4L));
                finishedOk = !failed && done > 0;
            } finally {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
        } finally {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }

    // ---- AAC / Opus through MediaMuxer ----

    private void pumpMuxed(String mime, int bitrate, int muxerFormat,
                           int aacProfile) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(mime, sampleRate,
                channels);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        if (aacProfile >= 0) {
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, aacProfile);
        }
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 262144);
        MediaCodec enc = MediaCodec.createEncoderByType(mime);
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
        } catch (Exception e) {
            try {
                enc.release();
            } catch (Exception ignored) {}
            throw e;
        }
        MediaMuxer muxer = null;
        MuxState mux = null;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        RandomAccessFile r = null;
        try {
            muxer = new MediaMuxer(partFile.getAbsolutePath(),
                    muxerFormat);
            mux = new MuxState(muxer);
            r = new RandomAccessFile(rawFile, "r");
        } catch (Exception | Error e) {
            // Setup threw after enc started: release it here, the main
            // try/finally below never runs.
            try {
                enc.release();
            } catch (Exception ignored) {}
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {}
            try {
                if (muxer != null) muxer.release();
            } catch (Exception ignored) {}
            throw e;
        }
        int pumpFrames = MemoryBudget.chunkFrames(PUMP_FRAMES, channels);
        byte[] in = new byte[pumpFrames * channels * 4];
        java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        try {
            long done = 0;
            boolean inEos = false;
            boolean outEos = false;
            long drainDeadline = 0;
            while (!outEos) {
                if (stopRequested && !finishing) return;
                if (!inEos) {
                    long avail = availableFrames();
                    framesSeen = Math.max(framesSeen, avail);
                    long left = avail - done;
                    if (left <= 0) {
                        if (finishing) {
                            int idx = enc.dequeueInputBuffer(2000);
                            if (idx >= 0) {
                                enc.queueInputBuffer(idx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inEos = true;
                                drainDeadline = SystemClock.elapsedRealtime()
                                        + DRAIN_TIMEOUT_MS;
                            }
                        } else {
                            // Drain whatever the encoder already holds, then
                            // park until capture appends more.
                            int parked = enc.dequeueOutputBuffer(bi, 5000);
                            if (drainMuxed(enc, mux, bi, parked)) {
                                outEos = true;
                            }
                            parkForMore();
                            continue;
                        }
                    } else {
                        int idx = enc.dequeueInputBuffer(2000);
                        if (idx >= 0) {
                            ByteBuffer dstBuf = enc.getInputBuffer(idx);
                            dstBuf.clear();
                            int maxByBuf = dstBuf.remaining() / 2
                                    / Math.max(1, channels);
                            int want = (int) Math.min(pumpFrames,
                                    Math.min(left, maxByBuf));
                            if (want <= 0) {
                                enc.queueInputBuffer(idx, 0, 0, 0, 0);
                            } else {
                                r.seek(dataStart + done * channels * 4L);
                                r.readFully(in, 0, want * channels * 4);
                                int shorts = want * channels;
                                java.nio.ShortBuffer sb = dstBuf
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer();
                                FormatConverter.toInt16(fb, shorts, sb);
                                dstBuf.position(0);
                                dstBuf.limit(shorts * 2);
                                long pts = done * 1_000_000L / sampleRate;
                                enc.queueInputBuffer(idx, 0, shorts * 2,
                                        pts, 0);
                                done += want;
                                framesEncoded = done;
                            }
                        }
                    }
                }
                int oidx = enc.dequeueOutputBuffer(bi, 2000);
                if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inEos && SystemClock.elapsedRealtime()
                            > drainDeadline) {
                        throw new IllegalStateException("encoder never finished");
                    }
                } else if (drainMuxed(enc, mux, bi, oidx)) {
                    outEos = true;
                }
            }
            finishedOk = !failed && mux.samples > 0;
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
                if (mux.started && mux.samples > 0) muxer.stop();
            } catch (Exception ignored) {}
            try {
                muxer.release();
            } catch (Exception ignored) {}
        }
        if (finishedOk && partFile.length() < 1024) {
            finishedOk = false;
            fail("encode failed");
        }
    }

    /** Muxer state shared between the feed loop and the parked drain. */
    private static final class MuxState {
        final MediaMuxer muxer;
        int track = -1;
        boolean started;
        int samples;

        MuxState(MediaMuxer muxer) {
            this.muxer = muxer;
        }
    }

    /**
     * Handles one dequeued output buffer: format change starts the muxer,
     * data buffers are written, EOS is reported. TRY_AGAIN_LATER returns
     * false (the caller owns deadlines). Returns true on end of stream.
     */
    private static boolean drainMuxed(MediaCodec enc, MuxState mux,
                                      MediaCodec.BufferInfo bi, int oidx)
            throws Exception {
        if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
        if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            mux.track = mux.muxer.addTrack(enc.getOutputFormat());
            mux.muxer.start();
            mux.started = true;
            return false;
        }
        if (oidx >= 0) {
            ByteBuffer ob = enc.getOutputBuffer(oidx);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    && bi.size > 0 && mux.started) {
                ob.position(bi.offset);
                ob.limit(bi.offset + bi.size);
                mux.muxer.writeSampleData(mux.track, ob, bi);
                mux.samples++;
            }
            enc.releaseOutputBuffer(oidx, false);
            return (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        }
        return false;
    }

    // ---- FLAC (raw stream + STREAMINFO) ----

    private void pumpFlac() throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat("audio/flac",
                sampleRate, channels);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 262144);
        MediaCodec enc = MediaCodec.createEncoderByType("audio/flac");
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
        } catch (Exception e) {
            try {
                enc.release();
            } catch (Exception ignored) {}
            throw e;
        }
        FileOutputStream out = null;
        boolean ok = false;
        try {
            out = new FileOutputStream(partFile);
        } catch (Exception | Error e) {
            // Setup threw after enc started: release it here, the main
            // try/finally below never runs.
            try {
                enc.release();
            } catch (Exception ignored) {}
            throw e;
        }
        try {
            // No magic here: FlacHeader.write() emits "fLaC" together with
            // the STREAMINFO block once the encoder publishes its config.
            boolean wroteInfo = false;
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            RandomAccessFile r = new RandomAccessFile(rawFile, "r");
            int pumpFrames = MemoryBudget.chunkFrames(PUMP_FRAMES, channels);
            byte[] in = new byte[pumpFrames * channels * 4];
            java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                    .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long done = 0;
            boolean inEos = false;
            boolean outEos = false;
            long drainDeadline = 0;
            try {
                while (!outEos) {
                    if (stopRequested && !finishing) return;
                    if (!inEos) {
                        long avail = availableFrames();
                        framesSeen = Math.max(framesSeen, avail);
                        long left = avail - done;
                        if (left <= 0) {
                            if (finishing) {
                                int idx = enc.dequeueInputBuffer(2000);
                                if (idx >= 0) {
                                    enc.queueInputBuffer(idx, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inEos = true;
                                    drainDeadline =
                                            SystemClock.elapsedRealtime()
                                                    + DRAIN_TIMEOUT_MS;
                                }
                            } else {
                                int oidx = enc.dequeueOutputBuffer(bi, 5000);
                                if (oidx >= 0) {
                                    wroteInfo = writeFlacSample(enc, oidx, bi,
                                            out, pending, wroteInfo);
                                    if ((bi.flags & MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outEos = true;
                                    }
                                } else if (oidx
                                        == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                    wroteInfo = writeFlacInfo(enc, out,
                                            pending, wroteInfo);
                                }
                                parkForMore();
                                continue;
                            }
                        } else {
                            int idx = enc.dequeueInputBuffer(2000);
                            if (idx >= 0) {
                                ByteBuffer dstBuf = enc.getInputBuffer(idx);
                                dstBuf.clear();
                                int maxByBuf = dstBuf.remaining() / 2
                                        / Math.max(1, channels);
                                int want = (int) Math.min(pumpFrames,
                                        Math.min(left, maxByBuf));
                                if (want <= 0) {
                                    enc.queueInputBuffer(idx, 0, 0, 0, 0);
                                } else {
                                    r.seek(dataStart + done * channels * 4L);
                                    r.readFully(in, 0, want * channels * 4);
                                    int shorts = want * channels;
                                    java.nio.ShortBuffer sb = dstBuf
                                            .order(ByteOrder.LITTLE_ENDIAN)
                                            .asShortBuffer();
                                    FormatConverter.toInt16(fb, shorts, sb);
                                    dstBuf.position(0);
                                    dstBuf.limit(shorts * 2);
                                    long pts = done * 1_000_000L / sampleRate;
                                    enc.queueInputBuffer(idx, 0, shorts * 2,
                                            pts, 0);
                                    done += want;
                                    framesEncoded = done;
                                }
                            }
                        }
                    }
                    int oidx = enc.dequeueOutputBuffer(bi, 2000);
                    if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (inEos && SystemClock.elapsedRealtime()
                                > drainDeadline) {
                            throw new IllegalStateException(
                                    "encoder never finished");
                        }
                    } else if (oidx
                            == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        wroteInfo = writeFlacInfo(enc, out, pending, wroteInfo);
                    } else if (oidx >= 0) {
                        wroteInfo = writeFlacSample(enc, oidx, bi, out,
                                pending, wroteInfo);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                != 0) {
                            outEos = true;
                        }
                    }
                }
            } finally {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
            out.flush();
            // STREAMINFO total_samples stays 0 on a streaming encode; patch it
            // now that every frame has been fed.
            try {
                RandomAccessFile rr = new RandomAccessFile(partFile, "rw");
                try {
                    FormatConverter.FlacHeader.patchTotalSamples(rr, done);
                } finally {
                    rr.close();
                }
            } catch (Exception ignored) {}
            ok = wroteInfo && partFile.length() > 1024;
            finishedOk = !failed && ok;
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
        }
        if (!ok) fail("flac encode failed");
    }

    private static boolean writeFlacInfo(MediaCodec enc, FileOutputStream out,
                                         ByteArrayOutputStream pending,
                                         boolean wroteInfo) {
        try {
            MediaFormat of = enc.getOutputFormat();
            if (wroteInfo) return wroteInfo;
            byte[] si = FormatConverter.FlacHeader.streamInfoFromCsd(
                    of.getByteBuffer("csd-0"));
            if (si != null) {
                FormatConverter.FlacHeader.write(out, si);
                wroteInfo = true;
                if (pending.size() > 0) {
                    pending.writeTo(out);
                    pending.reset();
                }
            }
        } catch (Exception ignored) {}
        return wroteInfo;
    }

    private static boolean writeFlacSample(MediaCodec enc, int oidx,
                                           MediaCodec.BufferInfo bi,
                                           FileOutputStream out,
                                           ByteArrayOutputStream pending,
                                           boolean wroteInfo) throws Exception {
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
        return wroteInfo;
    }

    // ---- AMR (streaming FIR decimation + encoder) ----

    private void pumpAmr(boolean wideband) throws Exception {
        final int outRate = wideband ? 16000 : 8000;
        // AMR encoders process exact 20 ms frames: 160 samples at 8 kHz,
        // 320 at 16 kHz.
        final int frameSamples = wideband ? 320 : 160;
        final int decim = Math.max(1, sampleRate / outRate);
        final double cutoff = (wideband ? 7000.0 : 3400.0) / sampleRate;
        final int taps = wideband ? 48 : 64;
        // Bounded streaming decimation: only the current chunk plus a
        // ~17k-sample filter window is ever retained, however long the take.
        final Dsp.FirDecimator decimator =
                new Dsp.FirDecimator(decim, Dsp.firLowpass(cutoff, taps));

        String mime = wideband ? "audio/amr-wb" : "audio/3gpp";
        int amrBitrate = wideband ? 23850 : 12200;
        MediaFormat fmt = MediaFormat.createAudioFormat(mime, outRate, 1);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, amrBitrate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768);
        MediaCodec enc = MediaCodec.createEncoderByType(mime);
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
        } catch (Exception e) {
            try {
                enc.release();
            } catch (Exception ignored) {}
            throw e;
        }
        MediaMuxer muxer = null;
        RandomAccessFile r = null;
        try {
            muxer = new MediaMuxer(partFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP);
            r = new RandomAccessFile(rawFile, "r");
        } catch (Exception | Error e) {
            // Setup threw after enc started: release it here, the main
            // try/finally below never runs.
            try {
                enc.release();
            } catch (Exception ignored) {}
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {}
            try {
                if (muxer != null) muxer.release();
            } catch (Exception ignored) {}
            throw e;
        }
        boolean muxStarted = false;
        int samplesWritten = 0;
        int track = -1;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        int pumpFrames = MemoryBudget.chunkFrames(PUMP_FRAMES, channels);
        byte[] in = new byte[pumpFrames * channels * 4];
        java.nio.FloatBuffer fb = ByteBuffer.wrap(in)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] mono = new float[pumpFrames];
        // Encoder backlog: decimated shorts awaiting codec room.
        short[] pend = new short[32768];
        int pendLen = 0;
        long readAbs = 0;
        long fed = 0;
        long nextOutPtsUs = 0L;
        boolean tailFlushed = false;
        try {
            boolean inEos = false;
            boolean outEos = false;
            long drainDeadline = 0;
            while (!outEos) {
                if (stopRequested && !finishing) return;
                long avail = availableFrames();
                framesSeen = Math.max(framesSeen, avail);
                if (!inEos) {
                    boolean canRead = readAbs < avail;
                    if (pendLen < pend.length / 2 && canRead) {
                        int want = (int) Math.min(pumpFrames, avail - readAbs);
                        r.seek(dataStart + readAbs * channels * 4L);
                        r.readFully(in, 0, want * channels * 4);
                        fb.clear();
                        fb.limit(want * channels);
                        if (channels == 2) {
                            for (int f = 0; f < want; f++) {
                                mono[f] = (fb.get(f * 2)
                                        + fb.get(f * 2 + 1)) * 0.5f;
                            }
                        } else if (channels > 2) {
                            float inv = 1f / channels;
                            for (int f = 0; f < want; f++) {
                                float sum = 0f;
                                int base = f * channels;
                                for (int c = 0; c < channels; c++) {
                                    sum += fb.get(base + c);
                                }
                                mono[f] = sum * inv;
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
                        // AUDIT-3-04 fix: decimate straight into the backlog;
                        // the intermediate buffer plus copy was pointless.
                        pendLen += decimator.push(mono, want, pend, pendLen);
                        readAbs += want;
                        framesEncoded = readAbs;
                    } else if (finishing && !canRead && !tailFlushed
                            && avail > 0) {
                        int need = pendLen + taps + frameSamples + 16;
                        if (need > pend.length) {
                            short[] bigger = new short[Math.max(
                                    pend.length * 2, need)];
                            System.arraycopy(pend, 0, bigger, 0, pendLen);
                            pend = bigger;
                        }
                        pendLen += decimator.flush(readAbs, pend, pendLen);
                        int pad = (frameSamples - (pendLen % frameSamples))
                                % frameSamples;
                        if (pad > 0) {
                            int padNeed = pendLen + pad;
                            if (padNeed > pend.length) {
                                short[] bigger = new short[Math.max(
                                        pend.length * 2, padNeed)];
                                System.arraycopy(pend, 0, bigger, 0,
                                        pendLen);
                                pend = bigger;
                            }
                            java.util.Arrays.fill(pend, pendLen,
                                    pendLen + pad, (short) 0);
                            pendLen += pad;
                        }
                        tailFlushed = true;
                    }
                    if (pendLen >= frameSamples) {
                        int idx = enc.dequeueInputBuffer(2000);
                        if (idx >= 0) {
                            ByteBuffer dstBuf = enc.getInputBuffer(idx);
                            dstBuf.clear();
                            // Feed exactly one 20 ms frame per input buffer.
                            int want = frameSamples;
                            java.nio.ShortBuffer sb = dstBuf
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .asShortBuffer();
                            sb.put(pend, 0, want);
                            dstBuf.position(0);
                            dstBuf.limit(want * 2);
                            long pts = fed * 1_000_000L / outRate;
                            enc.queueInputBuffer(idx, 0, want * 2, pts, 0);
                            fed += want;
                            System.arraycopy(pend, want, pend, 0,
                                    pendLen - want);
                            pendLen -= want;
                        }
                    } else if (finishing && tailFlushed && readAbs >= avail) {
                        // Everything read, decimated, and fed: close the
                        // stream. Queued input still drains before the EOS.
                        int idx = enc.dequeueInputBuffer(2000);
                        if (idx >= 0) {
                            enc.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                            drainDeadline = SystemClock.elapsedRealtime()
                                    + DRAIN_TIMEOUT_MS;
                        }
                    } else if (finishing && avail <= 0) {
                        // Finishing on an empty take: end it now.
                        int idx = enc.dequeueInputBuffer(2000);
                        if (idx >= 0) {
                            enc.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                            drainDeadline = SystemClock.elapsedRealtime()
                                    + DRAIN_TIMEOUT_MS;
                        }
                    } else if (!finishing) {
                        int oidx = enc.dequeueOutputBuffer(bi, 5000);
                        if (oidx >= 0) {
                            ByteBuffer ob = enc.getOutputBuffer(oidx);
                            if ((bi.flags
                                    & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                                    == 0 && bi.size > 0 && muxStarted) {
                                nextOutPtsUs = FormatConverter
                                        .writeAmrFramesSingle(muxer, track,
                                                ob, bi, wideband,
                                                nextOutPtsUs);
                                samplesWritten++;
                            }
                            enc.releaseOutputBuffer(oidx, false);
                            if ((bi.flags & MediaCodec
                                    .BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outEos = true;
                            }
                        } else if (oidx
                                == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(enc.getOutputFormat());
                            muxer.start();
                            muxStarted = true;
                        }
                        parkForMore();
                        continue;
                    }
                }
                int oidx = enc.dequeueOutputBuffer(bi, 2000);
                if (oidx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inEos && SystemClock.elapsedRealtime()
                            > drainDeadline) {
                        throw new IllegalStateException("encoder never finished");
                    }
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (oidx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(oidx);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && bi.size > 0 && muxStarted) {
                        nextOutPtsUs = FormatConverter.writeAmrFramesSingle(
                                muxer, track, ob, bi, wideband,
                                nextOutPtsUs);
                        samplesWritten++;
                    }
                    enc.releaseOutputBuffer(oidx, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outEos = true;
                    }
                }
            }
            finishedOk = !failed && samplesWritten > 0;
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
                if (muxStarted && samplesWritten > 0) muxer.stop();
            } catch (Exception ignored) {}
            try {
                muxer.release();
            } catch (Exception ignored) {}
        }
        if (finishedOk && partFile.length() < 512) {
            finishedOk = false;
            fail("amr encode failed");
        }
    }
}
