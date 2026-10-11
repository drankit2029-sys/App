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
}
