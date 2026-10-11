package com.example.hello;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Persisted library row. The in-memory {@link Take} stays the UI model;
 * this is its durable mirror so the library survives process death.
 */
@Entity(tableName = "takes")
public class TakeEntity {
    @PrimaryKey
    public long id;

    public String title;
    public long durationMs;
    public long timestamp;
    public String dateLabel;
    public String sizeLabel;
    public String format;
    public boolean favorite;
    public String filePath;

    /** recorded | imported | edited | demo | recovered. */
    public String origin;

    @ColumnInfo(defaultValue = "0")
    public long lastPositionMs;

    /** Phase-2 marker payload (JSON); reserved so the schema never migrates. */
    public String markersJson;

    public static TakeEntity fromTake(Take t) {
        TakeEntity e = new TakeEntity();
        e.id = t.id;
        e.title = t.title;
        e.durationMs = t.durationMs;
        e.timestamp = t.timestamp;
        e.dateLabel = t.dateLabel;
        e.sizeLabel = t.sizeLabel;
        e.format = t.format;
        e.favorite = t.favorite;
        e.filePath = t.filePath;
        e.origin = t.origin != null ? t.origin : "recorded";
        e.lastPositionMs = t.lastPositionMs;
        e.markersJson = t.markersJson;
        return e;
    }

    public Take toTake() {
        // Old installs can hold null columns; the library dereferences
        // title/format on the main thread (filter, sort), so normalize here
        // instead of trusting history.
        Take t = new Take(id, title != null ? title : "Take", durationMs, timestamp,
                sizeLabel, format != null ? format : "M4A", favorite, filePath);
        t.origin = origin != null ? origin : "recorded";
        t.lastPositionMs = lastPositionMs;
        t.markersJson = markersJson;
        return t;
    }
}
