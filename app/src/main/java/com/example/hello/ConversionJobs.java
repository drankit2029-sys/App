package com.example.hello;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Registry for background conversions: enqueue, cancel, observe, and recover.
 *
 * <p>Invariants the rest of the app relies on:
 * <ul>
 *   <li>Raw files under conversion are <b>never written to</b> — only read.
 *       Punch-in edits copy bytes out, so an edit can never invalidate an
 *       in-flight conversion. The only invalidations are delete and
 *       replace, which cancel the job first.</li>
 *   <li>One active job per raw file. Enqueueing for the same raw returns the
 *       existing job instead of doubling the work.</li>
 *   <li>Every state change is persisted to the job sidecar <em>and</em>
 *       fanned out to {@link Listener}s, so the library, notifications and
 *       share warnings stay in sync whether the app is open or not.</li>
 * </ul>
 */
public final class ConversionJobs {

    public interface Listener {
        void onJobsChanged();
    }

    public static final class SaveResult {
        /** True when the file is ready now (incremental output adopted). */
        public final boolean adopted;
        /** Unique immutable ID assigned to (or preserved for) this recording. */
        public final long recordingId;
        public final File file;
        public final String label;
        public final long durationMs;
        public final String title;
        /** Non-null when the save went to the background worker. */
        public final ConversionJob job;

        private SaveResult(boolean adopted, long recordingId, File file,
                           String label, long durationMs, String title,
                           ConversionJob job) {
            this.adopted = adopted;
            this.recordingId = recordingId;
            this.file = file;
            this.label = label;
            this.durationMs = durationMs;
            this.title = title;
            this.job = job;
        }

        static SaveResult adopted(long recordingId, File file, String label,
                                  long durationMs, String title) {
            return new SaveResult(true, recordingId, file, label, durationMs,
                    title, null);
        }

        static SaveResult background(ConversionJob job) {
            return new SaveResult(false, job.recordingId, null, null,
                    job.durationMs, job.title, job);
        }
    }

    private ConversionJobs() {}

    /**
     * How long a save may spend finishing the incremental encoder inline
     * before giving up and converting in the background. Kept small on
     * purpose: a budget overrun is paid as visible latency on the UI thread,
     * and a backlog that cannot drain inside it is handed to the worker
     * anyway.
     */
    private static final long ADOPT_DRAIN_BUDGET_MS = 350L;

    /**
     * Largest unencoded backlog still worth draining inline (~1.5s of audio),
     * which encodes in well under the budget above.
     */
    private static final long MAX_INLINE_TAIL_FRAMES =
            RecordingEngine.SAMPLE_RATE * 3L / 2L;

    private static final Map<String, ConversionJob> ACTIVE = new HashMap<>();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    /** rawPath -> finished library take id, for replace-after-completion. */
    private static final Map<String, Long> COMPLETED = new HashMap<>();
    /** rawPath -> immutable recordingId assigned when enqueued or saved. */
    private static final Map<String, Long> RAW_TO_RECORDING_ID = new HashMap<>();
    /**
     * raws with an in-flight reader. The worker defers its raw adoption/delete
     * while one is open, so an Edit on a converting card cannot have the file
     * pulled out from under AudioImporter mid-decode.
     */
    private static final Map<String, Integer> RAW_READERS =
            new HashMap<>();
    /** Synthetic (negative) take id -> raw path, for edit-replace lookup. */
    private static final Map<Long, String> NEG_TO_RAW = new HashMap<>();
    /**
     * rawPath -> latest user-chosen title from a rename requested while its
     * take was converting. Kept separately from {@link ConversionJob#title}
     * so a rename that arrives after the worker read the title but before the
     * finished take is visible can still be applied exactly once.
     */
    private static final Map<String, String> RENAMED_TITLES = new HashMap<>();

    public static synchronized void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    public static synchronized void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void fireChanged() {
        for (Listener l : LISTENERS) {
            try {
                l.onJobsChanged();
            } catch (Exception ignored) {}
        }
    }

    public static synchronized List<ConversionJob> active() {
        return new ArrayList<>(ACTIVE.values());
    }

    public static synchronized ConversionJob get(String jobId) {
        return ACTIVE.get(jobId);
    }

    public static synchronized ConversionJob forRaw(String rawPath) {
        for (ConversionJob j : ACTIVE.values()) {
            if (j.rawPath.equals(rawPath)) return j;
        }
        return null;
    }

    public static synchronized ConversionJob forTakeId(long takeId) {
        for (ConversionJob j : ACTIVE.values()) {
            if (j.takeId() == takeId) return j;
        }
        return null;
    }

    /**
     * Resolves any positive {@link Take#id} or negative synthetic
     * {@link ConversionJob#takeId()} to its canonical positive immutable
     * recording ID.
     */
    public static synchronized long resolveRecordingId(long takeOrJobId) {
        if (takeOrJobId > 0) return takeOrJobId;
        if (takeOrJobId == -1) return -1L;
        ConversionJob job = forTakeId(takeOrJobId);
        if (job != null && job.recordingId > 0) {
            return job.recordingId;
        }
        String raw = job != null ? job.rawPath : NEG_TO_RAW.get(takeOrJobId);
        if (raw != null) {
            Long done = COMPLETED.get(raw);
            if (done != null && done > 0) return done;
            Long mapped = RAW_TO_RECORDING_ID.get(raw);
            if (mapped != null && mapped > 0) return mapped;
        }
        return -1L;
    }

    public static synchronized void noteCompletion(String rawPath, long takeId) {
        COMPLETED.put(rawPath, takeId);
        if (takeId > 0) {
            RAW_TO_RECORDING_ID.put(rawPath, takeId);
        }
    }

    public static synchronized Long completedTakeIdForRaw(String rawPath) {
        return COMPLETED.get(rawPath);
    }

    /**
     * Atomically records a rename for a take that was (or still is)
     * converting. Updates the active job and sidecar when the job is still in
     * flight, remembers the override by raw path so the worker handoff cannot
     * miss it, and renames the finished take when the job already completed
     * while the rename dialog was open.
     *
     * <p>The repository rename happens after this class's lock is released:
     * the worker can hold the repository lock while calling back into this
     * registry, so nesting those two locks in the other order could deadlock.
     */
    public static String renameConvertingOrCompleted(String jobId,
                                                     long syntheticTakeId,
                                                     String rawPath,
                                                     String newTitle) {
        String clean = newTitle != null ? newTitle.trim() : "";
        if (clean.isEmpty()) return "";
        final String effectiveRaw;
        final Long doneId;
        synchronized (ConversionJobs.class) {
            ConversionJob job = null;
            if (jobId != null && !jobId.isEmpty()) {
                job = ACTIVE.get(jobId);
            }
            if (job == null && syntheticTakeId < 0) {
                for (ConversionJob j : ACTIVE.values()) {
                    if (j.takeId() == syntheticTakeId) {
                        job = j;
                        break;
                    }
                }
            }
            if (job == null && rawPath != null && !rawPath.isEmpty()) {
                for (ConversionJob j : ACTIVE.values()) {
                    if (rawPath.equals(j.rawPath)) {
                        job = j;
                        break;
                    }
                }
            }
            String eff = rawPath;
            if ((eff == null || eff.isEmpty()) && job != null) {
                eff = job.rawPath;
            }
            if ((eff == null || eff.isEmpty()) && syntheticTakeId < 0) {
                eff = NEG_TO_RAW.get(syntheticTakeId);
            }
            if (eff != null && !eff.isEmpty()) {
                RENAMED_TITLES.put(eff, clean);
            }
            if (job != null) {
                job.title = clean;
                if (job.replaceTitle != null) {
                    job.replaceTitle = clean;
                }
                job.updatedAt = System.currentTimeMillis();
                job.write();
            }
            effectiveRaw = eff;
            doneId = (eff != null && !eff.isEmpty())
                    ? COMPLETED.get(eff) : null;
        }
        if (doneId != null) {
            TakesRepository.rename(doneId, clean);
        }
        fireChanged();
        return clean;
    }

    /**
     * Returns the most up-to-date title for {@code job}, honouring any rename
     * that arrived while the job was queued or running.
     */
    public static synchronized String latestTitle(ConversionJob job,
                                                  String fallback) {
        String cleanFallback = fallback != null ? fallback : "";
        if (job == null) return cleanFallback;
        String byRaw = RENAMED_TITLES.get(job.rawPath);
        if (byRaw != null && !byRaw.trim().isEmpty()) {
            job.title = byRaw.trim();
            return job.title;
        }
        if (job.title != null && !job.title.trim().isEmpty()) {
            return job.title.trim();
        }
        return cleanFallback;
    }

    /**
     * Takes and clears a pending rename override recorded by
     * {@link #renameConvertingOrCompleted}. The worker calls this after the
     * finished take is visible, then applies the result without holding this
     * class's lock.
     */
    public static synchronized String consumeRenamedTitle(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        String renamed = RENAMED_TITLES.remove(rawPath);
        if (renamed == null || renamed.trim().isEmpty()) return null;
        return renamed.trim();
    }

    /**
     * Reads a pending rename override without clearing it, so the worker can
     * decide which title a completion should carry while still honouring the
     * initial edit-replace title when no rename arrived.
     */
    public static synchronized String peekRenamedTitle(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return null;
        String renamed = RENAMED_TITLES.get(rawPath);
        if (renamed == null || renamed.trim().isEmpty()) return null;
        return renamed.trim();
    }

    public static synchronized void noteTakeRaw(long takeId, String rawPath) {
        NEG_TO_RAW.put(takeId, rawPath);
    }

    public static synchronized String rawForTakeId(long takeId) {
        return NEG_TO_RAW.get(takeId);
    }

    public static synchronized void forgetCompletion(String rawPath) {
        COMPLETED.remove(rawPath);
        RAW_TO_RECORDING_ID.remove(rawPath);
        RENAMED_TITLES.remove(rawPath);
    }

    /** Marks {@code rawPath} as being read. Pair every call with {@link #endRawRead}. */
    public static void beginRawRead(String rawPath) {
        if (rawPath == null) return;
        synchronized (ConversionJobs.class) {
            Integer n = RAW_READERS.get(rawPath);
            RAW_READERS.put(rawPath, n == null ? 1 : n + 1);
        }
    }

    private static final class DeferredMaster {
        final File finalFile;
        final long recordingId;

        DeferredMaster(File finalFile, long recordingId) {
            this.finalFile = finalFile;
            this.recordingId = recordingId;
        }
    }

    public static void endRawRead(String rawPath) {
        if (rawPath == null) return;
        boolean doDelete = false;
        DeferredMaster masterTarget = null;
        synchronized (ConversionJobs.class) {
            Integer n = RAW_READERS.get(rawPath);
            if (n == null) return;
            boolean lastReader;
            if (n <= 1) {
                RAW_READERS.remove(rawPath);
                lastReader = true;
            } else {
                RAW_READERS.put(rawPath, n - 1);
                lastReader = false;
            }
            if (lastReader && forRaw(rawPath) == null) {
                if (DEFERRED_RAW_DELETES.remove(rawPath)) {
                    DEFERRED_RAW_MASTERS.remove(rawPath);
                    doDelete = true;
                } else {
                    masterTarget = DEFERRED_RAW_MASTERS.remove(rawPath);
                }
            }
        }
        if (doDelete) {
            try {
                // noinspection ResultOfMethodCallIgnored
                new File(rawPath).delete();
            } catch (Exception ignored) {}
        } else if (masterTarget != null) {
            TakesRepository.adoptFloatMaster(
                    masterTarget.recordingId,
                    new File(rawPath),
                    masterTarget.finalFile);
        }
    }

    /** Raws whose deletion was deferred until their last reader finishes. */
    private static final java.util.Set<String> DEFERRED_RAW_DELETES =
            new java.util.HashSet<>();
    /** Raws whose move to .float32/ was deferred until their last reader finishes. */
    private static final java.util.Map<String, DeferredMaster> DEFERRED_RAW_MASTERS =
            new java.util.HashMap<>();

    /** True while some thread is decoding this raw. */
    public static synchronized boolean rawIsBeingRead(String rawPath) {
        return RAW_READERS.containsKey(rawPath);
    }

    /**
     * Deletes a finished conversion's raw immediately, unless a reader
     * (preview playback or edit import) still holds it open — then the
     * deletion is deferred to {@link #endRawRead} so the file can neither be
     * pulled out from under the reader nor leaked into reconcile().
     */
    public static void deleteOrDeferRaw(String rawPath) {
        if (rawPath == null) return;
        synchronized (ConversionJobs.class) {
            DEFERRED_RAW_MASTERS.remove(rawPath);
            if (RAW_READERS.containsKey(rawPath)) {
                DEFERRED_RAW_DELETES.add(rawPath);
                return;
            }
        }
        try {
            // noinspection ResultOfMethodCallIgnored
            new File(rawPath).delete();
        } catch (Exception ignored) {}
    }

    /**
     * Preserves a finished conversion's 32-bit float raw as the persistent
     * float master linked to {@code recordingId} (and {@code finalFile}), or
     * deletes it if {@code finalFile} is already WAV32 / missing, deferring
     * until {@link #endRawRead} if a reader currently holds {@code rawPath}
     * open.
     */
    public static void completeOrDeferRawMaster(String rawPath, File finalFile,
                                                long recordingId) {
        if (rawPath == null) return;
        if (finalFile == null || !finalFile.exists()
                || AudioImporter.isNativeFloatWav(finalFile)) {
            deleteOrDeferRaw(rawPath);
            TakesRepository.deleteFloatMaster(recordingId, finalFile);
            return;
        }
        long effId = recordingId > 0 ? recordingId
                : TakesRepository.findIdByFile(finalFile);
        synchronized (ConversionJobs.class) {
            if (RAW_READERS.containsKey(rawPath)) {
                DEFERRED_RAW_MASTERS.put(rawPath,
                        new DeferredMaster(finalFile, effId));
                return;
            }
        }
        TakesRepository.adoptFloatMaster(effId, new File(rawPath), finalFile);
    }

    public static void completeOrDeferRawMaster(String rawPath, File finalFile) {
        completeOrDeferRawMaster(rawPath, finalFile, -1L);
    }

    /**
     * Enqueues a background conversion for a finalized raw take. Returns the
     * existing job when one is already active for the same raw.
     */
    public static synchronized ConversionJob enqueue(Context ctx, File raw,
                                                     String title,
                                                     RecordPreset preset,
                                                     SaveFormat format,
                                                     long durationMs) {
        return enqueue(ctx, raw, title, preset, format, durationMs, -1, null);
    }

    /**
     * Same, carrying edit-replace lineage: when the job completes, the take
     * with {@code replaceTakeId} is replaced (under {@code replaceTitle})
     * instead of adding a duplicate.
     */
    public static synchronized ConversionJob enqueue(Context ctx, File raw,
                                                     String title,
                                                     RecordPreset preset,
                                                     SaveFormat format,
                                                     long durationMs,
                                                     long replaceTakeId,
                                                     String replaceTitle) {
        return enqueue(ctx, raw, title, preset, format, durationMs,
                replaceTakeId, replaceTitle, -1L);
    }

    public static synchronized ConversionJob enqueue(Context ctx, File raw,
                                                     String title,
                                                     RecordPreset preset,
                                                     SaveFormat format,
                                                     long durationMs,
                                                     long replaceTakeId,
                                                     String replaceTitle,
                                                     long explicitRecordingId) {
        ConversionJob existing = forRaw(raw.getAbsolutePath());
        if (existing != null) return existing;
        RecordingEngine e = RecordingEngine.get();
        // AUDIT-3-02 fix: read the geometry from the `raw` file itself.
        // The engine may be idle/cleared (dataBytes 0, channels back to its
        // 2 default) at enqueue time, which recorded framesTotal = 1 and the
        // wrong channel count for a mono take.
        int ch = Math.max(1, e.channels);
        long bytes = e.dataBytes;
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            ch = Math.max(1, info.channels);
            bytes = info.dataBytes;
        } catch (Exception ignored) {}
        long framesTotal = Math.max(1, bytes / 4 / ch);
        String jobTitle = (replaceTitle != null && !replaceTitle.trim().isEmpty())
                ? replaceTitle.trim()
                : (title != null ? title : "");
        ConversionJob job = new ConversionJob(
                UUID.randomUUID().toString(),
                jobTitle,
                raw.getAbsolutePath(),
                ConversionJob.partFor(raw).getAbsolutePath(),
                preset != null ? preset.id : RecordPreset.MEMO.id,
                format != null ? format.id : SaveFormat.AUTO.id,
                ch,
                preset != null ? preset.aacBitrate : 128000,
                Math.max(durationMs, 1000),
                framesTotal);
        long resolvedRecId = explicitRecordingId;
        if (resolvedRecId <= 0 && replaceTakeId != -1) {
            resolvedRecId = resolveRecordingId(replaceTakeId);
        }
        if (resolvedRecId <= 0) {
            resolvedRecId = TakesRepository.allocateId();
        }
        job.recordingId = resolvedRecId;
        job.replaceTakeId = replaceTakeId;
        job.replaceTitle = replaceTitle;
        RAW_TO_RECORDING_ID.put(raw.getAbsolutePath(), job.recordingId);
        NEG_TO_RAW.put(job.takeId(), raw.getAbsolutePath());
        ACTIVE.put(job.jobId, job);
        job.write();
        if (ctx != null) {
            try {
                TrialAndBillingManager.get(ctx).onRecordingSaved(job.recordingId);
            } catch (Exception ignored) {}
        }
        fireChanged();
        return job;
    }

    /**
     * Cancels a job: stops the worker, deletes partial output and — when
     * {@code deleteRaw} — the raw take itself. The sidecar always goes.
     * When the raw is kept it gets its pending marker back, so the next
     * launch offers the usual continue/discard recovery instead of leaking
     * an invisible file.
     */
    public static synchronized boolean cancel(String jobId, boolean deleteRaw) {
        ConversionJob job = ACTIVE.get(jobId);
        if (job == null) return false;
        job.cancelled = true;
        job.state = ConversionJob.CANCELLED;
        job.updatedAt = System.currentTimeMillis();
        job.write();
        ACTIVE.remove(jobId);
        try {
            // noinspection ResultOfMethodCallIgnored
            new File(job.partPath).delete();
        } catch (Exception ignored) {}
        job.deleteSidecar();
        if (deleteRaw) {
            DEFERRED_RAW_DELETES.remove(job.rawPath);
            DEFERRED_RAW_MASTERS.remove(job.rawPath);
            try {
                // noinspection ResultOfMethodCallIgnored
                new File(job.rawPath).delete();
                // noinspection ResultOfMethodCallIgnored
                new File(job.rawPath + ".pending").delete();
            } catch (Exception ignored) {}
            forgetCompletion(job.rawPath);
        } else {
            try {
                File raw = new File(job.rawPath);
                if (raw.exists()) {
                    RecordingEngine.writePendingMarker(raw, job.title,
                            job.channels);
                }
            } catch (Exception ignored) {}
        }
        fireChanged();
        return true;
    }

    /** Cancels by library card id (negative synthetic ids map to jobs). */
    public static synchronized boolean cancelByTakeId(long takeId,
                                                      boolean deleteRaw) {
        ConversionJob job = forTakeId(takeId);
        return job != null && cancel(job.jobId, deleteRaw);
    }

    /**
     * Resolves or cancels the source of an Edit -> Replace save BEFORE the
     * edited take is converted or adopted.
     *
     * <ul>
     *   <li>If {@code replaceTakeId < 0} and the original conversion job is
     *       still queued or running, cancels it immediately (marking it
     *       {@code superseded} and deleting its raw + part + sidecar) so the
     *       worker never wastes time finishing the obsolete original file,
     *       and returns {@code -1} because no finished library row exists to
     *       delete (while preserving its immutable recordingId in
     *       {@link #resolveRecordingId(long)}).</li>
     *   <li>If {@code replaceTakeId < 0} and the original conversion job
     *       already finished while the user was editing, returns the positive
     *       {@code Take.id} of that finished take so the replacement deletes
     *       it and reuses that same immutable ID.</li>
     * </ul>
     */
    public static long cancelForReplace(Context ctx, long replaceTakeId) {
        if (replaceTakeId >= 0) return replaceTakeId;
        String jobIdToCancel = null;
        Long finishedId;
        synchronized (ConversionJobs.class) {
            ConversionJob srcJob = forTakeId(replaceTakeId);
            String srcRaw = srcJob != null ? srcJob.rawPath
                    : NEG_TO_RAW.get(replaceTakeId);
            long preservedRecId = (srcJob != null && srcJob.recordingId > 0)
                    ? srcJob.recordingId : -1L;
            if (srcJob != null) {
                srcJob.superseded = true;
                srcJob.cancelled = true;
                jobIdToCancel = srcJob.jobId;
                cancel(srcJob.jobId, true);
            }
            if (srcRaw != null && preservedRecId > 0) {
                RAW_TO_RECORDING_ID.put(srcRaw, preservedRecId);
            }
            finishedId = (srcRaw != null) ? COMPLETED.get(srcRaw) : null;
        }
        if (jobIdToCancel != null && ctx != null) {
            ConversionService.cancelJob(ctx, jobIdToCancel);
            ConversionService.onRecordingStateChanged();
        }
        return finishedId != null ? finishedId : -1L;
    }

    /**
     * Cancels any active background conversion job that was scheduled to
     * replace {@code deletedTakeId}. Prevents a replacement conversion from
     * resurrecting a take that the user deleted while the conversion was
     * still in flight.
     */
    public static void cancelForDeletedTargetTake(Context ctx, long deletedTakeId) {
        if (deletedTakeId < 0) return;
        List<String> jobsToCancel = new ArrayList<>();
        synchronized (ConversionJobs.class) {
            for (ConversionJob j : new ArrayList<>(ACTIVE.values())) {
                if (j.replaceTakeId == deletedTakeId
                        || j.recordingId == deletedTakeId) {
                    j.superseded = true;
                    j.cancelled = true;
                    jobsToCancel.add(j.jobId);
                    cancel(j.jobId, true);
                }
            }
        }
        if (ctx != null) {
            for (String jid : jobsToCancel) {
                ConversionService.cancelJob(ctx, jid);
            }
            if (!jobsToCancel.isEmpty()) {
                ConversionService.onRecordingStateChanged();
            }
        }
    }

    static synchronized void updateProgress(ConversionJob job, int pct,
                                            long framesDone) {
        job.progress = pct;
        job.framesDone = framesDone;
        job.updatedAt = System.currentTimeMillis();
        if ((pct % 5) == 0 || pct >= 100) job.write();
        fireChanged();
    }

    static synchronized void markRunning(ConversionJob job) {
        job.state = ConversionJob.RUNNING;
        job.updatedAt = System.currentTimeMillis();
        job.write();
        if (!ACTIVE.containsKey(job.jobId)) ACTIVE.put(job.jobId, job);
        fireChanged();
    }

    static synchronized void markDone(ConversionJob job) {
        job.state = ConversionJob.DONE;
        job.progress = 100;
        job.updatedAt = System.currentTimeMillis();
        ACTIVE.remove(job.jobId);
        job.deleteSidecar();
        fireChanged();
    }

    static synchronized void markFailed(ConversionJob job, String error) {
        job.state = ConversionJob.FAILED;
        job.error = error;
        job.updatedAt = System.currentTimeMillis();
        ACTIVE.remove(job.jobId);
        job.deleteSidecar();
        fireChanged();
    }

    /**
     * Startup sweep: resurrect interrupted jobs, drop garbage. Returns the
     * jobs the worker should run. Stale sidecars (orphaned > 7 days) and
     * partial outputs without a sidecar are deleted.
     */
    public static synchronized List<ConversionJob> sweep(Context ctx) {
        List<ConversionJob> resume = new ArrayList<>();
        File dir = RecordingEngine.takesDir(ctx);
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Exception e) {
            files = null;
        }
        if (files == null) return resume;
        long now = System.currentTimeMillis();
        Set<String> liveParts = new HashSet<>();
        // A live capture owns a .part with no sidecar yet. Sweep used to
        // assume it only ran at cold start, but the service sweeps on every
        // start command, so this file was deleted out from under the running
        // StreamingEncoder mid-recording.
        File activeRaw = RecordingEngine.get().rawFile;
        if (activeRaw != null) {
            liveParts.add(ConversionJob.partFor(activeRaw).getAbsolutePath());
        }
        // Same protection for a session that is being written but not yet
        // finalized (a .pending marker sits beside it).
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".pending")) {
                String rawName = f.getName().substring(0,
                        f.getName().length() - ".pending".length());
                liveParts.add(new File(dir, rawName + ".part")
                        .getAbsolutePath());
            }
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".conv.json.tmp")) {
                String mainName = f.getName().substring(
                        0, f.getName().length() - ".tmp".length());
                File mainSidecar = new File(dir, mainName);
                if (!mainSidecar.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    f.renameTo(mainSidecar);
                } else {
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".conv.json")) continue;
            ConversionJob job = ConversionJob.read(f);
            if (job == null) {
                // noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            liveParts.add(job.partPath);
            // Already tracked in memory (RUNNING or QUEUED): keep the live
            // instance. Replacing it reset progress to 0 and let the queue run
            // the same jobId twice, the second run reporting a false failure
            // because the raw was already gone.
            ConversionJob inMem = ACTIVE.get(job.jobId);
            if (inMem != null) {
                resume.add(inMem);
                continue;
            }
            boolean stale = now - job.updatedAt > 7L * 24 * 3600_000L;
            File raw = new File(job.rawPath);
            if (stale || !raw.exists()
                    || ConversionJob.DONE.equals(job.state)
                    || ConversionJob.FAILED.equals(job.state)
                    || ConversionJob.CANCELLED.equals(job.state)) {
                try {
                    // noinspection ResultOfMethodCallIgnored
                    new File(job.partPath).delete();
                } catch (Exception ignored) {}
                // noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            if (job.recordingId <= 0) {
                job.recordingId = TakesRepository.allocateId();
            }
            job.state = ConversionJob.QUEUED;
            job.cancelled = false;
            job.write();
            RAW_TO_RECORDING_ID.put(job.rawPath, job.recordingId);
            NEG_TO_RAW.put(job.takeId(), job.rawPath);
            ACTIVE.put(job.jobId, job);
            resume.add(job);
        }
        // Partial outputs with no sidecar are crash garbage — except the one
        // a live capture may be writing right now (protected above).
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".part")) continue;
            if (!liveParts.contains(f.getAbsolutePath())) {
                // noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (!resume.isEmpty()) fireChanged();
        return resume;
    }

    /**
     * The unified save dispatch: finalize the raw, then either adopt the
     * incremental output (fast path) or enqueue a background job. Never
     * blocks on conversion work beyond draining the incremental tail.
     */
    public static SaveResult dispatchSave(Context ctx, RecordPreset preset,
                                          SaveFormat format,
                                          StreamingEncoder.Progress cb) {
        return dispatchSave(ctx, preset, format, cb, -1, null);
    }

    /**
     * Same, carrying edit-replace lineage into the background job when the
     * fast path misses.
     */
    public static SaveResult dispatchSave(Context ctx, RecordPreset preset,
                                          SaveFormat format,
                                          StreamingEncoder.Progress cb,
                                          long replaceTakeId,
                                          String replaceTitle) {
        RecordingEngine e = RecordingEngine.get();
        File raw = e.finalizeRawForSave();
        if (raw == null || !raw.exists()) {
            throw new IllegalStateException("empty");
        }
        // Resolve the immutable recording ID before cancelling or deleting the
        // previous job/take so an Edit -> Replace always preserves the
        // original recording's immutable ID!
        long preservedRecordingId = (replaceTakeId != -1)
                ? resolveRecordingId(replaceTakeId) : -1L;
        // If replacing a take that is still converting (replaceTakeId < 0),
        // cancel the original conversion job immediately so we never convert
        // the original file first and then convert the edited file second.
        if (replaceTakeId < 0) {
            long finishedId = cancelForReplace(ctx, replaceTakeId);
            if (preservedRecordingId <= 0 && finishedId > 0) {
                preservedRecordingId = finishedId;
            }
            replaceTakeId = finishedId;
        }
        // If the original take already exists in the library (either because
        // the user edited a finished take, or because the converting take
        // finished while the user was editing in Studio), delete the previous
        // take and its file immediately before starting the new conversion!
        if (replaceTakeId > 0) {
            if (preservedRecordingId <= 0) {
                preservedRecordingId = replaceTakeId;
            }
            TakesRepository.deleteImmediateForReplace(replaceTakeId);
            replaceTakeId = -1L;
        }
        final long recordingId = preservedRecordingId > 0
                ? preservedRecordingId : TakesRepository.allocateId();
        synchronized (ConversionJobs.class) {
            RAW_TO_RECORDING_ID.put(raw.getAbsolutePath(), recordingId);
        }
        if (ctx != null) {
            TrialAndBillingManager.get(ctx).onRecordingSaved(recordingId);
        }

        String title = (replaceTitle != null && !replaceTitle.trim().isEmpty())
                ? replaceTitle.trim()
                : (e.takeName != null ? e.takeName : "");
        long dur = Math.max(e.durationMs(), 1000);
        StreamingEncoder sess = e.endStreamingDetach();
        SaveFormat eff = format.effective(preset);
        if (eff == SaveFormat.AUTO) eff = SaveFormat.M4A;
        boolean needsFxBake = e.isFxBakeRequired();

        // When saving as 32-bit Float WAV (SaveFormat.WAV32), `raw` is already
        // the 48 kHz 32-bit IEEE float WAV. Adopt `raw` directly in-place
        // without creating a duplicate .part file, a duplicate .float32 copy,
        // or a redundant background conversion job.
        if (eff == SaveFormat.WAV32 && AudioImporter.isNativeFloatWav(raw)) {
            if (sess != null) {
                try {
                    sess.abort();
                } catch (Exception ignored) {}
                sess = null;
            }
            try {
                // noinspection ResultOfMethodCallIgnored
                ConversionJob.partFor(raw).delete();
            } catch (Exception ignored) {}
            if (needsFxBake) {
                try {
                    if (ctx != null) {
                        DspChain.Config cfg = DspChain.Config.load(ctx);
                        if (cfg != null && cfg.isActive()) {
                            FormatConverter.WavInfo info =
                                    FormatConverter.readFloatWav(raw);
                            long totalFrames = info.dataBytes / 4
                                    / Math.max(1, info.channels);
                            if (totalFrames > 0) {
                                DspChain chain = new DspChain(info.channels, cfg);
                                chain.setSampleRate(info.sampleRate);
                                long regionEnd = totalFrames;
                                for (AudioProcessor proc : chain.offlineProcessors()) {
                                    long next = EditEngine.applyLengthChangingEffectToRange(
                                            raw, 0, regionEnd, proc, null);
                                    if (next > 0) regionEnd = next;
                                }
                                if (chain.hasInPlaceProcessors()) {
                                    DspChain inPlace = new DspChain(info.channels, cfg);
                                    inPlace.setSampleRate(info.sampleRate);
                                    inPlace.clearOfflineProcessors();
                                    EditEngine.applyDspToRangeWithProgress(
                                            raw, 0, regionEnd, inPlace, pct -> {
                                                if (cb != null) cb.onProgress(pct);
                                            });
                                }
                                e.dataBytes = RecordingEngine.payloadBytesOf(raw);
                            }
                        }
                    }
                } catch (Exception ex) {
                    CrashLog.breadcrumb(ctx, "dispatchSave wav32 fx bake failed: " + ex);
                }
                e.setFxBakeRequired(false);
            }
            String rawOrigPath = raw.getAbsolutePath();
            try {
                FormatConverter.validateFinal(raw, SaveFormat.WAV32);
                File fin = TakeSaver.uniqueFinal(raw, eff.extension());
                if (!fin.getAbsolutePath().equals(rawOrigPath)) {
                    if (!raw.renameTo(fin)) {
                        fin = raw;
                    }
                } else {
                    fin = raw;
                }
                // No duplicate .float32 companion file is needed when the saved
                // take itself is a 32-bit float WAV.
                TakesRepository.deleteFloatMaster(recordingId, fin);
                // noinspection ResultOfMethodCallIgnored
                new File(rawOrigPath + ".pending").delete();
                noteCompletion(rawOrigPath, recordingId);
                if (cb != null) cb.onProgress(100);
                return SaveResult.adopted(recordingId, fin,
                        TakeSaver.labelFor(preset, format), dur, title);
            } catch (Exception ex) {
                CrashLog.breadcrumb(ctx,
                        "dispatchSave wav32 adopt failed: " + ex);
            }
        }

        // An enabled-but-unbaked FX chain invalidates the incremental output:
        // the pump encoded raw microphone frames, so its .part would ship
        // without a single effect. Drop it and let the worker convert the
        // FX-baked raw instead of silently losing the whole chain.
        if (needsFxBake) {
            if (sess != null) {
                try {
                    sess.abort();
                } catch (Exception ignored) {}
                sess = null;
            }
            // Enqueue the background job FIRST so the Library shows the
            // converting card at 0% immediately instead of staying blank
            // while the full-file DSP bake runs.
            ConversionJob prequeued = enqueue(ctx, raw, title, preset, format,
                    dur, replaceTakeId, replaceTitle, recordingId);
            try {
                if (ctx != null) {
                    DspChain.Config cfg = DspChain.Config.load(ctx);
                    if (cfg != null && cfg.isActive()) {
                        FormatConverter.WavInfo info =
                                FormatConverter.readFloatWav(raw);
                        long totalFrames = info.dataBytes / 4
                                / Math.max(1, info.channels);
                        if (totalFrames > 0) {
                            DspChain chain = new DspChain(info.channels, cfg);
                            chain.setSampleRate(info.sampleRate);
                            long regionEnd = totalFrames;
                            for (AudioProcessor proc : chain.offlineProcessors()) {
                                long next = EditEngine.applyLengthChangingEffectToRange(
                                        raw, 0, regionEnd, proc, null);
                                if (next > 0) regionEnd = next;
                            }
                            if (chain.hasInPlaceProcessors()) {
                                DspChain inPlace = new DspChain(info.channels, cfg);
                                inPlace.setSampleRate(info.sampleRate);
                                inPlace.clearOfflineProcessors();
                                EditEngine.applyDspToRange(raw, 0, regionEnd,
                                        inPlace, null);
                            }
                            e.dataBytes = RecordingEngine.payloadBytesOf(raw);
                        }
                    }
                }
            } catch (Exception ex) {
                CrashLog.breadcrumb(ctx, "dispatchSave fx bake failed: " + ex);
            }
            e.setFxBakeRequired(false);
            // noinspection ResultOfMethodCallIgnored
            new File(raw.getAbsolutePath() + ".pending").delete();
            if (cb != null) cb.onProgress(0);
            return SaveResult.background(prequeued);
        }
        // framesEncoded() > 0 is NOT evidence that the pump kept up: a
        // format switch restarts the encoder from the top of the raw file, so
        // it reports progress after its first chunk while nearly the whole
        // take is still unencoded. Only drain inline when the real backlog is
        // tiny; otherwise skip straight to the worker with no stall at all.
        boolean canAdoptInline = sess != null && sess.healthy()
                && sess.matches(preset, format)
                && sess.framesEncoded() > 0
                && sess.unencodedFrames() <= MAX_INLINE_TAIL_FRAMES;
        if (canAdoptInline) {
            try {
                if (!sess.finish(ADOPT_DRAIN_BUDGET_MS)) {
                    throw new IllegalStateException("drain budget exceeded");
                }
                if (sess.finishedOk()) {
                    File part = sess.partFile();
                    // Same guard as the background path: a pass-through FLAC
                    // encoder hands back a near-full-size file under a
                    // lossless label. Fall through to the worker, which
                    // re-encodes as WAV24.
                    if (eff == SaveFormat.FLAC
                            && TakeSaver.flacLooksIneffective(part.length(),
                            raw.length())) {
                        throw new IllegalStateException(
                                "flac ineffective; fallback to worker");
                    }
                    FormatConverter.validateFinal(part, eff);
                    File fin = TakeSaver.uniqueFinal(raw,
                            eff.extension());
                    if (!part.renameTo(fin)) {
                        throw new IllegalStateException("adopt failed");
                    }
                    // Maintain the 32-bit float file in storage linked to
                    // recordingId (or delete raw if fin is already WAV32).
                    TakesRepository.adoptFloatMaster(recordingId, raw, fin);
                    // noinspection ResultOfMethodCallIgnored
                    new File(raw.getAbsolutePath() + ".pending").delete();
                    return SaveResult.adopted(recordingId, fin,
                            TakeSaver.labelFor(preset, format), dur, title);
                }
            } catch (Exception ex) {
                CrashLog.breadcrumb(ctx, "stream adopt failed: " + ex);
            }
            // abort(), never a bare unlink: a timed-out finish() leaves the
            // pump running, and only abort() stops it. Enqueuing a job onto
            // the same part path while that thread still writes it corrupts
            // the container.
            sess.abort();
        } else if (sess != null) {
            sess.abort();
        }
        ConversionJob job = enqueue(ctx, raw, title, preset, format, dur,
                replaceTakeId, replaceTitle, recordingId);
        // The sidecar now exists, so recovery no longer needs the pending
        // marker. Keep it until this instant to close the finalize/enqueue
        // crash window.
        // noinspection ResultOfMethodCallIgnored
        new File(raw.getAbsolutePath() + ".pending").delete();
        if (cb != null) cb.onProgress(0);
        return SaveResult.background(job);
    }
}
