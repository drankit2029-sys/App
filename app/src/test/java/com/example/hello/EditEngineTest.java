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
 * removes it with a crossfaded bridge, undo restores byte-identical audio
 * across 5 steps on full takes of any size (including >30 MB takes),
 * range-delta snapshots restore sub-range DSP edits in-place, and chunked
 * offline/in-place DSP range commits preserve file geometry.
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
    public void undoJournalIsBoundedToFiveStepsAndSupportsRedoOnFullTakes()
            throws Exception {
        assertEquals(5, EditEngine.MAX_UNDO_STEPS);
        File raw = dcRaw("bounded.wav", 48000);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        for (int i = 0; i < 8; i++) undo.recordSnapshot(raw);
        assertEquals(5, undo.undoDepth());
        assertFalse(undo.canRedo());
        for (int i = 0; i < 5; i++) assertTrue(undo.undo(raw));
        assertFalse(undo.canUndo());
        assertEquals(5, undo.redoDepth());
        for (int i = 0; i < 5; i++) assertTrue(undo.redo(raw));
        assertFalse(undo.canRedo());
        assertEquals(5, undo.undoDepth());
        // Recording a new snapshot clears the redo stack
        assertTrue(undo.undo(raw));
        assertTrue(undo.canRedo());
        assertTrue(undo.recordSnapshot(raw));
        assertFalse(undo.canRedo());
    }

    @Test
    public void undoJournalWorksOnEntireLargeTakesOver30Mb() throws Exception {
        File large = new File(tmp.getRoot(), "large_take.wav");
        long payloadBytes = 32L * 1024L * 1024L;
        try (RandomAccessFile w = new RandomAccessFile(large, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, payloadBytes));
            w.setLength(44L + payloadBytes);
            w.seek(44L + 1000L * 4L);
            Dsp.writeFloatsLE(w, new float[]{0.25f, -0.25f, 0.75f, -0.75f}, 0, 4);
        }
        EditEngine.UndoJournal undo = new EditEngine.UndoJournal(tmp.getRoot());
        assertTrue(undo.wouldRecord(large));
        assertTrue(undo.recordSnapshot(large));
        // Mutate the file and verify undo + redo restore exact samples and length
        try (RandomAccessFile w = new RandomAccessFile(large, "rw")) {
            w.seek(44L + 1000L * 4L);
            Dsp.writeFloatsLE(w, new float[]{0.9f, 0.9f, 0.9f, 0.9f}, 0, 4);
        }
        assertEquals(0.9f, sampleAt(large, 1000), 1e-5f);
        assertTrue(undo.undo(large));
        assertEquals(44L + payloadBytes, large.length());
        assertEquals(0.25f, sampleAt(large, 1000), 1e-5f);
        assertTrue(undo.redo(large));
        assertEquals(0.9f, sampleAt(large, 1000), 1e-5f);
    }

    @Test
    public void chunkedOfflineEffectWorksOnLargeRegionAndSupportsUndoRedo()
            throws Exception {
        int frames = AudioProcessor.MAX_IN_MEMORY_OFFLINE_FRAMES + 8192;
        File raw = dcRaw("large_offline.wav", frames);
        EditEngine.UndoJournal undo = new EditEngine.UndoJournal(tmp.getRoot());
        FadeProcessor fade = new FadeProcessor();
        fade.prepare(48000, 1);
        long outFrames = EditEngine.applyLengthChangingEffectToRange(
                raw, 0, frames, fade, undo);
        assertEquals(frames, outFrames);
        assertTrue(undo.canUndo());
        assertTrue(undo.undo(raw));
        assertEquals(0.5f, sampleAt(raw, 100), 1e-4f);
        assertTrue(undo.redo(raw));
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
    public void activeDspRangeKeepsGeometryAndRestoresViaRangeDeltaUndo()
            throws Exception {
        File raw = dcRaw("dsp.wav", 48000);
        byte[] before = bytesOf(raw);
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

        assertTrue(undo.undo(raw));
        byte[] restored = bytesOf(raw);
        assertEquals(before.length, restored.length);
        for (int i = 0; i < before.length; i++) {
            if (before[i] != restored[i]) fail("delta undo differs at " + i);
        }
    }

    @Test
    public void variableLengthSelectAndReplaceHandlesShorterAndLongerSegments()
            throws Exception {
        // 1) Create 48,000-frame take (1.0s @ 48 kHz mono)
        File raw = dcRaw("replace_var.wav", 48000);
        File tail = new File(tmp.getRoot(), "replace_var.reptail");
        EditEngine.UndoJournal undo = new EditEngine.UndoJournal(tmp.getRoot());
        undo.recordSnapshot(raw);

        // Save tail [30000, 48000) (18,000 frames) for replacing [10000, 30000)
        long savedTailFrames = EditEngine.saveTailFrom(raw, 30000, tail);
        assertEquals(18000L, savedTailFrames);

        // Case A: Shorter replacement — user records 5,000 frames starting at 10,000
        // (so replaceEndFrame = 15,000 instead of 30,000)
        long newBytesShort = EditEngine.spliceTailAfterReplace(raw, 15000, tail);
        long expectedFramesShort = 15000L + 18000L - Dsp.CROSSFADE_FRAMES;
        assertEquals(expectedFramesShort * 4L, newBytesShort);

        // Restore via Undo and test Case B: Longer replacement (35,000 frames
        // recorded from 10,000 -> replaceEndFrame = 45,000)
        assertTrue(undo.undo(raw));
        EditEngine.saveTailFrom(raw, 30000, tail);
        try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
            raf.seek(44L + 10000L * 4L);
            float[] longerRec = new float[35000];
            java.util.Arrays.fill(longerRec, 0.75f);
            Dsp.writeFloatsLE(raf, longerRec, 0, longerRec.length);
            raf.seek(0);
            raf.write(Dsp.wavFloatHeader(1, 48000, 45000L * 4L));
        }
        long newBytesLong = EditEngine.spliceTailAfterReplace(raw, 45000, tail);
        long expectedFramesLong = 45000L + 18000L - Dsp.CROSSFADE_FRAMES;
        assertEquals(expectedFramesLong * 4L, newBytesLong);
        assertEquals(0.75f, sampleAt(raw, 25000), 0.01f);
        assertEquals(0.5f, sampleAt(raw, (int) expectedFramesLong - 1000), 0.01f);
    }

    @Test
    public void applyDspChangesOnlyTheCommittedRange() throws Exception {
        // 440 Hz sine: voice polish must audibly reshape it, but only
        // inside the committed range — the rest stays bit-identical.
        File f = new File(tmp.getRoot(), "sine.wav");
        int frames = 48000;
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(1, 48000, (long) frames * 4));
            float[] buf = new float[frames];
            for (int i = 0; i < frames; i++) {
                buf[i] = (float) Math.sin(
                        i * 2 * Math.PI * 440 / 48000) * 0.7f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        byte[] before = bytesOf(f);
        EditEngine.UndoJournal undo =
                new EditEngine.UndoJournal(tmp.getRoot());
        EditEngine.applyDspToRange(f, 0, 4800,
                new DspChain(1, DspChain.Config.voicePolish()), undo);
        byte[] after = bytesOf(f);
        assertEquals(before.length, after.length);
        assertTrue(undo.canUndo());
        int changed = 0;
        for (int i = 44; i < 44 + 4800 * 4; i++) {
            if (before[i] != after[i]) changed++;
        }
        assertTrue("FX changed nothing in range", changed > 4800);
        for (int i = 44 + 4800 * 4; i < after.length; i++) {
            if (before[i] != after[i]) fail("touched outside range at " + i);
        }
    }
}
