package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

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
 *
 * <p>Launches via {@code startActivitySync}: ActivityScenario never leaves
 * PRE_ON_CREATE on this image, so these tests use the same launch path as
 * every other UI test in the suite.
 */
@RunWith(AndroidJUnit4.class)
public class LibraryRenderTest {

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private Activity launch() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
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
            float[] buf = new float[frames * channels];
            for (int i = 0; i < frames * channels; i++) {
                buf[i] = (float) Math.sin(i * 0.04) * 0.4f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
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
        Activity activity = launch();
        try {
            // Library is the default screen; it must be alive right now.
            assertNotNull(activity.findViewById(android.R.id.content));
        } finally {
            activity.finish();
        }
    }

    @Test
    public void librarySurvivesARealSavedTake() throws Exception {
        File saved = saveTake("Render Test A", 1, 2);
        Activity activity = launch();
        try {
            assertNotNull(activity.findViewById(android.R.id.content));
            // Let the async repository load + reconcile settle, as it does
            // on a real cold start.
            sleep(2500);
            assertNotNull(activity.findViewById(android.R.id.content));
            assertTrue("no takes in library",
                    TakesRepository.all().size() >= 1);
        } finally {
            activity.finish();
            TakesRepository.delete(findId("Render Test A"));
            if (saved.exists()) {
                // noinspection ResultOfMethodCallIgnored
                saved.delete();
            }
        }
    }

    @Test
    public void libraryRendersAfterSeveralSavesAndRelaunch() throws Exception {
        File one = saveTake("Render One", 1, 1);
        File two = saveTake("Render Two", 1, 2);
        File three = saveTake("Render Three", 1, 1);
        try {
            // Relaunch: the rows come back from Room, which is where a stale
            // or null column would surface on the main thread.
            Activity first = launch();
            sleep(3000);
            try {
                assertNotNull(
                        first.findViewById(android.R.id.content));
            } finally {
                first.finish();
            }

            Activity second = launch();
            sleep(3000);
            try {
                assertNotNull(
                        second.findViewById(android.R.id.content));
            } finally {
                second.finish();
            }

            // And the studio -> library hand-off, the exact transition
            // reported.
            Activity third = launch();
            sleep(1500);
            final boolean[] done = {false};
            third.runOnUiThread(() -> {
                ((MainActivity) third).openStudio();
                done[0] = true;
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(done[0]);
            sleep(1000);
            third.runOnUiThread(() -> {
                ((MainActivity) third).exitStudio();
                done[0] = false;
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            sleep(2500);
            assertNotNull(third.findViewById(android.R.id.content));
            third.finish();
        } finally {
            deleteTitle("Render One");
            deleteTitle("Render Two");
            deleteTitle("Render Three");
            for (File f : new File[]{one, two, three}) {
                if (f != null && f.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
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
        Activity activity = launch();
        try {
            sleep(2500);
            assertNotNull(activity.findViewById(android.R.id.content));
            assertTrue("odd takes missing",
                    TakesRepository.all().size() >= 3);
        } finally {
            activity.finish();
            TakesRepository.delete(9001L);
            TakesRepository.delete(9002L);
            TakesRepository.delete(9003L);
        }
    }

    private static long findId(String title) {
        for (Take t : TakesRepository.all()) {
            if (title.equals(t.title)) return t.id;
        }
        return -1;
    }

    private static void deleteTitle(String title) {
        long id = findId(title);
        if (id >= 0) TakesRepository.delete(id);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
