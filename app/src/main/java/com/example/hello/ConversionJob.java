package com.example.hello;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * One background conversion: a finalized raw take plus the format it must
 * become. Persisted as a JSON sidecar next to the raw file so a killed
 * process can resume on the next launch instead of losing the take.
 *
 * <p>States: {@code QUEUED} (waiting for the worker), {@code RUNNING},
 * {@code DONE}, {@code FAILED}, {@code CANCELLED}. Only QUEUED/RUNNING
 * sidecars are resurrected by {@link ConversionJobs#sweep}.
 */
public final class ConversionJob {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public final String jobId;
    /**
     * Library title. Not final: renaming a take while it converts updates
     * this so the finished file lands under the name the user chose. Read
     * from the worker thread, hence volatile.
     */
    public volatile String title;
    public final String rawPath;
    public final String partPath;
    public final String presetId;
    public final String formatId;
    public final int channels;
    public final int bitrate;
    public final long durationMs;
    public final long framesTotal;

    public volatile String state;
    public volatile int progress;
    public volatile long framesDone;
    public volatile long updatedAt;
    public volatile boolean cancelled;
    /** Transient UI status while capture is active; never persisted. */
    public volatile boolean paused;
    public volatile String error;
    /**
     * Edit-replace lineage (BUG-02): when an edit punch-in is saved to the
     * background worker, the user's replace-vs-new choice travels with the
     * job so completion replaces the original take instead of duplicating.
     * {@code -1} = plain save (no lineage).
     */
    public volatile long replaceTakeId = -1;
    /** Original title to restore when replacing (null = keep job title). */
    public volatile String replaceTitle = null;
    /**
     * True when this conversion job was superseded by an Edit -> Replace save,
     * so the worker aborts it silently and deletes its raw file.
     */
    public volatile boolean superseded;

    public ConversionJob(String jobId, String title, String rawPath,
                         String partPath, String presetId, String formatId,
                         int channels, int bitrate, long durationMs,
                         long framesTotal) {
        this.jobId = jobId;
        this.title = title;
        this.rawPath = rawPath;
        this.partPath = partPath;
        this.presetId = presetId;
        this.formatId = formatId;
        this.channels = channels;
        this.bitrate = bitrate;
        this.durationMs = durationMs;
        this.framesTotal = framesTotal;
        this.state = QUEUED;
        this.progress = 0;
        this.framesDone = 0;
        this.updatedAt = System.currentTimeMillis();
    }

    /** Sidecar path for a raw file. */
    public static File sidecarFor(File raw) {
        return new File(raw.getAbsolutePath() + ".conv.json");
    }

    /** Partial-output path for a raw file. */
    public static File partFor(File raw) {
        return new File(raw.getAbsolutePath() + ".part");
    }

    public File sidecar() {
        return new File(rawPath + ".conv.json");
    }

    public File tempSidecar() {
        return new File(rawPath + ".conv.json.tmp");
    }

    /**
     * Atomically persists the conversion sidecar: writes to `.conv.json.tmp`,
     * flushes + fsyncs the descriptor, then renames over `.conv.json` so a
     * process kill mid-write can never truncate or corrupt the live sidecar.
     */
    public synchronized void write() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("jobId", jobId);
            o.put("title", title);
            o.put("rawPath", rawPath);
            o.put("partPath", partPath);
            o.put("presetId", presetId);
            o.put("formatId", formatId);
            o.put("channels", channels);
            o.put("bitrate", bitrate);
            o.put("durationMs", durationMs);
            o.put("framesTotal", framesTotal);
            o.put("state", state);
            o.put("progress", progress);
            o.put("framesDone", framesDone);
            o.put("updatedAt", updatedAt);
            o.put("error", error != null ? error : "");
            o.put("replaceTakeId", replaceTakeId);
            o.put("replaceTitle", replaceTitle != null ? replaceTitle : "");

            byte[] payload = o.toString().getBytes("UTF-8");
            File target = sidecar();
            File tmp = tempSidecar();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                // noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(payload);
                out.flush();
                try {
                    out.getFD().sync();
                } catch (Exception ignored) {}
            }
            if (!tmp.renameTo(target)) {
                // Fallback for filesystems where renameTo over an existing
                // destination fails: write directly only after tmp is safely
                // on disk so read() can recover from tmp if interrupted.
                try (FileOutputStream direct = new FileOutputStream(target)) {
                    direct.write(payload);
                    direct.flush();
                    try {
                        direct.getFD().sync();
                    } catch (Exception ignored) {}
                }
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public static ConversionJob read(File sidecar) {
        ConversionJob primary = readSingleFile(sidecar);
        if (primary != null) return primary;
        if (sidecar != null) {
            File tmp = new File(sidecar.getAbsolutePath() + ".tmp");
            if (tmp.exists()) {
                ConversionJob recovered = readSingleFile(tmp);
                if (recovered != null) {
                    // Promote the valid tmp file into place.
                    // noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(sidecar);
                    return recovered;
                }
            }
        }
        return null;
    }

    private static ConversionJob readSingleFile(File file) {
        if (file == null || !file.exists()) return null;
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 8192)];
            int n;
            try (FileInputStream in = new FileInputStream(file)) {
                n = in.read(buf);
            }
            if (n <= 0) return null;
            JSONObject o = new JSONObject(new String(buf, 0, n, "UTF-8"));
            ConversionJob j = new ConversionJob(
                    o.optString("jobId", ""),
                    o.optString("title", ""),
                    o.optString("rawPath", ""),
                    o.optString("partPath", ""),
                    o.optString("presetId", "memo"),
                    o.optString("formatId", "auto"),
                    o.optInt("channels", 2),
                    o.optInt("bitrate", 128000),
                    o.optLong("durationMs", 0),
                    o.optLong("framesTotal", 0));
            if (j.jobId.isEmpty() || j.rawPath.isEmpty()) return null;
            j.state = o.optString("state", QUEUED);
            j.progress = o.optInt("progress", 0);
            j.framesDone = o.optLong("framesDone", 0);
            j.updatedAt = o.optLong("updatedAt", 0);
            String err = o.optString("error", "");
            j.error = err.isEmpty() ? null : err;
            j.replaceTakeId = o.optLong("replaceTakeId", -1);
            String rt = o.optString("replaceTitle", "");
            j.replaceTitle = rt.isEmpty() ? null : rt;
            return j;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void deleteSidecar() {
        try {
            // noinspection ResultOfMethodCallIgnored
            sidecar().delete();
        } catch (Exception ignored) {}
        try {
            // noinspection ResultOfMethodCallIgnored
            tempSidecar().delete();
        } catch (Exception ignored) {}
    }

    /** Stable negative row id for library cards (Room ids are positive). */
    public long takeId() {
        long h = jobId.hashCode();
        long id = -(h + (long) Integer.MAX_VALUE + 2L);
        return id == 0 ? Long.MIN_VALUE : id;
    }
}

