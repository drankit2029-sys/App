package com.example.hello;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Kill-resume scaffolding: sets up a slow job and holds the process open
 * while the host kills it mid-flight. The real assertions run host-side
 * (logcat/files); the test itself is designed never to finish normally.
 *
 * <p>A4-15: excluded from the suite with {@code @Ignore}. These tests sleep
 * for 90s + 180s and leave 45s/150s raw takes behind, which stalls every
 * instrumentation run and pollutes takes/ for the tests that follow.
 * To use it: drop the @Ignore, run only this class, kill the app from the
 * host mid-flight, then relaunch and check the sidecars.
 */
@RunWith(AndroidJUnit4.class)
@org.junit.Ignore("Host-driven kill/resume scaffolding; run manually")
public class TempKillTest {

    private File makeLongRaw(Context ctx, String name, int seconds)
            throws Exception {
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, name + "_" + System.nanoTime() + ".wav");
        int frames = 48000 * seconds;
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            byte[] chunk = new byte[65536];
            java.util.Random rnd = new java.util.Random(7);
            long left = (long) frames * 2 * 4;
            while (left > 0) {
                int n = (int) Math.min(chunk.length, left);
                rnd.nextBytes(chunk);
                w.write(chunk, 0, n);
                left -= n;
            }
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void holdQueuedJob() throws Exception {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        instr.startActivitySync(i);
        Context ctx = instr.getTargetContext();
        File raw = makeLongRaw(ctx, "killq", 45);
        ConversionJob job = ConversionJobs.enqueue(ctx, raw, "Kill Queued",
                RecordPreset.MEMO, SaveFormat.M4A, 45000);
        android.util.Log.i("KILLTEST",
                "queued " + job.jobId + " sleeping");
        Thread.sleep(90000);
        android.util.Log.i("KILLTEST", "survived; done="
                + (ConversionJobs.get(job.jobId) == null));
    }

    @Test
    public void holdRunningJob() throws Exception {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        instr.startActivitySync(i);
        Context ctx = instr.getTargetContext();
        File raw = makeLongRaw(ctx, "killr", 150);
        ConversionJob job = ConversionJobs.enqueue(ctx, raw, "Kill Running",
                RecordPreset.MEMO, SaveFormat.AMR_NB, 150000);
        ConversionService.startJob(ctx, job.jobId);
        android.util.Log.i("KILLTEST",
                "running " + job.jobId + " sleeping");
        Thread.sleep(180000);
        android.util.Log.i("KILLTEST", "survived");
    }
}
