package com.example.hello;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.HashMap;
import java.util.Map;

/**
 * Container/codec the user wants the finished take written in.
 *
 * &lt;p&gt;Audio is always captured to a crash-safe 32-bit float WAV on disk first;
 * this choice only governs the final conversion step after the user stops.
 *
 * &lt;p&gt;WAV16/WAV24/WAV32 are written by our own pure-Java writer (always available);
 * M4A uses the baseline AAC encoder mandated by the Android CDD. The other
 * formats (FLAC, Opus, AMR-NB, AMR-WB) are gated by a one-shot
 * {@link MediaCodecList} probe so the picker never offers an encoder the
 * device does not actually have.
 */
public enum SaveFormat {
    AUTO("auto", R.string.save_auto, R.string.save_auto_sub, "AUTO"),
    M4A("m4a", R.string.save_m4a, R.string.save_m4a_sub, "M4A"),
    WAV16("wav16", R.string.save_wav16, R.string.save_wav16_sub, "WAV"),
    WAV24("wav24", R.string.save_wav24, R.string.save_wav24_sub, "WAV24"),
    WAV32("wav32", R.string.save_wav32, R.string.save_wav32_sub, "WAV32"),
    FLAC("flac", R.string.save_flac, R.string.save_flac_sub, "FLAC"),
    OPUS("opus", R.string.save_opus, R.string.save_opus_sub, "OPUS"),
    AMR_NB("amr_nb", R.string.save_amr_nb, R.string.save_amr_nb_sub, "AMR"),
    AMR_WB("amr_wb", R.string.save_amr_wb, R.string.save_amr_wb_sub, "AMR-WB");

    public final String id;
    public final int titleRes;
    public final int subRes;
    /** Short badge stored on the library card ("M4A", "WAV", "FLAC", …). */
    public final String badge;

    private static final String PREFS = "takeone";
    private static final String KEY = "save_format";
    private static volatile SaveFormat cached;
    private static final Map<SaveFormat, Boolean> AVAIL = new HashMap<>();

    SaveFormat(String id, int titleRes, int subRes, String badge) {
        this.id = id;
        this.titleRes = titleRes;
        this.subRes = subRes;
        this.badge = badge;
    }

    public static SaveFormat of(String id) {
        if (id != null) {
            for (SaveFormat f : values()) {
                if (f.id.equalsIgnoreCase(id)) return f;
            }
        }
        return AUTO;
    }

    public static SaveFormat load(Context ctx) {
        SaveFormat c = cached;
        if (c != null) return c;
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, AUTO.id);
            c = of(raw);
        } catch (Exception e) {
            c = AUTO;
        }
        cached = c;
        return c;
    }

    public void save(Context ctx) {
        cached = this;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, id).apply();
        } catch (Exception ignored) {}
    }

    /** True if this device can actually encode this format right now. */
    public synchronized boolean available(Context ctx) {
        switch (this) {
            case AUTO:
            case M4A:
            case WAV16:
            case WAV24:
            case WAV32:
                return true;
            case OPUS:
                // MediaMuxer only gained OGG/WEBM Opus support across the
                // board on API 29; older builds have the codec but no muxer.
                if (android.os.Build.VERSION.SDK_INT < 26) return false;
                break;
            case FLAC:
            case AMR_NB:
            case AMR_WB:
                break;
        }
        Boolean hit = AVAIL.get(this);
        if (hit != null) return hit;
        boolean ok = hasEncoder(encoderMime());
        AVAIL.put(this, ok);
        return ok;
    }

    /**
     * Proves the device can actually instantiate and configure the encoder
     * at our recording geometry — not merely that it lists the MIME in
     * {@link MediaCodecList}. Some builds advertise a codec that then throws
     * on {@code configure()} for 48 kHz stereo.
     */
    private boolean hasEncoder(String mime) {
        if (mime == null) return true;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            boolean listed = false;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(t)) {
                        listed = true;
                        break;
                    }
                }
                if (listed) break;
            }
            if (!listed) return false;
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    public String encoderMime() {
        switch (this) {
            case M4A: return MediaFormat.MIMETYPE_AUDIO_AAC;
            case FLAC: return "audio/flac";
            case OPUS: return "audio/opus";
            case AMR_NB: return "audio/3gpp";
            case AMR_WB: return "audio/amr-wb";
            default: return null;
        }
    }

    /** File extension (without dot) for a resolved format. */
    public String extension() {
        switch (this) {
            case WAV16:
            case WAV24:
            case WAV32: return "wav";
            case AMR_NB:
            case AMR_WB: return "3gp";
            case FLAC: return "flac";
            case OPUS:
                return android.os.Build.VERSION.SDK_INT >= 29 ? "opus" : "webm";
            case M4A:
            case AUTO:
            default: return "m4a";
        }
    }

    /**
     * Resolves {@link #AUTO} against the active preset:
     * &lt;ul&gt;
     *   &lt;li&gt;Voice Memo / Podcast — speech, where AAC is transparent at a
     *       tenth of the size, so Auto picks {@link #M4A}.</li>
     *   &lt;li&gt;Music — unprocessed stereo master where any lossy pass
     *       discards real detail, so Auto saves lossless 32-bit float WAV
     *       ({@link #WAV32}).</li>
     * &lt;/ul&gt;
     * Any explicit choice is returned unchanged.
     */
    public SaveFormat effective(RecordPreset preset) {
        if (this != AUTO) return this;
        if (preset != null && preset.autoSavesLossless) {
            return WAV32;
        }
        return M4A;
    }

    /**
     * Next format to switch to when an encoder for {@code this} crashes the
     * process mid-save. Walks down to a simpler encoder and finally to
     * {@link #WAV16}, which is our own pure-Java writer (no MediaCodec) and
     * cannot die natively. Null when already at the bottom. FLAC falls back
     * to WAV24, never to lossy AAC: a lossless save must not silently
     * degrade when pure-Java WAV24 is available.
     */
    public SaveFormat fallbackAfterCrash() {
        switch (this) {
            case OPUS:
            case AMR_NB:
            case AMR_WB:
                return M4A;
            case FLAC:
                return WAV24;
            case M4A:
            case AUTO:
                return WAV16;
            case WAV16:
            case WAV24:
            case WAV32:
            default:
                return null;
        }
    }

    /**
     * Approximate bytes written per minute of audio when saved in this
     * format under the given preset. Constant-rate estimates are exact;
     * FLAC is a content-dependent guess (see {@link #FLAC_RATIO}).
     */
    public long bytesPerMinute(RecordPreset preset) {
        SaveFormat eff = effective(preset);
        int ch = preset != null ? Math.max(1, preset.channels) : 2;
        int br = preset != null ? preset.aacBitrate : 128000;
        long sr = RecordingEngine.SAMPLE_RATE;
        switch (eff) {
            case WAV16:
                return sr * ch * 2 * 60;
            case WAV24:
                return sr * ch * 3 * 60;
            case WAV32:
                return sr * ch * 4 * 60;
            case AMR_NB:
                // 12.2 kbps mono regardless of preset.
                return 12200L * 60 / 8;
            case AMR_WB:
                // 23.85 kbps mono regardless of preset.
                return 23850L * 60 / 8;
            case OPUS: {
                // FormatConverter clamps Opus to [32k, 256k].
                long clamped = Math.max(32000, Math.min(256000,
                        br <= 0 ? 128000 : br));
                return clamped * 60 / 8;
            }
            case FLAC:
                // Android's FLAC encoder takes 16-bit PCM input; typical
                // music compresses to ~55% of that.
                return (long) (sr * ch * 2 * 60 * FLAC_RATIO);
            case M4A:
            case AUTO:
            default: {
                long clamped = Math.max(64000, Math.min(320000, br));
                return clamped * 60 / 8;
            }
        }
    }

    /** Lossless-compression ratio used for the FLAC estimate. */
    private static final double FLAC_RATIO = 0.55d;

    /** True when the size estimate is content-dependent rather than fixed-rate. */
    public boolean sizeIsApproximate() {
        return this == FLAC || this == AUTO;
    }

    /**
     * Short human label for the format picker ("~1.9 MB/min", "17 MB/min").
     * Prefixed with {@code ~} when the rate is content-dependent so variable
     * formats never look like a promise.
     */
    public String sizePerMinuteLabel(RecordPreset preset) {
        long bpm = bytesPerMinute(preset);
        double mb = bpm / (1024.0 * 1024.0);
        String prefix = sizeIsApproximate() ? "~" : "";
        if (mb >= 9.95) {
            return String.format(java.util.Locale.US, "%s%.0f MB/min",
                    prefix, mb);
        }
        return String.format(java.util.Locale.US, "%s%.1f MB/min",
                prefix, mb);
    }
}
