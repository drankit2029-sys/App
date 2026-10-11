package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/**
 * The five recording presets. Everything is locked to 48&nbsp;kHz so
 * AudioFlinger never has to resample, and Music / Raw Studio use the
 * UNPROCESSED source to bypass AGC ducking and effects.
 */
public enum RecordPreset {
    VOICE_MEMO("voice", R.string.preset_voice, R.string.preset_voice_sub,
            "VOICE 64k", 1, 64000, MediaRecorder.AudioSource.MIC),
    STANDARD("standard", R.string.preset_standard, R.string.preset_standard_sub,
            "STD 128k", 1, 128000, MediaRecorder.AudioSource.MIC),
    PODCAST("podcast", R.string.preset_podcast, R.string.preset_podcast_sub,
            "POD 256k", 2, 256000, MediaRecorder.AudioSource.MIC),
    MUSIC("music", R.string.preset_music, R.string.preset_music_sub,
            "MUS 320k", 2, 320000, MediaRecorder.AudioSource.UNPROCESSED),
    RAW_STUDIO("raw", R.string.preset_raw, R.string.preset_raw_sub,
            "RAW 16-bit", 2, 0, MediaRecorder.AudioSource.UNPROCESSED);

    public final String id;
    public final int titleRes;
    public final int subRes;
    /** Short badge text, e.g. "MUS 320k". */
    public final String shortLabel;
    /** 1 = mono, 2 = stereo (before any mono fallback). */
    public final int channels;
    /** AAC bitrate for the save conversion; 0 for RAW (16-bit WAV). */
    public final int aacBitrate;
    public final int audioSource;
    /** True when the final file is WAV instead of M4A. */
    public final boolean savesWav;

    RecordPreset(String id, int titleRes, int subRes, String shortLabel,
                 int channels, int aacBitrate, int audioSource) {
        this.id = id;
        this.titleRes = titleRes;
        this.subRes = subRes;
        this.shortLabel = shortLabel;
        this.channels = channels;
        this.aacBitrate = aacBitrate;
        this.audioSource = audioSource;
        this.savesWav = aacBitrate == 0;
    }

    public boolean wantsStereo() {
        return channels == 2;
    }

    public boolean wantsUnprocessed() {
        return audioSource == MediaRecorder.AudioSource.UNPROCESSED;
    }

    public static RecordPreset of(String id) {
        if (id != null) {
            for (RecordPreset p : values()) {
                if (p.id.equals(id)) return p;
            }
        }
        return STANDARD;
    }

    public static RecordPreset[] all() {
        return values();
    }

    private static final String PREFS = "takeone";
    private static final String KEY = "rec_preset";

    public static RecordPreset load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return of(p.getString(KEY, STANDARD.id));
        } catch (Exception e) {
            return STANDARD;
        }
    }

    public void save(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, id).apply();
        } catch (Exception ignored) {}
    }
}
