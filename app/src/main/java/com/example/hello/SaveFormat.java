package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaFormat;

/**
 * Every container this app can write.
 *
 * <p>The capture is always a 32-bit float WAV working file — that stays
 * internal and is never a choice here, because offering it would just
 * duplicate "the file you already recorded". What the selector answers is
 * "how do you want this delivered?".
 *
 * <p>WAV16/WAV24 are written by our own pure-Java writer (always available);
 * M4A/AAC is mandatory per the CDD. Everything else is offered only when an
 * encoder can actually be created, so the list matches what the device
 * really supports (decode-only codecs like MP3 never appear).
 */
public enum SaveFormat {
    AUTO("auto", R.string.save_auto, R.string.save_auto_sub, "M4A"),
    WAV16("wav16", R.string.save_wav16, R.string.save_wav16_sub, "WAV"),
    WAV24("wav24", R.string.save_wav24, R.string.save_wav24_sub, "WAV24"),
    FLAC("flac", R.string.save_flac, R.string.save_flac_sub, "FLAC"),
    M4A("m4a", R.string.save_m4a, R.string.save_m4a_sub, "M4A"),
    OPUS("opus", R.string.save_opus, R.string.save_opus_sub, "OPUS"),
    AMR_NB("amr_nb", R.string.save_amr_nb, R.string.save_amr_nb_sub, "AMR"),
    AMR_WB("amr_wb", R.string.save_amr_wb, R.string.save_amr_wb_sub, "AMR-WB");

    public final String id;
    public final int titleRes;
    public final int subRes;
    /** Short library badge, e.g. "M4A". */
    public final String badge;

    SaveFormat(String id, int titleRes, int subRes, String badge) {
        this.id = id;
        this.titleRes = titleRes;
        this.subRes = subRes;
        this.badge = badge;
    }

    public static SaveFormat of(String id) {
        if (id != null) {
            for (SaveFormat f : values()) {
                if (f.id.equals(id)) return f;
            }
        }
        return AUTO;
    }

    private static final String PREFS = "takeone";
    private static final String KEY = "save_format";

    public static SaveFormat load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return of(p.getString(KEY, AUTO.id));
        } catch (Exception e) {
            return AUTO;
        }
    }

    public void save(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, id).apply();
        } catch (Exception ignored) {}
    }

    // ---- availability probing ----

    private static Boolean flacOk;
    private static Boolean opusOk;
    private static Boolean amrNbOk;
    private static Boolean amrWbOk;

    /** True when this format can actually be written on this device. */
    public boolean available(Context ctx) {
        switch (this) {
            case AUTO:
            case WAV16:
            case WAV24:
            case M4A:
                return true;
            case AMR_NB:
                if (amrNbOk == null) amrNbOk = hasEncoder("audio/3gpp");
                return amrNbOk;
            case AMR_WB:
                if (amrWbOk == null) amrWbOk = hasEncoder("audio/amr-wb");
                return amrWbOk;
            case FLAC:
                if (flacOk == null) flacOk = hasEncoder("audio/flac");
                return flacOk;
            case OPUS:
                if (opusOk == null) opusOk = hasEncoder("audio/opus");
                return opusOk;
            default:
                return false;
        }
    }

    /**
     * True only if an encoder for this MIME can actually be <em>created</em>.
     *
     * <p>Listing is not proof: {@link MediaCodecList} advertises codecs the
     * framework then refuses to instantiate — observed for {@code audio/vorbis}
     * on API 33, where the list says yes and {@code createEncoderByType}
     * throws NAME_NOT_FOUND. Offering a format that cannot be encoded turns
     * into a failed save at the worst possible moment, so the probe now does
     * the same work the converter will do.
     */
    private static boolean hasEncoder(String mime) {
        MediaCodec c = null;
        try {
            c = MediaCodec.createEncoderByType(mime);
            return true;
        } catch (Exception | Error e) {
            // NAME_NOT_FOUND / unsupported: the codec is not really usable.
            return false;
        } finally {
            if (c != null) {
                try {
                    c.release();
                } catch (Exception ignored) {}
            }
        }
    }

    /** Encoder MIME, or null for formats written without MediaCodec. */
    public String encoderMime() {
        switch (this) {
            case M4A: return MediaFormat.MIMETYPE_AUDIO_AAC;
            case AMR_NB: return "audio/3gpp";
            case AMR_WB: return "audio/amr-wb";
            case FLAC: return "audio/flac";
            case OPUS: return "audio/opus";
            default: return null;
        }
    }

    /** File extension for finished takes. */
    public String extension() {
        switch (this) {
            case M4A: return "m4a";
            case WAV16:
            case WAV24: return "wav";
            case AMR_NB:
            case AMR_WB: return "3gp";
            case FLAC: return "flac";
            case OPUS: return "webm";
            default: return "m4a";
        }
    }

    /**
     * What AUTO resolves to for a given preset — the container that actually
     * suits what was captured, so the user never has to think about it:
     *
     * <ul>
     *   <li><b>Memo / Podcast</b> — speech. AAC is transparent enough and
     *       gives a fraction of the size, which is the point of a memo.</li>
     *   <li><b>Music</b> — an unprocessed stereo master. Even 320 kbps AAC
     *       discards real detail, so Auto stays lossless. FLAC keeps every
     *       sample while landing at roughly a third of the float working
     *       file, which matters because the next step is loading this into
     *       an editor.</li>
     * </ul>
     */
    public SaveFormat effective(RecordPreset preset) {
        if (this != AUTO) return this;
        if (preset != null && preset.autoSavesLossless) return FLAC;
        return M4A;
    }

    /**
     * One rung down the reliability ladder after this encoder kills a save
     * on the device. Exotic/voice codecs fall back to platform AAC; AAC
     * itself falls back to WAV16, which is our own pure-Java writer and
     * cannot die natively. Null when already at the bottom.
     */
    public SaveFormat fallbackAfterCrash() {
        switch (this) {
            case OPUS:
            case FLAC:
            case AMR_NB:
            case AMR_WB:
                return M4A;
            case M4A:
            case AUTO:
                return WAV16;
            default:
                return null;
        }
    }

    // ---- storage estimate (shown on the picker chips) ----

    /**
     * Approximate bytes one minute of audio occupies in this container for
     * the given preset. Constant-time estimates are exact; FLAC is a
     * content-dependent guess (see {@link #FLAC_RATIO}).
     *
     * <p>Codec bitrates are clamped exactly as the converter clamps them, so
     * the chip matches the file that actually lands on disk rather than the
     * nominal preset value.
     */
    public long bytesPerMinute(RecordPreset preset) {
        int ch = preset != null ? Math.max(1, preset.channels) : 2;
        final int sr = 48000;
        switch (this) {
            case WAV16:
                return (long) sr * ch * 2 * 60;
            case WAV24:
                return (long) sr * ch * 3 * 60;
            case M4A: {
                int br = clamp(preset != null ? preset.aacBitrate : 128000,
                        64000, 320000);
                return (long) br / 8 * 60;
            }
            case OPUS: {
                int br = clamp(preset != null && preset.aacBitrate > 0
                        ? preset.aacBitrate : 128000, 32000, 256000);
                return (long) br / 8 * 60;
            }
            case AMR_NB:
                // Converter decimates and mixes to mono at a fixed bitrate.
                return 12200L / 8 * 60;
            case AMR_WB:
                return 23850L / 8 * 60;
            case FLAC:
                // Lossless compressed: ~55% of 16-bit PCM. Noisy source
                // lands nearer 70%, tonal material nearer 45%.
                return (long) (sr * ch * 2 * 60 * FLAC_RATIO);
            case AUTO:
            default:
                return effective(preset).bytesPerMinute(preset);
        }
    }

    /** Lossless-compression ratio used for the FLAC estimate. */
    private static final double FLAC_RATIO = 0.55d;

    /** True when {@link #bytesPerMinute} is an estimate, not a fixed rate. */
    public boolean sizeIsApproximate() {
        return this == FLAC || this == AUTO;
    }

    /**
     * Compact per-minute size for the chip, e.g. "17 MB/min", "0.7 MB/min",
     * "~6 MB/min" for the compressed/estimated cases.
     */
    public String sizePerMinuteLabel(RecordPreset preset) {
        double mb = bytesPerMinute(preset) / 1024000d;
        String num;
        if (mb < 1d) {
            num = String.format(java.util.Locale.US, "%.1f", Math.max(0.05d, mb));
        } else {
            num = String.valueOf(Math.round(mb));
        }
        return (sizeIsApproximate() ? "~" : "") + num + " MB/min";
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v <= 0 ? lo : v));
    }
}
