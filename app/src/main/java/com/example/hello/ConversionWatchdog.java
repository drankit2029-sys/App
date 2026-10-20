package com.example.hello;

import android.content.Context;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Persisted safety net for background conversions.
 *
 * <p>The foreground service is the fast path. If it is killed by an OEM
 * battery manager, a process trim or a foreground-service budget, no app code
 * runs to restart it. This one-shot WorkManager request survives process
 * death and reboots; it re-arms itself while a sidecar is unfinished and
 * starts the service with a temporary foreground allowance. WorkManager is
 * also allowed to start a foreground service when the app itself is in the
 * background, which a plain alarm or broadcast cannot do on Android 12+.
 */
final class ConversionWatchdog {

    static final String UNIQUE = "takeone-conversion-watchdog";
    /** First pass after a save: long enough for the service to start itself. */
    static final long FIRST_DELAY_MINUTES = 2L;
    /** Later passes while a job is still unfinished. */
    static final long RETRY_DELAY_MINUTES = 15L;

    private ConversionWatchdog() {}

    static void arm(Context ctx) {
        schedule(ctx, FIRST_DELAY_MINUTES);
    }

    static void retry(Context ctx) {
        schedule(ctx, RETRY_DELAY_MINUTES);
    }

    private static void schedule(Context ctx, long delayMinutes) {
        if (ctx == null) return;
        try {
            OneTimeWorkRequest request =
                    new OneTimeWorkRequest.Builder(
                            ConversionWatchdogWorker.class)
                            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                            .addTag(UNIQUE)
                            .build();
            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE,
                            request);
        } catch (Throwable t) {
            // WorkManager can be unavailable in stripped test environments;
            // the startup sweep on the next launch remains the fallback.
            CrashLog.breadcrumb(ctx, "conv watchdog arm failed: " + t);
        }
    }
}
