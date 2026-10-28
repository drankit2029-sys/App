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
    public static final String ACTION_RESTORE_NOTIF =
            "com.example.hello.action.RESTORE_NOTIF";

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

    private static volatile RecordingService activeInstance;

    private PendingIntent cachedContentIntent;
    private PendingIntent cachedToggleIntent;
    private PendingIntent cachedSaveIntent;
    private PendingIntent cachedCancelIntent;
    private PendingIntent cachedRestoreIntent;

    private String lastNotifTitle = "";
    private String lastNotifText = "";
    private Boolean lastNotifPaused = null;
    private int lastNotifPct = -2;
    private long lastChronoBaseMs = 0L;

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

    /**
     * Called by RecordingEngine whenever capture starts, pauses, resumes,
     * finalizes, clears, aborts, or renames, so the notification updates
     * immediately without waiting for the next tick.
     */
    public static void onRecordingStateChanged() {
        RecordingService s = activeInstance;
        if (s != null) {
            s.handler.post(() -> s.refreshNotif(true));
        }
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
        activeInstance = this;
        ensureChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        initPendingIntents();
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
        if (ACTION_RESTORE_NOTIF.equals(a)) {
            RecordingEngine e = RecordingEngine.get();
            if (e.hasSession() && (e.state == RecordingEngine.State.RECORDING
                    || e.state == RecordingEngine.State.PAUSED)) {
                startFg();
                refreshNotif(true);
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
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
        if (activeInstance == this) activeInstance = null;
        stopTick();
        abandonFocus();
        super.onDestroy();
    }

    // ---- audio focus: interruptions pause, never auto-resume ----

    private android.media.AudioFocusRequest focusReq26;

    /**
     * True when a cellular or VoIP call (ringing, dialing, or in-call) is active.
     * Prevents resuming AudioRecord while Android's telephony policy mutes the mic.
     */
    public static boolean isCallActive(Context ctx) {
        if (ctx == null) return false;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int mode = am.getMode();
                if (mode == AudioManager.MODE_IN_CALL
                        || mode == AudioManager.MODE_IN_COMMUNICATION
                        || mode == AudioManager.MODE_RINGTONE) {
                    return true;
                }
            }
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) ctx.getSystemService(
                            Context.TELEPHONY_SERVICE);
            if (tm != null) {
                int callState = tm.getCallState();
                if (callState == android.telephony.TelephonyManager.CALL_STATE_RINGING
                        || callState == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Requests audio focus and returns true only if granted.
     * If a call or exclusive speaker session rejects focus, returns false.
     */
    private boolean registerFocus() {
        try {
            if (isCallActive(this)) return false;
            if (audioManager == null) {
                audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            }
            if (audioManager == null) return true;
            if (focusListener == null) {
                focusListener = focus -> {
                    if (focus == AudioManager.AUDIOFOCUS_LOSS
                            || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        if (SystemClock.elapsedRealtime() - captureStartedAt
                                < FOCUS_GRACE_MS) {
                            return;
                        }
                        handler.post(() -> {
                            RecordingEngine e = RecordingEngine.get();
                            if (e.state == RecordingEngine.State.RECORDING) {
                                e.pauseCapture();
                                abandonFocus();
                                boolean call = isCallActive(RecordingService.this);
                                toast(getString(call
                                        ? R.string.msg_paused_call
                                        : R.string.msg_speaker_engaged));
                                refreshNotif(true);
                            }
                        });
                    }
                };
            }
            int res;
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq26 = new android.media.AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener, handler)
                        .build();
                res = audioManager.requestAudioFocus(focusReq26);
            } else {
                //noinspection deprecation
                res = audioManager.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception ignored) {
            return true;
        }
    }

    private void abandonFocus() {
        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26 && focusReq26 != null) {
                    audioManager.abandonAudioFocusRequest(focusReq26);
                    focusReq26 = null;
                } else if (focusListener != null) {
                    //noinspection deprecation
                    audioManager.abandonAudioFocus(focusListener);
                }
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
                abandonFocus();
            } else if (e.state == RecordingEngine.State.PAUSED) {
                if (isCallActive(this)) {
                    toast(getString(R.string.msg_call_active));
                    refreshNotif(true);
                    return;
                }
                captureStartedAt = now;
                if (!registerFocus()) {
                    toast(getString(R.string.msg_speaker_engaged));
                    refreshNotif(true);
                    return;
                }
                e.resumeCapture(getApplicationContext(),
                        RecordPreset.load(this).audioSource);
            } else {
                return;
            }
        } catch (SecurityException se) {
            toast(getString(R.string.msg_need_mic));
            refreshNotif(true);
            return;
        } catch (Exception ex) {
            toast(getString(R.string.couldnt_save));
            refreshNotif(true);
            return;
        }
        refreshNotif(true);
    }

    private void doSave() {
        if (busy) return;
        RecordingEngine e = RecordingEngine.get();
        if (!e.hasSession()) {
            stopSelf();
            return;
        }
        if (e.isSaving()) return;
        e.setSaving(true);
        busy = true;
        convertingPct = 0;
        lastPctPush = 0;
        refreshNotif();
        new Thread(() -> {
            boolean ok;
            String msg;
            String title = e.takeName != null ? e.takeName : "";
            try {
                Context appCtx = getApplicationContext();
                if (e.isInSubSession()) {
                    // The notification saved while a Select & Replace
                    // sub-session was open: splice first, then save the parent
                    // instead of exporting only the temporary snippet.
                    boolean commitSub = e.rawFile != null
                            && RecordingEngine.payloadBytesOf(e.rawFile) > 0;
                    e.exitSelectAndReplaceSubSession(commitSub, null);
                }
                RecordPreset preset = RecordPreset.load(this);
                SaveFormat save = SaveFormat.load(this);
                // NEW-06 fix: honour and clear stashed EditLink when saving
                // an edited take from the notification action.
                String rawPath = e.rawFile != null
                        ? e.rawFile.getAbsolutePath() : null;
                long replaceId = -1;
                String replaceTitle = null;
                if (EditLink.matches(appCtx, rawPath)) {
                    replaceId = EditLink.id(appCtx);
                    replaceTitle = EditLink.title(appCtx);
                }
                ConversionJobs.SaveResult r = ConversionJobs.dispatchSave(
                        appCtx, preset, save, this::setPct,
                        replaceId, replaceTitle);
                EditLink.clear(appCtx);
                if (r.adopted) {
                    if (replaceId > 0) {
                        TakesRepository.deleteImmediateForReplace(replaceId);
                    }
                    String rt = replaceTitle != null
                            && !replaceTitle.isEmpty()
                            ? replaceTitle : r.title;
                    TakesRepository.addRecordingWithId(
                            r.recordingId, r.file, r.durationMs,
                            rt, r.label);
                    e.clearSession();
                    ok = true;
                    msg = getString(R.string.convert_done_msg, r.title,
                            r.label);
                } else {
                    // Handed to the conversion service; this service is done.
                    ConversionService.startJob(appCtx, r.job.jobId);
                    e.clearSession();
                    ok = true;
                    msg = getString(R.string.conv_bg_toast);
                }
            } catch (Exception ex) {
                ok = false;
                msg = getString(R.string.convert_failed_msg, title);
            }
            final boolean done = ok;
            final String text = msg;
            final String t = title;
            handler.post(() -> {
                e.setSaving(false);
                try {
                    convertingPct = -1;
                    refreshNotif();
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
        if (RecordingEngine.get().isSaving()) return;
        busy = true;
        new Thread(() -> {
            RecordingEngine e = RecordingEngine.get();
            String title = e.takeName != null ? e.takeName : "";
            boolean ok = e.abortAndDelete();
            EditLink.clear(getApplicationContext());
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
        lastNotifTitle = "";
        lastNotifText = "";
        lastNotifPaused = null;
        lastNotifPct = -2;
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
        refreshNotif(false);
    }

    private void refreshNotif(boolean force) {
        try {
            RecordingEngine e = RecordingEngine.get();
            if (!e.hasSession() && !busy) return;
            String name = e.takeName != null ? e.takeName : getString(R.string.app_name);
            boolean paused = e.state != RecordingEngine.State.RECORDING;
            RecordPreset preset = RecordPreset.load(this);
            String presetTitle = getString(preset.titleRes);
            long durMs = e.durationMs();
            String saveLabel = TakeSaver.labelFor(preset, SaveFormat.load(this));

            String title;
            String text;
            if (convertingPct >= 0) {
                title = getString(R.string.notif_converting, convertingPct);
                text = getString(R.string.converting_msg, saveLabel, convertingPct);
            } else if (paused) {
                title = getString(R.string.notif_paused, name);
                // Chronometer is hidden when paused, so show frozen MM:SS here.
                text = presetTitle + " → " + saveLabel + " • "
                        + Take.formatDuration(durMs);
            } else {
                title = getString(R.string.notif_recording, name);
                // Native Chronometer in header is the single live timer;
                // do not duplicate a second text timer in contentText.
                text = presetTitle + " → " + saveLabel;
            }

            long expectedBase = System.currentTimeMillis() - Math.max(0L, durMs);
            boolean baseDrifted = !paused && convertingPct < 0
                    && Math.abs(expectedBase - lastChronoBaseMs) > 250L;

            if (!force && !baseDrifted
                    && title.equals(lastNotifTitle)
                    && text.equals(lastNotifText)
                    && lastNotifPaused != null && lastNotifPaused == paused
                    && lastNotifPct == convertingPct) {
                return;
            }
            lastNotifTitle = title;
            lastNotifText = text;
            lastNotifPaused = paused;
            lastNotifPct = convertingPct;
            if (!paused && convertingPct < 0) {
                lastChronoBaseMs = expectedBase;
            }

            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotifWith(title, text, paused, durMs));
            }
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
        boolean paused = e.state != RecordingEngine.State.RECORDING;
        RecordPreset preset = RecordPreset.load(this);
        String presetTitle = getString(preset.titleRes);
        long durMs = e.durationMs();
        String saveLabel = TakeSaver.labelFor(preset, SaveFormat.load(this));

        String title;
        String text;
        if (convertingPct >= 0) {
            title = getString(R.string.notif_converting, convertingPct);
            text = getString(R.string.converting_msg, saveLabel, convertingPct);
        } else if (paused) {
            title = getString(R.string.notif_paused, name);
            text = presetTitle + " → " + saveLabel + " • "
                    + Take.formatDuration(durMs);
        } else {
            title = getString(R.string.notif_recording, name);
            text = presetTitle + " → " + saveLabel;
        }
        lastNotifTitle = title;
        lastNotifText = text;
        lastNotifPaused = paused;
        lastNotifPct = convertingPct;
        return buildNotifWith(title, text, paused, durMs);
    }

    private Notification buildNotifWith(String title, String text,
                                        boolean paused, long durMs) {
        if (cachedContentIntent == null && cachedToggleIntent == null) {
            initPendingIntents();
        }
        RecordPreset preset = RecordPreset.load(this);
        String saveLabel = TakeSaver.labelFor(preset, SaveFormat.load(this));
        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_mic);
        // Match TakeOne brand colors: record red (#FF3B30) when active,
        // amber (#FF9F0A) when paused.
        // Tint only the icon badge and action buttons (never flood-fill the
        // card background with setColorized(true)).
        b.setColor(paused ? 0xFFFF9F0A : 0xFFFF3B30);
        b.setColorized(false);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE);
        b.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setSilent(true);
        if (cachedRestoreIntent != null) {
            b.setDeleteIntent(cachedRestoreIntent);
        }
        // Native SystemUI Chronometer ticks in real time on the system UI
        // thread without requiring sub-second Binder IPC updates.
        if (convertingPct < 0 && !paused) {
            long base = System.currentTimeMillis() - Math.max(0L, durMs);
            lastChronoBaseMs = base;
            b.setWhen(base);
            b.setShowWhen(true);
            b.setUsesChronometer(true);
        } else {
            b.setShowWhen(false);
            b.setUsesChronometer(false);
        }
        if (cachedContentIntent != null) {
            b.setContentIntent(cachedContentIntent);
        }
        if (convertingPct < 0) {
            b.addAction(0,
                    paused ? getString(R.string.notif_resume)
                            : getString(R.string.notif_pause),
                    cachedToggleIntent);
            b.addAction(0, getString(R.string.notif_save),
                    cachedSaveIntent);
            b.addAction(0, getString(R.string.notif_cancel),
                    cachedCancelIntent);
        } else {
            b.setProgress(100, Math.max(0, Math.min(100, convertingPct)), false);
        }
        try {
            Notification n = b.build();
            n.flags |= Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_NO_CLEAR
                    | Notification.FLAG_FOREGROUND_SERVICE;
            return n;
        } catch (Exception ex) {
            return new Notification();
        }
    }

    private void initPendingIntents() {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("open_studio", true);
        open.setAction("com.example.hello.OPEN_STUDIO");
        try {
            cachedContentIntent = PendingIntent.getActivity(this, 10, open,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception ex) {
            cachedContentIntent = null;
        }
        cachedToggleIntent = action(ACTION_TOGGLE, 21);
        cachedSaveIntent = action(ACTION_SAVE, 22);
        cachedCancelIntent = action(ACTION_CANCEL, 23);
        cachedRestoreIntent = action(ACTION_RESTORE_NOTIF, 24);
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
            refreshNotif(false);
            // Align to the next whole-second boundary while recording so the
            // MM:SS readout steps in sync with the audio duration and never
            // exceeds 1 update/sec.
            RecordingEngine e = RecordingEngine.get();
            long delay = 1000L;
            if (e.state == RecordingEngine.State.RECORDING) {
                long rem = 1000L - (e.durationMs() % 1000L);
                delay = Math.max(200L, Math.min(1000L, rem));
            }
            handler.postDelayed(this, delay);
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
}
