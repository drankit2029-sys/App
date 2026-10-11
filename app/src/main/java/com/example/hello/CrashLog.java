package com.example.hello;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Last-resort diagnostics. The default uncaught-exception handler writes the
 * stack trace to {@code takeone-crash.txt} before delegating to the system
 * handler, and {@link #breadcrumb} appends lifecycle markers to
 * {@code takeone-breadcrumbs.txt} so a launch loop leaves a trail even when
 * the killer is native code (which no try/catch can observe).
 */
public final class CrashLog {

    private static final String CRASH_FILE = "takeone-crash.txt";
    private static final String CRUMB_FILE = "takeone-breadcrumbs.txt";
    private static final long MAX_CRUMB_BYTES = 64 * 1024;

    private static volatile boolean installed;

    private CrashLog() {}

    /** Idempotent. Call first in every process entry point. */
    public static void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app;
        try {
            app = ctx.getApplicationContext();
        } catch (Exception e) {
            return;
        }
        breadcrumb(app, "process start");
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, err) -> {
            try {
                breadcrumb(app, "CRASH on " + thread.getName() + ": " + err);
                File f = new File(app.getFilesDir(), CRASH_FILE);
                FileWriter w = new FileWriter(f, false);
                try {
                    w.write(stamp() + " thread=" + thread.getName() + "\n");
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    try {
                        err.printStackTrace(pw);
                    } finally {
                        pw.close();
                    }
                    w.write(sw.toString());
                    w.write("\n--- breadcrumbs ---\n");
                    File crumbs = new File(app.getFilesDir(), CRUMB_FILE);
                    if (crumbs.exists()) {
                        java.io.FileInputStream in =
                                new java.io.FileInputStream(crumbs);
                        try {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                w.write(new String(buf, 0, n, "UTF-8"));
                            }
                        } finally {
                            try {
                                in.close();
                            } catch (Exception ignored) {}
                        }
                    }
                    // Flushed to the OS: process death doesn't lose it
                    // (only power loss would, and nothing can help that).
                    w.flush();
                } finally {
                    try {
                        w.close();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {
            } finally {
                if (prev != null) {
                    try {
                        prev.uncaughtException(thread, err);
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    /** Append one marker line (flushed; cheap enough for startup stages). */
    public static void breadcrumb(Context ctx, String mark) {
        if (ctx == null || mark == null) return;
        try {
            Context app = ctx.getApplicationContext();
            File f = new File(app.getFilesDir(), CRUMB_FILE);
            if (f.exists() && f.length() > MAX_CRUMB_BYTES) {
                // noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(stamp() + " " + mark + "\n");
                w.flush();
            } finally {
                try {
                    w.close();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** Crash report from a previous run, or null. */
    public static File recentCrash(Context ctx) {
        try {
            File f = new File(ctx.getApplicationContext().getFilesDir(), CRASH_FILE);
            return f.exists() && f.length() > 0 ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearCrash(Context ctx) {
        try {
            // noinspection ResultOfMethodCallIgnored
            new File(ctx.getApplicationContext().getFilesDir(), CRASH_FILE).delete();
        } catch (Exception ignored) {}
    }

    /** Share intent for the crash report (email, chat, …). */
    public static void share(Context ctx, File crash) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", crash);
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            i.putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "TakeOne crash report");
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(android.content.Intent.createChooser(i,
                    "Share crash report"));
        } catch (Exception ignored) {}
    }

    private static String stamp() {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
        } catch (Exception e) {
            return "?";
        }
    }
}
