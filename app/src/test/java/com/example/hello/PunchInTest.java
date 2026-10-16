package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Roadmap Phase 4 punch-in cursor API: cursor clamping, millisecond mapping
 * and punch-range arming on an attached session, without the microphone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PunchInTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dcRaw(String name, int frames, int ch) throws Exception {
        File f = new File(tmp.getRoot(), name);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(ch, 48000, (long) frames * ch * 4));
            float[] buf = new float[frames * ch];
            Arrays.fill(buf, 0.25f);
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void cursorStartsAtZero() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        File raw = dcRaw("cur0.wav", 48000, 2);
        try {
            e.attachExisting(ApplicationProvider.getApplicationContext(),
                    raw, "Cursor", 2, raw.length() - 44);
            assertEquals(48000L, e.totalFrames());
            assertEquals(0L, e.cursorFrames());
            assertEquals(0L, e.cursorMs());
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void seekClampsToSessionBounds() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        File raw = dcRaw("clamp.wav", 48000, 2);
        Context ctx = ApplicationProvider.getApplicationContext();
        try {
            e.attachExisting(ctx, raw, "Clamp", 2, raw.length() - 44);
            e.seekCursorFrame(24000);
            assertEquals(24000L, e.cursorFrames());
            assertEquals(500L, e.cursorMs());
            e.seekCursorFrame(99_999_999L);
            assertEquals(48000L, e.cursorFrames());
            e.seekCursorFrame(-10L);
            assertEquals(0L, e.cursorFrames());
        } finally {
            e.abortAndDelete();
        }
    }

    @Test
    public void armPunchRangePositionsCursor() throws Exception {
        RecordingEngine e = RecordingEngine.get();
        e.abortAndDelete();
        File raw = dcRaw("arm.wav", 48000, 1);
        Context ctx = ApplicationProvider.getApplicationContext();
        try {
            e.attachExisting(ctx, raw, "Arm", 1, raw.length() - 44);
            assertEquals(48000L, e.totalFrames());
            e.armPunchRange(1000, 5000);
            assertEquals(1000L, e.cursorFrames());
            // Inverted range still parks the cursor; punch-out is dropped.
            e.armPunchRange(9000, 1000);
            assertEquals(9000L, e.cursorFrames());
            assertTrue(e.hasSession());
        } finally {
            e.abortAndDelete();
        }
    }
}
