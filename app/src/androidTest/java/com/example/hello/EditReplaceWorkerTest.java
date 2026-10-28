package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * BUG-02 on device: an edit punch-in saved through the background worker
 * honours the replace choice — the original take is replaced under its own
 * title instead of duplicating the library.
 */
@RunWith(AndroidJUnit4.class)
public class EditReplaceWorkerTest {

    private File makeRaw(Context ctx, String name) throws Exception {
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, name + "_" + System.nanoTime() + ".wav");
        int frames = 48000 * 2; // 2s stereo
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            float[] buf = new float[frames * 2];
            for (int i = 0; i < frames * 2; i++) {
                buf[i] = (float) Math.sin(i * 0.05) * 0.4f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    private void runToDone(ConversionJob job) throws Exception {
        ConversionService.startJob(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                job.jobId);
        long t0 = System.currentTimeMillis();
        while (ConversionJobs.get(job.jobId) != null
                && System.currentTimeMillis() - t0 < 60_000) {
            Thread.sleep(500);
        }
        assertNull("job never finished", ConversionJobs.get(job.jobId));
    }

    private Take findByTitle(String title) {
        for (Take t : TakesRepository.all()) {
            if (title.equals(t.title)) return t;
        }
        return null;
    }

    @Test
    public void backgroundSaveWithReplaceLineageReplacesOriginal()
            throws Exception {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        instr.startActivitySync(i);
        Context ctx = instr.getTargetContext();

        // 1) A finished "original" take in the library.
        File raw1 = makeRaw(ctx, "EditOrig");
        ConversionJob j1 = ConversionJobs.enqueue(ctx, raw1, "Edit Original",
                RecordPreset.MEMO, SaveFormat.M4A, 2000);
        runToDone(j1);
        Take original = findByTitle("Edit Original");
        assertNotNull("original take missing", original);
        String originalPath = original.filePath;

        // 2) An edit of it, saved to the background worker with replace.
        File raw2 = makeRaw(ctx, "EditNew");
        ConversionJob j2 = ConversionJobs.enqueue(ctx, raw2, "Edit Session",
                RecordPreset.MEMO, SaveFormat.M4A, 2000,
                original.id, original.title);
        Take replaced = null;
        try {
            runToDone(j2);
            replaced = findByTitle("Edit Original");
            assertNotNull("replacement take missing", replaced);
            assertTrue("replacement must preserve immutable recording ID",
                    replaced.id == original.id);
            assertTrue("replacement has no file", replaced.hasFile());
        } finally {
            if (replaced != null) {
                TakesRepository.delete(replaced.id);
                File f = new File(replaced.filePath);
                if (f.exists()) f.delete();
            } else {
                Take leftover = findByTitle("Edit Session");
                if (leftover != null) TakesRepository.delete(leftover.id);
            }
            Take orig = findByTitle("Edit Original");
            if (orig != null && (replaced == null
                    || orig.id != replaced.id)) {
                TakesRepository.delete(orig.id);
            }
            ConversionJobs.cancel(j2.jobId, true);
            if (raw2.exists()) raw2.delete();
        }
    }
}
