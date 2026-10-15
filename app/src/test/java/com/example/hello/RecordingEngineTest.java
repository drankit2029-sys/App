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

/**
 * Capture session lifecycle on real files: create, measure, rename, attach,
 * abort — all without touching the microphone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RecordingEngineTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void startCreatesRawAndPending() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.startNewSession(ctx(), "  Hello World!  ", 2);
        try {
            assertNotNull(e.rawFile);
            assertTrue(e.rawFile.exists());
            assertTrue(new File(e.rawFile.getAbsolutePath() + ".pending")
                    .exists());
            assertEquals("Hello World!", e.takeName);
            assertTrue(e.hasSession());
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void duplicateStartThrows() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.startNewSession(ctx(), "One", 2);
        try {
            boolean threw = false;
            try {
                e.startNewSession(ctx(), "Two", 2);
            } catch (IllegalStateException expected) {
                threw = true;
            }
            assertTrue("second session must throw", threw);
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void durationTracksWrittenBytes() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.startNewSession(ctx(), "Dur", 2);
        try {
            // 48000 frames stereo float = exactly 1 second.
            java.io.RandomAccessFile w =
                    new java.io.RandomAccessFile(e.rawFile, "rw");
            try {
                w.seek(w.length());
                w.write(new byte[48000 * 2 * 4]);
            } finally {
                w.close();
            }
            e.dataBytes = e.rawFile.length() - 44;
            assertEquals(1000, e.durationMs());
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void abortDeletesEverything() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.startNewSession(ctx(), "Gone", 1);
        File raw = e.rawFile;
        assertTrue(e.abortAndDelete());
        assertTrue(!raw.exists());
        assertTrue(!new File(raw.getAbsolutePath() + ".pending").exists());
        assertFalse(e.hasSession());
        assertNull(e.rawFile);
    }

    @Test
    public void renameMovesFilesWhenIdle() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.startNewSession(ctx(), "Old Name", 2);
        try {
            e.renameSession(ctx(), "New Name");
            assertEquals("New Name", e.takeName);
            assertTrue(e.rawFile.getName().startsWith("New Name"));
            assertTrue(new File(e.rawFile.getAbsolutePath() + ".pending")
                    .exists());
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void attachExistingAdoptsUnfinished() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.startNewSession(ctx(), "Adopted", 2);
        File raw = e.rawFile;
        long bytes = 48000L * 2 * 4;
        e.abortAndDelete();
        // Simulate a crash leftover: raw without engine state.
        java.io.RandomAccessFile w = new java.io.RandomAccessFile(raw, "rw");
        try {
            w.seek(w.length());
            w.write(new byte[(int) bytes]);
        } finally {
            w.close();
        }
        RecordingEngine.writePendingMarker(raw, "Adopted", 2);
        try {
            e.attachExisting(ctx(), raw, "Adopted", 2, bytes);
            assertTrue(e.hasSession());
            assertEquals(bytes, e.dataBytes);
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void streamingWithoutSessionIsNoop() {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        e.beginStreaming(ctx(), RecordPreset.MEMO, SaveFormat.M4A);
        assertNull(e.streaming());
        assertNull(e.endStreamingDetach());
    }

    @Test
    public void payloadBytesOfMissingIsZero() {
        File f = new File(ctx().getCacheDir(), "missing-payload.wav");
        // noinspection ResultOfMethodCallIgnored
        f.delete();
        assertEquals(0, RecordingEngine.payloadBytesOf(f));
    }
}
