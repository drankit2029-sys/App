package com.example.hello;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Plays a 32-bit float WAV straight through {@link AudioTrack} with:
 * <ul>
 *   <li><b>Live dBFS Meter Level ({@link #level})</b>: Computes the real-time
 *       post-FX peak amplitude of every played chunk so {@link DbMeterView}
 *       shows live dBFS readings while previewing!</li>
 *   <li><b>Zero Playhead Jerk</b>: {@link #positionFrames()} tracks the
 *       monotonic hardware speaker head ({@code startFramePos + head}).</li>
 *   <li><b>Speaker Pre-Warming</b>: {@link #warmUpSpeaker(int)} primes a
 *       low-latency {@link AudioTrack} for instant start.</li>
 * </ul>
 */
public final class RawFloatPlayer {

    public interface Listener {
        /** Called once when playback stops: true = reached the end. */
        void onDone(boolean completed);
    }

    private static final Object WARM_LOCK = new Object();
    private static AudioTrack warmTrack;
    private static int warmChannels;
    private static volatile boolean warmingSpeaker;

    /**
     * Pre-warms the hardware speaker sink on a background thread when the user
     * starts scrolling the waveform so pressing Resume/Preview plays immediately.
     */
    public static void warmUpSpeaker(final int channels) {
        final int ch = channels == 1 ? 1 : 2;
        synchronized (WARM_LOCK) {
            if (warmTrack != null && warmChannels == ch
                    && warmTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                return;
            }
            if (warmingSpeaker) return;
            warmingSpeaker = true;
        }
        new Thread(() -> {
            AudioTrack built = null;
            try {
                built = createLowLatencyTrack(ch, 32768);
                if (built.getState() == AudioTrack.STATE_INITIALIZED) {
                    built.play();
                    float[] silence = new float[128 * ch];
                    built.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
                    built.pause();
                    built.flush();
                } else {
                    built.release();
                    built = null;
                }
            } catch (Throwable ignored) {
                if (built != null) {
                    try { built.release(); } catch (Throwable ignored2) {}
                    built = null;
                }
            }
            synchronized (WARM_LOCK) {
                warmingSpeaker = false;
                if (built != null) {
                    if (warmTrack != null) {
                        try { warmTrack.release(); } catch (Throwable ignored) {}
                    }
                    warmTrack = built;
                    warmChannels = ch;
                }
            }
        }, "takeone-speaker-warm").start();
    }

    public static void releaseWarmSpeaker() {
        synchronized (WARM_LOCK) {
            if (warmTrack != null) {
                try { warmTrack.stop(); } catch (Throwable ignored) {}
                try { warmTrack.release(); } catch (Throwable ignored) {}
                warmTrack = null;
            }
        }
    }

    private static AudioTrack obtainTrack(int channels, int desiredBytes) {
        synchronized (WARM_LOCK) {
            if (warmTrack != null && warmChannels == channels
                    && warmTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                AudioTrack t = warmTrack;
                warmTrack = null;
                return t;
            }
        }
        return createLowLatencyTrack(channels, desiredBytes);
    }

    private static AudioTrack createLowLatencyTrack(int channels, int desiredBytes) {
        int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int minBytes = AudioTrack.getMinBufferSize(
                RecordingEngine.SAMPLE_RATE,
                mask, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBytes <= 0) minBytes = 8192;
        int bufBytes = Math.max(minBytes, Math.min(32768, desiredBytes));
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RecordingEngine.SAMPLE_RATE)
                .setChannelMask(mask)
                .build();
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(af)
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } catch (Throwable ignored) {}
        }
        return new AudioTrack(attrs, af, bufBytes, AudioTrack.MODE_STREAM, 0);
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
    private volatile long maxReportedFrame;
    private volatile long playStartedRealtimeMs;
    private volatile AudioTrack activeTrack;
    private volatile long generation;
    private volatile DspChain dspChain;
    private volatile long stopAtFrame = -1;

    /**
     * Real-time peak amplitude (0..1) of the audio currently playing through
     * the speaker (including live DspChain FX), driving DbMeterView during preview!
     */
    public volatile float level = 0f;

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
        maxReportedFrame = framePos;
        playStartedRealtimeMs = SystemClock.elapsedRealtime();
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
        maxReportedFrame = framePos;
        playStartedRealtimeMs = SystemClock.elapsedRealtime();
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
        startFramePos = cur;
        maxReportedFrame = cur;
        level = 0f;
    }

    public synchronized void stop() {
        stopLocked();
        framePos = 0;
        startFramePos = 0;
        maxReportedFrame = 0;
        level = 0f;
    }

    public long positionFrames() {
        long limit = (stopAtFrame > 0 && stopAtFrame <= totalFrames)
                ? stopAtFrame : totalFrames;
        if (!stop && !paused) {
            AudioTrack t = activeTrack;
            if (t != null) {
                try {
                    long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    if (head > 0) {
                        long pos = Math.min(limit, startFramePos + head);
                        if (pos > maxReportedFrame) maxReportedFrame = pos;
                        return maxReportedFrame;
                    }
                    long elapsed = SystemClock.elapsedRealtime() - playStartedRealtimeMs;
                    if (elapsed > 160L && framePos > startFramePos) {
                        long sim = Math.min(limit,
                                startFramePos + (elapsed * RecordingEngine.SAMPLE_RATE / 1000L));
                        if (sim > maxReportedFrame) maxReportedFrame = sim;
                        return maxReportedFrame;
                    }
                } catch (Exception ignored) {}
            }
            return maxReportedFrame;
        }
        return maxReportedFrame > 0 ? maxReportedFrame : framePos;
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
        maxReportedFrame = framePos;
        if (wasPlaying) {
            stop = false;
            paused = false;
            playStartedRealtimeMs = SystemClock.elapsedRealtime();
            generation++;
            thread = new Thread(this::run, "takeone-raw-preview");
            thread.start();
        }
    }

    private synchronized void stopLocked() {
        generation++;
        stop = true;
        paused = true;
        level = 0f;
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
            long limitFrame = (stopAtFrame > 0 && stopAtFrame <= totalFrames)
                    ? stopAtFrame : totalFrames;
            long framesToPlay = Math.max(1L, limitFrame - startFramePos);
            int spanBytes = (int) Math.min(32768L, framesToPlay * channels * 4L);
            track = obtainTrack(channels, spanBytes);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) return;
            r = new RandomAccessFile(file, "r");
            float[] buf = new float[1024 * channels];
            byte[] raw = new byte[buf.length * 4];
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            playStartedRealtimeMs = SystemClock.elapsedRealtime();
            track.play();
            activeTrack = track;
            long writtenFrames = 0;
            while (!stop && framePos < limitFrame) {
                int want = (int) Math.min(1024, limitFrame - framePos);
                r.seek(dataStart + framePos * channels * 4L);
                r.readFully(raw, 0, want * channels * 4);
                fb.clear();
                fb.limit(want * channels);
                fb.get(buf, 0, want * channels);
                DspChain chain = this.dspChain;
                if (chain != null && chain.isActive()) {
                    chain.processInPlace(buf, want);
                }
                // Compute post-FX peak level for the dBFS meter during preview!
                float chunkPeak = 0f;
                int totalSamples = want * channels;
                for (int i = 0; i < totalSamples; i++) {
                    float a = Math.abs(buf[i]);
                    if (a > chunkPeak) chunkPeak = a;
                }
                this.level = Math.min(1f, chunkPeak);

                int written = 0;
                while (written < totalSamples && !stop) {
                    int n = track.write(buf, written,
                            totalSamples - written,
                            AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) break;
                    written += n;
                }
                framePos += want;
                writtenFrames += want;
            }
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
                        break;
                    }
                    try {
                        Thread.sleep(12);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
                if (!stop) {
                    maxReportedFrame = limitFrame;
                    completed = true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            this.level = 0f;
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
