package com.takeone.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the library adapter through appear/progress/disappear of a
 * converting card exactly like the live jobs listener does, catching
 * RecyclerView bookkeeping crashes without an emulator round trip.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConvertingCardsBindTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private static class NopListener implements TakesAdapter.Listener {
        public void onCardClick(Take take) {}
        public void onCardLongPress(Take take) {}
        public void onPlay(Take take) {}
        public void onFavorite(Take take) {}
        public void onRename(Take take) {}
        public void onExport(Take take) {}
        public void onEdit(Take take) {}
        public void onDelete(Take take) {}
        public void onCancelConversion(Take take) {}
        public void onSeek(Take take, long posMs) {}
    }

    private static Take roomTake(long id, String title) {
        return new Take(id, title, 60_000L, System.currentTimeMillis(),
                "1.0 MB", "M4A", false, "/tmp/" + title + ".m4a");
    }

    private static Take convertingTake(long id) {
        Take t = new Take(id, "Converting", 60_000L,
                System.currentTimeMillis(), "", "M4A", false, "");
        t.converting = true;
        t.convProgress = 10;
        t.convFormat = "M4A";
        t.convJobId = "job-x";
        t.convRawPath = "/tmp/x.wav";
        t.dateLabel = "Converting to M4A • 10%";
        return t;
    }

    @Test
    public void appearProgressDisappearSurvivesLayouts() {
        Activity a = activity();
        RecyclerView rv = new RecyclerView(a);
        a.setContentView(rv);
        rv.setLayoutManager(new LinearLayoutManager(a));
        TakesAdapter adapter = new TakesAdapter(new NopListener());
        rv.setAdapter(adapter);

        List<Take> room = new ArrayList<>();
        room.add(roomTake(101, "One"));
        room.add(roomTake(102, "Two"));
        room.add(roomTake(103, "Three"));

        layout(rv);
        adapter.setTakes(new ArrayList<>(room));
        layout(rv);

        // Job appears: converting card pinned at top.
        Take conv = convertingTake(-4294967297L);
        List<Take> withConv = new ArrayList<>();
        withConv.add(conv);
        withConv.addAll(room);
        adapter.setTakes(withConv);
        layout(rv);

        // Progress ticks: direct paints + field updates, like the listener.
        for (int pct = 20; pct <= 90; pct += 10) {
            conv.convProgress = pct;
            adapter.paintConvProgress(rv, conv.id, pct, "Converting");
            layout(rv);
        }

        // Job completes: card gone, finished row on top.
        List<Take> done = new ArrayList<>();
        done.add(roomTake(104, "Converting"));
        done.addAll(room);
        adapter.setTakes(done);
        layout(rv);

        assertTrue("survived all layouts", rv.getChildCount() > 0);
    }


    @Test
    public void expandedConvertingCardShowsStatusOnce() {
        Activity a = activity();
        RecyclerView rv = new RecyclerView(a);
        a.setContentView(rv);
        rv.setLayoutManager(new LinearLayoutManager(a));
        TakesAdapter adapter = new TakesAdapter(new NopListener());
        rv.setAdapter(adapter);
        Take conv = convertingTake(-7L);
        adapter.setTakes(new ArrayList<>(java.util.Collections.singletonList(conv)));
        adapter.setExpandedId(conv.id);
        layout(rv);

        int occurrences = 0;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View card = rv.getChildAt(i);
            occurrences += countText(card, "Converting to M4A");
        }
        assertEquals("conversion status must appear once on an expanded card",
                1, occurrences);
    }

    private static int countText(View v, String needle) {
        int hits = 0;
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.toString().contains(needle)) hits++;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                hits += countText(g.getChildAt(i), needle);
            }
        }
        return hits;
    }

    private static void layout(RecyclerView rv) {
        rv.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        rv.layout(0, 0, 320, 640);
    }
}
