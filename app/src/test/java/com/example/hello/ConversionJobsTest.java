package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/**
 * Job registry rules: one job per raw, stable card ids, cancel cleanup, and
 * the startup sweep resurrecting interrupted jobs while dropping garbage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConversionJobsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void takeIdsAreNegativeAndStable() {
        ConversionJob a = new ConversionJob("job-1", "T", "/r.wav",
                "/r.wav.part", "memo", "auto", 2, 96000, 1000, 100);
        ConversionJob b = new ConversionJob("job-1", "T", "/r.wav",
                "/r.wav.part", "memo", "auto", 2, 96000, 1000, 100);
        assertTrue("take id must be negative", a.takeId() < 0);
        assertEquals("take id must be stable", a.takeId(), b.takeId());
        ConversionJob c = new ConversionJob("job-2", "T", "/r.wav",
                "/r.wav.part", "memo", "auto", 2, 96000, 1000, 100);
        assertTrue("different jobs must differ", c.takeId() != a.takeId());
    }

    @Test
    public void sidecarRoundTrip() {
        File dir = RecordingEngine.takesDir(ctx());
        File raw = new File(dir, "jobtest.wav");
        ConversionJob job = new ConversionJob("job-9", "Title",
                raw.getAbsolutePath(), raw.getAbsolutePath() + ".part",
                "memo", "m4a", 1, 96000, 5000, 240000);
        job.progress = 42;
        job.framesDone = 100000;
        job.write();
        try {
            ConversionJob back = ConversionJob.read(job.sidecar());
            assertNotNull("sidecar unreadable", back);
            assertEquals("job-9", back.jobId);
            assertEquals("Title", back.title);
            assertEquals(42, back.progress);
            assertEquals(100000, back.framesDone);
            assertEquals(1, back.channels);
        } finally {
            job.deleteSidecar();
        }
    }

    @Test
    public void enqueueDedupesAndCancelCleans() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx(), "Job Test", 2);
        try {
            ConversionJob first = ConversionJobs.enqueue(ctx(), engine.rawFile,
                    "Job Test", RecordPreset.MEMO, SaveFormat.M4A, 1000);
            ConversionJob second = ConversionJobs.enqueue(ctx(), engine.rawFile,
                    "Job Test", RecordPreset.MEMO, SaveFormat.M4A, 1000);
            assertTrue("same raw must return the same job",
                    first.jobId.equals(second.jobId));
            assertTrue("sidecar missing", first.sidecar().exists());
            assertTrue("not active",
                    ConversionJobs.forRaw(engine.rawFile.getAbsolutePath())
                            != null);
            assertTrue(ConversionJobs.cancel(first.jobId, false));
            assertNull("still active after cancel",
                    ConversionJobs.get(first.jobId));
            assertTrue("sidecar survived cancel", !first.sidecar().exists());
        } finally {
            engine.abortAndDelete();
        }
    }

    @Test
    public void sweepResurrectsAndCleans() throws Exception {
        File dir = RecordingEngine.takesDir(ctx());
        File raw = new File(dir, "sweep.wav");
        new java.io.RandomAccessFile(raw, "rw").close();
        ConversionJob job = new ConversionJob("job-sweep", "Sweep",
                raw.getAbsolutePath(), raw.getAbsolutePath() + ".part",
                "memo", "auto", 2, 96000, 1000, 100);
        job.state = ConversionJob.RUNNING;
        job.write();
        File orphanPart = new File(dir, "orphan.wav.part");
        new java.io.RandomAccessFile(orphanPart, "rw").close();
        try {
            List<ConversionJob> resume = ConversionJobs.sweep(ctx());
            boolean found = false;
            for (ConversionJob j : resume) {
                if (j.jobId.equals("job-sweep")) found = true;
            }
            assertTrue("interrupted job was not resurrected", found);
            assertTrue("orphan part survived", !orphanPart.exists());
            ConversionJobs.cancel("job-sweep", true);
            assertTrue("raw survived cancel", !raw.exists());
        } finally {
            try {
                // noinspection ResultOfMethodCallIgnored
                raw.delete();
            } catch (Exception ignored) {}
            try {
                // noinspection ResultOfMethodCallIgnored
                orphanPart.delete();
            } catch (Exception ignored) {}
            job.deleteSidecar();
            ConversionJobs.cancel("job-sweep", true);
        }
    }

    @Test
    public void replaceLineageSurvivesSidecar() {
        File dir = RecordingEngine.takesDir(ctx());
        File raw = new File(dir, "jobreplace.wav");
        ConversionJob job = new ConversionJob("job-rep", "Edit",
                raw.getAbsolutePath(), raw.getAbsolutePath() + ".part",
                "memo", "m4a", 1, 96000, 5000, 240000);
        job.replaceTakeId = 12345L;
        job.replaceTitle = "Original";
        job.write();
        try {
            ConversionJob back = ConversionJob.read(job.sidecar());
            assertNotNull("sidecar unreadable", back);
            assertEquals(12345L, back.replaceTakeId);
            assertEquals("Original", back.replaceTitle);
        } finally {
            job.deleteSidecar();
        }
        // Old sidecars without the keys default to "no lineage".
        ConversionJob plain = new ConversionJob("job-plain", "T",
                raw.getAbsolutePath(), raw.getAbsolutePath() + ".part",
                "memo", "auto", 2, 96000, 1000, 100);
        assertEquals(-1L, plain.replaceTakeId);
        assertNull(plain.replaceTitle);
    }

    @Test
    public void sweepResurrectsEveryJob() throws Exception {
        File dir = RecordingEngine.takesDir(ctx());
        File raw1 = new File(dir, "sweep1.wav");
        File raw2 = new File(dir, "sweep2.wav");
        new java.io.RandomAccessFile(raw1, "rw").close();
        new java.io.RandomAccessFile(raw2, "rw").close();
        ConversionJob j1 = new ConversionJob("job-sweep-1", "One",
                raw1.getAbsolutePath(), raw1.getAbsolutePath() + ".part",
                "memo", "auto", 2, 96000, 1000, 100);
        ConversionJob j2 = new ConversionJob("job-sweep-2", "Two",
                raw2.getAbsolutePath(), raw2.getAbsolutePath() + ".part",
                "memo", "auto", 2, 96000, 1000, 100);
        j1.state = ConversionJob.RUNNING;
        j2.state = ConversionJob.QUEUED;
        j1.write();
        j2.write();
        try {
            List<ConversionJob> resume = ConversionJobs.sweep(ctx());
            boolean f1 = false, f2 = false;
            for (ConversionJob j : resume) {
                if (j.jobId.equals("job-sweep-1")) f1 = true;
                if (j.jobId.equals("job-sweep-2")) f2 = true;
            }
            assertTrue("first job lost", f1);
            assertTrue("second job lost (only first resumed)", f2);
        } finally {
            try {
                raw1.delete();
            } catch (Exception ignored) {}
            try {
                raw2.delete();
            } catch (Exception ignored) {}
            j1.deleteSidecar();
            j2.deleteSidecar();
            ConversionJobs.cancel("job-sweep-1", true);
            ConversionJobs.cancel("job-sweep-2", true);
        }
    }

    @Test
    public void cancelUnknownReturnsFalse() {
        assertFalse(ConversionJobs.cancel("no-such-job", false));
        assertFalse(ConversionJobs.cancelByTakeId(-123456789L, false));
    }

    @Test
    public void cancelForReplacePositiveIdPassesThrough() {
        assertEquals(42L, ConversionJobs.cancelForReplace(ctx(), 42L));
    }

    @Test
    public void cancelForReplaceUnknownNegativeReturnsMinusOne() {
        assertEquals(-1L,
                ConversionJobs.cancelForReplace(ctx(), -987654321L));
    }

    @Test
    public void renameActiveJobUpdatesReplaceTitle() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        String session = "Rename Replace " + System.nanoTime();
        engine.startNewSession(ctx(), session, 2);
        ConversionJob job = null;
        try {
            job = ConversionJobs.enqueue(ctx(), engine.rawFile, session,
                    RecordPreset.MEMO, SaveFormat.M4A, 1000,
                    4242L, "Original");
            String applied = ConversionJobs.renameConvertingOrCompleted(
                    job.jobId, job.takeId(), job.rawPath, "Renamed");
            assertEquals("Renamed", applied);
            assertEquals("Renamed", job.title);
            assertEquals("Renamed", job.replaceTitle);
            ConversionJob back = ConversionJob.read(job.sidecar());
            assertNotNull("sidecar unreadable", back);
            assertEquals("Renamed", back.title);
            assertEquals("Renamed", back.replaceTitle);
        } finally {
            if (job != null) ConversionJobs.cancel(job.jobId, true);
            engine.abortAndDelete();
        }
    }

    @Test
    public void completionMapRoundTrip() {
        ConversionJobs.noteCompletion("/tmp/a.wav", 4242L);
        assertEquals(Long.valueOf(4242L),
                ConversionJobs.completedTakeIdForRaw("/tmp/a.wav"));
        ConversionJobs.forgetCompletion("/tmp/a.wav");
        assertTrue(ConversionJobs.completedTakeIdForRaw("/tmp/a.wav") == null);
    }

    @Test
    public void dispatchSaveWithoutStreamingEnqueuesJob() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx(), "Dispatch", 2);
        ConversionJobs.SaveResult r = null;
        try {
            // No streaming session: must take the background path.
            assertTrue(engine.streaming() == null);
            r = ConversionJobs.dispatchSave(ctx(), RecordPreset.MEMO,
                    SaveFormat.M4A, null);
            assertTrue(!r.adopted);
            assertTrue(r.job != null);
            assertTrue(r.job.sidecar().exists());
            assertTrue(ConversionJobs.get(r.job.jobId) != null);
        } finally {
            if (r != null && r.job != null) {
                ConversionJobs.cancel(r.job.jobId, true);
            }
            engine.abortAndDelete();
        }
        assertTrue(ConversionJobs.get(
                r != null && r.job != null ? r.job.jobId : "?") == null);
    }

    @Test
    public void renameActiveJobUpdatesSidecarAndOverride() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        String session = "Rename Active " + System.nanoTime();
        engine.startNewSession(ctx(), session, 2);
        ConversionJob job = null;
        try {
            job = ConversionJobs.enqueue(ctx(), engine.rawFile, session,
                    RecordPreset.MEMO, SaveFormat.M4A, 1000);
            String applied = ConversionJobs.renameConvertingOrCompleted(
                    job.jobId, job.takeId(), job.rawPath, "Renamed Active");
            assertEquals("Renamed Active", applied);
            assertEquals("Renamed Active", job.title);
            ConversionJob back = ConversionJob.read(job.sidecar());
            assertNotNull("sidecar unreadable", back);
            assertEquals("Renamed Active", back.title);
            assertEquals("Renamed Active",
                    ConversionJobs.latestTitle(job, "fallback"));
        } finally {
            if (job != null) ConversionJobs.cancel(job.jobId, true);
            engine.abortAndDelete();
        }
    }

    @Test
    public void renameAfterCompletionRenamesFinishedTake() {
        File dir = RecordingEngine.takesDir(ctx());
        File raw = new File(dir, "rename-done-" + System.nanoTime() + ".m4a");
        Take take = TakesRepository.addRecording(raw, 1000, "Before", "M4A");
        try {
            ConversionJobs.noteCompletion(raw.getAbsolutePath(), take.id);
            String applied = ConversionJobs.renameConvertingOrCompleted(
                    null, -1, raw.getAbsolutePath(), "After");
            assertEquals("After", applied);
            assertNotNull(TakesRepository.find(take.id));
            assertEquals("After", TakesRepository.find(take.id).title);
        } finally {
            TakesRepository.delete(take.id);
            ConversionJobs.forgetCompletion(raw.getAbsolutePath());
        }
    }

    @Test
    public void completionConsumesPendingRenameOverride() {
        File dir = RecordingEngine.takesDir(ctx());
        String rawPath = new File(dir,
                "rename-consume-" + System.nanoTime() + ".wav")
                .getAbsolutePath();
        ConversionJob job = new ConversionJob("job-consume", "Before", rawPath,
                rawPath + ".part", "memo", "m4a", 1, 96000, 5000, 240000);
        try {
            assertEquals("After", ConversionJobs.renameConvertingOrCompleted(
                    null, -1, rawPath, "After"));
            assertEquals("After",
                    ConversionJobs.latestTitle(job, "fallback"));
            assertEquals("After",
                    ConversionJobs.consumeRenamedTitle(rawPath));
            assertNull(ConversionJobs.consumeRenamedTitle(rawPath));
        } finally {
            ConversionJobs.consumeRenamedTitle(rawPath);
            ConversionJobs.forgetCompletion(rawPath);
        }
    }
}
