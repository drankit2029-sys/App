package com.example.hello;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory store for takes, seeded with demo content. */
public final class TakesRepository {
    private static final List<Take> TAKES = new ArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(100);
    private static boolean seeded = false;

    private TakesRepository() {}

    public static synchronized void seedIfNeeded() {
        if (seeded) return;
        seeded = true;
        TAKES.add(new Take(1, "Midnight Vocals — Take 7", 252000, "Oct 9 • 6:42 PM", "4.1 MB", true, null));
        TAKES.add(new Take(2, "Podcast Intro Draft", 107000, "Oct 8 • 11:15 AM", "1.8 MB", false, null));
        TAKES.add(new Take(3, "Guitar Riff Idea", 38000, "Oct 7 • 9:03 PM", "0.7 MB", true, null));
        TAKES.add(new Take(4, "Voice Memo — Meeting", 725000, "Oct 6 • 2:30 PM", "11.9 MB", false, null));
        TAKES.add(new Take(5, "Drum Loop Test", 56000, "Oct 5 • 5:48 PM", "1.0 MB", false, null));
    }

    public static synchronized List<Take> all() {
        return new ArrayList<>(TAKES);
    }

    public static synchronized void addFirst(Take take) {
        TAKES.add(0, take);
    }

    public static synchronized Take addRecording(File file, long durationMs, String dateLabel) {
        long mbTenths = Math.max(1, file.length() / 102400);
        String size = String.format("%.1f MB", mbTenths / 10f);
        String base = file.getName().replaceFirst("\\.[^.]+$", "").replace('_', ' ');
        Take t = new Take(IDS.getAndIncrement(), base, durationMs, dateLabel, size, false,
                file.getAbsolutePath());
        TAKES.add(0, t);
        return t;
    }

    public static synchronized void toggleFavorite(long id) {
        for (Take t : TAKES) {
            if (t.id == id) {
                t.favorite = !t.favorite;
                return;
            }
        }
    }

    public static synchronized void delete(long id) {
        for (int i = 0; i < TAKES.size(); i++) {
            if (TAKES.get(i).id == id) {
                Take t = TAKES.remove(i);
                if (t.filePath != null) {
                    // noinspection ResultOfMethodCallIgnored
                    new File(t.filePath).delete();
                }
                return;
            }
        }
    }
}
