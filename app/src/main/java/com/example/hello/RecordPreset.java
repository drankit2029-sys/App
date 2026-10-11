package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;

/**
 * The three recording presets. Each one is a distinct capture geometry —
 * mono vs stereo, processed mic vs unprocessed — so none overlaps another.
 * Containers live in {@link SaveFormat}: "Raw Studio" as a preset was just
 * Music-plus-WAV, and "Voice vs Standard" was one mono capture at two
 * bitrates, so both collapsed away. Everything is locked to 48&nbsp;kHz so
 * AudioFlinger never has to resample, and Music uses the UNPROCESSED source
 * to bypass AGC ducking and effects.
 */
public enum RecordPreset {
    MEMO("memo", R.string.preset_memo, R.string.preset_memo_sub,
            "MEMO 96k", 1, 96000, MediaRecorder.AudioSource.MIC),
    PODCAST("podcast", R.string.preset_podcast, R.string.preset_podcast_sub,
            "POD 256k", 2, 256000, MediaRecorder.AudioSource.MIC),
    MUSIC("music", R.string.preset_music, R.string.preset_music_sub,
            "MUS 320k", 2, 320000, MediaRecorder.AudioSource.UNPROCESSED);

    public final String id;
    public final int titleRes;
    public final int subRes;
    /** Short badge text, e.g. "MUS 320k". */
    public final String shortLabel;
    /** 1 = mono, 2 = stereo (before any mono fallback). */
    public final int channels;
    /** AAC bitrate for the save conversion. */
    public final int aacBitrate;
    public final int audioSource;

    RecordPreset(String id, int titleRes, int subRes, String shortLabel,
                 int channels, int aacBitrate, int audioSource) {
        this.id = id;
        this.titleRes = titleRes;
        this.subRes = subRes;
        this.shortLabel = shortLabel;
        this.channels = channels;
        this.aacBitrate = aacBitrate;
        this.audioSource = audioSource;
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
            // Retired presets map to their closest surviving capture.
            if ("voice".equals(id) || "standard".equals(id)) return MEMO;
            if ("raw".equals(id)) return MUSIC;
        }
        return MEMO;
    }

    public static RecordPreset[] all() {
        return values();
    }

    private static final String PREFS = "takeone";
    private static final String KEY = "rec_preset";

    public static RecordPreset load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return of(p.getString(KEY, MEMO.id));
        } catch (Exception e) {
            return MEMO;
        }
    }

    public void save(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, id).apply();
        } catch (Exception ignored) {}
    }
}
