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
        SaveFormat eff = save.effective(preset);
        CrashLog.breadcrumb(ctx, "convert start " + eff.id);
        File fin = null;
        try {
            switch (eff) {
                case WAV32:
                    // True lossless: the raw 32-bit float capture IS the
                    // finished file. No conversion, no re-encode, nothing
                    // discarded — so it is kept in place rather than copied.
                    FormatConverter.readFloatWav(raw);
                    fin = raw;
                    post(cb, 100);
                    break;
                case WAV16:
                    fin = uniqueFinal(raw, "wav");
                    FormatConverter.toWav16(raw, fin, pct -> post(cb, pct));
                    break;
                case AMR_NB:
                    fin = uniqueFinal(raw, "3gp");
                    FormatConverter.toAmr(raw, fin, false, pct -> post(cb, pct));
                    break;
                case AMR_WB:
                    fin = uniqueFinal(raw, "3gp");
                    FormatConverter.toAmr(raw, fin, true, pct -> post(cb, pct));
                    break;
                case FLAC:
                    fin = uniqueFinal(raw, "flac");
                    FormatConverter.toFlac(raw, fin, pct -> post(cb, pct));
                    break;
                case OPUS:
                    fin = uniqueFinal(raw, "webm");
                    FormatConverter.toOpus(raw, fin, preset.aacBitrate,
                            pct -> post(cb, pct));
                    break;
                case VORBIS:
                    fin = uniqueFinal(raw, "webm");
                    FormatConverter.toVorbis(raw, fin, preset.aacBitrate,
                            pct -> post(cb, pct));
                    break;
                case M4A:
                case AUTO:
                default:
                    fin = uniqueFinal(raw, "m4a");
                    FormatConverter.toM4a(raw, fin, preset.aacBitrate,
                            pct -> post(cb, pct));
                    break;
            }
        } catch (Exception ex) {
            // A failed conversion must not leave a truncated file behind:
            // half-written containers are exactly the kind of poison that
            // haunts the next startup sweep. Never delete a WAV32 save, whose
            // output is the raw capture itself — that would destroy the take.
            if (fin != null && !fin.equals(raw)) {
                // noinspection ResultOfMethodCallIgnored
                fin.delete();
            }
            throw ex;
        }
        // Last line of defense before the library ever sees the file: a
        // container that fails its own sanity check (truncated by a dying
        // encoder, torn by a kill) is deleted here, not adopted later.
        try {
            FormatConverter.validateFinal(fin, eff);
        } catch (Exception ex) {
            if (!fin.equals(raw)) {
                // noinspection ResultOfMethodCallIgnored
                fin.delete();
            }
            throw ex;
        }
        // The raw is scratch once a converted copy exists — but when the
        // output IS the raw (WAV32) it must survive.
        if (!fin.equals(raw)) {
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
        CrashLog.breadcrumb(ctx, "convert end " + eff.id);
        post(cb, 100);
        return new Result(fin, eff.badge, dur, title);
    }

    private static void post(Progress cb, int pct) {
        if (cb != null) {
            try {
                cb.onProgress(pct);
            } catch (Exception ignored) {}
        }
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
        String base = raw.getName().replaceFirst("\\.[^.]+$", "");
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
