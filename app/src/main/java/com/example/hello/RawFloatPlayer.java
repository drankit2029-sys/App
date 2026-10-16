package com.example.hello;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Plays a 32-bit float WAV (the retained raw of a take that is still
 * converting) straight through {@link AudioTrack}. Lets the user preview a
 * recording while its format conversion runs in the background.
 *
 * <p>Play/pause/stop plus sample-accurate seek (the file layout is fixed),
 * all on a dedicated thread. Any I/O or audio error ends playback through
 * {@link Listener#onDone(boolean)} rather than throwing.
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
        framePos = Math.max(0, Math.min(totalFrames, frame));
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
        stopAtFrame = (endFrame > framePos && endFrame <= totalFrames)
                ? endFrame : -1;
        generation++;
        thread = new Thread(this::run, "takeone-raw-preview");
        thread.start();
    }

    public synchronized void pause() {
        paused = true;
        stopLocked();
    }

    public synchronized void stop() {
        stopLocked();
        framePos = 0;
    }

    public long positionFrames() {
        return framePos;
    }

    public long positionMs() {
        return framePos * 1000L / RecordingEngine.SAMPLE_RATE;
    }

    public synchronized void seekToMs(long ms) {
        long f = ms * RecordingEngine.SAMPLE_RATE / 1000L;
        boolean wasPlaying = thread != null && !paused;
        stopLocked();
        framePos = Math.max(0, Math.min(totalFrames, f));
        if (wasPlaying) {
            stop = false;
            paused = false;
            generation++;
            thread = new Thread(this::run, "takeone-raw-preview");
            thread.start();
        }
    }

    private synchronized void stopLocked() {
        // Retire the running generation FIRST so its finally block cannot
        // fire listener.onDone(false) into a pause/seek/stop caller.
        generation++;
        stop = true;
        paused = true;
        // Unblock a writer parked in WRITE_BLOCKING so join() below cannot
        // stall; without this a pause during a slow drain leaks a live
        // thread that then races the next playback over the shared position.
        AudioTrack t = activeTrack;
        if (t != null) {
            try {
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
        // Hold the raw for as long as we read it: the conversion worker
        // deletes this file the moment the job finishes, which used to pull it
        // out from under this reader and truncate playback.
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
            track = new AudioTrack(attrs, af, Math.max(minBytes, 65536),
                    AudioTrack.MODE_STREAM, 0);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) return;
            r = new RandomAccessFile(file, "r");
            float[] buf = new float[4096 * channels];
            byte[] raw = new byte[buf.length * 4];
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            track.play();
            activeTrack = track;
            long limitFrame = (stopAtFrame > 0 && stopAtFrame <= totalFrames)
                    ? stopAtFrame : totalFrames;
            while (!stop && framePos < limitFrame) {
                int want = (int) Math.min(4096, limitFrame - framePos);
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
            }
            completed = !stop && framePos >= limitFrame;
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
            // Only the latest generation reports: a stopped-then-replayed
            // thread must not overwrite the new playback's completion.
            if (gen == generation && listener != null) {
                try {
                    listener.onDone(completed);
                } catch (Exception ignored) {}
            }
        }
    }
}
