package com.example.hello;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Foreground host for an ongoing take: keeps capture alive when the app is
 * backgrounded and exposes a persistent notification with pause/resume plus
 * Save / Cancel actions that work without opening the app.
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking;
    private boolean busy; // a save/cancel is running
    private int convertingPct = -1;

    public static void start(Context ctx, String formatId) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_FORMAT, formatId);
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
        super.onCreate();
        ensureChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Restarted by the system: re-attach foreground if still active.
            if (RecordingEngine.get().hasSession()) {
                startFg();
                startTick();
            } else {
                stopSelf();
            }
            return START_STICKY;
        }
        String a = intent.getAction();
        if (ACTION_START.equals(a)) {
            String fmt = intent.getStringExtra(EXTRA_FORMAT);
            if (fmt != null) saveFormat(fmt);
            startFg();
            startTick();
        } else if (ACTION_TOGGLE.equals(a)) {
            doToggle();
        } else if (ACTION_SAVE.equals(a)) {
            doSave();
        } else if (ACTION_CANCEL.equals(a)) {
            doCancel();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTick();
        super.onDestroy();
    }

    // ---- actions ----

    private void doToggle() {
        if (busy) return;
        RecordingEngine e = RecordingEngine.get();
        try {
            if (e.state == RecordingEngine.State.RECORDING) {
                e.pauseCapture();
            } else if (e.state == RecordingEngine.State.PAUSED) {
                e.resumeCapture(getApplicationContext());
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
        refreshNotif();
        new Thread(() -> {
            boolean ok;
            String msg;
            String title = e.takeName != null ? e.takeName : "";
            try {
                File raw = e.finalizeRaw();
                if (raw == null || !raw.exists()) {
                    throw new IllegalStateException("no audio");
                }
                String fmt = loadFormat();
                // Duration comes from the raw capture (M4A output has no WAV header).
                long dur = Math.max(durationOf(raw), 1000);
                File fin;
                String label;
                if ("raw32".equals(fmt)) {
                    fin = raw;
                    label = "WAV";
                } else if ("wav16".equals(fmt)) {
                    fin = uniqueFinal(raw, "wav");
                    label = "WAV";
                    FormatConverter.toWav16(raw, fin, this::setPct);
                    if (fin.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        raw.delete();
                    }
                } else {
                    fin = uniqueFinal(raw, "m4a");
                    label = "M4A";
                    FormatConverter.toM4a(raw, fin, this::setPct);
                    if (fin.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        raw.delete();
                    }
                }
                TakesRepository.addRecording(
                        fin, Math.max(dur, 1000), title, label);
                e.clearSession();
                ok = true;
                msg = getString(R.string.convert_done_msg, title, label);
            } catch (Exception ex) {
                ok = false;
                msg = getString(R.string.convert_failed_msg, title);
            }
            final boolean done = ok;
            final String text = msg;
            final String t = title;
            handler.post(() -> {
                toast(text);
                Intent b = new Intent(BROADCAST_DONE);
                b.putExtra(EXTRA_OK, done);
                b.putExtra(EXTRA_KIND, "save");
                b.putExtra(EXTRA_MESSAGE, text);
                b.putExtra(EXTRA_TITLE, t);
                try {
                    sendBroadcast(b);
                } catch (Exception ignored) {}
                busy = false;
                convertingPct = -1;
                try {
                    stopForeground(true);
                } catch (Exception ignored) {}
                stopSelf();
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
                    sendBroadcast(b);
                } catch (Exception ignored) {}
                busy = false;
                try {
                    stopForeground(true);
                } catch (Exception ignored) {}
                stopSelf();
            });
        }).start();
    }

    // ---- notification ----

    private void ensureChannel() {
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
        } catch (Exception e) {
            try {
                startForeground(NOTIF_ID, n);
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

    private Notification buildNotif() {
        RecordingEngine e = RecordingEngine.get();
        String name = e.takeName != null ? e.takeName : getString(R.string.app_name);
        boolean paused = e.state == RecordingEngine.State.PAUSED;
        String fmtLabel = formatLabel(loadFormat());
        String timer = formatTimer(e.durationMs());

        String title;
        if (convertingPct >= 0) {
            title = getString(R.string.notif_converting, convertingPct);
        } else if (paused) {
            title = getString(R.string.notif_paused, name);
        } else {
            title = getString(R.string.notif_recording, name);
        }
        String sub = convertingPct >= 0
                ? getString(R.string.converting_msg, fmtLabel, convertingPct)
                : fmtLabel + " • " + timer
                + (e.hasSession() ? " • " + getString(R.string.studio_streaming) : "");

        RemoteViews rv = new RemoteViews(getPackageName(),
                R.layout.notification_recording);
        try {
            rv.setTextViewText(R.id.notifName, title);
            rv.setTextViewText(R.id.notifSub, sub);
            rv.setImageViewResource(R.id.notifToggle,
                    paused ? R.drawable.ic_play : R.drawable.ic_pause);
            rv.setOnClickPendingIntent(R.id.notifToggle,
                    action(ACTION_TOGGLE, 11));
            rv.setOnClickPendingIntent(R.id.notifSave,
                    action(ACTION_SAVE, 12));
            rv.setOnClickPendingIntent(R.id.notifCancel,
                    action(ACTION_CANCEL, 13));
        } catch (Exception ignored) {}

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

        Notification.Builder b;
        b = new Notification.Builder(this, CHANNEL);
        b.setSmallIcon(R.drawable.ic_mic);
        b.setContentTitle(title);
        b.setContentText(sub);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        try {
            b.setCustomContentView(rv);
            b.setStyle(new Notification.DecoratedCustomViewStyle());
        } catch (Exception ignored) {}
        if (content != null) b.setContentIntent(content);
        // Direct actions for system surfaces that ignore the custom view.
        try {
            b.addAction(new Notification.Action.Builder(
                    null,
                    paused ? getString(R.string.notif_resume)
                            : getString(R.string.notif_pause),
                    action(ACTION_TOGGLE, 21)).build());
            b.addAction(new Notification.Action.Builder(
                    null, getString(R.string.notif_save),
                    action(ACTION_SAVE, 22)).build());
            b.addAction(new Notification.Action.Builder(
                    null, getString(R.string.notif_cancel),
                    action(ACTION_CANCEL, 23)).build());
        } catch (Exception ignored) {}
        try {
            return b.build();
        } catch (Exception ex) {
            return new Notification.Builder(this).build();
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
            handler.postDelayed(this, 1000);
        }
    };

    private void setPct(int pct) {
        convertingPct = pct;
        handler.post(this::refreshNotif);
    }

    // ---- helpers ----

    private void toast(String text) {
        try {
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    private String loadFormat() {
        try {
            SharedPreferences p = getSharedPreferences(
                    "takeone", Context.MODE_PRIVATE);
            String f = p.getString("rec_format", "m4a");
            if ("raw32".equals(f) || "wav16".equals(f) || "m4a".equals(f)) return f;
        } catch (Exception ignored) {}
        return "m4a";
    }

    private void saveFormat(String fmt) {
        try {
            getSharedPreferences("takeone", Context.MODE_PRIVATE)
                    .edit().putString("rec_format", fmt).apply();
        } catch (Exception ignored) {}
    }

    private String formatLabel(String fmt) {
        if ("raw32".equals(fmt)) return getString(R.string.fmt_raw32);
        if ("wav16".equals(fmt)) return getString(R.string.fmt_wav16);
        return getString(R.string.fmt_m4a);
    }

    private static String formatTimer(long ms) {
        long tenths = (ms / 100) % 10;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000);
        return String.format(Locale.US, "%02d:%02d.%d", m, s, tenths);
    }

    private static long durationOf(File f) {
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(f);
            long samples = info.dataBytes / 4 / Math.max(1, info.channels);
            return samples * 1000L / Math.max(1, info.sampleRate);
        } catch (Exception e) {
            return 1000;
        }
    }

    private static File uniqueFinal(File raw, String ext) {
        File dir = raw.getParentFile();
        String base = raw.getName().replaceFirst("\\.[^.]+$", "");
        if (base.endsWith("_RAW")) base = base.substring(0, base.length() - 4);
        // Raw names carry a timestamp suffix already; keep it for uniqueness.
        File c = new File(dir, base + "." + ext);
        if (!c.exists()) return c;
        int i = 2;
        while (true) {
            File n = new File(dir, base + " (" + i + ")." + ext);
            if (!n.exists()) return n;
            i++;
        }
    }
}
