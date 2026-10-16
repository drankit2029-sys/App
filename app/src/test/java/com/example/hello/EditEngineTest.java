package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Roadmap Phase 3 splice engine: trim keeps exactly the selection, cut
 * removes it with a crossfaded bridge, undo restores byte-identical audio,
 * and DSP range commits preserve file geometry.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** DC 0.5 mono: every frame has identical energy, so zero-crossing snap
     * lands exactly on the requested frame and lengths are deterministic. */
    private File dcRaw(String name, int frames) throws Exception {
        File f = new File(tmp.getRoot(), name);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(1, 48000, (long) frames * 4));
            float[] buf = new float[frames];
            java.util.Arrays.fill(buf, 0.5f);
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    private static byte[] bytesOf(File f) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        } finally {
            r.close();
        }
    }

    private static float sampleAt(File f, int frame) throws Exception {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        try {
            r.seek(44L + (long) frame * 4);
            byte[] b = new byte[4];
            r.readFully(b);
            return java.nio.ByteBuffer.wrap(b)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat();
        } finally {
            r.close();
        }
    }

    @Test
    public void cropKeepsExactlyTheSelection() throws Exception {
        File raw = dcRaw("crop.wav", 48000);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        long kept = EditEngine.cropToSelection(raw, 4800, 9600, undo);
        assertEquals(4800L * 4, kept);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        assertEquals(4800L * 4, info.dataBytes);
        assertEquals(4800, info.dataBytes / 4 / info.channels);
        // Interior untouched by the 5 ms edge fades stays at DC level.
        assertEquals(0.5f, sampleAt(raw, 2400), 0.01f);
        assertTrue(undo.canUndo());
    }

    @Test
    public void cropRejectsTooShortSelection() throws Exception {
        File raw = dcRaw("short.wav", 48000);
        try {
            EditEngine.cropToSelection(raw, 100, 200,
                    new EditEngine.UndoJournal(tmp.getRoot()));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void deleteRemovesSelectionWithBridge() throws Exception {
        File raw = dcRaw("cut.wav", 48000);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        long kept = EditEngine.deleteSelection(raw, 4800, 9600, undo);
        // 4800 deleted frames + 240 crossfade-bridge frames are consumed.
        assertEquals((48000L - 4800 - Dsp.CROSSFADE_FRAMES) * 4, kept);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        assertEquals(kept, info.dataBytes);
        // DC spliced to DC stays DC across the cut boundary.
        assertEquals(0.5f, sampleAt(raw, 4700), 0.02f);
        assertTrue(undo.canUndo());
    }

    @Test
    public void deleteRejectsWholeTake() throws Exception {
        File raw = dcRaw("whole.wav", 48000);
        try {
            EditEngine.deleteSelection(raw, 0, 48000,
                    new EditEngine.UndoJournal(tmp.getRoot()));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void undoRestoresByteIdenticalAudio() throws Exception {
        File raw = dcRaw("undo.wav", 48000);
        byte[] before = bytesOf(raw);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        EditEngine.cropToSelection(raw, 4800, 9600, undo);
        assertTrue(undo.canUndo());
        assertTrue(undo.undo(raw));
        assertFalse(undo.canUndo());
        byte[] after = bytesOf(raw);
        assertEquals(before.length, after.length);
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) fail("byte differs at " + i);
        }
    }

    @Test
    public void undoJournalIsBoundedToThreeSteps() throws Exception {
        File raw = dcRaw("bounded.wav", 48000);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        for (int i = 0; i < 4; i++) undo.recordSnapshot(raw);
        for (int i = 0; i < 3; i++) assertTrue(undo.undo(raw));
        assertFalse(undo.canUndo());
    }

    @Test
    public void inactiveDspRangeIsNoOpWithoutSnapshot() throws Exception {
        File raw = dcRaw("noop.wav", 48000);
        byte[] before = bytesOf(raw);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        EditEngine.applyDspToRange(raw, 0, 4800,
                new DspChain(1, new DspChain.Config()), undo);
        assertFalse(undo.canUndo());
        byte[] after = bytesOf(raw);
        assertEquals(before.length, after.length);
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) fail("byte differs at " + i);
        }
    }

    @Test
    public void activeDspRangeKeepsGeometry() throws Exception {
        File raw = dcRaw("dsp.wav", 48000);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        EditEngine.applyDspToRange(raw, 0, 4800,
                new DspChain(1, DspChain.Config.voicePolish()), undo);
        FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
        assertEquals(48000L * 4, info.dataBytes);
        assertTrue(undo.canUndo());
        float mid = sampleAt(raw, 2400);
        assertTrue("non-finite " + mid, Float.isFinite(mid));
        assertTrue("unbounded " + mid, Math.abs(mid) <= 1.0f);
    }
}
