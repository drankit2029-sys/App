package com.example.hello;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A single recorded take. filePath is null for built-in demo takes. */
public class Take {
    public final long id;
    public String title;
    public long durationMs;
    public long timestamp;
    public String dateLabel;
    public String sizeLabel;
    public String format;
    public boolean favorite;
    public String filePath;

    public Take(long id, String title, long durationMs, long timestamp,
               String sizeLabel, String format, boolean favorite, String filePath) {
        this.id = id;
        this.title = title;
        this.durationMs = durationMs;
        this.timestamp = timestamp;
        this.dateLabel = formatDate(timestamp);
        this.sizeLabel = sizeLabel;
        this.format = format;
        this.favorite = favorite;
        this.filePath = filePath;
    }

    public boolean hasFile() {
        return filePath != null && new java.io.File(filePath).exists();
    }

    public static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String formatDate(long timestamp) {
        try {
            return new SimpleDateFormat("MMM d • h:mm a", Locale.US).format(new Date(timestamp));
        } catch (Exception e) {
            return "";
        }
    }
}
