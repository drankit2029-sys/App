package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TakeDaoTest {

    private TakeDatabase db;

    @Before
    public void open() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = TakeDatabase.inMemory(ctx);
    }

    @After
    public void close() {
        try {
            db.close();
        } catch (Exception ignored) {}
    }

    private TakeEntity row(long id, String title) {
        TakeEntity e = new TakeEntity();
        e.id = id;
        e.title = title;
        e.durationMs = 1000;
        e.timestamp = id;
        e.dateLabel = "d";
        e.sizeLabel = "0.1 MB";
        e.format = "M4A";
        e.favorite = false;
        e.filePath = null;
        e.origin = "recorded";
        e.lastPositionMs = 0;
        e.markersJson = null;
        return e;
    }

    @Test
    public void upsertAndReadBack() {
        db.takes().upsert(row(1, "Hello"));
        TakeEntity got = db.takes().byId(1);
        assertEquals("Hello", got.title);
        assertEquals("recorded", got.origin);
    }

    @Test
    public void replaceOnConflict() {
        db.takes().upsert(row(1, "Old"));
        db.takes().upsert(row(1, "New"));
        assertEquals("New", db.takes().byId(1).title);
        assertEquals(1, db.takes().count());
    }

    @Test
    public void orderedNewestFirst() {
        db.takes().upsertAll(Arrays.asList(row(1, "Old"), row(2, "New")));
        List<TakeEntity> all = db.takes().allOrdered();
        assertEquals(2, all.size());
        assertEquals(2, all.get(0).id);
    }

    @Test
    public void updatePosition() {
        db.takes().upsert(row(5, "Long"));
        db.takes().updatePosition(5, 12345);
        assertEquals(12345, db.takes().byId(5).lastPositionMs);
    }

    @Test
    public void deleteByIds() {
        db.takes().upsertAll(Arrays.asList(row(1, "A"), row(2, "B")));
        db.takes().deleteByIds(Arrays.asList(1L));
        assertNull(db.takes().byId(1));
        assertEquals("B", db.takes().byId(2).title);
    }

    @Test
    public void takeRoundTripKeepsPhase0Fields() {
        Take t = new Take(9, "T", 2000, 1000, "0.2 MB", "WAV", true, "/x.wav");
        t.origin = "edited";
        t.lastPositionMs = 500;
        t.markersJson = "{\"m\":[]}";
        db.takes().upsert(TakeEntity.fromTake(t));
        Take back = db.takes().byId(9).toTake();
        assertEquals("edited", back.origin);
        assertEquals(500, back.lastPositionMs);
        assertEquals("{\"m\":[]}", back.markersJson);
        assertTrue(back.favorite);
    }

    @Test
    public void entityDefaultsAreSane() {
        Take t = new Take(3, "T", 1, 1, "s", "M4A", false, null);
        TakeEntity e = TakeEntity.fromTake(t);
        assertEquals("recorded", e.origin);
        assertFalse(e.favorite);
    }
}
