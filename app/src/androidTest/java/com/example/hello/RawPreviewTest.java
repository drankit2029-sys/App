package com.example.hello;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Proves the stored-raw preview path works on a real device image: play,
 * pause, seek and completion through AudioTrack without throwing.
 */
@RunWith(AndroidJUnit4.class)
public class RawPreviewTest {

    private File makeRaw(Context ctx, String tag, int frames) throws Exception {
        File takes = RecordingEngine.takesDir(ctx);
        File f = new File(takes, "preview_" + tag + "_" + System.nanoTime()
                + ".wav");
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(2, 48000, (long) frames * 2 * 4));
            float[] buf = new float[frames * 2];
            for (int i = 0; i < frames * 2; i++) {
                buf[i] = (float) Math.sin(i * 0.03) * 0.5f;
            }
            Dsp.writeFloatsLE(w, buf, 0, buf.length);
        } finally {
            w.close();
        }
        return f;
    }

    @Test
    public void previewPlaysPausesAndSeeks() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File raw = makeRaw(ctx, "long", 48000 * 3);
        final boolean[] done = {false};
        try {
            RawFloatPlayer player = new RawFloatPlayer(raw, ok -> {
                done[0] = true;
            });
            assertTrue("wrong duration: " + player.durationMs(),
                    player.durationMs() >= 2900
                            && player.durationMs() <= 3100);
            player.play();
            Thread.sleep(1200);
            long pos = player.positionMs();
            assertTrue("position did not advance (" + pos + "ms)", pos > 300);
            player.pause();
            long frozen = player.positionMs();
            Thread.sleep(400);
            assertTrue("pause did not hold",
                    Math.abs(player.positionMs() - frozen) < 200);
            player.seekToMs(200);
            assertTrue("seek missed",
                    Math.abs(player.positionMs() - 200) < 300);
            player.stop();
        } finally {
            if (raw.exists()) {
                // noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
        }
    }

    @Test
    public void previewSignalsCompletion() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        // Short enough to fit the track buffer (~64KB): completion then
        // depends only on the player reaching end-of-file, not on the sink
        // draining. 7200 frames stereo float = 57.6KB.
        File raw = makeRaw(ctx, "short", 7200);
        final boolean[] done = {false};
        final boolean[] completed = {false};
        try {
            RawFloatPlayer player = new RawFloatPlayer(raw, ok -> {
                done[0] = true;
                completed[0] = ok;
            });
            player.play();
            long t0 = System.currentTimeMillis();
            while (!done[0] && System.currentTimeMillis() - t0 < 8000) {
                Thread.sleep(200);
            }
            assertTrue("never finished", done[0]);
            assertTrue("did not complete", completed[0]);
            player.stop();
        } finally {
            if (raw.exists()) {
                // noinspection ResultOfMethodCallIgnored
                raw.delete();
            }
        }
    }
}
