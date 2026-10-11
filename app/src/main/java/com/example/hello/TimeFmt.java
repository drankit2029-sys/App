package com.example.hello;

import java.util.Locale;

/**
 * Centisecond timer rendering shared by the studio and the notification.
 * Pure Java so it is unit-testable on plain JUnit.
 */
public final class TimeFmt {

    private TimeFmt() {}

    /** 00:00.00 style, with hundredths of a second. */
    public static String formatTimer(long ms) {
        if (ms < 0) ms = 0;
        long cs = (ms % 1000) / 10;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000);
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }
}
