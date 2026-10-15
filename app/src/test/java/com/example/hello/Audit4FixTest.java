package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.MotionEvent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * AUDIT-4 regression tests for the fixes that are observable off-device:
 * crash-healer breadcrumbs (A4-01), importer cleanup (A4-05), trash restore
 * path reporting (A4-09), script clearing (A4-10), prompter drag axis (A4-11),
 * importer header re-injection (A4-13) and the A4-06 init merge.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class Audit4FixTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private android.content.Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    // ---- A4-01: a failed conversion must close the breadcrumb stage ----

    @Test
    public void failedConversionTerminatesTheStage() throws Exception {
        File dir = tmp.newFolder();
        File garbage = new File(dir, "bad.wav");
        RandomAccessFile w = new RandomAccessFile(garbage, "rw");
        try {
            w.setLength(0);
            w.write(new byte[200]); // not a WAV
        } finally {
            w.close();
        }
        boolean threw = false;
        try {
            TakeSaver.convertRaw(ctx(), garbage, "Audit4",
                    RecordPreset.MEMO, SaveFormat.M4A, null);
        } catch (Exception expected) {
            threw = true;
        }
        assertTrue("garbage raw must fail", threw);
        // The healer keys off an unterminated "convert start" line.
        assertNull("convert stage left open after a failure",
                CrashLog.unterminatedStage(ctx()));
    }

    // ---- A4-05: a failed import must not leave a session file behind ----

    @Test
    public void failedImportLeavesNoSessionFile() throws Exception {
        File bad = new File(tmp.newFolder(), "broken.m4a");
        RandomAccessFile w = new RandomAccessFile(bad, "rw");
        try {
            w.setLength(0);
            w.write(new byte[64]); // not a decodable container
        } finally {
            w.close();
        }
        File takes = RecordingEngine.takesDir(ctx());
        int before = countSessionFiles(takes);
        boolean threw = false;
        try {
            AudioImporter.takeToFloatWav(bad, takes, "Audit4 Import", null);
        } catch (Exception expected) {
            threw = true;
        }
        assertTrue("a corrupt container must fail the import", threw);
        assertEquals("orphan session file left for reconcile to adopt",
                before, countSessionFiles(takes));
    }

    private static int countSessionFiles(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return 0;
        int n = 0;
        for (File f : all) {
            String s = f.getName();
            if (f.isFile() && s.endsWith(".wav") && !s.endsWith(".pending")
                    && !s.endsWith(".part")) {
                n++;
            }
        }
        return n;
    }

    // ---- A4-09: restore reports where the file actually landed ----

    @Test
    public void restoreFromTrashReportsRealPath() throws Exception {
        File takes = tmp.newFolder("takes");
        File audio = new File(takes, "song.m4a");
        RandomAccessFile w = new RandomAccessFile(audio, "rw");
        try {
            w.write(new byte[128]);
        } finally {
            w.close();
        }
        File trashed = Dsp.moveToTrash(takes, audio);
        assertNotNull("trash move failed", trashed);
        assertFalse(audio.exists());

        // Take the destination: the restore must uniquify and say where.
        File squatter = new File(takes, "song.m4a");
        RandomAccessFile w2 = new RandomAccessFile(squatter, "rw");
        try {
            w2.write(new byte[10]);
        } finally {
            w2.close();
        }

        File back = Dsp.restoreFromTrash(trashed, new File(takes, "song.m4a"));
        assertNotNull("restore returned null", back);
        // The destination was taken, so the restore must uniquify and the
        // caller must be told the real name.
        assertFalse("restore overwrote the existing take",
                back.getName().equals("song.m4a"));
        assertTrue("unexpected restored name: " + back.getName(),
                back.getName().startsWith("song"));
        assertEquals("restore must not clobber the existing file",
                10, squatter.length());
        assertTrue("restored file missing", back.exists());
        assertEquals(128, back.length());
        assertNull("missing source must return null",
                Dsp.restoreFromTrash(new File(takes, "ghost.m4a"),
                        new File(takes, "ghost2.m4a")));
    }

    // ---- A4-10: an empty script is a valid (clearing) save ----

    @Test
    public void editorSavesEmptyScriptAndFiresOnce() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        final String[] seen = new String[3];
        final int[] calls = {0};
        android.app.Dialog d = TeleDialogs.showEditor(a, "old script", 120,
                text -> {
                    if (calls[0] < 3) seen[calls[0]] = text;
                    calls[0]++;
                }, null);
        View save = d.findViewById(R.id.teleSaveBtn);
        assertNotNull("no save button", save);
        View input = d.findViewById(R.id.teleEditorInput);
        assertNotNull("no editor field", input);
        ((android.widget.EditText) input).setText("");
        a.runOnUiThread(save::performClick);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals("empty script must be savable (it clears the prompter)",
                1, calls[0]);
        assertEquals("", seen[0]);
    }

    // ---- A4-11: a horizontal swipe must not scroll the script ----

    private TeleprompterView prompter() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        TeleprompterView v = new TeleprompterView(a);
        v.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600,
                        View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 900, 600);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            if (i > 0) sb.append(' ');
            sb.append("word").append(i);
        }
        v.setScript(sb.toString());
        v.setScrollOffset(v.lineHeight() * 10f);
        return v;
    }

    private static MotionEvent ev(int action, float x, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    @Test
    public void horizontalSwipeDoesNotScroll() {
        TeleprompterView v = prompter();
        float before = v.scrollOffset();
        v.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 450f, 300f));
        // Mostly sideways, a little vertical.
        v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 400f, 306f));
        v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 300f, 312f));
        v.onTouchEvent(ev(MotionEvent.ACTION_UP, 300f, 312f));
        assertEquals("a horizontal swipe scrolled the script",
                before, v.scrollOffset(), 0.5f);
    }

    @Test
    public void verticalDragScrolls() {
        TeleprompterView v = prompter();
        float before = v.scrollOffset();
        v.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 450f, 300f));
        v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 452f, 200f));
        v.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 450f, 120f));
        v.onTouchEvent(ev(MotionEvent.ACTION_UP, 450f, 120f));
        assertTrue("a vertical drag did not scroll the script",
                v.scrollOffset() > before);
    }

    // ---- A4-13: sniffed header bytes are never dropped ----

    @Test
    public void importerWrapperReplaysEveryHeaderByte() throws Exception {
        // The sniffed magic bytes are re-injected in front of a stream that
        // has already consumed them, exactly as readDocx()/readPlain() see it.
        byte[] payload = {'h', 'e', 'l', 'l', 'o'};
        File f = tmp.newFile("sniff.bin");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(payload);
        } finally {
            w.close();
        }
        java.io.InputStream raw = new java.io.FileInputStream(f);
        java.io.InputStream wrapped =
                ScriptImporter.wrapForTest(raw, new byte[]{'P', 'K', 3, 4});
        try {
            // One byte at a time: the old headDone flag dropped the rest.
            assertEquals('P', wrapped.read());
            assertEquals('K', wrapped.read());
            assertEquals(3, wrapped.read());
            assertEquals(4, wrapped.read());
            // Then the real payload continues.
            assertEquals('h', wrapped.read());
            assertEquals('e', wrapped.read());
            wrapped.close();
        } finally {
            try {
                raw.close();
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void importerWrapperServesBulkAndMixedReads() throws Exception {
        File f = tmp.newFile("sniff2.bin");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.write(new byte[]{'x', 'y', 'z'});
        } finally {
            w.close();
        }
        java.io.InputStream raw = new java.io.FileInputStream(f);
        java.io.InputStream wrapped =
                ScriptImporter.wrapForTest(raw, new byte[]{'P', 'K', 3, 4});
        try {
            byte[] two = new byte[2];
            assertEquals("bulk read returned short", 2,
                    wrapped.read(two, 0, 2));
            assertEquals('P', two[0]);
            assertEquals('K', two[1]);
            // Mixed access: one single byte, then a bulk read that must
            // continue from byte 3 of the header, not from the source.
            assertEquals(3, wrapped.read());
            byte[] rest = new byte[4];
            int n = wrapped.read(rest, 0, 4);
            assertEquals("mixed read returned short", 4, n);
            assertEquals(4, rest[0] & 0xFF);
            assertEquals('x', rest[1] & 0xFF);
            wrapped.close();
        } finally {
            try {
                raw.close();
            } catch (Exception ignored) {}
        }
    }

    // ---- A4-14: picker labels honour the step ----

    @Test
    public void pickerLabelsUseConfiguredStep() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        TeleValue tv = new TeleValue(a);
        tv.configure(40, 400, 5, 120, "");
        String[] labels = tv.steppedLabelsForTest();
        assertTrue("too few labels: " + labels.length, labels.length > 10);
        assertEquals("40", labels[0]);
        assertEquals("45", labels[1]);
        assertEquals("50", labels[2]);
    }
}