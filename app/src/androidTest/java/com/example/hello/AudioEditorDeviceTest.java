package com.example.hello;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Roadmap Phase 4 on-device: selection-range preview stops at the range end
 * (not the file end) and the live DSP chain previews without errors through
 * a real AudioTrack.
 */
@RunWith(AndroidJUnit4.class)
public class AudioEditorDeviceTest {

    private File makeRaw(Context ctx, String tag, int frames) throws Exception {
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes,
                "edit_" + tag + "_" + System.nanoTime() + ".wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            float[] buf = new float[frames * 2];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (float) Math.sin(i * 0.03) * 0.5f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void playRangeStopsAtRangeEnd() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File raw = makeRaw(ctx, "range", 48000 * 6);
        final boolean[] done = {false};
        RawFloatPlayer player = null;
        long start = SystemClock.elapsedRealtime();
        try {
            player = new RawFloatPlayer(raw, ok -> {
                done[0] = ok;
            });
            player.playRange(0, 48000);
            long deadline = start + 8000;
            while (!done[0] && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(200);
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            assertTrue("range preview never completed", done[0]);
            assertTrue("range preview took " + elapsed
                            + "ms (played past the range?)",
                    elapsed < 5000);
            assertTrue("position " + player.positionMs(),
                    Math.abs(player.positionMs() - 1000) < 400);
        } finally {
            if (player != null) player.stop();
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    @Test
    public void dspChainPreviewCompletes() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File raw = makeRaw(ctx, "dsp", 48000 * 2);
        final boolean[] done = {false};
        RawFloatPlayer player = null;
        long start = SystemClock.elapsedRealtime();
        try {
            player = new RawFloatPlayer(raw, ok -> {
                done[0] = ok;
            });
            player.setDspChain(
                    new DspChain(2, DspChain.Config.voicePolish()));
            player.play();
            long deadline = start + 10000;
            while (!done[0] && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(200);
            }
            assertTrue("dsp preview never completed", done[0]);
        } finally {
            if (player != null) player.stop();
            // noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }
}
