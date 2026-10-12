package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * End-to-end execution of the real stop/save pipeline: engine session ->
 * float capture on disk -> finalize -> convert -> library add -> the startup
 * sweep that runs on the next launch. The WAV16 path is pure Java, so it runs
 * identically off-device, which makes it the one honest test of the whole
 * tail after the progress bar completes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SavePipelineTest {

    private Context ctx;
    private RecordingEngine engine;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        engine = RecordingEngine.get();
        engine.clearSession();
        for (File f : RecordingEngine.takesDir(ctx).listFiles() != null
                ? RecordingEngine.takesDir(ctx).listFiles()
                : new File[0]) {
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @After
    public void tearDown() {
        engine.clearSession();
    }

    /** Writes real float samples into the live session file, like capLoop. */
    private void writeCaptureSeconds(double seconds) throws Exception {
        File raw = engine.rawFile;
        assertNotNull("no session file", raw);
        int frames = (int) (RecordingEngine.SAMPLE_RATE * seconds);
        float[] buf = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float v = (float) Math.sin(i * 0.05);
            buf[i * 2] = v;
            buf[i * 2 + 1] = v;
        }
        RandomAccessFile w = new RandomAccessFile(raw, "rw");
        try {
            w.seek(w.length());
            for (int i = 0; i < buf.length; i++) w.writeFloat(buf[i]);
        } finally {
            w.close();
        }
    }

    @Test
    public void fullStopSaveLibraryAndRelaunch() throws Exception {
        // 1) Session opens, raw float file exists with a pending marker.
        engine.startNewSession(ctx, "E2E Take", 2);
        File raw = engine.rawFile;
        assertTrue(raw.exists());
        assertTrue(new File(raw.getAbsolutePath() + ".pending").exists());

        // 2) Capture lands on disk.
        writeCaptureSeconds(1.0);

        // 3) Stop -> convert -> validate. This is the tail that used to kill
        //    the app; it must run to completion and report progress.
        final int[] lastPct = {-1};
        TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx,
                RecordPreset.MEMO, SaveFormat.WAV16,
                pct -> lastPct[0] = pct);

        assertEquals(100, lastPct[0]);
        assertNotNull(r.file);
        assertTrue("converted file missing", r.file.exists());
        assertEquals("WAV", r.label);
        assertEquals("E2E Take", r.title);
        // One second of stereo 48 kHz capture.
        assertTrue("duration off: " + r.durationMs,
                Math.abs(r.durationMs - 1000) < 60);
        // The final file is a real, playable 16-bit WAV.
        assertTrue(Dsp.wavDurationMs(r.file) > 0);
        // The raw was cleaned up after a successful conversion.
        assertFalse("raw not deleted", raw.exists());
        // The pending marker is gone.
        assertFalse(new File(raw.getAbsolutePath() + ".pending").exists());

        // 4) Library add (what the save button does next).
        int before = TakesRepository.all().size();
        TakesRepository.addRecording(r.file, r.durationMs, r.title, r.label);
        assertEquals(before + 1, TakesRepository.all().size());

        // 5) Next launch: the sweep must not throw, must not re-adopt the
        //    take we just added, and must keep the file.
        TakesRepository.ReconcileResult rr = TakesRepository.reconcile(ctx);
        assertNotNull(rr);
        assertEquals(0, rr.adopted);
        assertTrue(r.file.exists());
        assertEquals(before + 1, TakesRepository.all().size());
    }

    @Test
    public void convertedOutputSurvivesItsOwnValidation() throws Exception {
        // Every container we can produce must pass the gate we run before
        // handing a file to the library. WAV16 is the only pure-Java path
        // that produces real bytes off-device; the encoder-backed ones are
        // covered by validateFinalAcceptsHealthyContainers/RejectsTorn.
        engine.startNewSession(ctx, "Validate", 1);
        writeCaptureSeconds(0.5);
        File raw = engine.rawFile;
        TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx,
                RecordPreset.MEMO, SaveFormat.WAV16, null);
        FormatConverter.validateFinal(r.file, SaveFormat.WAV16);
        assertFalse(raw.exists());
    }

    @Test
    public void musicAutoSavesTheRawCaptureLosslessly() throws Exception {
        // Music defaults to the 32-bit float capture: the finished file must
        // BE that capture (same bytes), and it must survive the save.
        engine.startNewSession(ctx, "Lossless Master", 2);
        File raw = engine.rawFile;
        writeCaptureSeconds(0.5);
        long rawBytes = raw.length();

        TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx,
                RecordPreset.MUSIC, SaveFormat.AUTO, null);

        assertEquals("WAV32", r.label);
        assertTrue("output must be the raw capture itself",
                r.file.equals(raw));
        assertTrue("raw capture was deleted", raw.exists());
        assertEquals(rawBytes, r.file.length());
        // Still a genuine, readable 32-bit float WAV.
        assertTrue(Dsp.wavDurationMs(r.file) > 0);
        FormatConverter.validateFinal(r.file, SaveFormat.WAV32);
    }

    @Test
    public void memoAutoStillProducesCompressedM4A() throws Exception {
        engine.startNewSession(ctx, "Memo", 1);
        File raw = engine.rawFile;
        writeCaptureSeconds(0.5);
        TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx,
                RecordPreset.MEMO, SaveFormat.AUTO, null);
        assertEquals("M4A", r.label);
        assertTrue(r.file.getName().endsWith(".m4a"));
        assertFalse("raw should be cleaned up after conversion", raw.exists());
        FormatConverter.validateFinal(r.file, SaveFormat.M4A);
    }

    @Test
    public void repeatedSavesLeaveNoOrphans() throws Exception {
        // The reported symptom was "a few saves then dead": repeated cycles
        // must not leak raw files or pending markers. (WAV16 explicitly:
        // under Robolectric the platform encoders are no-ops.)
        for (int i = 0; i < 4; i++) {
            engine.clearSession();
            engine.startNewSession(ctx, "Take " + i, 1);
            writeCaptureSeconds(0.2);
            TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx,
                    RecordPreset.MEMO, SaveFormat.WAV16, null);
            assertTrue(r.file.exists());
            TakesRepository.addRecording(r.file, r.durationMs, r.title,
                    r.label);
        }
        engine.clearSession();

        // No unfinished raw or pending marker survives a completed save.
        File[] files = RecordingEngine.takesDir(ctx).listFiles();
        int raws = 0;
        for (File f : files != null ? files : new File[0]) {
            if (f.getName().endsWith(".pending")) raws++;
        }
        assertEquals("pending markers leaked", 0, raws);

        // And the startup sweep stays happy afterwards.
        assertNotNull(TakesRepository.reconcile(ctx));
    }
}
