package com.takeone.studio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tier 1 of the FX verification harness: the exhaustive parameter matrix.
 *
 * <p>Runs one effect (selected by {@code FX_EFFECT}) through every parameter it
 * exposes, at the parameter's minimum, default and maximum (or every enum
 * label / both boolean states). Secondary parameters are exercised at an
 * active primary operating point, and the mode parameters are switched to a
 * state where the parameter applies, so a secondary control that only works
 * once its effect is actually engaged is measured honestly. Each case is
 * rendered against the probes that can actually show
 * the parameter's effect, and every render is written out as a 32-bit float
 * WAV next to a JSON manifest, so the analysis side never has to guess what a
 * file contains.
 *
 * <p>The DSP layer is pure Java, so this runs on a plain JVM shard - no
 * emulator, no MediaCodec - which is what makes an exhaustive sweep cheap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FxParamMatrixDumpTest {

    private static final int SR = 48000;
    private static final int FULL_FRAMES = 30 * SR;

    /** Probes that can show a given effect's behaviour. */
    private static final Map<String, String[]> EFFECT_PROBES = new HashMap<>();
    /** Per-parameter overrides, keyed {@code effect.param}. */
    private static final Map<String, String[]> PARAM_PROBES = new HashMap<>();
    private static final String[] DEFAULT_PROBES = {"clip", "tone"};

    static {
        EFFECT_PROBES.put("noise", new String[]{"noise"});
        EFFECT_PROBES.put("specnr", new String[]{"noise"});
        EFFECT_PROBES.put("deess", new String[]{"sibilant"});
        EFFECT_PROBES.put("deesssplit", new String[]{"sibilant"});
        EFFECT_PROBES.put("hpf", new String[]{"sweep", "tone"});
        EFFECT_PROBES.put("highpass", new String[]{"sweep", "tone"});
        EFFECT_PROBES.put("lowpass", new String[]{"sweep", "tone"});
        EFFECT_PROBES.put("presence", new String[]{"sweep"});
        EFFECT_PROBES.put("warmth", new String[]{"sweep"});
        EFFECT_PROBES.put("gate", new String[]{"gaps", "noise"});
        EFFECT_PROBES.put("gateexp", new String[]{"gaps", "noise"});
        EFFECT_PROBES.put("limiter", new String[]{"tone", "clipped"});
        EFFECT_PROBES.put("bricklim", new String[]{"tone", "clipped"});
        EFFECT_PROBES.put("comp", new String[]{"tone", "gaps"});
        EFFECT_PROBES.put("dyncomp", new String[]{"tone", "gaps"});
        EFFECT_PROBES.put("mbcomp", new String[]{"sweep", "tone"});
        EFFECT_PROBES.put("eq6", new String[]{"sweep"});
        EFFECT_PROBES.put("geq", new String[]{"sweep"});
        EFFECT_PROBES.put("fcurve", new String[]{"sweep"});
        EFFECT_PROBES.put("basstreble", new String[]{"sweep"});
        EFFECT_PROBES.put("notch", new String[]{"sweep"});
        EFFECT_PROBES.put("shelf", new String[]{"sweep"});
        EFFECT_PROBES.put("classic", new String[]{"sweep"});
        EFFECT_PROBES.put("amplify", new String[]{"tone"});
        EFFECT_PROBES.put("normalize", new String[]{"tone"});
        EFFECT_PROBES.put("loudness", new String[]{"tone"});
        EFFECT_PROBES.put("declick", new String[]{"clicks"});
        EFFECT_PROBES.put("clipfix", new String[]{"clipped"});
        EFFECT_PROBES.put("repair", new String[]{"gaps"});
        EFFECT_PROBES.put("dehum", new String[]{"hum"});
        EFFECT_PROBES.put("plosive", new String[]{"plosive"});
        EFFECT_PROBES.put("reverb", new String[]{"impulse"});
        EFFECT_PROBES.put("multidelay", new String[]{"impulse"});
        EFFECT_PROBES.put("echo", new String[]{"impulse"});
        EFFECT_PROBES.put("stereoimg", new String[]{"stereo"});
        EFFECT_PROBES.put("distort", new String[]{"tone"});
        EFFECT_PROBES.put("phaser", new String[]{"tone"});
        EFFECT_PROBES.put("chorus", new String[]{"tone"});
        EFFECT_PROBES.put("tremolo", new String[]{"tone"});
        EFFECT_PROBES.put("wah", new String[]{"tone"});
        EFFECT_PROBES.put("vocoder", new String[]{"tone", "stereo"});
        EFFECT_PROBES.put("changepitch", new String[]{"tone"});
        EFFECT_PROBES.put("tempo", new String[]{"tone"});
        EFFECT_PROBES.put("speedpitch", new String[]{"tone"});
        EFFECT_PROBES.put("slidestretch", new String[]{"tone"});
        EFFECT_PROBES.put("paulstretch", new String[]{"tone"});
        EFFECT_PROBES.put("fade", new String[]{"tone"});
        EFFECT_PROBES.put("timeline", new String[]{"tone"});
        EFFECT_PROBES.put("truncsil", new String[]{"gaps"});
        EFFECT_PROBES.put("spectraledit", new String[]{"sweep"});
        EFFECT_PROBES.put("generator", new String[]{"silence"});
        EFFECT_PROBES.put("autoduck", new String[]{"gaps"});

        // Vocoder carriers and stereo tools only make sense in stereo, and the
        // per-parameter overrides below pick the damage each repair tool needs.
        PARAM_PROBES.put("stereoimg.st_pan", new String[]{"stereo"});
        PARAM_PROBES.put("stereoimg.st_center", new String[]{"stereo"});
        PARAM_PROBES.put("vocoder.vc_carrier", new String[]{"stereo", "tone"});
        PARAM_PROBES.put("vocoder.vc_orig", new String[]{"tone"});
        PARAM_PROBES.put("dehum.hum_freq", new String[]{"hum"});
        PARAM_PROBES.put("dehum.hum_harm", new String[]{"hum"});
        PARAM_PROBES.put("dehum.hum_q", new String[]{"hum"});
        PARAM_PROBES.put("dehum.hum_depth", new String[]{"hum"});
    }

    @Test
    public void dumpParameterMatrix() throws Exception {
        String effectId = arg("FX_EFFECT");
        // Runs in the fx-verify workflow, one shard per effect. The normal unit
        // suite has no effect selected, so this test steps aside there.
        org.junit.Assume.assumeTrue(
                "FX_EFFECT not set: this shard test runs from fx-verify.yml",
                effectId != null && !effectId.isEmpty());
        boolean full = "1".equals(arg("FX_FULL"));
        int frames = full ? FULL_FRAMES : 12 * SR;
        int maxCases = intArg("FX_MAX_CASES", 1500);

        DspChain.EffectType type = null;
        for (DspChain.EffectType t : DspChain.EffectType.values()) {
            if (t.id.equals(effectId)) type = t;
        }
        if (type == null) throw new IllegalStateException("Unknown effect " + effectId);

        File out = FxVerifySupport.outRoot(effectId);
        FxVerifySupport.WavData clip = FxVerifySupport.readFloatWav(
                FxVerifySupport.fixtureClip());

        AudioProcessor proto = FxRackChain.createProcessor(effectId);
        if (proto == null) throw new IllegalStateException("No processor " + effectId);
        List<FxParameter> params = proto.getParameters();
        boolean profiled = type.requiresLearnedProfile();
        String primary = FxVerifySupport.primaryParamId(type);

        // ---- plan the cases -------------------------------------------------
        Set<String> probesUsed = new LinkedHashSet<>();
        List<Case> cases = new ArrayList<>();
        Map<String, Integer> defaultValues = new HashMap<>();
        for (FxParameter q : params) defaultValues.put(q.id, q.defaultRaw);
        for (FxParameter p : params) {
            String[] probes = probesFor(effectId, p.id, primary);
            for (String probe : probes) probesUsed.add(probe);
            int[] values = valuesFor(p);
            Map<String, Integer> context =
                    activeContext(proto, p, primary, defaultValues);
            for (int i = 0; i < values.length; i++) {
                Map<String, Integer> candidateValues =
                        new HashMap<>(context);
                candidateValues.put(p.id, values[i]);
                if (!proto.isParamApplicable(p.id, candidateValues)) continue;
                String[] modes = profiled
                        ? new String[]{"adaptive", "profiled"}
                        : new String[]{"plain"};
                for (String mode : modes) {
                    for (String probe : probes) {
                        Case c = new Case();
                        c.param = p.id;
                        c.paramName = p.name;
                        c.type = p.type.name();
                        c.unit = p.unit;
                        c.valueRaw = values[i];
                        c.valueDisplay = p.formatRaw(values[i]);
                        c.defaultRaw = p.defaultRaw;
                        c.isDefaultValue = values[i] == p.defaultRaw;
                        c.probe = probe;
                        c.mode = mode;
                        c.kind = "param";
                        c.context = context;
                        c.id = sanitize(p.id + "_" + values[i] + "_" + probe
                                + ("plain".equals(mode) ? "" : "_" + mode));
                        cases.add(c);
                    }
                }
            }
        }
        // Series: a five point walk of every continuous parameter, for
        // monotonicity evidence rather than a single point comparison.
        List<Case> series = new ArrayList<>();
        for (FxParameter p : params) {
            if (p.type != FxParameter.Type.INT && p.type != FxParameter.Type.FLOAT
                    && p.type != FxParameter.Type.CURVE_NODES) continue;
            if (p.maxRaw <= p.minRaw) continue;
            String probe = probesFor(effectId, p.id, primary)[0];
            probesUsed.add(probe);
            int[] vals = fiveSteps(p);
            Map<String, Integer> context =
                    activeContext(proto, p, primary, defaultValues);
            for (int i = 0; i < vals.length; i++) {
                Map<String, Integer> candidateValues =
                        new HashMap<>(context);
                candidateValues.put(p.id, vals[i]);
                if (!proto.isParamApplicable(p.id, candidateValues)) continue;
                Case c = new Case();
                c.param = p.id;
                c.paramName = p.name;
                c.type = p.type.name();
                c.unit = p.unit;
                c.valueRaw = vals[i];
                c.valueDisplay = p.formatRaw(vals[i]);
                c.defaultRaw = p.defaultRaw;
                c.isDefaultValue = vals[i] == p.defaultRaw;
                c.probe = probe;
                c.mode = profiled ? "profiled" : "plain";
                c.kind = "series";
                c.seriesIndex = i;
                c.context = context;
                c.id = sanitize("series_" + p.id + "_" + i);
                series.add(c);
            }
        }

        List<Case> all = new ArrayList<>(cases);
        all.addAll(series);
        if (all.size() > maxCases) {
            throw new IllegalStateException("Shard " + effectId + " planned "
                    + all.size() + " cases, above FX_MAX_CASES=" + maxCases
                    + "; raise the cap or narrow the probe policy");
        }

        // ---- render the probes once ----------------------------------------
        JSONArray probeJson = new JSONArray();
        Map<String, File> dryFiles = new HashMap<>();
        Map<String, Integer> probeFrames = new HashMap<>();
        for (String name : probesUsed) {
            int ch = FxVerifySupport.probeChannels(name);
            int n = frames;
            if ("clip".equals(name)) n = Math.min(frames, clip.frames);
            float[] buf = FxVerifySupport.buildProbe(name, n, ch, clip);
            File f = new File(out, "dry/" + name + ".wav");
            FxVerifySupport.writeFloatWav(f, buf, ch, SR);
            dryFiles.put(name, f);
            probeFrames.put(name, n);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("channels", ch);
            o.put("frames", n);
            o.put("file", "dry/" + name + ".wav");
            o.put("description", FxVerifySupport.probeDescription(name));
            probeJson.put(o);
        }

        // ---- render the cases ----------------------------------------------
        JSONArray caseJson = new JSONArray();
        JSONArray errorJson = new JSONArray();
        int rendered = 0;
        for (Case c : all) {
            AudioProcessor p = FxRackChain.createProcessor(effectId);
            if (p == null) throw new IllegalStateException("No processor " + effectId);
            for (FxParameter fp : p.getParameters()) fp.resetToDefault();
            if (c.context != null) {
                for (Map.Entry<String, Integer> e : c.context.entrySet()) {
                    FxParameter cp = p.getParam(e.getKey());
                    if (cp != null) cp.setCurrentRaw(e.getValue());
                }
            }
            String profileNote = "";
            if (profiled && "profiled".equals(c.mode)) {
                profileNote = installProfile(p, frames, clip);
                if (profileNote == null) c.mode = "adaptive";
            }
            FxParameter target = p.getParam(c.param);
            if (target == null) {
                errorJson.put("missing parameter " + c.param + " on " + effectId);
                continue;
            }
            target.setCurrentRaw(c.valueRaw);
            int ch = FxVerifySupport.probeChannels(c.probe);
            int n = probeFrames.get(c.probe);
            float[] in = FxVerifySupport.buildProbe(c.probe, n, ch, clip);

            JSONObject o = c.toJson();
            // Preserve-Formants only has a meaning during an actual pitch
            // shift. Keep the matrix case at a real +4 st operating point.
            if ("changepitch".equals(effectId)
                    && "pitch_formant".equals(c.param)
                    && p.getParam("pitch_semitones") != null
                    && Math.abs(p.getParam("pitch_semitones").getFloat()) < 1f) {
                p.getParam("pitch_semitones").setFloat(4f);
                o.put("pitchShiftForFormant", 4.0);
            }
            o.put("channels", ch);
            o.put("inFrames", n);
            o.put("dry", "dry/" + c.probe + ".wav");
            o.put("offlineOnly", p.isOfflineOnly());
            o.put("latencyFrames", p.getLatencyFrames());
            o.put("tailFrames", p.getTailFrames());
            o.put("handlesOwnWetDryMix", p.handlesOwnWetDryMix());
            if (!profileNote.isEmpty()) o.put("profile", profileNote);
            try {
                p.prepare(SR, ch);
                float[] result;
                if (p.isOfflineOnly()) {
                    float[] res = p.processOfflineRegion(in, n, ch);
                    if (res == null) {
                        o.put("skipped", "offline renderer returned null");
                        caseJson.put(o);
                        continue;
                    }
                    result = res;
                } else {
                    p.processInPlace(in, n, ch);
                    result = in;
                }
                int outFrames = result.length / Math.max(1, ch);
                File wet = new File(out, "wet/" + c.id + ".wav");
                FxVerifySupport.writeFloatWav(wet, result, ch, SR);
                o.put("wet", "wet/" + c.id + ".wav");
                o.put("outFrames", outFrames);
                rendered++;
            } catch (Throwable t) {
                o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                errorJson.put(c.id + " -> " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
            caseJson.put(o);
        }

        JSONObject root = new JSONObject();
        root.put("effect", effectId);
        root.put("title", type.title);
        root.put("sampleRate", SR);
        root.put("frames", frames);
        root.put("fullLength", full);
        root.put("channels", FxVerifySupport.probeChannels("clip"));
        root.put("offlineOnly", proto.isOfflineOnly());
        root.put("requiresLearnedProfile", profiled);
        root.put("primaryParam", primary == null ? "" : primary);
        root.put("paramCount", params.size());
        root.put("caseCount", caseJson.length());
        root.put("rendered", rendered);
        root.put("probes", probeJson);
        root.put("cases", caseJson);
        root.put("errors", errorJson);

        File manifest = new File(out, "cases.json");
        write(manifest, root.toString());
        System.out.println("FX matrix " + effectId + ": " + rendered + "/"
                + caseJson.length() + " cases rendered, " + errorJson.length()
                + " errors, probes=" + probesUsed);
        if (errorJson.length() > 0) {
            System.out.println("FX matrix errors: " + errorJson);
        }
    }

    /** Installs a learned noise profile; returns the note, or null when absent. */
    private static String installProfile(AudioProcessor p, int frames,
                                         FxVerifySupport.WavData clip) {
        boolean speech = "speech".equalsIgnoreCase(arg("FX_PROBE_OVERRIDE"));
        float[] noise = speech
                ? FxVerifySupport.speechNoiseFloor(frames)
                : FxVerifySupport.buildProbe("noise", frames, 1, clip);
        if (p instanceof NoiseSuppressionProcessor) {
            float[] prof = NoiseSuppressionProcessor.learnProfile(noise, 1);
            if (prof == null) return null;
            ((NoiseSuppressionProcessor) p).setNoiseProfile(prof);
            return "noise profile learned from the noise probe";
        }
        if (p instanceof SpectralNoiseReductionProcessor) {
            float[] prof = SpectralNoiseReductionProcessor.learnProfile(noise, 1);
            if (prof == null) return null;
            ((SpectralNoiseReductionProcessor) p).setNoiseProfile(prof);
            return "noise profile learned from the noise probe";
        }
        return null;
    }

    private static String[] probesFor(String effectId, String paramId,
                                      String primary) {
        String forced = arg("FX_PROBE_OVERRIDE");
        if (forced != null && !forced.isEmpty()) {
            String probe = "speech".equalsIgnoreCase(forced)
                    ? speechProbeFor(effectId) : forced;
            return new String[]{probe};
        }
        String[] override = PARAM_PROBES.get(effectId + "." + paramId);
        String[] base = override != null ? override
                : EFFECT_PROBES.getOrDefault(effectId, DEFAULT_PROBES);
        boolean isPrimary = primary != null && primary.equals(paramId);
        if (!isPrimary || Arrays.asList(base).contains("clip")) return base;
        // The uploaded clip is part of the evidence for every effect, so the
        // headline control always gets it in addition to the specialised probe.
        List<String> merged = new ArrayList<>(Arrays.asList(base));
        merged.add("clip");
        return merged.toArray(new String[0]);
    }

    /** The speech probe that can actually show each effect's behaviour. */
    private static String speechProbeFor(String effectId) {
        switch (effectId) {
            case "noise":
            case "specnr":
                return "speech_noise";
            case "gate":
            case "gateexp":
            case "autoduck":
            case "truncsil":
                return "speech_gaps";
            case "declick":
            case "repair":
                return "speech_clicks";
            case "clipfix":
                return "speech_clipped";
            case "dehum":
            case "notch":
                return "speech_hum";
            case "stereoimg":
            case "vocoder":
                return "speech_stereo";
            default:
                return "speech";
        }
    }

    /**
     * Builds the context a case is rendered in: the tested control applies in
     * the selected mode, and the primary dial sits at an operating point where
     * the effect actually does something. Without this, secondary controls of
     * an effect whose primary defaults to zero (mix, amount, gain) measure no
     * movement even when they work correctly.
     */
    private static Map<String, Integer> activeContext(
            AudioProcessor proto, FxParameter tested, String primary,
            Map<String, Integer> defaults) {
        Map<String, Integer> context = new HashMap<>(defaults);
        if (!proto.isParamApplicable(tested.id, context)) {
            for (FxParameter mode : proto.getParameters()) {
                if (mode.type != FxParameter.Type.ENUM
                        && mode.type != FxParameter.Type.BOOLEAN) {
                    continue;
                }
                int states = mode.type == FxParameter.Type.ENUM
                        ? (mode.enumLabels == null ? 0 : mode.enumLabels.length)
                        : 2;
                for (int mi = 0; mi < states; mi++) {
                    Map<String, Integer> trial = new HashMap<>(context);
                    trial.put(mode.id, mi);
                    if (proto.isParamApplicable(tested.id, trial)) {
                        context = trial;
                        break;
                    }
                }
                if (proto.isParamApplicable(tested.id, context)) break;
            }
        }
        if (primary != null && !primary.equals(tested.id)
                && context.containsKey(primary)) {
            FxParameter dial = proto.getParam(primary);
            if (dial != null && dial.type != FxParameter.Type.ENUM
                    && dial.type != FxParameter.Type.BOOLEAN
                    && dial.type != FxParameter.Type.ACTION
                    && dial.type != FxParameter.Type.CURVE_NODES) {
                Map<String, Integer> withDial = new HashMap<>(context);
                withDial.put(primary, activeValue(dial));
                if (proto.isParamApplicable(tested.id, withDial)) {
                    context = withDial;
                }
            }
        }
        return context;
    }

    /** An audible operating point for the effect's primary dial. */
    private static int activeValue(FxParameter p) {
        if (p.type == FxParameter.Type.ENUM
                || p.type == FxParameter.Type.BOOLEAN
                || p.type == FxParameter.Type.ACTION
                || p.type == FxParameter.Type.CURVE_NODES) {
            return p.defaultRaw;
        }
        // A zero-centred modulation primary hides every secondary control
        // until it is moved; give the two time/pitch primaries a real shift.
        if ("pitch_semitones".equals(p.id)) return p.clampRaw(400);
        if ("tempo_percent".equals(p.id)) return p.clampRaw(2000);
        String name = p.name.toLowerCase(java.util.Locale.US);
        boolean amountLike = name.contains("amount") || name.contains("mix")
                || name.contains("depth") || name.contains("drive")
                || name.contains("gain") || name.contains("warmth")
                || name.contains("presence") || name.contains("amplif")
                || name.contains("reduction") || name.contains("compression");
        if (!amountLike) return p.defaultRaw;
        int span = p.maxRaw - p.minRaw;
        if (span <= 0) return p.defaultRaw;
        int value;
        if (" dB".equals(p.unit)) {
            int up = p.defaultRaw + 600;
            value = up <= p.maxRaw ? up : p.defaultRaw - 600;
        } else {
            value = p.minRaw + Math.round(span * 0.5f);
        }
        value = p.clampRaw(value);
        if (value == p.defaultRaw) {
            value = p.clampRaw(p.defaultRaw
                    + Math.max(1, p.interactionStepRaw()));
        }
        return value;
    }

    /** Candidate values for one parameter: bounds, plus every enum state. */
    private static int[] valuesFor(FxParameter p) {
        switch (p.type) {
            case BOOLEAN:
                return new int[]{0, 1};
            case ENUM: {
                int n = p.enumLabels == null ? 1 : p.enumLabels.length;
                int[] out = new int[n];
                for (int i = 0; i < n; i++) out[i] = p.clampRaw(i);
                return out;
            }
            case ACTION:
                return new int[]{1};
            default: {
                int[] raw = {p.clampRaw(p.minRaw), p.clampRaw(p.defaultRaw),
                        p.clampRaw(p.maxRaw)};
                return raw;
            }
        }
    }

    private static int[] fiveSteps(FxParameter p) {
        int[] out = new int[5];
        for (int i = 0; i < 5; i++) {
            long v = p.minRaw + Math.round((p.maxRaw - p.minRaw) * (i / 4.0));
            out[i] = p.clampRaw((int) v);
        }
        return out;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String arg(String key) {
        String env = System.getenv(key);
        if (env != null && !env.isEmpty()) return env;
        return System.getProperty(key.toLowerCase().replace('_', '.'), null);
    }

    private static int intArg(String key, int def) {
        String v = arg(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void write(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f),
                StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }

    /** One planned render. */
    private static final class Case {
        String id;
        String kind;
        String param;
        String paramName;
        String type;
        String unit;
        int valueRaw;
        String valueDisplay;
        int defaultRaw;
        boolean isDefaultValue;
        String probe;
        String mode;
        int seriesIndex = -1;
        Map<String, Integer> context;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("kind", kind);
            o.put("param", param);
            o.put("paramName", paramName);
            o.put("type", type);
            o.put("unit", unit);
            o.put("valueRaw", valueRaw);
            o.put("valueDisplay", valueDisplay);
            o.put("defaultRaw", defaultRaw);
            o.put("isDefaultValue", isDefaultValue);
            o.put("probe", probe);
            o.put("mode", mode);
            if (context != null) {
                JSONObject ctx = new JSONObject();
                for (Map.Entry<String, Integer> e : context.entrySet()) {
                    ctx.put(e.getKey(), e.getValue());
                }
                o.put("context", ctx);
            }
            if (seriesIndex >= 0) o.put("seriesIndex", seriesIndex);
            return o;
        }
    }
}
