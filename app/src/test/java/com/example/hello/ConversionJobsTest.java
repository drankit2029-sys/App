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

    @Test
    public void dispatchSaveReplaceCancelsActiveConversionAndDeletesPreviousFileImmediately()
            throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        File dir = RecordingEngine.takesDir(ctx());

        // Case 1: Original take is still converting when user edits and chooses Replace
        File origRaw = new File(dir, "orig_conv_" + System.nanoTime() + ".raw.wav");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(origRaw, "rw")) {
            w.write(Dsp.wavFloatHeader(2, 48000, 48000L * 2 * 4));
            w.write(new byte[48000 * 2 * 4]);
        }
        ConversionJob origJob = ConversionJobs.enqueue(ctx(), origRaw,
                "Original Take", RecordPreset.MEMO, SaveFormat.M4A, 1000);
        long origSyntheticId = origJob.takeId();
        assertTrue("original job must be active",
                ConversionJobs.get(origJob.jobId) != null);

        // User edits in Studio and chooses a different format (OPUS) + Replace
        engine.startNewSession(ctx(), "Edited Session", 2);
        ConversionJobs.SaveResult r1 = ConversionJobs.dispatchSave(
                ctx(), RecordPreset.MEMO, SaveFormat.OPUS, null,
                origSyntheticId, "Original Take");
        try {
            assertNull("original conversion must be cancelled immediately",
                    ConversionJobs.get(origJob.jobId));
            assertFalse("original raw must be deleted immediately",
                    origRaw.exists());
            assertFalse("original sidecar must be deleted immediately",
                    origJob.sidecar().exists());
            assertNotNull("new conversion job must start immediately", r1.job);
            assertEquals("Original Take", r1.job.title);
            assertEquals("opus", r1.job.formatId);
            assertEquals(-1L, r1.job.replaceTakeId);
        } finally {
            if (r1.job != null) ConversionJobs.cancel(r1.job.jobId, true);
            engine.abortAndDelete();
        }

        // Case 2: Original conversion finished (or was already a finished file)
        // before user clicked Replace -> must delete old file & Take immediately
        // before the new conversion runs!
        File finishedFile = new File(dir, "orig_done_" + System.nanoTime() + ".m4a");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(finishedFile, "rw")) {
            w.write(new byte[2048]);
        }
        Take oldTake = TakesRepository.addRecording(
                finishedFile, 1000, "Finished Take", "M4A");
        String finishedRawPath = new File(dir, "orig_done_raw.wav").getAbsolutePath();
        long finishedSyntheticId = -777777L;
        ConversionJobs.noteTakeRaw(finishedSyntheticId, finishedRawPath);
        ConversionJobs.noteCompletion(finishedRawPath, oldTake.id);

        engine.startNewSession(ctx(), "Edited Session 2", 2);
        ConversionJobs.SaveResult r2 = ConversionJobs.dispatchSave(
                ctx(), RecordPreset.MEMO, SaveFormat.FLAC, null,
                finishedSyntheticId, "Finished Take");
        try {
            assertNull("old Take row must be deleted immediately when Replace is chosen",
                    TakesRepository.find(oldTake.id));
            assertFalse("old file on disk must be deleted immediately when Replace is chosen",
                    finishedFile.exists());
            assertNotNull("new conversion job must be enqueued immediately", r2.job);
            assertEquals("Finished Take", r2.job.title);
            assertEquals("flac", r2.job.formatId);
            assertEquals(-1L, r2.job.replaceTakeId);
        } finally {
            if (r2.job != null) ConversionJobs.cancel(r2.job.jobId, true);
            ConversionJobs.forgetCompletion(finishedRawPath);
            engine.abortAndDelete();
        }
    }

    @Test
    public void persistentFloatMasterLifecycleForRecordingsImportsAndCleanup()
            throws Exception {
        File dir = RecordingEngine.takesDir(ctx());

        // 1) Recorded take saved as M4A: raw 32-bit float file is maintained in .float32/
        File recRaw = new File(dir, "rec_float_" + System.nanoTime() + ".raw.wav");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(recRaw, "rw")) {
            w.write(Dsp.wavFloatHeader(1, 48000, 4800L * 4));
            w.write(new byte[4800 * 4]);
        }
        File recFinal = new File(dir, "rec_float_" + System.nanoTime() + ".m4a");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(recFinal, "rw")) {
            w.write(new byte[2048]);
        }
        Take recTake = TakesRepository.addRecording(
                recFinal, 1000, "Recorded Take", "M4A");
        ConversionJobs.completeOrDeferRawMaster(
                recRaw.getAbsolutePath(), recFinal);
        assertFalse("original scratch raw path must be moved out of takes/",
                recRaw.exists());
        File recMaster = TakesRepository.floatMasterFor(recFinal);
        assertNotNull(recMaster);
        assertTrue("persistent 32-bit float master must exist in .float32/",
                recMaster.exists());
        assertEquals("resolveFloatMaster must return the persistent float32 file",
                recMaster.getAbsolutePath(),
                TakesRepository.resolveFloatMaster(recFinal).getAbsolutePath());
        assertTrue("resolved master must be a native 32-bit float WAV",
                AudioImporter.isNativeFloatWav(
                        TakesRepository.resolveFloatMaster(recFinal)));

        // 2) Recorded take saved as WAV32: already 32-bit float -> no redundant copy in .float32/
        File wav32Raw = new File(dir, "rec_w32_" + System.nanoTime() + ".raw.wav");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(wav32Raw, "rw")) {
            w.write(Dsp.wavFloatHeader(1, 48000, 4800L * 4));
            w.write(new byte[4800 * 4]);
        }
        File wav32Final = new File(dir, "rec_w32_" + System.nanoTime() + ".wav");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(wav32Final, "rw")) {
            w.write(Dsp.wavFloatHeader(1, 48000, 4800L * 4));
            w.write(new byte[4800 * 4]);
        }
        ConversionJobs.completeOrDeferRawMaster(
                wav32Raw.getAbsolutePath(), wav32Final);
        assertFalse("wav32 raw must be deleted to avoid duplicate files",
                wav32Raw.exists());
        assertFalse("wav32 final must not create a redundant .float32 copy",
                TakesRepository.floatMasterFor(wav32Final).exists());
        assertEquals(wav32Final.getAbsolutePath(),
                TakesRepository.resolveFloatMaster(wav32Final).getAbsolutePath());
        wav32Final.delete();

        // 3) Imported recording: no float32 file until first Edit, then persisted on first Edit
        File impFile = new File(dir, "imp_" + System.nanoTime() + ".wav");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(impFile, "rw")) {
            w.write(Dsp.wav16Header(1, 48000, 4800L * 2));
            w.write(new byte[4800 * 2]);
        }
        Take impTake = TakesRepository.addImported(
                impFile, 1000, "Imported Take", "WAV");
        assertEquals("imported", impTake.origin);
        assertFalse("imported file must not have a float32 master before first edit",
                TakesRepository.floatMasterFor(impFile).exists());
        assertFalse("16-bit imported file is not native float32 yet",
                AudioImporter.isNativeFloatWav(
                        TakesRepository.resolveFloatMaster(impFile)));

        // Simulate first Edit press on the imported take
        File firstEditSession = AudioImporter.takeToFloatWav(
                impFile, dir, "Imported_Take", null);
        TakesRepository.saveFloatMasterCopy(firstEditSession, impFile);
        firstEditSession.delete();
        assertTrue("float32 master must be created and persisted on first edit",
                TakesRepository.floatMasterFor(impFile).exists());
        assertTrue("subsequent edits must resolve directly to native float32 master",
                AudioImporter.isNativeFloatWav(
                        TakesRepository.resolveFloatMaster(impFile)));

        // 3b) Renaming a converted library-visible recording renames the disk
        // file while keeping its immutable ID and .float32/rec_<id>.f32.wav link intact!
        long recImmutableId = recTake.id;
        assertEquals("rec_" + recImmutableId + ".f32.wav", recMaster.getName());
        TakesRepository.rename(recImmutableId, "Renamed Chorus Vocal");
        Take afterRename = TakesRepository.find(recImmutableId);
        assertNotNull(afterRename);
        assertEquals("Renamed Chorus Vocal", afterRename.title);
        File renamedDiskFile = new File(afterRename.filePath);
        assertTrue("renamed converted file must exist on disk",
                renamedDiskFile.exists());
        assertTrue("renamed file name must reflect new title",
                renamedDiskFile.getName().startsWith("Renamed_Chorus_Vocal"));
        assertEquals("ID-linked float32 master must still resolve after rename",
                recMaster.getAbsolutePath(),
                TakesRepository.resolveFloatMaster(
                        recImmutableId, renamedDiskFile).getAbsolutePath());

        // 4) Replace & Delete clean up both the main file and the .float32 master
        assertTrue(TakesRepository.deleteImmediateForReplace(recTake.id));
        assertFalse("replaced take file must be deleted", renamedDiskFile.exists());
        assertFalse("replaced take float32 master must be deleted",
                recMaster.exists());

        TakesRepository.deleteImmediateForReplace(impTake.id);
        assertFalse("deleted imported file must be removed", impFile.exists());
        assertFalse("deleted imported float32 master must be removed",
                TakesRepository.floatMasterFor(impTake.id, impFile).exists());
    }

    @Test
    public void firstRecordingAllowsAllEditsAfterSavingAndReplacingWhileSecondBlocks()
            throws Exception {
        for (Take t : new java.util.ArrayList<>(TakesRepository.all())) {
            TakesRepository.deleteImmediateForReplace(t.id);
        }
        for (ConversionJob j : new java.util.ArrayList<>(ConversionJobs.active())) {
            ConversionJobs.cancel(j.jobId, true);
        }
        ctx().getSharedPreferences("takeone", Context.MODE_PRIVATE)
                .edit().clear().commit();

        TrialAndBillingManager mgr = TrialAndBillingManager.get(ctx());
        mgr.setActiveStudioSourceId(-1L);
        assertTrue("Fresh first recording must allow all features",
                mgr.evaluateFreeTierAccessForRecording(-1L));

        File dir = RecordingEngine.takesDir(ctx());
        File f1 = new File(dir, "first_take_" + System.nanoTime() + ".m4a");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(f1, "rw")) {
            w.write(new byte[2048]);
        }
        Take firstTake = TakesRepository.addRecording(f1, 2000L, "First Take", "M4A");
        assertEquals(firstTake.id, mgr.getFreeRecordingId());

        // Editing the first recording after saving it once MUST still be allowed
        mgr.setActiveStudioSourceId(firstTake.id);
        assertTrue("Editing first recording after saving once must be allowed",
                mgr.evaluateFreeTierAccessForRecording(firstTake.id));

        // Replacing the first recording preserves its immutable ID and keeps edits allowed
        File f1Replaced = new File(dir, "first_take_rep_" + System.nanoTime() + ".m4a");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(f1Replaced, "rw")) {
            w.write(new byte[2048]);
        }
        Take replacedFirst = TakesRepository.replaceRecording(
                firstTake.id, f1Replaced, 2500L, "First Take Edited", "M4A");
        assertEquals("Immutable ID must be preserved on Replace",
                firstTake.id, replacedFirst.id);
        assertTrue("Editing first recording after Replace must still be allowed",
                mgr.evaluateFreeTierAccessForRecording(replacedFirst.id));

        // Starting a 2nd recording (-1) or editing a 2nd recording must be blocked
        mgr.setActiveStudioSourceId(-1L);
        assertFalse("Starting a 2nd recording must be gated by paywall",
                mgr.evaluateFreeTierAccessForRecording(-1L));

        File f2 = new File(dir, "second_take_" + System.nanoTime() + ".m4a");
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(f2, "rw")) {
            w.write(new byte[2048]);
        }
        Take secondTake = TakesRepository.addRecording(f2, 2000L, "Second Take", "M4A");
        assertFalse("Editing 2nd recording must be gated by paywall",
                mgr.evaluateFreeTierAccessForRecording(secondTake.id));
        assertTrue("Editing 1st recording must still be allowed even after 2nd recording exists",
                mgr.evaluateFreeTierAccessForRecording(replacedFirst.id));

        TakesRepository.deleteImmediateForReplace(replacedFirst.id);
        TakesRepository.deleteImmediateForReplace(secondTake.id);
    }

    @Test
    public void formatConversionReportsGranularProgressCallbacks() throws Exception {
        File dir = RecordingEngine.takesDir(ctx());
        File raw = new File(dir, "prog_test_" + System.nanoTime() + ".raw.wav");
        int samples = 48000; // 1 second mono
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(raw, "rw")) {
            w.write(Dsp.wavFloatHeader(1, 48000, samples * 4L));
            w.write(new byte[samples * 4]);
        }
        File out = new File(dir, "prog_test_" + System.nanoTime() + ".wav");
        java.util.List<Integer> ticks = new java.util.ArrayList<>();
        try {
            FormatConverter.toWav16(raw, out, ticks::add);
            assertTrue("Expected multiple granular progress updates for 1s audio, got " + ticks.size(),
                    ticks.size() >= 10);
            assertEquals(Integer.valueOf(100), ticks.get(ticks.size() - 1));
        } finally {
            raw.delete();
            out.delete();
        }
    }

    @Test
    public void dispatchSaveWav32AdoptsRawDirectlyWithoutDuplicateWavOrJob() throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        engine.startNewSession(ctx(), "Wav32 Dedup Test", 2);
        File raw = engine.rawFile;
        try (java.io.RandomAccessFile w = new java.io.RandomAccessFile(raw, "rw")) {
            int samples = 4800 * 2;
            w.write(Dsp.wavFloatHeader(2, 48000, samples * 4L));
            w.write(new byte[samples * 4]);
        }
        engine.dataBytes = 4800L * 2 * 4;
        final int[] lastProgress = {-1};
        ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(
                ctx(), RecordPreset.MUSIC, SaveFormat.WAV32,
                pct -> lastProgress[0] = pct);
        try {
            assertTrue("WAV32 save must adopt raw directly without background job",
                    r.adopted);
            assertNull("WAV32 save must not enqueue a background ConversionJob",
                    r.job);
            assertNotNull("Adopted WAV32 file must exist", r.file);
            assertTrue("Adopted WAV32 file must exist on disk", r.file.exists());
            assertEquals("WAV32 save must report 100% completion immediately",
                    100, lastProgress[0]);
            assertFalse("Original .raw.wav must be renamed, not duplicated",
                    raw.exists() && !raw.getAbsolutePath().equals(r.file.getAbsolutePath()));
            assertFalse("No .part duplicate must exist for WAV32",
                    ConversionJob.partFor(raw).exists());
            assertFalse("No .float32 duplicate master must exist for WAV32",
                    TakesRepository.floatMasterFor(r.recordingId, r.file).exists());
        } finally {
            if (r.file != null) r.file.delete();
            engine.abortAndDelete();
        }
    }

    @Test
    public void pressingDisabledButtonShowsBillingPopupAndPressingBuyOnFetchErrorShowsErrorPopup() {
        android.app.Activity activity = org.robolectric.Robolectric
                .buildActivity(android.app.Activity.class).setup().get();
        TrialAndBillingManager mgr = TrialAndBillingManager.get(activity);
        TrialAndBillingManager.setTestOverridePro(false);
        try {
            boolean allowed = TrialAndBillingManager.requireProOrShowPaywall(
                    activity, "Trim", null);
            assertFalse("Locked feature must not run onAllowed", allowed);
            android.app.Dialog paywall = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("Billing popup must show up on pressing disabled button", paywall);
            assertTrue("Billing popup must be showing", paywall.isShowing());
            android.widget.TextView title = paywall.findViewById(R.id.dialogTitle);
            assertNotNull(title);
            assertEquals("Unlock TakeOne Pro", title.getText().toString());
            paywall.dismiss();

            // Pressing Buy when Google Play price fetching failed must show an error popup
            mgr.onBuyPressed(activity, TrialAndBillingManager.SKU_LIFETIME);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            android.app.Dialog errDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("Error popup must be shown when price fetch fails on Buy", errDialog);
            assertTrue(errDialog.isShowing());
            android.widget.TextView errTitle = errDialog.findViewById(R.id.resultTitle);
            assertNotNull(errTitle);
            assertEquals("Error Fetching Price", errTitle.getText().toString());
            errDialog.dismiss();
        } finally {
            TrialAndBillingManager.setTestOverridePro(null);
        }
    }
}
