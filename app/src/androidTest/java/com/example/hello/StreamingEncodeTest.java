package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Proves incremental encoding works on a real device image: while capture
 * runs, the streaming encoder follows the raw file, and saving adopts the
 * already-encoded output instead of reconverting.
 */
@RunWith(AndroidJUnit4.class)
public class StreamingEncodeTest {

    @Rule
    public androidx.test.rule.GrantPermissionRule mic =
            androidx.test.rule.GrantPermissionRule.grant(
                    android.Manifest.permission.RECORD_AUDIO);

    @Test
    public void partFileGrowsDuringCapture() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Streaming", 2);
        try {
            engine.beginStreaming(ctx, RecordPreset.MEMO, SaveFormat.M4A);
            StreamingEncoder sess = engine.streaming();
            assertNotNull("no streaming session", sess);
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(2500);
            assertTrue("streaming died: " + sess.failure(), sess.healthy());
            assertTrue("nothing encoded yet (frames=" + sess.framesEncoded()
                    + ")", sess.framesEncoded() > 48000);
            assertTrue("no part file", sess.partFile().exists()
                    && sess.partFile().length() > 1024);
        } finally {
            engine.abortAndDelete();
        }
    }

    @Test
    public void saveAdoptsStreamedOutput() throws Exception {        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Streaming Adopt", 2);
        File adopted = null;
        try {
            engine.beginStreaming(ctx, RecordPreset.MEMO, SaveFormat.M4A);
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(2500);
            engine.pauseCapture();
            ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx,
                    RecordPreset.MEMO, SaveFormat.M4A, null);
            assertTrue("did not adopt the streamed output", r.adopted);
            adopted = r.file;
            assertTrue("adopted file missing",
                    adopted != null && adopted.exists());
            FormatConverter.validateFinal(adopted, SaveFormat.M4A);
            assertTrue("raw was not deleted", !engine.hasSession()
                    || engine.rawFile == null || !engine.rawFile.exists());
        } finally {
            engine.clearSession();
            if (adopted != null && adopted.exists()) {
                // noinspection ResultOfMethodCallIgnored
                adopted.delete();
            }
            engine.abortAndDelete();
        }
    }

    @Test
    public void wav16StreamAdopts() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Streaming WAV16", 2);
        File adopted = null;
        try {
            engine.beginStreaming(ctx, RecordPreset.MEMO, SaveFormat.WAV16);
            assertNotNull(engine.streaming());
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(2000);
            engine.pauseCapture();
            ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx,
                    RecordPreset.MEMO, SaveFormat.WAV16, null);
            assertTrue("wav16 stream was not adopted", r.adopted);
            adopted = r.file;
            FormatConverter.validateFinal(adopted, SaveFormat.WAV16);
            // ~2s stereo 16-bit: between 1s and 4s of payload plus header.
            long payload = adopted.length() - 44;
            assertTrue("payload=" + payload,
                    payload > 48000L * 2 * 2 * 1
                            && payload < 48000L * 2 * 2 * 4);
        } finally {
            engine.clearSession();
            if (adopted != null && adopted.exists()) {
                // noinspection ResultOfMethodCallIgnored
                adopted.delete();
            }
            engine.abortAndDelete();
        }
    }

    @Test
    public void wav32StreamAdoptsRawFloat() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Streaming WAV32", 2);
        File adopted = null;
        try {
            engine.beginStreaming(ctx, RecordPreset.MUSIC, SaveFormat.WAV32);
            assertNotNull(engine.streaming());
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(2000);
            engine.pauseCapture();
            ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx,
                    RecordPreset.MUSIC, SaveFormat.WAV32, null);
            assertTrue("wav32 stream was not adopted", r.adopted);
            adopted = r.file;
            FormatConverter.validateFinal(adopted, SaveFormat.WAV32);
            long payload = adopted.length() - 44;
            assertTrue("payload=" + payload,
                    payload > 48000L * 2 * 4 * 1
                            && payload < 48000L * 2 * 4 * 4);
        } finally {
            engine.clearSession();
            if (adopted != null && adopted.exists()) {
                // noinspection ResultOfMethodCallIgnored
                adopted.delete();
            }
            engine.abortAndDelete();
        }
    }

    @Test
    public void opusStreamAdoptsWhenEncoderExists() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        org.junit.Assume.assumeTrue("no opus encoder on this device",
                SaveFormat.OPUS.available(ctx));
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Streaming Opus", 2);
        File adopted = null;
        try {
            engine.beginStreaming(ctx, RecordPreset.MEMO, SaveFormat.OPUS);
            assertNotNull(engine.streaming());
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(2500);
            engine.pauseCapture();
            ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx,
                    RecordPreset.MEMO, SaveFormat.OPUS, null);
            assertTrue("opus stream was not adopted", r.adopted);
            adopted = r.file;
            FormatConverter.validateFinal(adopted, SaveFormat.OPUS);
        } finally {
            engine.clearSession();
            if (adopted != null && adopted.exists()) {
                // noinspection ResultOfMethodCallIgnored
                adopted.delete();
            }
            engine.abortAndDelete();
        }
    }

    @Test
    public void pauseResumeKeepsStreamAdoptable() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx, "Streaming Pause", 2);
        File adopted = null;
        try {
            engine.beginStreaming(ctx, RecordPreset.MEMO, SaveFormat.M4A);
            assertNotNull(engine.streaming());
            engine.startCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(1500);
            engine.pauseCapture();
            // Encoder idles while paused; resume must continue the same
            // stream with contiguous timestamps.
            Thread.sleep(800);
            engine.resumeCapture(ctx,
                    android.media.MediaRecorder.AudioSource.MIC);
            Thread.sleep(1500);
            engine.pauseCapture();
            assertTrue("streaming died across pause: "
                    + (engine.streaming() != null
                            ? engine.streaming().failure() : "gone"),
                    engine.streaming() != null
                            && engine.streaming().healthy());
            ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx,
                    RecordPreset.MEMO, SaveFormat.M4A, null);
            assertTrue("paused/resumed stream was not adopted", r.adopted);
            adopted = r.file;
            FormatConverter.validateFinal(adopted, SaveFormat.M4A);
            // ~3s of audio across the pause gap (gap itself not recorded).
            assertTrue("duration=" + r.durationMs,
                    r.durationMs >= 2000 && r.durationMs <= 5000);
        } finally {
            engine.clearSession();
            if (adopted != null && adopted.exists()) {
                // noinspection ResultOfMethodCallIgnored
                adopted.delete();
            }
            engine.abortAndDelete();
        }
    }
}
