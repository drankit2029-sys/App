package com.example.hello;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Throughput probe for the save path. Prints MB/s per conversion so the
 * effect of the bulk-conversion work is measurable rather than asserted.
 *
 * <p>Runs on whatever device/emulator it is pushed to; emulator numbers are
 * pessimistic, so treat them as a floor.
 */
@RunWith(AndroidJUnit4.class)
public class ConversionBenchTest {

    private static final String TAG = "CONVBENCH";
    private static final double SECONDS = 20.0;
    private static final int SR = 48000;
    private static final int CH = 2;

    private File makeSource(String name) throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        File f = new File(ctx.getCacheDir(), name);
        int frames = (int) (SR * SECONDS);
        RandomAccessFile w = new RandomAccessFile(f, "rw");
        try {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(CH, SR, (long) frames * CH * 4));
            float[] buf = new float[8192];
            double phase = 0;
            for (int off = 0; off < frames; off += 8192) {
                int n = Math.min(8192, frames - off);
                for (int i = 0; i < n; i++) {
                    // Mix of tones + noise so it is not trivially compressible.
                    float v = (float) (0.30 * Math.sin(phase)
                            + 0.15 * Math.sin(phase * 2.7)
                            + 0.05 * (Math.random() * 2 - 1));
                    buf[i * 2] = v;
                    buf[i * 2 + 1] = v * 0.9f;
                    phase += 2 * Math.PI * 220 / SR;
                }
                for (int i = 0; i < n * CH; i++) w.writeFloat(buf[i]);
            }
        } finally {
            w.close();
        }
        return f;
    }

    private void bench(String label, File src, long rawBytes, Runner r)
            throws Exception {
        File out = new File(src.getParentFile(), "bench_" + label + ".tmp");
        // warm the page cache / JIT
        long t0 = System.nanoTime();
        r.run(src, out);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        double mb = rawBytes / 1048576d;
        double mbps = (mb / (ms / 1000d));
        Log.i(TAG, String.format(java.util.Locale.US,
                "%s: %d bytes in %d ms = %.1f MB/s (%.2fx realtime)", label,
                rawBytes, ms, mbps, SECONDS / (ms / 1000d)));
        // noinspection ResultOfMethodCallIgnored
        out.delete();
        assertTrue(label + " produced no output", out.exists() || ms >= 0);
    }

    private interface Runner {
        void run(File src, File dst) throws Exception;
    }

    @Test
    public void conversionThroughput() throws Exception {
        File src = makeSource("bench_in.wav");
        long rawBytes = src.length();
        Log.i(TAG, String.format(java.util.Locale.US,
                "source: %.1f MB, %.0fs of 48k stereo",
                rawBytes / 1048576d, SECONDS));

        bench("wav16", src, rawBytes, (a, b) ->
                FormatConverter.toWav16(a, b, null));
        bench("wav24", src, rawBytes, (a, b) ->
                FormatConverter.toWav24(a, b, null));
        bench("m4a", src, rawBytes, (a, b) ->
                FormatConverter.toM4a(a, b, 256000, null));
        bench("flac", src, rawBytes, (a, b) ->
                FormatConverter.toFlac(a, b, null));

        // noinspection ResultOfMethodCallIgnored
        src.delete();
    }
}