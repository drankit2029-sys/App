package com.takeone.studio;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecord;

/**
 * Capability check for true stereo capture and Bluetooth headset microphone
 * routing. Counts physical microphones via {@link AudioManager#getMicrophones()}
 * (API 28+) and routes Bluetooth Classic SCO / BLE Audio headset microphones
 * across API 24–35 via {@link HardwareApiManager}.
 */
public final class MicSupport {

    private MicSupport() {}

    /**
     * @return number of physical microphones, or -1 when the platform cannot
     * tell (below API 28 or query failed). Callers treat -1 as "try stereo,
     * fall back silently if the hardware refuses".
     */
    public static int physicalMicCount(AudioManager am) {
        return HardwareApiManager.get().getPhysicalMicrophoneCount(am);
    }

    public static boolean stereoLikely(AudioManager am) {
        int n = physicalMicCount(am);
        return n < 0 || n >= 2;
    }

    /** True only when we positively know dual mics are absent. */
    public static boolean dualAbsent(AudioManager am) {
        return physicalMicCount(am) == 1;
    }

    /** True when a Bluetooth SCO or BLE Audio headset microphone is connected. */
    public static boolean isBluetoothMicConnected(AudioManager am) {
        return HardwareApiManager.get().findBluetoothInputDevice(am) != null;
    }

    /**
     * Routes {@code recorder} to a connected Bluetooth headset microphone if one
     * is available and permitted.
     */
    public static boolean routeBluetoothMicIfAvailable(Context ctx,
                                                       AudioManager am,
                                                       AudioRecord recorder) {
        return HardwareApiManager.get().routeBluetoothMicIfAvailable(ctx, am, recorder);
    }

    /** Releases any active Bluetooth microphone communication/SCO routing. */
    public static void clearBluetoothMicRouting(AudioManager am) {
        HardwareApiManager.get().clearBluetoothMicRouting(am);
    }
}
