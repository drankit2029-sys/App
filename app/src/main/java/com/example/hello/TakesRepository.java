package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Library store. The in-memory list stays the synchronous UI model, but
 * every row is mirrored into Room, so the library (titles, favorites,
 * positions, lineage) survives process death. All database work runs on a
 * single background thread, in order, so callers never block and writes
 * can never overtake the initial load.
 */
public final class TakesRepository {

    /** Removed takes + their positions, so a delete can be undone. */
    public static final class RestoreOp {
        public final List<Take> takes = new ArrayList<>();
        public final List<Integer> positions = new ArrayList<>();
        /** Parallel to takes: trash location, or null if hard-deleted. */
        public final List<String> trashPaths = new ArrayList<>();
    }

    /** Startup reconcile outcome, for the one summary popup. */
    public static final class ReconcileResult {
        public int adopted;
        public int pruned;
        public int rawsCleaned;
        public long trashFreedBytes;
    }

    /** Trash retention: week-old soft deletes are reaped on startup. */
    private static final long TRASH_MAX_AGE_MS = 7L * 24 * 3600_000L;

    private static final List<Take> TAKES = new ArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(100);
    private static final String PREFS = "takeone";
    private static final String KEY_SEEDED = "library_seeded_once";
    /**
     * Probe attempts that never reported back. A native parser death
     * mid-probe leaves the mark behind (nothing after it runs), so the next
     * launch skips that file instead of dying on it again.
     */
    private static final String KEY_PROBE_TRIED = "probe_tried_paths";

    public interface Listener {
        void onTakesChanged();
    }

    private static final Set<Listener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArraySet<>();
    private static volatile boolean ready;
    private static final List<Runnable> READY_QUEUE = new ArrayList<>();

    public static void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void fireChanged() {
        for (Listener l : LISTENERS) {
            try {
                l.onTakesChanged();
            } catch (Exception ignored) {}
        }
    }
    private static TakeDatabase db;
    private static Context app;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile ReconcileResult lastReconcile;

    private TakesRepository() {}

    // ---- persistent 32-bit float master storage ----

    /**
     * Hidden directory inside {@code takes/} that holds persistent 32-bit
     * float masters for non-WAV32 takes. Being a subdirectory, it is ignored
     * by {@link #reconcile(Context)}'s top-level orphan adoption and by
     * {@link RecordingEngine#scanUnfinished(Context)}.
     */
    public static File floatMastersDir(File takesDir) {
        return new File(takesDir, ".float32");
    }

    /**
     * Companion 32-bit float master path for {@code takeFile}, located at
     * {@code takes/.float32/<takeFile.getName()>.f32.wav}.
     */
    public static File floatMasterFor(File takeFile) {
        if (takeFile == null) return null;
        File parent = takeFile.getParentFile();
        if (parent == null) return null;
        return new File(floatMastersDir(parent),
                takeFile.getName() + ".f32.wav");
    }

    /**
     * Returns the 32-bit float WAV file to use when opening {@code takeFile}
     * for editing:
     * <ul>
     *   <li>If {@code takeFile} is already a 48 kHz 32-bit float WAV (e.g. a
     *       WAV32 take or a converting take's raw), returns {@code takeFile}
     *       itself.</li>
     *   <li>If a persisted 32-bit float master exists in {@code .float32/},
     *       returns that master file.</li>
     *   <li>Otherwise returns {@code takeFile} (e.g. an imported recording
     *       being edited for the first time).</li>
     * </ul>
     */
    public static File resolveFloatMaster(File takeFile) {
        if (takeFile == null || !takeFile.exists()) return takeFile;
        if (AudioImporter.isNativeFloatWav(takeFile)) return takeFile;
        File master = floatMasterFor(takeFile);
        if (master != null && master.exists()
                && AudioImporter.isNativeFloatWav(master)) {
            return master;
        }
        return takeFile;
    }

    /** True when {@code takeFile} is itself float32 or has a valid float32 master. */
    public static boolean hasFloatMaster(File takeFile) {
        if (takeFile == null || !takeFile.exists()) return false;
        if (AudioImporter.isNativeFloatWav(takeFile)) return true;
        File master = floatMasterFor(takeFile);
        return master != null && master.exists()
                && AudioImporter.isNativeFloatWav(master);
    }

    /**
     * Adopts a finalized recording/edit {@code rawFloatWav} as the persistent
     * 32-bit float master for {@code finalTakeFile}.
     *
     * <p>When {@code finalTakeFile} is already a 48 kHz 32-bit float WAV
     * (saved as {@link SaveFormat#WAV32}), {@code finalTakeFile} is itself the
     * 32-bit float file, so {@code rawFloatWav} (and any stale companion in
     * {@code .float32/}) is deleted to avoid storing redundant copies.
     */
    public static void adoptFloatMaster(File rawFloatWav, File finalTakeFile) {
        if (rawFloatWav == null) return;
        try {
            // noinspection ResultOfMethodCallIgnored
            new File(rawFloatWav.getAbsolutePath() + ".pending").delete();
            EditEngine.clearSessionUndo(rawFloatWav);
        } catch (Exception ignored) {}
        if (!rawFloatWav.exists()) return;
        if (finalTakeFile == null || !finalTakeFile.exists()) {
            // noinspection ResultOfMethodCallIgnored
            rawFloatWav.delete();
            return;
        }
        if (rawFloatWav.getAbsolutePath().equals(
                finalTakeFile.getAbsolutePath())) {
            deleteFloatMaster(finalTakeFile);
            return;
        }
        if (AudioImporter.isNativeFloatWav(finalTakeFile)) {
            // Final file is already 32-bit float (WAV32): never keep a second
            // redundant 32-bit float file on disk.
            // noinspection ResultOfMethodCallIgnored
            rawFloatWav.delete();
            deleteFloatMaster(finalTakeFile);
            return;
        }
        File master = floatMasterFor(finalTakeFile);
        if (master == null) {
            // noinspection ResultOfMethodCallIgnored
            rawFloatWav.delete();
            return;
        }
        try {
            File dir = master.getParentFile();
            if (dir != null && !dir.exists()) {
                // noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            if (master.exists()) {
                // noinspection ResultOfMethodCallIgnored
                master.delete();
            }
            if (rawFloatWav.renameTo(master)) {
                return;
            }
            copyFileContents(rawFloatWav, master);
        } catch (Exception ignored) {
            try {
                if (master.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    master.delete();
                }
            } catch (Exception ignored2) {}
        } finally {
            if (rawFloatWav.exists()) {
                // noinspection ResultOfMethodCallIgnored
                rawFloatWav.delete();
            }
        }
    }

    /**
     * Persists a copy of {@code floatWavSession} as the 32-bit float master
     * for {@code finalTakeFile}. Used the first time the user presses Edit on
     * an imported take so subsequent edits reuse the persisted 32-bit float
     * file without re-converting.
     */
    public static void saveFloatMasterCopy(File floatWavSession,
                                           File finalTakeFile) {
        if (floatWavSession == null || !floatWavSession.exists()
                || finalTakeFile == null || !finalTakeFile.exists()) {
            return;
        }
        if (AudioImporter.isNativeFloatWav(finalTakeFile)) {
            // Already 32-bit float: do not create a redundant copy.
            return;
        }
        File master = floatMasterFor(finalTakeFile);
        if (master == null) return;
        File dir = master.getParentFile();
        if (dir != null && !dir.exists()) {
            // noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File tmp = new File(dir, master.getName() + ".tmp");
        try {
            copyFileContents(floatWavSession, tmp);
            if (!finalTakeFile.exists()) {
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return;
            }
            if (master.exists()) {
                // noinspection ResultOfMethodCallIgnored
                master.delete();
            }
            if (!tmp.renameTo(master)) {
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {
            try {
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } catch (Exception ignored2) {}
        }
    }

    /** Deletes the companion 32-bit float master for {@code takeFile}, if any. */
    public static void deleteFloatMaster(File takeFile) {
        File master = floatMasterFor(takeFile);
        if (master != null && master.exists()) {
            // noinspection ResultOfMethodCallIgnored
            master.delete();
        }
    }

    private static void copyFileContents(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        }
    }

    // ---- lifecycle ----

    /** Idempotent. Loads rows, seeds once, reconciles disk, then fires ready. */
    public static synchronized void init(Context ctx) {
        if (app != null) return;
        app = ctx.getApplicationContext();
        db = TakeDatabase.get(app);
        IO.execute(TakesRepository::loadReconcileSeed);
    }

    public static boolean isReady() {
        return ready;
    }

    /** Runs now (main thread) if ready, else right after load finishes. */
    public static void onReady(Runnable r) {
        boolean run;
        synchronized (TakesRepository.class) {
            run = ready;
            if (!run) READY_QUEUE.add(r);
        }
        if (run) r.run();
    }

    public static ReconcileResult lastReconcile() {
        return lastReconcile;
    }

    private static void loadReconcileSeed() {
        List<TakeEntity> rows = new ArrayList<>();
        try {
            rows = db.takes().allOrdered();
        } catch (Exception ignored) {}
        List<Runnable> queued;
        synchronized (TakesRepository.class) {
            // A4-06: the DB load runs on the IO executor while a save or an
            // import can already have called addRecording()/addTake() on
            // another thread. Blanket-clearing would wipe those entries
            // (and could hand out a colliding id), so keep anything already
            // in memory and only ever move the id counter forward.
            List<Take> pending = new ArrayList<>(TAKES);
            TAKES.clear();
            long max = 99;
            Set<Long> seenIds = new HashSet<>();
            for (TakeEntity e : rows) {
                try {
                    Take t = e.toTake();
                    TAKES.add(t);
                    seenIds.add(t.id);
                    if (t.id > max) max = t.id;
                } catch (Exception ignored) {}
            }
            for (Take t : pending) {
                if (seenIds.contains(t.id)) {
                    // A save raced the load and reused an id the database
                    // already owns. Re-id the in-memory copy so the adapter's
                    // stable ids stay unique and the write-through inserts a
                    // distinct row instead of replacing the existing one.
                    Take copy = new Take(++max, t.title, t.durationMs,
                            t.timestamp, t.sizeLabel, t.format, t.favorite,
                            t.filePath);
                    copy.origin = t.origin;
                    copy.lastPositionMs = t.lastPositionMs;
                    copy.markersJson = t.markersJson;
                    TAKES.add(copy);
                    seenIds.add(copy.id);
                    persistAsync(copy);
                } else {
                    TAKES.add(t);
                    seenIds.add(t.id);
                    if (t.id > max) max = t.id;
                }
            }
            IDS.set(Math.max(IDS.get(), max + 1));
            sortByTimestampDesc();
        }
        CrashLog.breadcrumb(app, "repo rows=" + TAKES.size());
        ReconcileResult rr = new ReconcileResult();
        try {
            CrashLog.breadcrumb(app, "reconcile start");
            rr = reconcile(app);
            CrashLog.breadcrumb(app, "reconcile end adopted=" + rr.adopted
                    + " pruned=" + rr.pruned + " raws=" + rr.rawsCleaned);
        } catch (Exception e) {
            // A poisoned disk state (truncated files, I/O errors) must never
            // take the app down on every launch: degrade to whatever loaded
            // from the database and still signal ready so the UI can open.
            android.util.Log.w("TakesRepository", "reconcile failed", e);
            CrashLog.breadcrumb(app, "reconcile FAILED: " + e);
        }
        lastReconcile = rr;
        // Interrupted background conversions resume from their sidecars;
        // crash garbage (orphan parts) is swept here, before any capture.
        // Every resurrected job is (re)started: the worker serialises them,
        // so starting only the first would strand the rest until the next
        // process start that happens to resweep.
        try {
            List<ConversionJob> resume = ConversionJobs.sweep(app);
            for (ConversionJob job : resume) {
                ConversionService.startJob(app, job.jobId);
            }
        } catch (Exception e) {
            CrashLog.breadcrumb(app, "conversion sweep FAILED: " + e);
        }
        synchronized (TakesRepository.class) {
            ready = true;
            queued = new ArrayList<>(READY_QUEUE);
            READY_QUEUE.clear();
        }
        android.os.Handler main = new android.os.Handler(
                android.os.Looper.getMainLooper());
        for (Runnable r : queued) main.post(r);
    }

    private static void sortByTimestampDesc() {
        TAKES.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
    }

    // ---- reads (synchronous, in-memory) ----

    public static synchronized List<Take> all() {
        return new ArrayList<>(TAKES);
    }

    public static synchronized Take find(long id) {
        for (Take t : TAKES) {
            if (t.id == id) return t;
        }
        return null;
    }

    // ---- mutations (synchronous cache + ordered async write-through) ----

    public static synchronized Take addRecording(File file, long durationMs) {
        Take t = buildTake(file, durationMs, null, null);
        TAKES.add(0, t);
        persistAsync(t);
        fireChanged();
        return t;
    }

    public static synchronized Take addRecording(File file, long durationMs,
                                                 String title, String format) {
        Take t = buildTake(file, durationMs, title, format);
        TAKES.add(0, t);
        persistAsync(t);
        fireChanged();
        return t;
    }

    /** Inserts an imported audio file without pre-converting to 32-bit float. */
    public static synchronized Take addImported(File file, long durationMs,
                                                String title, String format) {
        Take t = buildTake(file, durationMs, title, format);
        t.origin = "imported";
        TAKES.add(0, t);
        persistAsync(t);
        fireChanged();
        return t;
    }

    /** Used by reconcile + tests to insert a fully-formed take. */
    public static synchronized Take addTake(Take t) {
        TAKES.add(t);
        sortByTimestampDesc();
        persistAsync(t);
        fireChanged();
        return t;
    }

    /**
     * Immediately deletes a take (and its backing file + 32-bit float master)
     * when the user chooses Replace in the editor, so the obsolete files are
     * removed right away before the new conversion starts.
     */
    public static synchronized boolean deleteImmediateForReplace(long targetId) {
        if (targetId < 0) {
            targetId = ConversionJobs.cancelForReplace(app, targetId);
        }
        if (targetId <= 0) return false;
        for (int i = 0; i < TAKES.size(); i++) {
            if (TAKES.get(i).id == targetId) {
                Take t = TAKES.remove(i);
                deleteBackingFile(t);
                deleteAsync(targetId);
                fireChanged();
                return true;
            }
        }
        return false;
    }

    public static synchronized Take replaceRecording(long originalId,
                                                     File saved,
                                                     long durationMs,
                                                     String title,
                                                     String format) {
        long targetId = originalId;
        if (targetId < 0) {
            targetId = ConversionJobs.cancelForReplace(app, targetId);
        }
        if (targetId > 0) {
            deleteImmediateForReplace(targetId);
        }
        return addRecording(saved, durationMs, title, format);
    }

    private static Take buildTake(File file, long durationMs,
                                  String title, String format) {
        String size = Dsp.sizeLabel(file.length());
        String name = file.getName();
        String ext = "M4A";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            ext = name.substring(dot + 1).toUpperCase(Locale.US);
        }
        String base = Dsp.prettyFileTitle(name);
        String t = title != null ? title.trim() : "";
        if (t.isEmpty()) t = base;
        String f = format != null ? format.trim().toUpperCase(Locale.US) : "";
        if (f.isEmpty()) f = ext;
        Take take = new Take(IDS.getAndIncrement(), t, durationMs,
                System.currentTimeMillis(), size, f, false, file.getAbsolutePath());
        take.origin = "recorded";
        return take;
    }

    public static synchronized void toggleFavorite(long id) {
        Take t = find(id);
        if (t != null) {
            t.favorite = !t.favorite;
            persistAsync(t);
        }
    }

    public static synchronized void rename(long id, String newTitle) {
        Take t = find(id);
        if (t != null && newTitle != null && !newTitle.trim().isEmpty()) {
            t.title = newTitle.trim();
            persistAsync(t);
        }
    }

    public static synchronized void updatePosition(long id, long posMs) {
        Take t = find(id);
        if (t == null) return;
        t.lastPositionMs = Math.max(0, posMs);
        final long pid = id;
        final long ppos = t.lastPositionMs;
        IO.execute(() -> {
            try {
                if (db != null) db.takes().updatePosition(pid, ppos);
            } catch (Exception ignored) {}
        });
    }

    public static synchronized void delete(long id) {
        ConversionJobs.cancelForDeletedTargetTake(app, id);
        for (int i = 0; i < TAKES.size(); i++) {
            if (TAKES.get(i).id == id) {
                Take t = TAKES.remove(i);
                trashBackingFile(t);
                deleteAsync(id);
                return;
            }
        }
    }

    public static synchronized RestoreOp deleteMany(Set<Long> ids) {
        return deleteManyInternal(ids, true);
    }

    private static synchronized RestoreOp deleteManyInternal(
            Set<Long> ids, boolean cancelReplacingJobs) {
        RestoreOp op = new RestoreOp();
        if (ids == null || ids.isEmpty()) return op;
        if (cancelReplacingJobs) {
            for (Long id : ids) {
                if (id != null && id > 0) {
                    ConversionJobs.cancelForDeletedTargetTake(app, id);
                }
            }
        }
        List<Long> gone = new ArrayList<>();
        for (int i = TAKES.size() - 1; i >= 0; i--) {
            if (ids.contains(TAKES.get(i).id)) {
                op.takes.add(0, TAKES.get(i));
                op.positions.add(0, i);
                op.trashPaths.add(0, trashBackingFile(TAKES.get(i)));
                gone.add(TAKES.get(i).id);
                TAKES.remove(i);
            }
        }
        if (!gone.isEmpty()) {
            final List<Long> idsCopy = new ArrayList<>(gone);
            IO.execute(() -> {
                try {
                    if (db != null) db.takes().deleteByIds(idsCopy);
                } catch (Exception ignored) {}
            });
        }
        return op;
    }

    public static synchronized void restore(RestoreOp op) {
        if (op == null) return;
        List<TakeEntity> rows = new ArrayList<>();
        for (int i = 0; i < op.takes.size(); i++) {
            int pos = Math.min(op.positions.get(i), TAKES.size());
            Take t = op.takes.get(i);
            // Bring the audio back too when it went through the trash.
            if (i < op.trashPaths.size() && op.trashPaths.get(i) != null
                    && t.filePath != null) {
                // A4-09: the restored file may land on a uniquified name, so
                // follow it and persist the real path, otherwise the row
                // points at a file that is not there.
                File trashedFile = new File(op.trashPaths.get(i));
                File back = Dsp.restoreFromTrash(
                        trashedFile, new File(t.filePath));
                if (back != null) {
                    t.filePath = back.getAbsolutePath();
                    // Restore companion 32-bit float master if one was trashed
                    // alongside this take.
                    File trashedMaster = new File(trashedFile.getParentFile(),
                            trashedFile.getName() + ".f32.wav");
                    if (trashedMaster.exists()) {
                        File restoredMaster = floatMasterFor(back);
                        if (restoredMaster != null) {
                            File mDir = restoredMaster.getParentFile();
                            if (mDir != null && !mDir.exists()) {
                                // noinspection ResultOfMethodCallIgnored
                                mDir.mkdirs();
                            }
                            if (restoredMaster.exists()) {
                                // noinspection ResultOfMethodCallIgnored
                                restoredMaster.delete();
                            }
                            if (!trashedMaster.renameTo(restoredMaster)) {
                                // noinspection ResultOfMethodCallIgnored
                                trashedMaster.delete();
                            }
                        }
                    }
                }
            }
            TAKES.add(pos, t);
            rows.add(TakeEntity.fromTake(t));
        }
        if (!rows.isEmpty()) {
            final List<TakeEntity> copy = new ArrayList<>(rows);
            IO.execute(() -> {
                try {
                    if (db != null) db.takes().upsertAll(copy);
                } catch (Exception ignored) {}
            });
        }
    }

    private static void persistAsync(Take t) {
        final TakeEntity e = TakeEntity.fromTake(t);
        IO.execute(() -> {
            try {
                if (db != null) db.takes().upsert(e);
            } catch (Exception ignored) {}
        });
    }

    private static void deleteAsync(long id) {
        IO.execute(() -> {
            try {
                if (db != null) db.takes().deleteById(id);
            } catch (Exception ignored) {}
        });
    }

    /**
     * Soft-delete: the file moves to .trash so undo restores audio, not
     * just the entry. Also moves any companion 32-bit float master into
     * .trash so undo preserves instant editing and expiry reaps both.
     * Falls back to a straight delete when the move fails.
     *
     * @return trash location, or null when hard-deleted / no file.
     */
    private static String trashBackingFile(Take t) {
        if (t.filePath == null) return null;
        File src = new File(t.filePath);
        if (!src.exists()) {
            deleteFloatMaster(src);
            return null;
        }
        try {
            File takesDir = app != null
                    ? RecordingEngine.takesDir(app) : src.getParentFile();
            File master = floatMasterFor(src);
            File trashed = Dsp.moveToTrash(takesDir, src);
            if (trashed != null) {
                if (master != null && master.exists()) {
                    File trashedMaster = new File(trashed.getParentFile(),
                            trashed.getName() + ".f32.wav");
                    if (trashedMaster.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        trashedMaster.delete();
                    }
                    if (!master.renameTo(trashedMaster)) {
                        // noinspection ResultOfMethodCallIgnored
                        master.delete();
                    }
                }
                return trashed.getAbsolutePath();
            }
        } catch (Exception ignored) {}
        deleteBackingFile(t);
        return null;
    }

    private static void deleteBackingFile(Take t) {
        if (t.filePath != null) {
            File f = new File(t.filePath);
            // noinspection ResultOfMethodCallIgnored
            f.delete();
            deleteFloatMaster(f);
        }
    }

    // ---- first-run demo seeding (once ever) ----

    private static boolean seededOnce(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return p.getBoolean(KEY_SEEDED, false);
        } catch (Exception e) {
            return true; // fail closed: never reseed blindly
        }
    }

    private static void markSeeded(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SEEDED, true).apply();
        } catch (Exception ignored) {}
    }

    private static void seedDemos(Context ctx) {
        long now = System.currentTimeMillis();
        long h = 3600_000L;
        long d = 24 * h;
        List<TakeEntity> rows = new ArrayList<>();
        Take[] demos = {
                demo(1, "Midnight Vocals — Take 7", 252000, now - 2 * h, "4.1 MB", "WAV", true),
                demo(2, "Quick Humming Idea", 44000, now - 40 * 60_000L, "0.8 MB", "M4A", false),
                demo(3, "Podcast Intro Draft", 107000, now - 20 * h, "1.8 MB", "M4A", false),
                demo(4, "Guitar Riff Idea", 38000, now - 3 * d, "0.7 MB", "WAV", true),
                demo(5, "Voice Memo — Meeting", 725000, now - 9 * d, "11.9 MB", "M4A", false),
                demo(6, "Drum Loop Test", 56000, now - 15 * d, "1.0 MB", "WAV", false),
        };
        synchronized (TakesRepository.class) {
            for (Take t : demos) {
                TAKES.add(t);
                rows.add(TakeEntity.fromTake(t));
            }
            IDS.set(100);
            sortByTimestampDesc();
        }
        try {
            db.takes().upsertAll(rows);
        } catch (Exception ignored) {}
        markSeeded(ctx);
    }

    private static Take demo(long id, String title, long dur, long ts,
                             String size, String fmt, boolean fav) {
        Take t = new Take(id, title, dur, ts, size, fmt, fav, null);
        t.origin = "demo";
        return t;
    }

    // ---- startup reconcile ----

    private static final String[] AUDIO_EXTS = {
            "wav", "m4a", "3gp", "3ga", "flac", "opus", "webm", "mp3", "ogg",
            "aac",
    };

    private static boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".pending")) return false;
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lower.substring(dot + 1);
        for (String a : AUDIO_EXTS) {
            if (a.equals(ext)) return true;
        }
        return false;
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        // Scratch raws are "<base>.raw.wav"; strip that marker so a finished
        // conversion groups with its leftover raw instead of orphaning it.
        stem = stem.replaceFirst("\\.raw$", "");
        // Old WAV saves landed as "<base> (2).wav" because the raw
        // "<base>.wav" still existed when uniqueFinal() ran. Strip the
        // uniquifier too so legacy leftovers still group.
        return stem.replaceFirst("\\s*\\(\\d+\\)$", "");
    }

    /**
     * Makes disk and database agree: seeds demos once on a truly fresh
     * install, drops rows whose files vanished, adopts or cleans finalized
     * raw leftovers shadowed by a finished conversion, adopts orphaned
     * recordings back into the library, and prunes any redundant or orphaned
     * files in {@code .float32/}. Files with a .pending marker are left
     * alone — the unfinished-take flow owns them.
     */
    static ReconcileResult reconcile(Context ctx) {
        ReconcileResult rr = new ReconcileResult();
        File dir;
        try {
            dir = RecordingEngine.takesDir(ctx);
        } catch (Exception e) {
            return rr;
        }
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];

        // Week-old soft deletes are reaped so the trash can't grow forever.
        // Runs before adoption so a resurrected file is never swept same-boot.
        try {
            rr.trashFreedBytes = Dsp.pruneTrash(Dsp.trashDir(dir), TRASH_MAX_AGE_MS);
        } catch (Exception ignored) {}

        Map<String, File> byPath = new HashMap<>();
        synchronized (TakesRepository.class) {
            for (Take t : TAKES) {
                if (t.filePath != null) byPath.put(t.filePath, new File(t.filePath));
            }
        }

        Set<String> pendingStems = new HashSet<>();
        for (File f : files) {
            // NEW-03 fix: a stem with an interrupted conversion sidecar must
            // be left alone (sweep resumes it); otherwise the raw leftover
            // cleaner or the orphan adopter races the worker.
            if (f.getName().endsWith(".pending")
                    || f.getName().endsWith(".conv.json")) {
                String n = f.getName()
                        .replaceFirst("\\.pending$", "")
                        .replaceFirst("\\.conv\\.json$", "");
                pendingStems.add(stemOf(n));
            }
        }

        // 1) Prune rows whose backing files are gone.
        List<Long> prune = new ArrayList<>();
        synchronized (TakesRepository.class) {
            for (Take t : TAKES) {
                if (t.filePath != null && !(new File(t.filePath).exists())) {
                    prune.add(t.id);
                    deleteFloatMaster(new File(t.filePath));
                }
            }
            for (long id : prune) {
                for (int i = TAKES.size() - 1; i >= 0; i--) {
                    if (TAKES.get(i).id == id) TAKES.remove(i);
                }
            }
        }
        if (!prune.isEmpty()) {
            try {
                db.takes().deleteByIds(prune);
            } catch (Exception ignored) {}
            rr.pruned = prune.size();
        }

        // Refresh the referenced set after pruning.
        Set<String> referenced = new HashSet<>();
        synchronized (TakesRepository.class) {
            for (Take t : TAKES) {
                if (t.filePath != null) referenced.add(t.filePath);
            }
        }

        // 2) Finalized raw leftovers: a float32 .wav sharing a stem with a
        // finished conversion (and no .pending / .conv.json) is a
        // crashed-save remnant. Adopt it into .float32/ for the finished
        // conversion if needed, or delete it if redundant.
        Map<String, List<File>> byStem = new HashMap<>();
        for (File f : files) {
            if (!f.isFile() || !isAudioFile(f.getName())) continue;
            String stem = stemOf(f.getName());
            List<File> g = byStem.get(stem);
            if (g == null) {
                g = new ArrayList<>();
                byStem.put(stem, g);
            }
            g.add(f);
        }
        for (Map.Entry<String, List<File>> e : byStem.entrySet()) {
            List<File> g = e.getValue();
            if (g.size() < 2 || pendingStems.contains(e.getKey())) continue;
            File finalFile = null;
            File floatWav = null;
            for (File f : g) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".wav") && isFloat32Wav(f)
                        && !referenced.contains(f.getAbsolutePath())) {
                    floatWav = f;
                } else {
                    finalFile = f;
                }
            }
            if (finalFile != null && floatWav != null && !referenced.contains(
                    floatWav.getAbsolutePath())) {
                if (!AudioImporter.isNativeFloatWav(finalFile)
                        && !hasFloatMaster(finalFile)) {
                    adoptFloatMaster(floatWav, finalFile);
                    if (!floatWav.exists()) rr.rawsCleaned++;
                } else {
                    // noinspection ResultOfMethodCallIgnored
                    if (floatWav.delete()) rr.rawsCleaned++;
                }
            }
        }

        // 3) Adopt orphans back into the library.
        List<TakeEntity> adopted = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile() || !isAudioFile(f.getName())) continue;
            if (f.getName().contains("_subrep_")) {
                // A crashed Select & Replace snippet is never a standalone
                // take; the parent raw remains the only source of truth.
                RecordingEngine.deletePendingMarker(f);
                // noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            String abs = f.getAbsolutePath();
            if (referenced.contains(abs)) continue;
            if (pendingStems.contains(stemOf(f.getName()))) continue;
            if (!f.exists()) continue;
            Take t = adoptFile(ctx, f);
            if (t == null) continue;
            synchronized (TakesRepository.class) {
                TAKES.add(t);
                referenced.add(abs);
            }
            adopted.add(TakeEntity.fromTake(t));
            rr.adopted++;
        }
        if (!adopted.isEmpty()) {
            try {
                db.takes().upsertAll(adopted);
            } catch (Exception ignored) {}
            synchronized (TakesRepository.class) {
                sortByTimestampDesc();
            }
        }

        // 4) Sweep .float32/ and remove any orphaned, corrupt, or redundant
        // (e.g. parent take is already WAV32 or no longer exists) float32 files.
        try {
            File mastersDir = floatMastersDir(dir);
            File[] masterFiles = mastersDir.listFiles();
            if (masterFiles != null && masterFiles.length > 0) {
                Set<String> validMasterNames = new HashSet<>();
                synchronized (TakesRepository.class) {
                    for (Take t : TAKES) {
                        if (t.filePath == null) continue;
                        File tf = new File(t.filePath);
                        if (tf.exists() && !AudioImporter.isNativeFloatWav(tf)) {
                            validMasterNames.add(tf.getName() + ".f32.wav");
                        }
                    }
                }
                for (File mf : masterFiles) {
                    if (!mf.isFile()
                            || !validMasterNames.contains(mf.getName())
                            || !AudioImporter.isNativeFloatWav(mf)) {
                        // noinspection ResultOfMethodCallIgnored
                        if (mf.delete()) rr.rawsCleaned++;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 5) New installs start with an empty library: no synthetic demos.
        markSeeded(ctx);
        return rr;
    }

    private static boolean hasAnyAudio(File[] files) {
        for (File f : files) {
            if (f.isFile() && isAudioFile(f.getName())) return true;
        }
        return false;
    }

    /** NEW-07 fix: use {@link Dsp#parseWavHeader(java.io.RandomAccessFile)}. */
    private static boolean isFloat32Wav(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            return h.audioFormat == 3 && h.bits == 32;
        } catch (Exception e) {
            return false;
        }
    }

    /** Distinguish integer 24-bit WAV from the standard 16/float badge. */
    static String wavBadge(File f) {
        try (java.io.RandomAccessFile r =
                     new java.io.RandomAccessFile(f, "r")) {
            Dsp.WavHeader h = Dsp.parseWavHeader(r);
            if (h.audioFormat == 1 && h.bits == 24) return "WAV24";
        } catch (Exception ignored) {}
        return "WAV";
    }

    /** True when an ASCII/byte token occurs anywhere in the file. */
    static boolean containsToken(File f, byte[] token) {
        if (f == null || token == null || token.length == 0) return false;
        try (java.io.RandomAccessFile r =
                     new java.io.RandomAccessFile(f, "r")) {
            byte[] buf = new byte[65536];
            int carry = 0;
            long left = r.length();
            r.seek(0);
            while (left > 0) {
                int n = (int) Math.min(buf.length, left);
                r.readFully(buf, 0, n);
                left -= n;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == token[carry]) {
                        if (++carry == token.length) return true;
                    } else {
                        carry = buf[i] == token[0] ? 1 : 0;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static Take adoptFile(Context ctx, File f) {
        try {
            String ext = "";
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (dot > 0) ext = n.substring(dot + 1).toLowerCase(Locale.US);
            long dur = Dsp.wavDurationMs(f);
            if (dur < 0) {
                // Truncated conversion remnants (< 2 KB) are adopted with a
                // fallback duration instead of being fed to the platform
                // parser: never let a half-written file near stagefright.
                // Files whose probe never reported back (native death
                // mid-probe) are skipped the same way, once and for all.
                String abs = f.getAbsolutePath();
                if (f.length() < 2048 || probeTried(ctx, abs)) dur = 1000;
                else dur = probeDuration(ctx, f);
            }
            if (dur < 0) dur = 1000;
            String badge;
            switch (ext) {
                case "wav":
                    badge = wavBadge(f);
                    break;
                case "m4a":
                    badge = "M4A";
                    break;
                case "3gp":
                case "3ga":
                    badge = containsToken(f, new byte[]{'s', 'a', 'w', 'b'})
                            ? "AMR-WB" : "AMR";
                    break;
                case "flac":
                    badge = "FLAC";
                    break;
                case "opus":
                case "webm":
                    badge = "OPUS";
                    break;
                case "mp3":
                    badge = "MP3";
                    break;
                default:
                    badge = ext.toUpperCase(Locale.US);
                    break;
            }
            Take t;
            synchronized (TakesRepository.class) {
                t = new Take(IDS.getAndIncrement(), Dsp.prettyFileTitle(n), dur,
                        Math.max(1, f.lastModified()), Dsp.sizeLabel(f.length()),
                        badge, false, f.getAbsolutePath());
                t.origin = "recovered";
            }
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private static long probeDuration(Context ctx, File f) {
        String abs = f.getAbsolutePath();
        // Marked BEFORE the probe with a synchronous write: if the parser
        // dies natively, nothing after this runs and the mark survives.
        markProbeTried(ctx, abs, true);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            CrashLog.breadcrumb(ctx, "probe " + abs);
            r.setDataSource(abs);
            String d = r.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long ms = (long) Math.max(0, Double.parseDouble(d));
            markProbeTried(ctx, abs, false);
            return ms;
        } catch (Exception e) {
            // Catchable failure (unsupported file, no metadata): allow a
            // retry next launch, it throws the same way every time.
            markProbeTried(ctx, abs, false);
            return -1;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {}
        }
    }

    private static boolean probeTried(Context ctx, String abs) {
        try {
            Set<String> s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY_PROBE_TRIED, null);
            return s != null && s.contains(abs);
        } catch (Exception e) {
            return false;
        }
    }

    private static void markProbeTried(Context ctx, String abs, boolean tried) {
        try {
            SharedPreferences p =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Set<String> cur = p.getStringSet(KEY_PROBE_TRIED, null);
            Set<String> next = cur != null
                    ? new HashSet<>(cur) : new HashSet<String>();
            if (tried) next.add(abs);
            else next.remove(abs);
            SharedPreferences.Editor ed = p.edit();
            ed.putStringSet(KEY_PROBE_TRIED, next);
            // Synchronous: the mark must be on disk before the probe runs.
            // noinspection ApplySharedPref
            ed.commit();
        } catch (Exception ignored) {}
    }
}
