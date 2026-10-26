package com.example.hello;

import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Tier 3 of the FX verification harness: converts the uploaded 32-bit float WAV
 * into every container the app can write and records exactly what came out, so
 * the container/codec claims can be checked independently (with ffprobe) rather
 * than trusted.
 *
 * <p>Formats that need a hardware/software encoder are probed first, and a
 * format the device cannot encode is reported as skipped with its reason.
 */
@RunWith(AndroidJUnit4.class)
public class FormatMatrixDeviceTest {

    @Test
    public void convertTheUploadedClipIntoEveryFormat() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File work = new File(RecordingEngine.takesDir(ctx), "fxverify-formats");
        work.mkdirs();

        File input = new File(work, "input.wav");
        copyAsset("take2_48k_f32.wav", input);
        FxVerifySupportDevice.WavInfo info = FxVerifySupportDevice.readInfo(input);

        JSONObject root = new JSONObject();
        root.put("input", "input.wav");
        root.put("inputFrames", info.frames);
        root.put("inputChannels", info.channels);
        root.put("inputSampleRate", info.sampleRate);
        root.put("inputAudioFormat", info.audioFormat);
        root.put("inputBits", info.bits);
        JSONArray results = new JSONArray();

        for (SaveFormat fmt : SaveFormat.values()) {
            JSONObject entry = new JSONObject();
            entry.put("id", fmt.id);
            entry.put("extension", fmt.extension());
            entry.put("effective", fmt.effective(RecordPreset.PODCAST).id);
            try {
                if (!fmt.available(ctx)) {
                    entry.put("skipped", "no encoder for " + fmt.encoderMime());
                    results.put(entry);
                    continue;
                }
                SaveFormat effective = fmt.effective(RecordPreset.PODCAST);
                TakeSaver.Result r = TakeSaver.convertRaw(ctx, input,
                        "take2-" + fmt.id, RecordPreset.PODCAST, fmt, null);
                assertNotNull("convertRaw returned nothing for " + fmt.id, r);
                assertNotNull("no file for " + fmt.id, r.file);
                File out = new File(work, "fmt-" + fmt.id + "." + r.file.getName()
                        .substring(r.file.getName().lastIndexOf('.') + 1));
                copy(r.file, out);
                entry.put("file", out.getName());
                entry.put("bytes", out.length());
                entry.put("label", r.label);
                entry.put("durationMs", r.durationMs);
                entry.put("title", r.title);
                entry.put("effectiveId", effective.id);
                try {
                    FormatConverter.validateFinal(out, effective);
                    entry.put("validateFinal", "ok");
                } catch (Throwable t) {
                    entry.put("validateFinal", "failed: " + t.getMessage());
                }
                entry.put("probe", probeHeader(out));
            } catch (Throwable t) {
                entry.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            results.put(entry);
        }

        JSONArray strays = new JSONArray();
        File[] siblings = work.getParentFile() == null ? new File[0]
                : work.getParentFile().listFiles();
        if (siblings != null) {
            for (File f : siblings) {
                if (f.getName().endsWith(".part") || f.getName().contains(".png")) {
                    strays.put(f.getName());
                }
            }
        }
        root.put("leftoverParts", strays);
        root.put("results", results);

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(new File(work, "formats.json")),
                StandardCharsets.UTF_8)) {
            w.write(root.toString(2));
        }
        System.out.println("Format matrix: " + results.length() + " formats attempted");
    }

    /** First bytes of the container, so the analysis side can check the magic. */
    private static String probeHeader(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[16];
            int n = in.read(head);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.max(0, n); i++) {
                sb.append(String.format("%02x", head[i]));
            }
            return sb.toString();
        }
    }

    private static void copyAsset(String name, File dest) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation()
                .getContext().getAssets().open(name);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
