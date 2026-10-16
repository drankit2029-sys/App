package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

/**
 * The flow the user reported: record, stop, save, land in the library with a
 * conversion card, then let it finish while the library is on screen.
 *
 * <p>Covers three regressions: the studio must not sit blocked while an
 * unconverted tail is encoded (the save hands off to the worker instead), the
 * library must survive the converting card turning into a finished take, and
 * the finished take must carry the title the user set while it converted.
 */
@RunWith(AndroidJUnit4.class)
public class SaveWhileConvertingTest {

    @Rule
    public androidx.test.rule.GrantPermissionRule mic =
            androidx.test.rule.GrantPermissionRule.grant(
                    android.Manifest.permission.RECORD_AUDIO);

    /**
     * The studio capture path gates on POST_NOTIFICATIONS (SDK 33+): without
     * it the fab opens a rationale instead of recording. Granted once per
     * class so these tests verify recording instead of skipping.
     */
    @org.junit.BeforeClass
    public static void grantNotifForUiRecording() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().getUiAutomation()
                        .executeShellCommand("pm grant "
                                + androidx.test.platform.app
                                        .InstrumentationRegistry
                                        .getInstrumentation()
                                        .getTargetContext().getPackageName()
                                + " android.permission.POST_NOTIFICATIONS");
            } catch (Exception ignored) {}
        }
    }

    private void pump(long ms) {
        // One clock only: elapsedRealtime() and uptimeMillis() diverge by
        // the device's accumulated sleep time, which would stretch this
        // wait by that whole offset.
        long end = android.os.SystemClock.elapsedRealtime() + ms;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}
        }
    }

    @Test
    public void saveHandsOffToWorkerAndLibrarySurvivesCompletion()
            throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        android.app.Activity a = instr.startActivitySync(i);

        RecordingEngine.get().abortAndDelete();
        RecordingEngine.get().startNewSession(ctx, "Conv Flow", 2);
        // Write real audio so the pump has actual frames to encode.
        File raw = RecordingEngine.get().rawFile;
        int frames = 48000 * 12; // 12 s: long enough that the pump lags
        java.io.RandomAccessFile w = new java.io.RandomAccessFile(raw, "rw");
        try {
            w.seek(w.length());
            float[] buf = new float[65536 * 2];
            long written = 0;
            while (written < (long) frames * 2) {
                int n = (int) Math.min(buf.length, (long) frames * 2 - written);
                for (int k = 0; k < n; k++) {
                    buf[k] = (float) Math.sin((written + k) * 0.05) * 0.4f;
                }
                Dsp.writeFloatsLE(w, buf, 0, n);
                written += n;
            }
        } finally {
            w.close();
        }

        View fab = a.findViewById(R.id.fabWrap);
        assertNotNull("no record button", fab);
        a.runOnUiThread(fab::performClick);
        pump(1500);
        a.runOnUiThread(() -> {
            View stop = a.findViewById(R.id.fabStopWrap);
            if (stop != null) stop.performClick();
        });
        pump(800);
        // Choose Save in the stop sheet.
        androidx.test.uiautomator.UiDevice d = androidx.test.uiautomator
                .UiDevice.getInstance(instr);
        androidx.test.uiautomator.UiObject save = d.findObject(
                new androidx.test.uiautomator.UiSelector().text("Save"));
        assertTrue("save option never appeared", save.waitForExists(6000));
        save.click();

        long t0 = android.os.SystemClock.elapsedRealtime();
        boolean leftStudio = false;
        while (android.os.SystemClock.elapsedRealtime() - t0 < 20000) {
            pump(400);
            if (a.findViewById(R.id.teleView) == null) {
                leftStudio = true;
                break;
            }
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - t0;
        assertTrue("save never left the studio", leftStudio);
        assertTrue("studio blocked for " + elapsed + "ms while converting",
                elapsed < 8000);

        // Wait for OUR take to appear. A fresh install seeds demo rows, so
        // waiting for "any take" returns instantly and proves nothing.
        long t1 = android.os.SystemClock.elapsedRealtime();
        while (android.os.SystemClock.elapsedRealtime() - t1 < 90000) {
            pump(1000);
            boolean found = false;
            for (Take t : TakesRepository.all()) {
                if ("Conv Flow".equals(t.title)) found = true;
            }
            if (found) break;
        }
        // The activity must still be alive: the crash reported by the user
        // happened here, with the converting card turning into a real take.
        assertTrue("library died while the conversion completed",
                !a.isFinishing() && !a.isDestroyed());

        // Look for our own take: a fresh install also seeds demo rows.
        Take finished = null;
        StringBuilder titles = new StringBuilder();
        for (Take t : TakesRepository.all()) {
            titles.append(t.title).append('/');
            if ("Conv Flow".equals(t.title)) finished = t;
        }
        StringBuilder jobs = new StringBuilder();
        for (ConversionJob j : ConversionJobs.active()) {
            jobs.append(j.jobId).append(':').append(j.title).append(' ')
                    .append(j.state).append(' ')
                    .append(j.progress).append("% ");
        }
        assertNotNull("finished take never landed; takes=[" + titles
                + "] activeJobs=[" + jobs + "]", finished);
        assertTrue("finished take has no file", finished.hasFile());
        assertTrue("conversion job should be gone once finished",
                ConversionJobs.forRaw(
                        ConversionJob.sidecarFor(new File(finished.filePath))
                                .getAbsolutePath().replace(".conv.json", ""))
                        == null);
    }

    /**
     * Renaming while the take converts must stick: the worker inserts the
     * finished file under the job's title, so the new name has to travel
     * through the sidecar.
     */
    @Test
    public void renamingAConvertingTakeReachesTheJob() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File takes = RecordingEngine.takesDir(ctx);
        File raw = new File(takes, "renconv_" + System.nanoTime() + ".wav");
        java.io.RandomAccessFile w = new java.io.RandomAccessFile(raw, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, 48000L * 2 * 4));
            byte[] zeros = new byte[65536];
            for (long left = 48000L * 2 * 4; left > 0;
                 left -= Math.min(left, zeros.length)) {
                w.write(zeros, 0, (int) Math.min(left, zeros.length));
            }
        } finally {
            w.close();
        }
        ConversionJob job = null;
        try {
            job = ConversionJobs.enqueue(ctx, raw, "Old Name",
                    RecordPreset.MEMO, SaveFormat.M4A, 1000);
            assertTrue("job not active",
                    ConversionJobs.forRaw(raw.getAbsolutePath()) != null);

            // What the library's rename path does for a converting card.
            job.title = "New Name";
            job.write();
            assertEquals("New Name", job.title);

            // A fresh read of the sidecar must see it too, so a process
            // death mid-conversion does not resurrect the old name.
            ConversionJob back = ConversionJob.read(job.sidecar());
            assertNotNull("sidecar unreadable", back);
            assertEquals("rename did not survive the sidecar",
                    "New Name", back.title);

            // And the library renders that name.
            assertNotNull("job lost", ConversionJobs.get(job.jobId));
            assertEquals("New Name", ConversionJobs.get(job.jobId).title);
        } finally {
            if (job != null) ConversionJobs.cancel(job.jobId, true);
            if (raw.exists()) {
                // noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
        }
    }
}