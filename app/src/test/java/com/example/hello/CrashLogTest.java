package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

/** Parser for the breadcrumb trail: finds stages that never closed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CrashLogTest {

    private void clearCrumbs(Context ctx) {
        File f = new File(ctx.getApplicationContext().getFilesDir(),
                "takeone-breadcrumbs.txt");
        // noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Test
    public void unterminatedConvertIsFound() {
        Context ctx = ApplicationProvider.getApplicationContext();
        clearCrumbs(ctx);
        assertNull(CrashLog.unterminatedStage(ctx));
        CrashLog.breadcrumb(ctx, "finalize start");
        CrashLog.breadcrumb(ctx, "finalize end");
        CrashLog.breadcrumb(ctx, "convert start opus");
        assertEquals("convert opus", CrashLog.unterminatedStage(ctx));
        CrashLog.breadcrumb(ctx, "convert end opus");
        assertNull(CrashLog.unterminatedStage(ctx));
    }

    @Test
    public void autoSwitchConsumesPending() {
        Context ctx = ApplicationProvider.getApplicationContext();
        clearCrumbs(ctx);
        CrashLog.breadcrumb(ctx, "convert start flac");
        assertEquals("convert flac", CrashLog.unterminatedStage(ctx));
        CrashLog.breadcrumb(ctx, "auto-switch flac->m4a");
        assertNull(CrashLog.unterminatedStage(ctx));
    }

    @Test
    public void laterSuccessClearsEarlierStart() {
        Context ctx = ApplicationProvider.getApplicationContext();
        clearCrumbs(ctx);
        CrashLog.breadcrumb(ctx, "convert start m4a");
        CrashLog.breadcrumb(ctx, "convert end m4a");
        CrashLog.breadcrumb(ctx, "convert start wav16");
        CrashLog.breadcrumb(ctx, "convert end wav16");
        assertNull(CrashLog.unterminatedStage(ctx));
    }
}
