package com.example.hello;

import android.content.Context;

import java.io.File;

/**
 * Single home for the stop/save pipeline: finalize the raw 32-bit float
 * capture, convert to the requested container, delete the raw on success.
 * Both the studio and the notification save path funnel through here so the
 * two can never drift apart.
 */
public final class TakeSaver {

    public interface Progress {
        void onProgress(int percent);
    }

    public static final class Result {
        public final File file;
        public final String label;
        public final long durationMs;
        public final String title;

        Result(File file, String label, long durationMs, String title) {
            this.file = file;
            this.label = label;
            this.durationMs = durationMs;
            this.title = title;
        }
    }

    private TakeSaver() {}

    /**
     * If a FLAC save comes out larger than this fraction of the float raw
     * file, the encoder is not compressing and WAV24 is the better answer.
     * Real FLAC lands far below this; only a pass-through lands above.
     */
    private static final double FLAC_MIN_RATIO = 0.9d;

    /**
     * True when a "compressed" FLAC save came out no smaller than the float
     * source, i.e. the encoder passed frames through instead of compressing.
     * Exposed for the unit test; real-encoder behaviour is covered on device.
     */
    static boolean flacLooksIneffective(long flacBytes, long rawBytes) {
        if (rawBytes <= 0) return false;
        return flacBytes > rawBytes * FLAC_MIN_RATIO;
    }

    /** Short UI label for a (preset, save-format) pair, e.g. "M4A". */
    public static String labelFor(RecordPreset preset, SaveFormat save) {
        return save.effective(preset).badge;
    }

    public static Result finalizeAndConvert(Context ctx, RecordPreset preset,
                                            SaveFormat save, Progress cb)
            throws Exception {
        RecordingEngine e = RecordingEngine.get();
        CrashLog.breadcrumb(ctx, "finalize start");
        File raw = e.finalizeRaw();
        CrashLog.breadcrumb(ctx, "finalize end");
        if (raw == null || !raw.exists()) throw new IllegalStateException("empty");
        // Duration comes from the raw capture (compressed outputs carry no
        // PCM length of their own).
        long dur = Math.max(durationOf(raw), 1000);
        String title = e.takeName != null ? e.takeName : "";
        Result r = convertRaw(ctx, raw, title, preset, save, cb);
        // The raw float capture was scratch; remove it unless the result
        // somehow landed on the same path.
        if (r.file == null
                || !r.file.getAbsolutePath().equals(raw.getAbsolutePath())) {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
        return r;
    }

    /**
     * Converts an already-finalized raw take without touching the engine
     * session. Used by the background conversion worker; the caller owns the
     * raw lifecycle (delete on success, keep for recovery on failure).
     *
     * <p>NEW-03 fix: encodes into {@link ConversionJob#partFor(File)}
     * ({@code raw.wav.part}) first, validates the container, and only renames
     * to {@link #uniqueFinal(File, String)} at the very end. That way a
     * process kill or native encoder crash mid-conversion never leaves a
     * half-written {@code .m4a}/{@code .wav} in {@code takes/} for startup
     * reconcile to adopt as a corrupt take, and WAV conversions interrupted
     * by process death leave a real {@code .part} file for
     * {@link ConversionService#resumeWavConvert} to resume.
     */
    public static Result convertRaw(Context ctx, File raw, String title,
                                    RecordPreset preset, SaveFormat save,
                                    Progress cb) throws Exception {
        if (raw == null || !raw.exists()) throw new IllegalStateException("empty");
        long dur = Math.max(durationOf(raw), 1000);
        SaveFormat eff = save.effective(preset);
        CrashLog.breadcrumb(ctx, "convert start " + eff.id);
        File part = ConversionJob.partFor(raw);
        // noinspection ResultOfMethodCallIgnored
        part.delete();
        File fin = null;
        try {
            switch (eff) {
                case WAV16:
                    FormatConverter.toWav16(raw, part, pct -> post(cb, pct));
                    break;
                case WAV24:
                    FormatConverter.toWav24(raw, part, pct -> post(cb, pct));
                    break;
                case AMR_NB:
                    FormatConverter.toAmr(raw, part, false, pct -> post(cb, pct));
                    break;
                case AMR_WB:
                    FormatConverter.toAmr(raw, part, true, pct -> post(cb, pct));
                    break;
                case FLAC:
                    FormatConverter.toFlac(raw, part, pct -> post(cb, pct));
                    break;
                case OPUS:
                    FormatConverter.toOpus(raw, part, preset.aacBitrate,
                            pct -> post(cb, pct));
                    break;
                case M4A:
                case AUTO:
                default:
                    FormatConverter.toM4a(raw, part, preset.aacBitrate,
                            pct -> post(cb, pct));
                    break;
            }
            // A FLAC encoder that doesn't actually compress would silently
            // hand back a near-full-size file under a lossless-compressed
            // label. Some devices do that, so measure rather than trust: fall
            // back to 24-bit WAV, which is lossless by definition.
            if (eff == SaveFormat.FLAC && flacLooksIneffective(part.length(),
                    raw.length())) {
                long bloated = part.length();
                // noinspection ResultOfMethodCallIgnored
                part.delete();
                FormatConverter.toWav24(raw, part, null);
                eff = SaveFormat.WAV24;
                CrashLog.breadcrumb(ctx, "flac ineffective ("
                        + bloated + " bytes), fell back to wav24");
            }
            // Validate the finished .part container before exposing it under a
            // real audio extension in takes/.
            FormatConverter.validateFinal(part, eff);
            // Report 100% BEFORE the rename: post() can throw
            // CancellationException (via awaitRecordingIdle), and past this
            // point fin exists on disk — a cancel here must not leave a
            // converted file behind that reconcile() would later adopt.
            post(cb, 100);
            fin = uniqueFinal(raw, eff.extension());
            if (!part.renameTo(fin)) {
                throw new IllegalStateException("adopt part failed");
            }
        } catch (Throwable t) {
            // Preserve the .part on an Android 15 FGS timeout for resumable
            // WAV formats so the restarted job can pick up where it stopped.
            boolean keepWavPart = t.getClass().getSimpleName().contains("Timeout")
                    && (eff == SaveFormat.WAV16 || eff == SaveFormat.WAV24);
            if (!keepWavPart && part.exists()) {
                // noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            if (fin != null && fin.exists()) {
                // noinspection ResultOfMethodCallIgnored
                fin.delete();
            }
            // A4-01: close the stage even on failure/cancel. Without this an
            // unterminated "convert start" makes maybeHealCrashedEncoder()
            // blame a native codec crash on the next launch and downgrade
            // the save format for no reason. Catch Throwable, not Exception:
            // native codec failures throw Error, which must not bypass the
            // cleanup and breadcrumb below.
            CrashLog.breadcrumb(ctx, "convert end " + eff.id + " (failed)");
            if (t instanceof Error) throw (Error) t;
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException(t);
        }
        CrashLog.breadcrumb(ctx, "convert end " + eff.id);
        return new Result(fin, eff.badge, dur, title);
    }

    private static void post(Progress cb, int pct) {
        if (cb == null) return;
        try {
            cb.onProgress(pct);
        } catch (RuntimeException e) {
            // Cancellation (and only cancellation — normal progress
            // callbacks never throw) must propagate so a cancelled
            // background job stops at the next progress tick instead of
            // running to completion.
            throw e;
        } catch (Exception ignored) {}
    }

    static long durationOf(File f) {
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(f);
            long samples = info.dataBytes / 4 / Math.max(1, info.channels);
            return samples * 1000L / Math.max(1, info.sampleRate);
        } catch (Exception e) {
            return 1000;
        }
    }

    static File uniqueFinal(File raw, String ext) {
        File dir = raw.getParentFile();
        String name = raw.getName();
        String base = name.endsWith(".raw.wav")
                ? name.substring(0, name.length() - ".raw.wav".length())
                : name.replaceFirst("\\.[^.]+$", "");
        File c = new File(dir, base + "." + ext);
        if (!c.exists()) return c;
        int i = 2;
        while (true) {
            File n = new File(dir, base + " (" + i + ")." + ext);
            if (!n.exists()) return n;
            i++;
        }
    }
}
