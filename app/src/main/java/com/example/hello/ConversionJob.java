package com.example.hello;

import org.json.JSONObject;

import java.io.File;

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

    public void write() {
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
            java.io.FileWriter w = new java.io.FileWriter(sidecar());
            try {
                w.write(o.toString());
            } finally {
                w.close();
            }
        } catch (Exception ignored) {}
    }

    public static ConversionJob read(File sidecar) {
        try {
            byte[] buf = new byte[(int) Math.min(sidecar.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(sidecar);
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
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

    public void deleteSidecar() {
        try {
            // noinspection ResultOfMethodCallIgnored
            sidecar().delete();
        } catch (Exception ignored) {}
    }

    /** Stable negative row id for library cards (Room ids are positive). */
    public long takeId() {
        long h = jobId.hashCode();
        long id = -(h + (long) Integer.MAX_VALUE + 2L);
        return id == 0 ? Long.MIN_VALUE : id;
    }
}
