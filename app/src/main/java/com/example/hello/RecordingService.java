package com.example.hello;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Foreground host for an ongoing take: keeps capture alive when the app is
 * backgrounded, owns audio-focus interruption handling (pause, never
 * auto-resume) and exposes one clean persistent notification — name, preset,
 * live timer plus pause/resume, Save and Cancel actions that work without
 * opening the app.
 */
public class RecordingService extends Service {

    public static final String ACTION_START =
            "com.example.hello.REC_START";
    public static final String ACTION_TOGGLE =
            "com.example.hello.REC_TOGGLE";
    public static final String ACTION_SAVE =
            "com.example.hello.REC_SAVE";
    public static final String ACTION_CANCEL =
            "com.example.hello.REC_CANCEL";

    public static final String EXTRA_FORMAT = "format";

    /** Broadcast when a background save/cancel finishes. */
    public static final String BROADCAST_DONE =
            "com.example.hello.REC_DONE";
    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_KIND = "kind"; // "save" or "cancel"

    private static final String CHANNEL = "takeone_recording";
    private static final int NOTIF_ID = 41;
    /** Focus losses inside this window after a (re)start are spurious. */
    private static final long FOCUS_GRACE_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking;
    private boolean busy; // a save/cancel is running
    private int convertingPct = -1;
    private long lastToggleAt;
    private long lastPctPush;
    private long captureStartedAt;

    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    public static void start(Context ctx, String presetId) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_FORMAT, presetId);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            try {
                ctx.startService(i);
            } catch (Exception ignored) {}
        }
    }

    public static void stopService(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, RecordingService.class));
        } catch (Exception ignored) {}
    }

    public static boolean isRunning(Context ctx) {
        // Best-effort: true while the engine holds an active capture session.
        RecordingEngine e = RecordingEngine.get();
        return e.hasSession()
                && (e.state == RecordingEngine.State.RECORDING
                || e.state == RecordingEngine.State.PAUSED);
    }

    @Override
    public void onCreate() {
        CrashLog.install(this);
        super.onCreate();
        ensureChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // System-restarted. START_NOT_STICKY means this should not happen,
            // but never re-enter startForeground() from here: a restart
            // racing the save could hit the Android 14+ rule that a
            // microphone FGS may not start from the background, and a
            // startForegroundService() that never promotes itself is fatal
            // to the process.
            stopSelf();
            return START_NOT_STICKY;
        }
        String a = intent.getAction();
        if (ACTION_START.equals(a)) {
            String preset = intent.getStringExtra(EXTRA_FORMAT);
            if (preset != null) RecordPreset.of(preset).save(this);
            captureStartedAt = SystemClock.elapsedRealtime();
            registerFocus();
            startFg();
            startTick();
        } else if (ACTION_TOGGLE.equals(a)) {
            doToggle();
        } else if (ACTION_SAVE.equals(a)) {
            doSave();
        } else if (ACTION_CANCEL.equals(a)) {
            doCancel();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTick();
        abandonFocus();
        super.onDestroy();
    }

    // ---- audio focus: interruptions pause, never auto-resume ----

    private void registerFocus() {
        try {
            if (focusListener != null || audioManager == null) return;
            focusListener = focus -> {
                if (focus == AudioManager.AUDIOFOCUS_LOSS
                        || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    // Spurious callbacks fire right after our own request on
                    // some devices; only honor losses outside the grace window.
                    if (SystemClock.elapsedRealtime() - captureStartedAt
                            < FOCUS_GRACE_MS) {
                        return;
                    }
                    handler.post(() -> {
                        RecordingEngine e = RecordingEngine.get();
                        if (e.state == RecordingEngine.State.RECORDING) {
                            e.pauseCapture();
                            toast(getString(R.string.msg_paused_call));
                            refreshNotif();
                        }
                    });
                }
            };
            audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        } catch (Exception ignored) {}
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) {}
        focusListener = null;
    }

    // ---- actions (debounced: one tap, one transition) ----

    private void doToggle() {
        if (busy) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastToggleAt < 400) return;
        lastToggleAt = now;
        RecordingEngine e = RecordingEngine.get();
        try {
            if (e.state == RecordingEngine.State.RECORDING) {
                e.pauseCapture();
            } else if (e.state == RecordingEngine.State.PAUSED) {
                captureStartedAt = now;
                e.resumeCapture(getApplicationContext(),
                        RecordPreset.load(this).audioSource);
            } else {
                return;
            }
        } catch (Exception ex) {
            toast(getString(R.string.couldnt_save));
            return;
        }
        refreshNotif();
    }

    private void doSave() {
        if (busy) return;
        RecordingEngine e = RecordingEngine.get();
        if (!e.hasSession()) {
            stopSelf();
            return;
        }
        busy = true;
        convertingPct = 0;
        lastPctPush = 0;
        refreshNotif();
        new Thread(() -> {
            boolean ok;
            String msg;
            String title = e.takeName != null ? e.takeName : "";
            try {
                RecordPreset preset = RecordPreset.load(this);
                SaveFormat save = SaveFormat.load(this);
                TakeSaver.Result r = TakeSaver.finalizeAndConvert(
                        getApplicationContext(), preset, save, this::setPct);
                TakesRepository.addRecording(
                        r.file, r.durationMs, r.title, r.label);
                e.clearSession();
                ok = true;
                msg = getString(R.string.convert_done_msg, r.title, r.label);
            } catch (Exception ex) {
                ok = false;
                msg = getString(R.string.convert_failed_msg, title);
            }
            final boolean done = ok;
            final String text = msg;
            final String t = title;
            handler.post(() -> {
                try {
                    toast(text);
                    Intent b = new Intent(BROADCAST_DONE);
                b.putExtra(EXTRA_OK, done);
                b.putExtra(EXTRA_KIND, "save");
                b.putExtra(EXTRA_MESSAGE, text);
                b.putExtra(EXTRA_TITLE, t);
                try {
                    // Explicit package: stays inside the app, satisfies
                    // UnsafeImplicitIntentLaunch, same delivery as before.
                    b.setPackage(getPackageName());
                    sendBroadcast(b);
                } catch (Exception ignored) {}
                busy = false;
                convertingPct = -1;
                abandonFocus();
                try {
                    stopForeground(true);
                } catch (Exception ignored) {}
                stopSelf();
                } catch (Exception ex) {
                    CrashLog.breadcrumb(getApplicationContext(),
                            "service completion FAILED: " + ex);
                    try {
                        stopSelf();
                    } catch (Exception ignored) {}
                }
            });
        }).start();
    }

    private void doCancel() {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            RecordingEngine e = RecordingEngine.get();
            String title = e.takeName != null ? e.takeName : "";
            boolean ok = e.abortAndDelete();
            final String text = ok
                    ? getString(R.string.delete_success_one, title)
                    : getString(R.string.delete_failed_msg);
            handler.post(() -> {
                toast(text);
                Intent b = new Intent(BROADCAST_DONE);
                b.putExtra(EXTRA_OK, ok);
                b.putExtra(EXTRA_KIND, "cancel");
                b.putExtra(EXTRA_MESSAGE, text);
                b.putExtra(EXTRA_TITLE, title);
                try {
                    b.setPackage(getPackageName());
                    sendBroadcast(b);
                } catch (Exception ignored) {}
                busy = false;
                abandonFocus();
                try {
                    stopForeground(true);
                } catch (Exception ignored) {}
                stopSelf();
            });
        }).start();
    }

    // ---- notification: one clean standard layout ----

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {}
    }

    private void startFg() {
        Notification n = buildNotif();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            return;
        } catch (Exception typed) {
            CrashLog.breadcrumb(this, "typed startForeground failed: " + typed);
        }
        // Untyped retry: a service launched with startForegroundService()
        // must reach the foreground within ~5s or the system kills the app,
        // so it is always worth attempting the permissive call.
        try {
            startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            CrashLog.breadcrumb(this, "startForeground failed: " + e);
            try {
                stopSelf();
            } catch (Exception ignored) {}
        }
    }

    private void refreshNotif() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif());
        } catch (Exception ignored) {}
    }

    /**
     * A single standard notification: small mic icon, "Recording — name"
     * title, "preset • live timer" text and exactly three actions. Built
     * with NotificationCompat so it renders identically (and lint-clean)
     * from API 24 up, with no custom RemoteViews for the system to duplicate.
     */
    private Notification buildNotif() {
        RecordingEngine e = RecordingEngine.get();
        String name = e.takeName != null ? e.takeName : getString(R.string.app_name);
        boolean paused = e.state == RecordingEngine.State.PAUSED;
        RecordPreset preset = RecordPreset.load(this);
        String presetTitle = getString(preset.titleRes);
        String timer = formatTimer(e.durationMs());

        String title;
        String text;
        if (convertingPct >= 0) {
            title = getString(R.string.notif_converting, convertingPct);
            text = getString(R.string.converting_msg, presetTitle, convertingPct);
        } else if (paused) {
            title = getString(R.string.notif_paused, name);
            text = presetTitle + " • " + timer;
        } else {
            title = getString(R.string.notif_recording, name);
            text = presetTitle + " • " + timer;
        }

        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("open_studio", true);
        open.setAction("com.example.hello.OPEN_STUDIO");
        PendingIntent content;
        try {
            content = PendingIntent.getActivity(this, 10, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ex) {
            content = null;
        }

        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_mic);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        if (content != null) b.setContentIntent(content);
        if (convertingPct < 0) {
            b.addAction(0,
                    paused ? getString(R.string.notif_resume)
                            : getString(R.string.notif_pause),
                    action(ACTION_TOGGLE, 21));
            b.addAction(0, getString(R.string.notif_save),
                    action(ACTION_SAVE, 22));
            b.addAction(0, getString(R.string.notif_cancel),
                    action(ACTION_CANCEL, 23));
        }
        try {
            return b.build();
        } catch (Exception ex) {
            return new Notification();
        }
    }

    private PendingIntent action(String what, int code) {
        Intent i = new Intent(this, RecordingService.class);
        i.setAction(what);
        try {
            return PendingIntent.getService(this, code, i,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception e) {
            return PendingIntent.getService(this, code, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }

    private void startTick() {
        if (ticking) return;
        ticking = true;
        handler.post(tick);
    }

    private void stopTick() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            refreshNotif();
            handler.postDelayed(this, 250);
        }
    };

    private void setPct(int pct) {
        long now = SystemClock.elapsedRealtime();
        if (pct == convertingPct) return;
        if (pct < 100 && now - lastPctPush < 500) {
            convertingPct = pct;
            return;
        }
        convertingPct = pct;
        lastPctPush = now;
        handler.post(this::refreshNotif);
    }

    // ---- helpers ----

    private void toast(String text) {
        try {
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    /** Centisecond timer. */
    static String formatTimer(long ms) {
        return TimeFmt.formatTimer(ms);
    }
}
