package com.example.hello;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Plays a 32-bit float WAV straight through {@link AudioTrack} with sample-accurate
 * playhead tracking, range preview, full hardware buffer draining (so short takes
 * and selections are never cut off before the speaker plays them), and real-time
 * {@link DspChain} preview.
 */
public final class RawFloatPlayer {

    public interface Listener {
        /** Called once when playback stops: true = reached the end. */
        void onDone(boolean completed);
    }

    private final File file;
    private final int channels;
    private final long dataStart;
    private final long totalFrames;
    private final Listener listener;

    private Thread thread;
    private volatile boolean stop;
    private volatile boolean paused;
    private volatile long startFramePos;
    private volatile long framePos;
    private volatile AudioTrack activeTrack;
    private volatile long generation;
    private volatile DspChain dspChain;
    private volatile long stopAtFrame = -1;

    public void setDspChain(DspChain chain) {
        this.dspChain = chain;
    }

    public RawFloatPlayer(File file, Listener listener) throws Exception {
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(file);
        if (info.channels < 1 || info.channels > 2) {
            throw new IllegalStateException("bad channels");
        }
        this.file = file;
        this.channels = info.channels;
        this.dataStart = info.dataStart;
        this.totalFrames = info.dataBytes / 4 / info.channels;
        if (totalFrames <= 0) throw new IllegalStateException("empty");
        this.listener = listener;
    }

    public long durationMs() {
        return totalFrames * 1000L / RecordingEngine.SAMPLE_RATE;
    }

    public synchronized void play() {
        playFrom(framePos);
    }

    public synchronized void playFrom(long frame) {
        stopLocked();
        stop = false;
        paused = false;
        stopAtFrame = -1;
        framePos = Math.max(0, Math.min(totalFrames, frame));
        startFramePos = framePos;
        generation++;
        thread = new Thread(this::run, "takeone-raw-preview");
        thread.start();
    }

    /** Play a specific frame range `[startFrame, endFrame)` (e.g. selection preview). */
    public synchronized void playRange(long startFrame, long endFrame) {
        stopLocked();
        stop = false;
        paused = false;
        framePos = Math.max(0, Math.min(totalFrames, startFrame));
        startFramePos = framePos;
        stopAtFrame = (endFrame > framePos && endFrame <= totalFrames)
                ? endFrame : -1;
        generation++;
        thread = new Thread(this::run, "takeone-raw-preview");
        thread.start();
    }

    public synchronized void pause() {
        long cur = positionFrames();
        paused = true;
        stopLocked();
        framePos = cur;
    }

    public synchronized void stop() {
        stopLocked();
        framePos = 0;
        startFramePos = 0;
    }

    public long positionFrames() {
        AudioTrack t = activeTrack;
        if (t != null && !stop && !paused) {
            try {
                long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                if (head > 0) {
                    long limit = (stopAtFrame > 0 && stopAtFrame <= totalFrames)
                            ? stopAtFrame : totalFrames;
                    return Math.min(limit, startFramePos + head);
                }
            } catch (Exception ignored) {}
        }
        return framePos;
    }

    public long positionMs() {
        return positionFrames() * 1000L / RecordingEngine.SAMPLE_RATE;
    }

    public synchronized void seekToMs(long ms) {
        long f = ms * RecordingEngine.SAMPLE_RATE / 1000L;
        boolean wasPlaying = thread != null && !paused;
        stopLocked();
        framePos = Math.max(0, Math.min(totalFrames, f));
        startFramePos = framePos;
        if (wasPlaying) {
            stop = false;
            paused = false;
            generation++;
            thread = new Thread(this::run, "takeone-raw-preview");
            thread.start();
        }
    }

    private synchronized void stopLocked() {
        generation++;
        stop = true;
        paused = true;
        AudioTrack t = activeTrack;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.stop();
            } catch (Exception ignored) {}
        }
        Thread th = thread;
        thread = null;
        if (th != null) {
            try {
                th.join(800);
            } catch (InterruptedException ignored) {}
        }
    }

    private void run() {
        final long gen = generation;
        boolean completed = false;
        AudioTrack track = null;
        RandomAccessFile r = null;
        ConversionJobs.beginRawRead(file.getAbsolutePath());
        try {
            int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO
                    : AudioFormat.CHANNEL_OUT_MONO;
            int minBytes = AudioTrack.getMinBufferSize(
                    RecordingEngine.SAMPLE_RATE,
                    mask, AudioFormat.ENCODING_PCM_FLOAT);
            if (minBytes <= 0) minBytes = 8192;
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            android.media.AudioFormat af = new android.media.AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(RecordingEngine.SAMPLE_RATE)
                    .setChannelMask(mask)
                    .build();
            long limitFrame = (stopAtFrame > 0 && stopAtFrame <= totalFrames)
                    ? stopAtFrame : totalFrames;
            long framesToPlay = Math.max(1L, limitFrame - startFramePos);
            int spanBytes = (int) Math.min(65536L,
                    Math.max(minBytes, framesToPlay * channels * 4L));
            track = new AudioTrack(attrs, af, spanBytes,
                    AudioTrack.MODE_STREAM, 0);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) return;
            r = new RandomAccessFile(file, "r");
            float[] buf = new float[2048 * channels];
            byte[] raw = new byte[buf.length * 4];
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            track.play();
            activeTrack = track;
            long writtenFrames = 0;
            while (!stop && framePos < limitFrame) {
                int want = (int) Math.min(2048, limitFrame - framePos);
                r.seek(dataStart + framePos * channels * 4L);
                r.readFully(raw, 0, want * channels * 4);
                fb.clear();
                fb.limit(want * channels);
                fb.get(buf, 0, want * channels);
                DspChain chain = this.dspChain;
                if (chain != null && chain.isActive()) {
                    chain.processInPlace(buf, want);
                }
                int written = 0;
                while (written < want * channels && !stop) {
                    int n = track.write(buf, written,
                            want * channels - written,
                            AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) break;
                    written += n;
                }
                framePos += want;
                writtenFrames += want;
            }
            // Wait for the AudioTrack hardware buffer to finish playing out
            // before stopping/releasing the track in finally!
            if (!stop && framePos >= limitFrame && writtenFrames > 0) {
                long drainStart = SystemClock.elapsedRealtime();
                long maxDrainMs = Math.min(1500L,
                        (writtenFrames * 1000L / RecordingEngine.SAMPLE_RATE) + 350L);
                long lastHead = -1;
                long lastAdvanceAt = drainStart;
                while (!stop) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - drainStart >= maxDrainMs) break;
                    long head = 0;
                    try {
                        head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    } catch (Exception ignored) {}
                    if (head >= writtenFrames) break;
                    if (head != lastHead) {
                        lastHead = head;
                        lastAdvanceAt = now;
                    } else if (now - lastAdvanceAt > 180L) {
                        // Headless / Robolectric AudioTrack stub where playback
                        // head does not advance: exit promptly.
                        break;
                    }
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                completed = !stop;
            }
        } catch (Exception ignored) {
        } finally {
            activeTrack = null;
            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {}
                try {
                    track.release();
                } catch (Exception ignored) {}
            }
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }
            ConversionJobs.endRawRead(file.getAbsolutePath());
            if (gen == generation && listener != null) {
                try {
                    listener.onDone(completed);
                } catch (Exception ignored) {}
            }
        }
    }
}


