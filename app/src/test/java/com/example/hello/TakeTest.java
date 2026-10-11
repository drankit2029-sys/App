package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TakeTest {

    @Test
    public void formatDuration() {
        assertEquals("00:00", Take.formatDuration(0));
        assertEquals("00:44", Take.formatDuration(44_000));
        assertEquals("04:12", Take.formatDuration(252_000));
        assertEquals("12:05", Take.formatDuration(725_000));
    }

    @Test
    public void formatDateNeverEmpty() {
        String d = Take.formatDate(System.currentTimeMillis());
        assertTrue(d != null && !d.isEmpty());
    }

    @Test
    public void defaults() {
        Take t = new Take(7, "X", 1000, 0, "0.1 MB", "M4A", false, null);
        assertEquals("recorded", t.origin);
        assertEquals(0, t.lastPositionMs);
        assertTrue(t.markersJson == null);
    }

    @Test
    public void entityNullsNormalize() {
        // Legacy rows can hold null columns; the library dereferences
        // title/format on the main thread, so toTake must never pass nulls.
        TakeEntity e = new TakeEntity();
        e.id = 99;
        e.title = null;
        e.format = null;
        Take t = e.toTake();
        assertTrue(t.title != null && !t.title.isEmpty());
        assertTrue(t.format != null && !t.format.isEmpty());
        // Lower-casing (filter) and comparison (sort) must not throw.
        assertTrue(t.title.toLowerCase().length() >= 0);
        assertTrue(t.title.compareToIgnoreCase("zzz") != 0);
    }
}
