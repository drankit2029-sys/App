package com.example.hello;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory store for takes, seeded with demo content. */
public final class TakesRepository {

    /** Removed takes + their positions, so a delete can be undone. */
    public static final class RestoreOp {
        public final List<Take> takes = new ArrayList<>();
        public final List<Integer> positions = new ArrayList<>();
    }

    private static final List<Take> TAKES = new ArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(100);
    private static boolean seeded = false;

    private TakesRepository() {}

    public static synchronized void seedIfNeeded() {
        if (seeded) return;
        seeded = true;
        long now = System.currentTimeMillis();
        long h = 3600_000L;
        long d = 24 * h;
        TAKES.add(new Take(1, "Midnight Vocals — Take 7", 252000, now - 2 * h, "4.1 MB", "WAV", true, null));
        TAKES.add(new Take(2, "Quick Humming Idea", 44000, now - 40 * 60_000L, "0.8 MB", "M4A", false, null));
        TAKES.add(new Take(3, "Podcast Intro Draft", 107000, now - 20 * h, "1.8 MB", "M4A", false, null));
        TAKES.add(new Take(4, "Guitar Riff Idea", 38000, now - 3 * d, "0.7 MB", "WAV", true, null));
        TAKES.add(new Take(5, "Voice Memo — Meeting", 725000, now - 9 * d, "11.9 MB", "M4A", false, null));
        TAKES.add(new Take(6, "Drum Loop Test", 56000, now - 15 * d, "1.0 MB", "WAV", false, null));
    }

    public static synchronized List<Take> all() {
        return new ArrayList<>(TAKES);
    }

    public static synchronized Take find(long id) {
        for (Take t : TAKES) {
            if (t.id == id) return t;
        }
        return null;
    }

    public static synchronized Take addRecording(File file, long durationMs) {
        long mbTenths = Math.max(1, file.length() / 102400);
        String size = String.format(Locale.US, "%.1f MB", mbTenths / 10f);
        String name = file.getName();
        String ext = "M4A";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            ext = name.substring(dot + 1).toUpperCase(Locale.US);
        }
        String base = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ');
        Take t = new Take(IDS.getAndIncrement(), base, durationMs, System.currentTimeMillis(),
                size, ext, false, file.getAbsolutePath());
        TAKES.add(0, t);
        return t;
    }

    public static synchronized void toggleFavorite(long id) {
        Take t = find(id);
        if (t != null) t.favorite = !t.favorite;
    }

    public static synchronized void rename(long id, String newTitle) {
        Take t = find(id);
        if (t != null && newTitle != null && !newTitle.trim().isEmpty()) {
            t.title = newTitle.trim();
        }
    }

    public static synchronized void delete(long id) {
        for (int i = 0; i < TAKES.size(); i++) {
            if (TAKES.get(i).id == id) {
                Take t = TAKES.remove(i);
                deleteBackingFile(t);
                return;
            }
        }
    }

    public static synchronized RestoreOp deleteMany(Set<Long> ids) {
        RestoreOp op = new RestoreOp();
        for (int i = TAKES.size() - 1; i >= 0; i--) {
            if (ids.contains(TAKES.get(i).id)) {
                op.takes.add(0, TAKES.get(i));
                op.positions.add(0, i);
                deleteBackingFile(TAKES.get(i));
                TAKES.remove(i);
            }
        }
        return op;
    }

    public static synchronized void restore(RestoreOp op) {
        if (op == null) return;
        for (int i = 0; i < op.takes.size(); i++) {
            int pos = Math.min(op.positions.get(i), TAKES.size());
            TAKES.add(pos, op.takes.get(i));
        }
    }

    private static void deleteBackingFile(Take t) {
        if (t.filePath != null) {
            // noinspection ResultOfMethodCallIgnored
            new File(t.filePath).delete();
        }
    }
}
