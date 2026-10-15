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
 * Proves the background conversion path end to end: enqueue a job for a
 * synthetic raw take, run it through {@link ConversionService}, and assert
 * the finished take lands in the library while the raw is deleted.
 */
@RunWith(AndroidJUnit4.class)
public class ConversionWorkerTest {

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

    @Test
    public void backgroundJobConvertsAndCleansUp() throws Exception {
        // Foreground activity so the conversion service may start.
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        instr.startActivitySync(i);
        Context ctx = instr.getTargetContext();
        File raw = makeRaw(ctx, "Worker");
        ConversionJob job = null;
        Take finished = null;
        try {
            job = ConversionJobs.enqueue(ctx, raw, "Worker Take",
                    RecordPreset.MEMO, SaveFormat.M4A, 2000);
            assertNotNull("no job", job);
            assertTrue("sidecar missing", job.sidecar().exists());
            ConversionService.startJob(ctx, job.jobId);

            long t0 = System.currentTimeMillis();
            while (ConversionJobs.get(job.jobId) != null
                    && System.currentTimeMillis() - t0 < 60_000) {
                Thread.sleep(500);
            }
            assertNull("job never finished",
                    ConversionJobs.get(job.jobId));

            List<Take> takes = TakesRepository.all();
            for (Take t : takes) {
                if ("Worker Take".equals(t.title)) finished = t;
            }
            assertNotNull("finished take not in library", finished);
            assertTrue("finished file missing",
                    finished.hasFile());
            assertTrue("raw was not deleted", !raw.exists());
            assertTrue("sidecar was not deleted", !job.sidecar().exists());
        } finally {
            if (finished != null) {
                TakesRepository.delete(finished.id);
                File f = new File(finished.filePath);
                if (f.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            if (job != null) ConversionJobs.cancel(job.jobId, true);
            if (raw.exists()) {
                // noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
        }
    }
}
