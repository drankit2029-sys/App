package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Library screen flows against the real UI: seeded cards render, search and
 * chips filter, favorites persist, and converting takes surface with the
 * share warning.
 */
@RunWith(AndroidJUnit4.class)
public class LibraryFlowTest {

    private Activity launch() {
        android.app.Instrumentation instr =
                InstrumentationRegistry.getInstrumentation();
        android.content.Intent i = new android.content.Intent(
                instr.getTargetContext(), MainActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return instr.startActivitySync(i);
    }

    /**
     * Records why a uiautomator lookup came up empty, using only the view tree
     * the test already has: how many takes exist, what is converting, and the
     * real state of the views being looked for.
     */
    private void diag(Activity a, String tag) {
        try {
            android.util.Log.e("LibDiag", tag + " takes="
                    + TakesRepository.all().size()
                    + " activeJobs=" + ConversionJobs.active().size());
            View root = a.getWindow().getDecorView();
            int[] ids = {R.id.chipAll, R.id.chipFav, R.id.searchToggle,
                    R.id.sortBtn};
            String[] names = {"chipAll", "chipFav", "searchToggle", "sortBtn"};
            for (int i = 0; i < ids.length; i++) {
                View v = root.findViewById(ids[i]);
                android.util.Log.e("LibDiag", tag + " " + names[i] + "="
                        + (v == null ? "NULL"
                        : "vis=" + v.getVisibility() + " shown=" + v.getShown()
                        + " size=" + v.getWidth() + "x" + v.getHeight()));
            }
            android.util.Log.e("LibDiag", tag + " windowFocus="
                    + a.getWindow().getDecorView().isFocused()
                    + " resumed=" + a.hasWindowFocus());
        } catch (Throwable t) {
            android.util.Log.e("LibDiag", tag + " diag failed: " + t);
        }
    }

    private UiDevice device() {
        return UiDevice.getInstance(
                InstrumentationRegistry.getInstrumentation());
    }

    private void seedTake(String title, String format) throws Exception {
        android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, "flowlib_" + System.nanoTime()
                + (format.equals("WAV") ? ".wav" : ".m4a"));
        RandomAccessFile w = new RandomAccessFile(f, "rw");
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
        TakesRepository.addRecording(f, 1000, title, format);
    }

    private Take findByTitle(String title) {
        for (Take t : TakesRepository.all()) {
            if (title.equals(t.title)) return t;
        }
        return null;
    }

    private void cleanupTitle(String title) {        try {
            for (Take t : new ArrayList<>(TakesRepository.all())) {
                if (title.equals(t.title)) {
                    TakesRepository.delete(t.id);
                    File f = new File(t.filePath);
                    if (f.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static View findContaining(View v, String needle) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.toString().contains(needle)) return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findContaining(g.getChildAt(i), needle);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static View findByText(View v, String needle) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && needle.equals(cs.toString().trim())) return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), needle);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    @Test
    public void seededCardsRender() throws Exception {
        seedTake("FlowLib Alpha", "M4A");
        seedTake("FlowLib Beta", "WAV");
        Activity a = launch();
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(800);
            View root = a.getWindow().getDecorView();
            assertNotNull("alpha card missing",
                    findByText(root, "FlowLib Alpha"));
            assertNotNull("beta card missing",
                    findByText(root, "FlowLib Beta"));
        } finally {
            cleanupTitle("FlowLib Alpha");
            cleanupTitle("FlowLib Beta");
        }
    }

    @Test
    public void searchFiltersToMatch() throws Exception {
        Activity a = launch();
        UiDevice d = device();
        try {
            seedTake("FlowLib Alpha", "M4A");
            seedTake("FlowLib Beta", "WAV");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            UiObject search = d.findObject(new UiSelector()
                    .description("Search takes"));
            if (!search.waitForExists(3000)) diag(a, "search");
            assertTrue("no search toggle", search.waitForExists(5000));
            search.click();
            UiObject input = d.findObject(new UiSelector()
                    .resourceId("com.example.hello:id/searchInput"));
            assertTrue("no search input", input.waitForExists(5000));
            input.setText("FlowLib Alpha");
            Thread.sleep(800);
            View root = a.getWindow().getDecorView();
            assertNotNull("match hidden by search",
                    findByText(root, "FlowLib Alpha"));
            assertTrue("non-match shown by search",
                    findByText(root, "FlowLib Beta") == null);
            d.pressBack();
        } finally {
            cleanupTitle("FlowLib Alpha");
            cleanupTitle("FlowLib Beta");
        }
    }

    @Test
    public void favoriteTogglePersists() throws Exception {
        seedTake("FlowLib Fav", "M4A");
        Activity a = launch();
        try {
            Take t = findByTitle("FlowLib Fav");
            assertNotNull(t);
            assertTrue(!t.favorite);
            UiDevice d = device();
            // Newest take is first: its heart is the first fav icon.
            UiObject heart = d.findObject(new UiSelector()
                    .resourceId("com.example.hello:id/favIcon").instance(0));
            assertTrue("no heart", heart.waitForExists(5000));
            heart.click();
            Thread.sleep(600);
            assertTrue("favorite did not persist",
                    findByTitle("FlowLib Fav").favorite);
            heart.click();
            Thread.sleep(600);
            assertTrue("unfavorite did not persist",
                    !findByTitle("FlowLib Fav").favorite);
        } finally {
            cleanupTitle("FlowLib Fav");
        }
    }

    @Test
    public void favoritesChipFilters() throws Exception {
        Activity a = launch();
        UiDevice d = device();
        try {
            seedTake("FlowLib Fav", "M4A");
            seedTake("FlowLib Plain", "M4A");
            Take fav = findByTitle("FlowLib Fav");
            assertNotNull(fav);
            TakesRepository.toggleFavorite(fav.id);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);
            UiObject chip = d.findObject(new UiSelector().text("Favorites"));
            if (!chip.waitForExists(3000)) diag(a, "favChip");
            assertTrue("no favorites chip", chip.waitForExists(5000));
            chip.click();
            Thread.sleep(800);
            View root = a.getWindow().getDecorView();
            assertNotNull("favorite hidden by chip",
                    findByText(root, "FlowLib Fav"));
            assertTrue("non-favorite shown by chip",
                    findByText(root, "FlowLib Plain") == null);
            UiObject all = d.findObject(new UiSelector().text("All"));
            if (all.waitForExists(3000)) all.click();
        } finally {
            cleanupTitle("FlowLib Fav");
            cleanupTitle("FlowLib Plain");
        }
    }

    @Test
    public void formatChipsFilterWav24FlacAndAmr() throws Exception {
        Activity a = launch();
        UiDevice d = device();
        try {
            seedTakeExt("FlowLib W24", "WAV24", ".wav");
            seedTakeExt("FlowLib Flac", "FLAC", ".m4a");
            seedTakeExt("FlowLib Amr", "AMR", ".3gp");
            seedTakeExt("FlowLib M4", "M4A", ".m4a");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);

            clickChip(a, "com.example.hello:id/chipWav");
            Thread.sleep(800);
            View root = a.getWindow().getDecorView();
            assertNotNull("WAV24 hidden by WAV chip",
                    findByText(root, "FlowLib W24"));
            assertTrue("FLAC shown by WAV chip",
                    findByText(root, "FlowLib Flac") == null);

            clickChip(a, "com.example.hello:id/chipFlac");
            Thread.sleep(800);
            root = a.getWindow().getDecorView();
            assertNotNull("FLAC hidden by FLAC chip",
                    findByText(root, "FlowLib Flac"));
            assertTrue("WAV24 shown by FLAC chip",
                    findByText(root, "FlowLib W24") == null);

            clickChip(a, "com.example.hello:id/chipAmr");
            Thread.sleep(800);
            root = a.getWindow().getDecorView();
            assertNotNull("AMR hidden by AMR chip",
                    findByText(root, "FlowLib Amr"));
            assertTrue("M4A shown by AMR chip",
                    findByText(root, "FlowLib M4") == null);

            clickChip(a, "com.example.hello:id/chipAll");
            Thread.sleep(800);
        } finally {
            cleanupTitle("FlowLib W24");
            cleanupTitle("FlowLib Flac");
            cleanupTitle("FlowLib Amr");
            cleanupTitle("FlowLib M4");
        }
    }

    private void seedTakeExt(String title, String format, String ext)
            throws Exception {
        android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, "flowlib_" + System.nanoTime() + ext);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
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
        TakesRepository.addRecording(f, 1000, title, format);
    }

    private void clickChip(Activity a, String resId) throws Exception {
        // Click through the activity, not the accessibility tree: card
        // badges can carry the exact same text as a chip ("FLAC"), which
        // makes text selectors ambiguous.
        int id = a.getResources().getIdentifier(resId.substring(
                resId.lastIndexOf('/') + 1), "id", a.getPackageName());
        assertTrue("no chip id " + resId, id != 0);
        a.runOnUiThread(() -> {
            View chip = a.findViewById(id);
            assertNotNull("no chip view " + resId, chip);
            chip.performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void convertingCardAppearsWithCount() throws Exception {
        Activity a = launch();
        android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        File takes = RecordingEngine.takesDir(ctx);
        File raw = new File(takes, "flowconv_" + System.nanoTime() + ".wav");
        RandomAccessFile w = new RandomAccessFile(raw, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, 48000L * 2 * 4));
        } finally {
            w.close();
        }
        ConversionJob job = null;
        try {
            job = ConversionJobs.enqueue(ctx, raw, "FlowLib Converting",
                    RecordPreset.MEMO, SaveFormat.M4A, 1000);
            Thread.sleep(1200);
            View root = a.getWindow().getDecorView();
            assertNotNull("converting card missing",
                    findByText(root, "FlowLib Converting"));
            assertNotNull("header count missing",
                    findContaining(root, "1 converting"));
        } finally {
            if (job != null) ConversionJobs.cancel(job.jobId, true);
        }
    }

    @Test
    public void shareWarningAppearsAndDismisses() throws Exception {
        Activity a = launch();
        UiDevice d = device();
        android.content.Context ctx =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        File takes = RecordingEngine.takesDir(ctx);
        File raw = new File(takes, "flowwarn_" + System.nanoTime() + ".wav");
        RandomAccessFile w = new RandomAccessFile(raw, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, 48000L * 2 * 4));
        } finally {
            w.close();
        }
        ConversionJob job = null;
        try {
            job = ConversionJobs.enqueue(ctx, raw, "FlowLib Warn",
                    RecordPreset.MEMO, SaveFormat.M4A, 1000);
            Thread.sleep(1200);
            UiObject card = d.findObject(new UiSelector()
                    .text("FlowLib Warn"));
            assertTrue("no converting card", card.waitForExists(5000));
            card.click();
            Thread.sleep(600);
            UiObject export = d.findObject(new UiSelector().text("Export"));
            assertTrue("no export button", export.waitForExists(5000));
            export.click();
            UiObject title = d.findObject(
                    new UiSelector().text("Still converting"));
            assertTrue("no share warning", title.waitForExists(5000));
            assertTrue("no wav share option",
                    d.findObject(new UiSelector().text("Share WAV"))
                            .waitForExists(3000));
            assertTrue("no view-conversions option",
                    d.findObject(new UiSelector().text("View conversions"))
                            .waitForExists(3000));
            UiObject cancel = d.findObject(new UiSelector().text("Cancel"));
            assertTrue("no cancel", cancel.waitForExists(3000));
            cancel.click();
            Thread.sleep(600);
            assertTrue("warning did not dismiss",
                    !d.findObject(new UiSelector().text("Still converting"))
                            .exists());
        } finally {
            if (job != null) ConversionJobs.cancel(job.jobId, true);
        }
    }

}
