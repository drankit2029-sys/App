package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Reproduces the reported flow on a real Android UI stack: a take is saved,
 * the studio hands back to the library, and the library must render and
 * survive a relaunch.
 *
 * <p>The emulator cannot fake a microphone, so the capture is synthesised
 * (same as the recorder would stream) and driven through the real
 * save/convert/library path. Everything downstream of the file is genuine.
 */
@RunWith(AndroidJUnit4.class)
public class LibraryRenderTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** Writes a real float capture and runs the genuine save pipeline. */
    private File saveTake(String name, int seconds, int channels)
            throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.clearSession();
        engine.startNewSession(ctx(), name, channels);
        File raw = engine.rawFile;
        int frames = RecordingEngine.SAMPLE_RATE * seconds;
        RandomAccessFile w = new RandomAccessFile(raw, "rw");
        try {
            w.seek(w.length());
            for (int i = 0; i < frames * channels; i++) {
                w.writeFloat((float) Math.sin(i * 0.04) * 0.4f);
            }
        } finally {
            w.close();
        }
        TakeSaver.Result r = TakeSaver.finalizeAndConvert(ctx(),
                RecordPreset.MEMO, SaveFormat.M4A, null);
        TakesRepository.addRecording(r.file, r.durationMs, r.title, r.label);
        engine.clearSession();
        return r.file;
    }

    @Test
    public void libraryRendersAfterSave() {
        ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            // Library is the default screen; it must be alive right now.
            assertNotNull(activity.findViewById(android.R.id.content));
        });
        scenario.close();
    }

    @Test
    public void librarySurvivesARealSavedTake() throws Exception {
        saveTake("Render Test A", 1, 2);

        ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            // Render the library with a freshly saved take present.
            activity.findViewById(android.R.id.content).post(() -> { });
        });
        // Let the async repository load + reconcile settle, as it does on a
        // real cold start.
        sleep(2500);
        scenario.onActivity(activity ->
                assertNotNull(activity.findViewById(android.R.id.content)));
        assertTrue("no takes in library", TakesRepository.all().size() >= 1);
        scenario.close();
    }

    @Test
    public void libraryRendersAfterSeveralSavesAndRelaunch() throws Exception {
        saveTake("Render One", 1, 1);
        saveTake("Render Two", 1, 2);
        saveTake("Render Three", 1, 1);

        // Relaunch: the rows come back from Room, which is where a stale or
        // null column would surface on the main thread.
        ActivityScenario<MainActivity> s1 =
                ActivityScenario.launch(MainActivity.class);
        sleep(3000);
        s1.onActivity(a -> assertNotNull(a.findViewById(android.R.id.content)));
        s1.close();

        ActivityScenario<MainActivity> s2 =
                ActivityScenario.launch(MainActivity.class);
        sleep(3000);
        s2.onActivity(a -> assertNotNull(a.findViewById(android.R.id.content)));
        s2.close();

        // And the studio -> library hand-off, the exact transition reported.
        ActivityScenario<MainActivity> s3 =
                ActivityScenario.launch(MainActivity.class);
        sleep(1500);
        s3.onActivity(activity -> {
            activity.openStudio();
            activity.findViewById(android.R.id.content).post(() ->
                    activity.exitStudio());
        });
        sleep(2500);
        s3.onActivity(a -> assertNotNull(a.findViewById(android.R.id.content)));
        s3.close();
    }

    @Test
    public void everyTakeShapeBindsWithoutThrowing() {
        // Demo rows (null filePath), real rows, and odd values all have to
        // survive the adapter's bind + the library's filter/sort.
        TakesRepository.addTake(new Take(9001, "Demo-ish", 1000,
                System.currentTimeMillis(), "0.1 MB", "WAV", false, null));
        TakesRepository.addTake(new Take(9002, "Odd", 0,
                0, "0.0 MB", "M4A", true, "/nonexistent/x.m4a"));
        TakesRepository.addTake(new Take(9003, "Huge", Long.MAX_VALUE / 4,
                System.currentTimeMillis(), "999.9 MB", "FLAC", false, null));

        ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class);
        sleep(2500);
        scenario.onActivity(a -> assertNotNull(a.findViewById(android.R.id.content)));
        scenario.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
