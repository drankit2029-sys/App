package com.example.hello;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Recovery pass for conversions whose service was killed in the background.
 * Sweeps sidecars, re-kicks every unfinished job, then re-arms itself until
 * the queue is empty. Runs as a short foreground worker so Android 12+
 * permits it to restart the conversion service from the background.
 */
public class ConversionWatchdogWorker extends Worker {

    private static final int NOTIF_ID = 93;

    public ConversionWatchdogWorker(@NonNull Context context,
                                    @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        goForeground(ctx);
        List<ConversionJob> pending;
        try {
            pending = ConversionJobs.sweep(ctx);
        } catch (Throwable t) {
            CrashLog.breadcrumb(ctx, "conv watchdog sweep failed: " + t);
            ConversionWatchdog.retry(ctx);
            return Result.retry();
        }
        if (pending.isEmpty()) return Result.success();
        boolean kicked = false;
        for (ConversionJob job : pending) {
            if (job == null || job.cancelled) continue;
            // kick(), not startJob(): arming here would REPLACE this running
            // worker and could cancel it before it finishes.
            if (ConversionService.kick(ctx, job.jobId)) kicked = true;
        }
        // Stay armed: if this pass could not start the service, or the service
        // dies again, the next pass retries. The chain stops by itself when a
        // sweep finds no unfinished sidecars. Always return success here so
        // WorkManager's own backoff does not race this explicit schedule.
        ConversionWatchdog.retry(ctx);
        if (!kicked) {
            CrashLog.breadcrumb(ctx,
                    "conv watchdog could not kick " + pending.size()
                            + " job(s)");
        }
        return Result.success();
    }

    private void goForeground(Context ctx) {
        try {
            Notification n = ConversionService.buildWatchdogNotif(ctx);
            ForegroundInfo info = Build.VERSION.SDK_INT >= 29
                    ? new ForegroundInfo(NOTIF_ID, n,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    : new ForegroundInfo(NOTIF_ID, n);
            setForegroundAsync(info).get(8, TimeUnit.SECONDS);
        } catch (Throwable t) {
            CrashLog.breadcrumb(ctx, "conv watchdog foreground failed: " + t);
        }
    }
}
