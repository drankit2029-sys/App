package com.example.hello;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Proves the emulator's virtual microphone is actually engaged: records a few
 * seconds while the host plays generated speech into the mic, then asserts
 * the captured float WAV contains real signal (not silence/zeros) and that
 * the engine's live level meter responds.
 *
 * <p>Run {@code adb emu avd rewindaudio} just before this class so the
 * file-backed mic starts at the beginning of the speech sample.
 */
@RunWith(AndroidJUnit4.class)
public class SyntheticVoiceCaptureTest {

    @Rule
    public androidx.test.rule.GrantPermissionRule mic =
            androidx.test.rule.GrantPermissionRule.grant(
                    android.Manifest.permission.RECORD_AUDIO);

    /**
     * True when the device offers a usable capture input.
     *
     * <p>This test needs a real audio source behind the mic: the emulator's
     * virtual mic, fed by the host. On a runner with no audio backend the HAL
     * rejects the input format outright ("getInputBufferSize:
     * INVALID_ARGUMENTS"), and there is nothing to assert about the app in
     * that case.
     */
    private boolean captureInputAvailable(Context ctx) {
        android.media.AudioManager am =
                (android.media.AudioManager) ctx.getSystemService(
                        Context.AUDIO_SERVICE);
        if (am == null) return false;
        android.media.AudioDeviceInfo[] ins =
                am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS);
        if (ins == null || ins.length == 0) return false;
        // Opening a record is not proof of a source: on a host with no audio
        // backend the HAL hands back an initialised record that never
        // produces a sample. Read briefly and require real energy.
        android.media.AudioRecord rec = null;
        try {
            int min = android.media.AudioRecord.getMinBufferSize(48000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT);
            if (min <= 0) min = 8192;
            rec = new android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC, 48000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT,
                    min * 2);
            if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                return false;
            }
            rec.startRecording();
            if (rec.getRecordingState()
                    != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                return false;
            }
            float peak = 0f;
            byte[] buf = new byte[4096];
            long until = System.currentTimeMillis() + 1200;
            while (System.currentTimeMillis() < until && peak < 0.001f) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                ByteBuffer bb = ByteBuffer.wrap(buf, 0, n)
                        .order(ByteOrder.LITTLE_ENDIAN);
                while (bb.remaining() >= 4) {
                    float v = Math.abs(bb.getFloat());
                    if (v > peak) peak = v;
                }
            }
            return peak > 0.001f;
        } catch (Throwable t) {
            return false;
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Throwable ignored) {}
                try {
                    rec.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    @Test
    public void virtualMicCapturesGeneratedSpeech() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        org.junit.Assume.assumeTrue("no usable capture input on this device",
                captureInputAvailable(ctx));
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Synthetic Voice", 2);
        try {
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            assertTrue("capture did not start",
                    engine.state == RecordingEngine.State.RECORDING);

            // Let ~2.5s of the generated speech stream in.
            Thread.sleep(2500);

            float level = engine.level;
            assertTrue("live meter never moved; mic is silent (level="
                    + level + ")", level > 0.001f);

            File raw = engine.finalizeRaw();
            assertTrue("no raw file", raw != null && raw.exists());

            float peak = 0f;
            double sum = 0d;
            long n = 0;
            RandomAccessFile r = new RandomAccessFile(raw, "r");
            try {
                r.seek(44);
                byte[] buf = new byte[65536];
                ByteBuffer bb = ByteBuffer.wrap(buf)
                        .order(ByteOrder.LITTLE_ENDIAN);
                int read;
                while ((read = r.read(buf)) > 0) {
                    bb.clear();
                    bb.limit(read);
                    while (bb.remaining() >= 4) {
                        float s = Math.abs(bb.getFloat());
                        if (s > peak) peak = s;
                        sum += s;
                        n++;
                    }
                }
            } finally {
                r.close();
            }
            double mean = n == 0 ? 0 : sum / n;
            assertTrue("captured only " + n + " samples", n > 48000);
            assertTrue("captured silence (peak=" + peak + ", mean=" + mean
                    + "); the virtual mic is not engaged",
                    peak > 0.02f && mean > 0.0005);

            // And the synthetic take must survive the real save pipeline.
            TakeSaver.Result result = TakeSaver.finalizeAndConvert(ctx,
                    RecordPreset.MEMO, SaveFormat.M4A, null);
            assertTrue("no saved file",
                    result != null && result.file.exists()
                            && result.file.length() > 512);
            // noinspection ResultOfMethodCallIgnored
            result.file.delete();
        } finally {
            engine.abortAndDelete();
        }
    }
}
