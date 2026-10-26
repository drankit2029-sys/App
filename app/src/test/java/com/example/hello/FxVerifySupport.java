package com.example.hello;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * Shared plumbing for the FX verification harness: where artifacts go, how the
 * deterministic probes are synthesised, and 32-bit float WAV I/O that matches
 * the app's own working format.
 */
public final class FxVerifySupport {

    private FxVerifySupport() {}

    /** Every probe the matrix can render through an effect. */
    public static final String[] PROBES = {
            "clip", "tone", "sweep", "noise", "impulse", "clicks",
            "hum", "clipped", "gaps", "plosive", "stereo", "silence",
            "sibilant"};

    public static int probeChannels(String name) {
        return "stereo".equals(name) ? 2 : 1;
    }

    /** Repo root, found by walking up from the test working directory. */
    public static File repoRoot() {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (new File(dir, "settings.gradle").isFile()) return dir;
            dir = dir.getParentFile();
        }
        return new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }

    /** Artifact directory: {@code $FX_OUT} when set, else app/build/fxverify. */
    public static File outRoot(String sub) {
        String env = System.getenv("FX_OUT");
        File base = (env != null && !env.isEmpty())
                ? new File(env)
                : new File(repoRoot(), "app/build/fxverify");
        File dir = (sub == null || sub.isEmpty()) ? base : new File(base, sub);
        dir.mkdirs();
        return dir;
    }

    /** The uploaded verification clip, committed as a harness fixture. */
    public static File fixtureClip() {
        File f = new File(repoRoot(), "tools/fxverify/fixtures/take2_48k_f32.wav");
        if (!f.isFile()) {
            throw new IllegalStateException("Fixture clip missing: " + f);
        }
        return f;
    }

    /** The dial the FX card drives for an effect (null when it has none). */
    public static String primaryParamId(DspChain.EffectType type) {
        try {
            Method m = DspChain.class.getDeclaredMethod("primaryParamId",
                    DspChain.EffectType.class);
            m.setAccessible(true);
            Object v = m.invoke(null, type);
            return v == null ? null : (String) v;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- WAV I/O

    public static final class WavData {
        public final float[] samples;
        public final int channels;
        public final int sampleRate;
        public final int frames;

        WavData(float[] samples, int channels, int sampleRate) {
            this.samples = samples;
            this.channels = channels;
            this.sampleRate = sampleRate;
            this.frames = channels == 0 ? 0 : samples.length / channels;
        }
    }

    /** Writes interleaved floats as the app's 32-bit float WAV. */
    public static void writeFloatWav(File f, float[] samples, int channels,
                                     int sampleRate) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(0);
            w.write(Dsp.wavFloatHeader(channels, sampleRate,
                    (long) samples.length * 4));
            Dsp.writeFloatsLE(w, samples, 0, samples.length);
        }
    }

    /** Reads any 32-bit float WAV, tolerating extra RIFF chunks. */
    public static WavData readFloatWav(File f) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[12];
            r.readFully(head);
            int channels = 1;
            int sampleRate = 48000;
            int bits = 0;
            int format = 0;
            long dataStart = -1;
            long dataBytes = 0;
            long pos = 12;
            long len = r.length();
            while (pos + 8 <= len) {
                r.seek(pos);
                byte[] id = new byte[4];
                r.readFully(id);
                byte[] sz = new byte[4];
                r.readFully(sz);
                long size = Integer.toUnsignedLong(
                        ByteBuffer.wrap(sz).order(ByteOrder.LITTLE_ENDIAN).getInt());
                String tag = new String(id, "US-ASCII");
                if ("fmt ".equals(tag)) {
                    byte[] fmt = new byte[(int) Math.min(size, 40)];
                    r.readFully(fmt);
                    ByteBuffer b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    format = b.getShort() & 0xFFFF;
                    channels = b.getShort() & 0xFFFF;
                    sampleRate = b.getInt();
                    bits = b.getShort() & 0xFFFF;
                } else if ("data".equals(tag)) {
                    dataStart = pos + 8;
                    dataBytes = Math.min(size, len - dataStart);
                }
                pos += 8 + size + (size % 2);
            }
            if (format != 3 || bits != 32 || dataStart < 0) {
                throw new IllegalStateException("Not a 32-bit float WAV: " + f
                        + " (format=" + format + " bits=" + bits + ")");
            }
            int count = (int) (dataBytes / 4);
            byte[] bytes = new byte[count * 4];
            r.seek(dataStart);
            r.readFully(bytes);
            float[] out = new float[count];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(out);
            return new WavData(out, channels, sampleRate);
        }
    }

    // ------------------------------------------------------------ probe signal

    /**
     * Deterministic probe synthesis. The same recipe is used for every shard, so
     * a case rendered in one job is comparable with any other run.
     */
    public static float[] buildProbe(String name, int frames, int channels,
                                     WavData clip) {
        float[] out = new float[frames * channels];
        switch (name) {
            case "clip": {
                int ch = clip.channels;
                int n = Math.min(frames, clip.frames);
                for (int f = 0; f < n; f++) {
                    for (int c = 0; c < channels; c++) {
                        out[f * channels + c] = clip.samples[f * ch + (c % ch)];
                    }
                }
                return out;
            }
            case "tone": {
                for (int f = 0; f < frames; f++) {
                    float s = (float) Math.sin(2 * Math.PI * 1000.0 * f / 48000.0) * 0.3f;
                    for (int c = 0; c < channels; c++) out[f * channels + c] = s;
                }
                return out;
            }
            case "sweep": {
                double f0 = 20.0, f1 = 20000.0, t = frames / 48000.0;
                double k = Math.log(f1 / f0);
                for (int i = 0; i < frames; i++) {
                    double time = i / 48000.0;
                    double phase = 2 * Math.PI * f0 * t / k * (Math.exp(k * time / t) - 1);
                    float s = (float) Math.sin(phase) * 0.5f;
                    for (int c = 0; c < channels; c++) out[i * channels + c] = s;
                }
                return out;
            }
            case "sibilant": {
                for (int f = 0; f < frames; f++) {
                    double t = f / 48000.0;
                    double s = Math.sin(2 * Math.PI * 7000.0 * t) * 0.5
                            + Math.sin(2 * Math.PI * 8400.0 * t) * 0.25
                            + Math.sin(2 * Math.PI * 1000.0 * t) * 0.2;
                    for (int c = 0; c < channels; c++) {
                        out[f * channels + c] = (float) s;
                    }
                }
                return out;
            }
            case "noise": {
                Random rnd = new Random(42);
                for (int f = 0; f < frames; f++) {
                    float s = (rnd.nextFloat() * 2f - 1f) * 0.05f;
                    for (int c = 0; c < channels; c++) out[f * channels + c] = s;
                }
                return out;
            }
            case "impulse": {
                for (int hit = 0; hit < 4; hit++) {
                    int at = (int) ((0.5 + hit * 3.0) * 48000);
                    if (at >= frames) break;
                    for (int c = 0; c < channels; c++) out[at * channels + c] = 0.9f;
                }
                return out;
            }
            case "clicks": {
                for (int f = 0; f < frames; f++) {
                    float s = (float) Math.sin(2 * Math.PI * 1000.0 * f / 48000.0) * 0.2f;
                    for (int c = 0; c < channels; c++) out[f * channels + c] = s;
                }
                for (int hit = 0; hit < frames / 24000; hit++) {
                    int at = hit * 24000 + 12000;
                    if (at >= frames) break;
                    for (int c = 0; c < channels; c++) out[at * channels + c] = 0.7f;
                }
                return out;
            }
            case "hum": {
                for (int f = 0; f < frames; f++) {
                    double t = f / 48000.0;
                    double s = Math.sin(2 * Math.PI * 1000.0 * t) * 0.25
                            + Math.sin(2 * Math.PI * 50.0 * t) * 0.25
                            + Math.sin(2 * Math.PI * 100.0 * t) * 0.08
                            + Math.sin(2 * Math.PI * 150.0 * t) * 0.05
                            + Math.sin(2 * Math.PI * 200.0 * t) * 0.03;
                    for (int c = 0; c < channels; c++) {
                        out[f * channels + c] = (float) s;
                    }
                }
                return out;
            }
            case "clipped": {
                for (int f = 0; f < frames; f++) {
                    float s = (float) (Math.sin(2 * Math.PI * 400.0 * f / 48000.0) * 1.4);
                    s = Math.max(-0.95f, Math.min(0.95f, s));
                    for (int c = 0; c < channels; c++) out[f * channels + c] = s;
                }
                return out;
            }
            case "gaps": {
                for (int f = 0; f < frames; f++) {
                    float s = (float) Math.sin(2 * Math.PI * 1000.0 * f / 48000.0) * 0.3f;
                    double t = f / 48000.0;
                    boolean silent = (t >= 2.0 && t < 3.0) || (t >= 6.0 && t < 7.5)
                            || (t >= 10.0 && t < 10.6);
                    for (int c = 0; c < channels; c++) {
                        out[f * channels + c] = silent ? 0f : s;
                    }
                }
                return out;
            }
            case "plosive": {
                for (int f = 0; f < frames; f++) {
                    float s = (float) Math.sin(2 * Math.PI * 1000.0 * f / 48000.0) * 0.25f;
                    for (int c = 0; c < channels; c++) out[f * channels + c] = s;
                }
                for (int hit = 0; hit < 4; hit++) {
                    int start = (int) ((1.0 + hit * 2.0) * 48000);
                    int end = Math.min(frames, start + (int) (0.3 * 48000));
                    for (int f = start; f < end; f++) {
                        float burst = (float) (Math.sin(2 * Math.PI * 90.0 * f / 48000.0)
                                * 0.8 * Math.exp(-(f - start) / 4000.0));
                        for (int c = 0; c < channels; c++) {
                            out[f * channels + c] += burst;
                        }
                    }
                }
                return out;
            }
            case "stereo": {
                for (int f = 0; f < frames; f++) {
                    out[f * 2] = (float) Math.sin(2 * Math.PI * 440.0 * f / 48000.0) * 0.4f;
                    out[f * 2 + 1] = (float) Math.sin(2 * Math.PI * 660.0 * f / 48000.0) * 0.4f;
                }
                return out;
            }
            case "silence":
            default:
                return out;
        }
    }

    /** Human readable probe description for the report. */
    public static String probeDescription(String name) {
        switch (name) {
            case "clip": return "the uploaded Take 2 recording (truncated)";
            case "tone": return "1 kHz sine at -10 dBFS";
            case "sweep": return "20 Hz to 20 kHz log sweep";
            case "noise": return "white noise at -26 dBFS";
            case "impulse": return "impulses at 0.5 s, 3.5 s, 6.5 s, 9.5 s";
            case "clicks": return "1 kHz tone with periodic full-scale clicks";
            case "hum": return "1 kHz tone with 50 Hz mains hum and harmonics";
            case "clipped": return "400 Hz sine hard-clipped at 0.95";
            case "gaps": return "1 kHz tone with 1 s, 1.5 s and 0.6 s silent gaps";
            case "plosive": return "1 kHz tone with four 90 Hz plosive bursts";
            case "sibilant": return "7 kHz and 8.4 kHz sibilance over a 1 kHz body tone";
            case "stereo": return "440 Hz left, 660 Hz right";
            case "silence": return "digital silence";
            default: return name;
        }
    }
}
