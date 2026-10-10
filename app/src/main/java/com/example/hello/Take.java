package com.example.hello;

/** A single recorded take. filePath is null for built-in demo takes. */
public class Take {
    public final long id;
    public String title;
    public long durationMs;
    public String dateLabel;
    public String sizeLabel;
    public boolean favorite;
    public String filePath;

    public Take(long id, String title, long durationMs, String dateLabel,
               String sizeLabel, boolean favorite, String filePath) {
        this.id = id;
        this.title = title;
        this.durationMs = durationMs;
        this.dateLabel = dateLabel;
        this.sizeLabel = sizeLabel;
        this.favorite = favorite;
        this.filePath = filePath;
    }

    public static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format("%02d:%02d", m, s);
    }
}
