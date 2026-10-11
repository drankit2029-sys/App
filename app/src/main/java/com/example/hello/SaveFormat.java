package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

/**
 * Every container this app can write. WAV32/WAV16 are produced by our own
 * writer (always available); M4A/AAC is mandatory per the CDD. Everything
 * else is offered only when {@link MediaCodecList} actually finds a platform
 * encoder, so the list gracefully matches what the device supports
 * (encode side — decode-only codecs like MP3/Vorbis-in-some-cases never
 * appear as save targets).
 */
public enum SaveFormat {
    AUTO("auto", R.string.save_auto, R.string.save_auto_sub, "M4A"),
    M4A("m4a", R.string.save_m4a, R.string.save_m4a_sub, "M4A"),
    WAV16("wav16", R.string.save_wav16, R.string.save_wav16_sub, "WAV"),
    WAV32("wav32", R.string.save_wav32, R.string.save_wav32_sub, "WAV32"),
    AMR_NB("amr_nb", R.string.save_amr_nb, R.string.save_amr_nb_sub, "AMR"),
    AMR_WB("amr_wb", R.string.save_amr_wb, R.string.save_amr_wb_sub, "AMR-WB"),
    FLAC("flac", R.string.save_flac, R.string.save_flac_sub, "FLAC"),
    OPUS("opus", R.string.save_opus, R.string.save_opus_sub, "OPUS"),
    VORBIS("vorbis", R.string.save_vorbis, R.string.save_vorbis_sub, "VORBIS");

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
    private static Boolean vorbisOk;
    private static Boolean amrNbOk;
    private static Boolean amrWbOk;

    /** True when this format can actually be written on this device. */
    public boolean available(Context ctx) {
        switch (this) {
            case AUTO:
            case M4A:
            case WAV16:
            case WAV32:
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
            case VORBIS:
                if (vorbisOk == null) vorbisOk = hasEncoder("audio/vorbis");
                return vorbisOk;
            default:
                return false;
        }
    }

    private static boolean hasEncoder(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String t : info.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Encoder MIME, or null for formats written without MediaCodec. */
    public String encoderMime() {
        switch (this) {
            case M4A: return MediaFormat.MIMETYPE_AUDIO_AAC;
            case AMR_NB: return "audio/3gpp";
            case AMR_WB: return "audio/amr-wb";
            case FLAC: return "audio/flac";
            case OPUS: return "audio/opus";
            case VORBIS: return "audio/vorbis";
            default: return null;
        }
    }

    /** File extension for finished takes. */
    public String extension() {
        switch (this) {
            case M4A: return "m4a";
            case WAV16:
            case WAV32: return "wav";
            case AMR_NB:
            case AMR_WB: return "3gp";
            case FLAC: return "flac";
            case OPUS:
            case VORBIS: return "webm";
            default: return "m4a";
        }
    }

    /** Resolve AUTO to the preset's native container. */
    public SaveFormat effective(RecordPreset preset) {
        if (this != AUTO) return this;
        return preset.savesWav ? WAV16 : M4A;
    }

    /**
     * Whether this container can store a preset's capture. Raw Studio is a
     * lossless master: only lossless containers (or Auto, which resolves to
     * one) make sense for it — a 32-bit float master degrades to nothing by
     * squeezing it through a voice codec. Lossy presets can use anything
     * except the raw-float passthrough.
     */
    public boolean supportsPreset(RecordPreset preset) {
        if (preset != null && preset.savesWav) {
            switch (this) {
                case AUTO:
                case WAV16:
                case WAV32:
                case FLAC:
                    return true;
                default:
                    return false;
            }
        }
        return this != WAV32;
    }
}
