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

    @Test
    public void virtualMicCapturesGeneratedSpeech() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
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
