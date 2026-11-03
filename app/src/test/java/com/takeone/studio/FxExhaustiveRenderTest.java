package com.takeone.studio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic renderer for an exhaustive effect plan produced by
 * {@code tools/fxverify/exhaustive_plan.py}. The plan supplies the input file,
 * every case's raw parameter values and the output name; this test only sets
 * parameters, renders, and records measurements. It is gated by
 * {@code FX_EXHAUSTIVE_PLAN} so the normal unit shards skip it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxExhaustiveRenderTest {

    @Test
    public void renderEveryPlannedCase() throws Exception {
        String planPath = System.getenv("FX_EXHAUSTIVE_PLAN");
        Assume.assumeTrue(
                "FX_EXHAUSTIVE_PLAN not set: runs from the exhaustive job",
                planPath != null && !planPath.isEmpty());

        File planFile = new File(planPath);
        if (!planFile.isFile()) throw new IllegalStateException("Missing plan " + planFile);
        JSONObject plan = new JSONObject(readText(planFile));
        String effectId = plan.getString("effect");
        String outputFormat = plan.optString("output_format", "pcm16");
        String rootPath = System.getenv("FX_EXHAUSTIVE_ROOT");
        if (rootPath == null || rootPath.isEmpty()) {
            throw new IllegalStateException("FX_EXHAUSTIVE_ROOT not set");
        }
        File root = new File(rootPath);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("Cannot create " + root);
        }

        File inputFile = new File(root, plan.getString("input"));
        FxVerifySupport.WavData input = FxVerifySupport.readFloatWav(inputFile);
        int sampleRate = input.sampleRate;
        int channels = input.channels;
        int frames = input.frames;

        JSONArray results = new JSONArray();
        JSONArray errors = new JSONArray();
        JSONArray cases = plan.getJSONArray("cases");
        int rendered = 0;
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            if (!c.optBoolean("render", false)) continue;
            String id = c.getString("id");
            String outputName = c.optString("output", "");
            if (outputName.isEmpty()) {
                errors.put(id + ": missing output name");
                continue;
            }

            AudioProcessor processor = FxRackChain.createProcessor(effectId);
            if (processor == null) {
                errors.put(id + ": no processor for " + effectId);
                continue;
            }
            for (FxParameter p : processor.getParameters()) p.resetToDefault();
            JSONObject values = c.getJSONObject("values");
            Map<String, Integer> rawValues = new HashMap<>();
            for (FxParameter p : processor.getParameters()) {
                int raw = values.optInt(p.id, p.defaultRaw);
                rawValues.put(p.id, raw);
                p.setCurrentRaw(raw);
            }

            boolean disabledCase = "disabled".equals(c.optString("kind", ""));
            String relevanceError = null;
            for (FxParameter p : processor.getParameters()) {
                boolean applicable = processor.isParamApplicable(p.id, rawValues);
                if (!applicable) {
                    int raw = rawValues.get(p.id);
                    if (disabledCase && raw != p.defaultRaw) continue;
                    if (raw != p.defaultRaw) {
                        relevanceError = p.id + " is inactive but was set to " + raw;
                        break;
                    }
                }
            }
            if (relevanceError != null) {
                errors.put(id + ": " + relevanceError);
                continue;
            }

            try {
                processor.prepare(sampleRate, channels);
                processor.reset();
                float[] result;
                float[] in = input.samples.clone();
                if (processor.isOfflineOnly()) {
                    result = processor.processOfflineRegion(in, frames, channels);
                } else {
                    processor.processInPlace(in, frames, channels);
                    result = in;
                }
                if (result == null) {
                    errors.put(id + ": processor returned null");
                    continue;
                }
                File out = new File(root, outputName);
                if ("pcm16".equals(outputFormat)) {
                    writePcm16(out, result, channels, sampleRate);
                } else {
                    FxVerifySupport.writeFloatWav(out, result, channels, sampleRate);
                }
                JSONObject r = new JSONObject();
                r.put("id", id);
                r.put("kind", c.optString("kind", ""));
                r.put("output", outputName);
                r.put("frames", result.length / Math.max(1, channels));
                r.put("channels", channels);
                r.put("sampleRate", sampleRate);
                r.put("peak", peak(result));
                r.put("finite", finite(result));
                results.put(r);
                rendered++;
            } catch (Throwable t) {
                errors.put(id + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }

        JSONObject rootJson = new JSONObject();
        rootJson.put("effect", effectId);
        rootJson.put("rendered", rendered);
        rootJson.put("cases", results);
        rootJson.put("errors", errors);
        Files.write(new File(root, "results.json").toPath(),
                rootJson.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("FX exhaustive " + effectId + ": " + rendered
                + " rendered, " + errors.length() + " errors");
    }

    private static float peak(float[] samples) {
        float peak = 0f;
        for (float v : samples) peak = Math.max(peak, Math.abs(v));
        return peak;
    }

    private static boolean finite(float[] samples) {
        for (float v : samples) {
            if (!Float.isFinite(v)) return false;
        }
        return true;
    }

    private static String readText(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Writes 16-bit PCM so an exhaustive run stays small and easy to inspect. */
    private static void writePcm16(File f, float[] samples, int channels,
                                   int sampleRate) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        int dataBytes = samples.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + dataBytes);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * channels * 2);
        bb.putShort((short) (channels * 2));
        bb.putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(dataBytes);
        for (float v : samples) {
            int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            bb.putShort((short) s);
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.write(bb.array());
        }
    }
}
