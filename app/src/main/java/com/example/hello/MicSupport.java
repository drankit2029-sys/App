package com.example.hello;

import android.media.AudioManager;
import android.media.MicrophoneInfo;
import android.os.Build;

import java.util.List;

/**
 * Capability check for true stereo capture. Counts physical microphones via
 * {@link AudioManager#getMicrophones()} (API 28+) so a stereo preset can
 * fall back to mono — with a warning — instead of recording dual-mono or
 * silence on single-mic hardware.
 */
public final class MicSupport {

    private MicSupport() {}

    /**
     * @return number of physical microphones, or -1 when the platform cannot
     * tell (below API 28 or query failed). Callers treat -1 as "try stereo,
     * fall back silently if the hardware refuses".
     */
    public static int physicalMicCount(AudioManager am) {
        if (am == null || Build.VERSION.SDK_INT < 28) return -1;
        try {
            List<MicrophoneInfo> mics = am.getMicrophones();
            if (mics == null) return -1;
            return mics.size();
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean stereoLikely(AudioManager am) {
        int n = physicalMicCount(am);
        return n < 0 || n >= 2;
    }

    /** True only when we positively know dual mics are absent. */
    public static boolean dualAbsent(AudioManager am) {
        return physicalMicCount(am) == 1;
    }
}
