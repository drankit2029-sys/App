package com.example.hello;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Regression coverage for the reported "background converter completely
 * stops" behaviour. The old worker parked forever while a take was open for
 * editing (or while any reader held its raw); editing the same take and
 * previewing its raw must now both leave the conversion running to
 * completion in the background.
 */
@RunWith(AndroidJUnit4.class)
public class BackgroundConversionResilienceTest {

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private File makeRaw(Context ctx, String name) throws Exception {
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, name + "_" + System.nanoTime() + ".wav");
        int frames = 48000 * 2;
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

    private void launchActivity() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        Intent i = new Intent(instr.getTargetContext(), MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        instr.startActivitySync(i);
    }

    @Test
    public void conversionFinishesWhileTheSameTakeIsOpenForEditing()
            throws Exception {
        launchActivity();
        Context ctx = ctx();
        RecordingEngine engine = RecordingEngine.get();
        engine.abortAndDelete();
        File raw = makeRaw(ctx, "Background edit");
        ConversionJob job = null;
        Take finished = null;
        try {
            job = ConversionJobs.enqueue(ctx, raw, "Background edit",
                    RecordPreset.MEMO, SaveFormat.M4A, 2000);
            assertNotNull("job was not enqueued", job);
            // Exactly the old park condition: an edit session for the same
            // synthetic take id, plus a live raw reader (preview/import).
            EditLink.save(ctx, job.takeId(), "Background edit",
                    raw.getAbsolutePath());
            engine.attachExisting(ctx, raw, "Background edit", 2,
                    RecordingEngine.payloadBytesOf(raw));
            ConversionJobs.beginRawRead(raw.getAbsolutePath());
            assertTrue("session should be open", engine.hasSession());
            assertTrue("raw should be marked as read",
                    ConversionJobs.rawIsBeingRead(raw.getAbsolutePath()));

            ConversionService.startJob(ctx, job.jobId);

            long deadline = System.currentTimeMillis() + 60_000L;
            while (ConversionJobs.get(job.jobId) != null
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertNull("conversion stalled while the take was being edited",
                    ConversionJobs.get(job.jobId));

            List<Take> takes = TakesRepository.all();
            for (Take t : takes) {
                if ("Background edit".equals(t.title)) finished = t;
            }
            assertNotNull("finished take not in library", finished);
            assertTrue("finished file missing", finished.hasFile());
            assertTrue("reader must defer raw deletion until it closes",
                    raw.exists());
        } finally {
            ConversionJobs.endRawRead(raw.getAbsolutePath());
            engine.clearSession();
            EditLink.clear(ctx);
            if (job != null) ConversionJobs.cancel(job.jobId, true);
            if (finished != null) TakesRepository.delete(finished.id);
            if (finished != null) {
                // noinspection ResultOfMethodCallIgnored
                new File(finished.filePath).delete();
            }
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
            // noinspection ResultOfMethodCallIgnored
            new File(raw.getAbsolutePath() + ".pending").delete();
        }
    }
}
