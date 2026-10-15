package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * The user-reported contention cases around the background converter.
 *
 * <p>Three regressions are covered: switching save format mid-recording must
 * not park the studio on a multi-second inline drain (the restarted encoder
 * has re-encoded one chunk but is still seconds behind), a save that gives up
 * on the fast path must leave no encoder thread writing the part file the
 * worker is about to claim, and a sweep must never delete a live capture's
 * partial output or re-arm a job that is already tracked.
 */
@RunWith(AndroidJUnit4.class)
public class ConversionConcurrencyTest {

    @Rule
    public androidx.test.rule.GrantPermissionRule mic =
            androidx.test.rule.GrantPermissionRule.grant(
                    android.Manifest.permission.RECORD_AUDIO);

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private boolean streamThreadAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("takeone-stream-enc".equals(t.getName()) && t.isAlive()) return true;
        }
        return false;
    }

    private RecordingEngine recordedSession(String name, SaveFormat fmt,
                                           long millis) throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx(), name, 2);
        engine.beginStreaming(ctx(), RecordPreset.MEMO, fmt);
        engine.startCapture(ctx(),
                android.media.MediaRecorder.AudioSource.MIC);
        Thread.sleep(millis);
        engine.pauseCapture();
        return engine;
    }

    /**
     * Changing the save format while recording restarts the incremental
     * encoder from the top of the raw file. It reports progress after its
     * first chunk, so a naive "has it started?" check sent the save into a
     * full-tail inline drain and froze the studio for seconds.
     */
    @Test
    public void formatSwitchSaveSkipsStallAndGoesBackground() throws Exception {
        RecordingEngine engine = recordedSession("Format switch", SaveFormat.M4A,
                3000);
        // The user changed the format on the last moment: same take, new
        // encoder from the start, whole recording unencoded again.
        assertTrue("expected a restarted encoder",
                engine.restartStreaming(ctx(), RecordPreset.MEMO, SaveFormat.OPUS));
        StreamingEncoder sess = engine.streaming();
        assertNotNull("restarted encoder must exist", sess);
        // Give the fresh pump just enough time to encode its first chunk: it
        // looks productive while being almost entirely behind.
        long untilEncoded = System.currentTimeMillis() + 4000;
        while (sess.framesEncoded() == 0
                && System.currentTimeMillis() < untilEncoded) {
            Thread.sleep(50);
        }
        assertTrue("encoder never reported progress", sess.framesEncoded() > 0);

        long t0 = System.currentTimeMillis();
        ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx(),
                RecordPreset.MEMO, SaveFormat.OPUS, null);
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse("a take with a multi-second backlog cannot adopt inline",
                r.adopted);
        assertNotNull("save must hand off to the worker", r.job);
        assertTrue("save blocked the studio for " + elapsed + "ms",
                elapsed < 2000);
        ConversionJobs.cancel(r.job.jobId, false);
    }

    /**
     * Giving up on the fast path used to unlink the part file while the
     * encoder thread was still writing it, then hand the same path to the
     * worker: two writers, one container, corrupt output.
     */
    @Test
    public void abandonedAdoptLeavesNoEncoderOnThePartFile() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        // A long take on disk, so the backlog is huge by construction and the
        // test does not depend on how fast the emulator happens to encode.
        File raw = new File(RecordingEngine.takesDir(ctx()),
                "abandon.wav");
        long frames = 48000L * 60L;
        writeRaw(raw, (int) frames);
        engine.attachExisting(ctx(), raw, "Abandoned adopt", 2,
                frames * 4L * 2L);
        engine.beginStreaming(ctx(), RecordPreset.MEMO, SaveFormat.M4A);
        StreamingEncoder sess = engine.streaming();
        assertNotNull("streaming encoder did not start", sess);
        File part = sess.partFile();

        // Let the fresh pump encode its first chunk: from here it looks
        // productive while being a minute behind, exactly the state that used
        // to send the save into a full inline drain.
        long untilEncoded = System.currentTimeMillis() + 8000;
        while (sess.framesEncoded() == 0
                && System.currentTimeMillis() < untilEncoded) {
            Thread.sleep(50);
        }
        assertTrue("encoder never reported progress", sess.framesEncoded() > 0);
        assertTrue("expected a large unencoded backlog",
                sess.unencodedFrames() > 48000L);

        ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(ctx(),
                RecordPreset.MEMO, SaveFormat.M4A, null);
        assertFalse("a minute-long backlog cannot adopt inline", r.adopted);
        assertNotNull("save must hand off to the worker", r.job);
        // The app starts the worker at its save call site; do the same here.
        ConversionService.startJob(ctx(), r.job.jobId);

        // The encoder must be gone: a survivor would still be writing the very
        // part file the worker now owns.
        long untilGone = System.currentTimeMillis() + 3000;
        while (streamThreadAlive() && System.currentTimeMillis() < untilGone) {
            Thread.sleep(50);
        }
        assertFalse("encoder thread outlived the abandoned adoption",
                streamThreadAlive());
        assertTrue("abandoned part file must not survive",
                !part.exists() || part.length() == 0);

        // And the worker must still produce a valid take from that raw: the
        // output it writes onto the same part path must be intact.
        ConversionJob job = ConversionJobs.get(r.job.jobId);
        long untilDone = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < untilDone) {
            job = ConversionJobs.get(r.job.jobId);
            if (job == null) break;
            if (ConversionJob.DONE.equals(job.state)
                    || ConversionJob.FAILED.equals(job.state)) break;
            Thread.sleep(250);
        }
        if (job != null) {
            assertEquals("conversion must succeed, not corrupt: " + job.error,
                    ConversionJob.DONE, job.state);
        }
        Take made = null;
        for (Take t : TakesRepository.all()) {
            if ("Abandoned adopt".equalsIgnoreCase(t.title)) { made = t; break; }
        }
        assertNotNull("no take was produced from the abandoned encoder", made);
        assertTrue("produced take has no file", made.filePath != null);
        File out = new File(made.filePath);
        assertTrue("produced file is missing", out.exists());
        // A corrupted container is exactly what two writers on one path makes.
        FormatConverter.validateFinal(out, SaveFormat.M4A);
        TakesRepository.delete(made.id);
    }

    /**
     * Sweep ran on every service start command while assuming it only ever ran
     * at cold start, so it deleted the partial file a live capture was still
     * writing and re-armed jobs already tracked in memory.
     */
    @Test
    public void sweepKeepsLiveCaptureAndTrackedJob() throws Exception {
        File dir = RecordingEngine.takesDir(ctx());
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();

        // A partial output with no sidecar: exactly what a live capture owns.
        File live = new File(dir, "sweep-live.wav");
        File livePart = new File(dir, "sweep-live.wav.part");
        writeRaw(live, 48000);
        writeEmpty(livePart);
        // The live capture also drops a .pending marker beside its raw.
        File pending = new File(dir, "sweep-live.wav.pending");
        pending.createNewFile();

        // A tracked job: in memory, so sweep must hand back the same instance
        // instead of a re-read copy reset to QUEUED.
        File raw2 = new File(dir, "sweep-job.wav");
        writeRaw(raw2, 24000);
        ConversionJob job = ConversionJobs.enqueue(ctx(), raw2, "Sweep job",
                RecordPreset.MEMO, SaveFormat.M4A, 500);
        ConversionJob inMemBefore = ConversionJobs.get(job.jobId);
        assertNotNull(inMemBefore);
        String stateBefore = inMemBefore.state;

        try {
            List<ConversionJob> resume = ConversionJobs.sweep(ctx());
            assertTrue("live capture's part file was swept",
                    livePart.exists());
            assertTrue("job was lost by the sweep",
                    ConversionJobs.get(job.jobId) != null);
            assertSame("sweep replaced a tracked job with a re-read copy",
                    inMemBefore, ConversionJobs.get(job.jobId));
            assertEquals("a tracked job was re-armed by the sweep",
                    stateBefore, ConversionJobs.get(job.jobId).state);
            boolean found = false;
            for (ConversionJob j : resume) {
                if (j.jobId.equals(job.jobId)) found = true;
            }
            assertTrue("tracked job missing from the resume list", found);
        } finally {
            ConversionJobs.cancel(job.jobId, false);
            // noinspection ResultOfMethodCallIgnored
            pending.delete();
            // noinspection ResultOfMethodCallIgnored
            livePart.delete();
            // noinspection ResultOfMethodCallIgnored
            raw2.delete();
        }
    }

    /**
     * Starting the service again for a job that is already running used to
     * re-queue it: its id had been polled off the queue, so the "already
     * queued" check missed it, and the second run reported a false failure
     * because the raw was already gone.
     */
    @Test
    public void repeatedStartServiceRunsTheJobOnce() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        File dir = RecordingEngine.takesDir(ctx());
        File raw = new File(dir, "dedupe.wav");
        writeRaw(raw, 96000);
        int before = TakesRepository.all().size();
        ConversionJob job = ConversionJobs.enqueue(ctx(), raw, "Dedupe",
                RecordPreset.MEMO, SaveFormat.M4A, 2000);
        try {
            // Same job delivered twice, as happens when a second save starts
            // the service while the first job is still converting.
            ConversionService.startJob(ctx(), job.jobId);
            ConversionService.startJob(ctx(), job.jobId);

            long until = System.currentTimeMillis() + 120000;
            ConversionJob cur;
            while (System.currentTimeMillis() < until) {
                cur = ConversionJobs.get(job.jobId);
                // A finished job is retired from ACTIVE, so "gone" means done.
                if (cur == null
                        || ConversionJob.DONE.equals(cur.state)
                        || ConversionJob.FAILED.equals(cur.state)) break;
                Thread.sleep(250);
            }
            cur = ConversionJobs.get(job.jobId);
            if (cur != null) {
                assertEquals("duplicate execution reported a false failure: "
                        + cur.error, ConversionJob.DONE, cur.state);
            }
            assertEquals("job produced more than one take",
                    before + 1, TakesRepository.all().size());
            Take made = TakesRepository.all().get(0);
            assertNotNull(made.filePath);
            FormatConverter.validateFinal(new File(made.filePath),
                    SaveFormat.M4A);
            assertFalse("a part file was left behind",
                    ConversionJob.partFor(raw).exists());
        } finally {
            ConversionJobs.cancel(job.jobId, false);
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /**
     * Renaming a take while it converts must be the name the finished take
     * ends up with. The conversion captures its title when it starts, so a
     * rename made mid-flight used to be dropped and the take snapped back to
     * the original name the moment the card turned into a real take.
     */
    @Test
    public void renameDuringConversionReachesTheFinishedTake() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        File raw = new File(RecordingEngine.takesDir(ctx()), "renamed.wav");
        long frames = 48000L * 90L;
        writeRaw(raw, (int) frames);
        int before = TakesRepository.all().size();

        ConversionJob job = ConversionJobs.enqueue(ctx(), raw,
                "Original name", RecordPreset.MEMO, SaveFormat.M4A, 90000);
        ConversionService.startJob(ctx(), job.jobId);
        try {
            // Wait until the worker is genuinely mid-conversion.
            ConversionJob cur = ConversionJobs.get(job.jobId);
            long until = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < until) {
                cur = ConversionJobs.get(job.jobId);
                if (cur == null) break;
                if (ConversionJob.RUNNING.equals(cur.state)) break;
                Thread.sleep(50);
            }
            assertNotNull("job never started", cur);
            assertEquals("conversion finished before it could be renamed; "
                    + "use a longer take", ConversionJob.RUNNING, cur.state);

            // This is what the library does when a converting card is renamed.
            cur.title = "Renamed mid flight";
            cur.write();

            until = System.currentTimeMillis() + 180000;
            while (System.currentTimeMillis() < until) {
                cur = ConversionJobs.get(job.jobId);
                if (cur == null) break;
                if (ConversionJob.DONE.equals(cur.state)
                        || ConversionJob.FAILED.equals(cur.state)) break;
                Thread.sleep(200);
            }
            if (cur != null) {
                assertEquals("conversion failed: " + cur.error,
                        ConversionJob.DONE, cur.state);
            }
            assertEquals("job produced more than one take",
                    before + 1, TakesRepository.all().size());
            Take made = TakesRepository.all().get(0);
            assertEquals("rename during conversion was dropped",
                    "Renamed mid flight", made.title);
            TakesRepository.delete(made.id);
        } finally {
            ConversionJobs.cancel(job.jobId, false);
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    private void writeRaw(File f, int frames) throws Exception {
        byte[] payload = new byte[frames * 4 * 2];
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, RecordingEngine.SAMPLE_RATE,
                    frames * 4L * 2));
            w.write(payload);
        }
    }

    private void writeEmpty(File f) throws Exception {
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
        }
    }
}
