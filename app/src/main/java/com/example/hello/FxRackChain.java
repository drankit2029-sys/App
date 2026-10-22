package com.example.hello;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Ordered rack of {@link AudioProcessor}s. This replaces the hardcoded
 * internals of {@link DspChain}: processors run in rack order, a bypassed
 * processor is skipped, and a processor whose wet/dry mix is below 1.0 is
 * blended sample-accurately against the dry signal.
 *
 * <p>The legacy voice-chain stages are registered by id so a rack saved as
 * JSON can be rebuilt without knowing the concrete classes.
 */
public final class FxRackChain {

    /** Creates a fresh processor instance for JSON deserialization. */
    public interface ProcessorFactory {
        AudioProcessor create();
    }

    private static final Map<String, ProcessorFactory> REGISTRY =
            new LinkedHashMap<>();
    private static final Map<String, AudioProcessor> PROTOTYPES =
            new LinkedHashMap<>();

    private static final String[] CANONICAL_LEGACY_ORDER = {
            "hpf", "noise", "gate", "tone", "deess", "comp", "limiter"
    };

    static {
        register("hpf", ProHpfProcessor::new);
        register("noise", NoiseSuppressionProcessor::new);
        register("gate", ProGateProcessor::new);
        register("tone", ProToneProcessor::new);
        register("deess", ProDeEsserProcessor::new);
        register("comp", ProCompressorProcessor::new);
        register("limiter", ProLimiterProcessor::new);
        // Stage 3 EQ & filter suite.
        register("eq6", ParametricEq6BandProcessor::new);
        register("geq", GraphicEqProcessor::new);
        register("fcurve", FilterCurveEqProcessor::new);
        register("basstreble", BassAndTrebleProcessor::new);
        register("highpass", () -> new SlopeFilterProcessor(true));
        register("lowpass", () -> new SlopeFilterProcessor(false));
        register("notch", NotchFilterProcessor::new);
        register("shelf", ShelfFilterProcessor::new);
        register("classic", ClassicFiltersProcessor::new);
        // Stage 4 dynamics & leveling suite.
        register("amplify", AmplifyProcessor::new);
        register("normalize", NormalizeProcessor::new);
        register("loudness", LoudnessNormalizationProcessor::new);
        register("dyncomp", CompressorProcessor::new);
        register("mbcomp", MultibandCompressorProcessor::new);
        register("bricklim", BrickwallLimiterProcessor::new);
        register("gateexp", NoiseGateExpanderProcessor::new);
        register("deesssplit", DeEsserProcessor::new);
        register("autoduck", AutoDuckProcessor::new);
        // Stage 5 restoration suite.
        register("specnr", SpectralNoiseReductionProcessor::new);
        register("declick", ClickRemovalProcessor::new);
        register("clipfix", ClipFixProcessor::new);
        register("repair", RepairProcessor::new);
        register("dehum", DeHumProcessor::new);
        register("plosive", PlosiveBreathSuppressorProcessor::new);
        // Stage 6 spatial, distortion & modulation suite.
        register("reverb", FreeverbReverbProcessor::new);
        register("multidelay", MultiDelayProcessor::new);
        register("echo", EchoProcessor::new);
        register("stereoimg", StereoImagingProcessor::new);
        register("distort", DistortionWaveshaperProcessor::new);
        register("phaser", PhaserProcessor::new);
        register("chorus", ChorusFlangerProcessor::new);
        register("tremolo", TremoloProcessor::new);
        register("wah", WahwahProcessor::new);
        register("vocoder", VocoderProcessor::new);
        // Stage 7 time, utility, generator & analyzer suite.
        register("changepitch", ChangePitchProcessor::new);
        register("tempo", ChangeTempoProcessor::new);
        register("speedpitch", ChangeSpeedAndPitchProcessor::new);
        register("slidestretch", SlidingStretchProcessor::new);
        register("paulstretch", PaulstretchProcessor::new);
        register("fade", FadeProcessor::new);
        register("timeline", TimelineUtilityProcessor::new);
        register("truncsil", TruncateSilenceProcessor::new);
        register("spectraledit", SpectralEditProcessor::new);
        register("generator", AudioGeneratorTool::new);
    }

    /** Registers (or overrides) a processor id for JSON round-trips. */
    public static synchronized void register(String id, ProcessorFactory factory) {
        if (id == null || id.isEmpty() || factory == null) return;
        REGISTRY.put(id, factory);
        PROTOTYPES.remove(id);
    }

    /** Instantiates a registered processor, or null when unknown. */
    public static synchronized AudioProcessor createProcessor(String id) {
        if (id == null) return null;
        ProcessorFactory f = REGISTRY.get(resolveId(id));
        return f == null ? null : f.create();
    }

    /**
     * Maps an effect id onto the processor that implements it. The warmth
     * and presence slots share one tone processor, so their ids resolve to
     * it.
     */
    static String resolveId(String id) {
        if ("warmth".equals(id) || "presence".equals(id)) return "tone";
        return id;
    }

    /**
     * Returns a cached prototype processor for UI metadata and parameter
     * inspection without allocating a throwaway DSP instance on the UI thread.
     */
    public static synchronized AudioProcessor getPrototypeProcessor(String id) {
        if (id == null) return null;
        String lookupId = resolveId(id);
        AudioProcessor p = PROTOTYPES.get(lookupId);
        if (p == null) {
            ProcessorFactory f = REGISTRY.get(lookupId);
            if (f != null) {
                p = f.create();
                if (p != null) {
                    PROTOTYPES.put(lookupId, p);
                }
            }
        }
        return p;
    }

    private final CopyOnWriteArrayList<AudioProcessor> processors =
            new CopyOnWriteArrayList<>();
    private final int channels;
    private int sampleRate = 48000;
    private float[] scratch = new float[0];

    /**
     * Non-null when both {@link DspChain.EffectType#WARMTH_BODY} and
     * {@link DspChain.EffectType#VOCAL_PRESENCE} are enabled and separated by
     * at least one other enabled processor in {@code cfg.slots}. When null
     * (the default/adjacent case), {@link ProToneProcessor} runs both in
     * its single combined shelf pass, so warmth and presence stay in one slot.
     */
    private DspChain.EffectType splitToneFirstType = null;
    private String splitToneAfterProcessorId = null;
    /**
     * Instance key of the processor the second tone stage runs after, so two
     * copies of the same effect are told apart.
     */
    private String splitToneAfterInstanceKey = null;
    private float splitToneFirstMix = 1.0f;
    private float splitToneSecondMix = 1.0f;

    public FxRackChain(int channels) {
        this.channels = Math.max(1, Math.min(2, channels));
    }

    public int getChannels() {
        return channels;
    }

    public boolean addProcessor(AudioProcessor p) {
        if (p == null) return false;
        p.prepare(sampleRate, channels);
        return processors.addIfAbsent(p);
    }

    /** Removes and returns the processor at {@code idx}, or null. */
    public AudioProcessor removeProcessor(int idx) {
        if (idx < 0 || idx >= processors.size()) return null;
        return processors.remove(idx);
    }

    /** Moves a processor to a new rack position (drag reorder). */
    public boolean moveProcessor(int fromIdx, int toIdx) {
        if (fromIdx < 0 || fromIdx >= processors.size()
                || toIdx < 0 || toIdx >= processors.size()
                || fromIdx == toIdx) {
            return false;
        }
        AudioProcessor p = processors.remove(fromIdx);
        if (p == null) return false;
        processors.add(toIdx, p);
        return true;
    }

    public AudioProcessor getProcessor(int idx) {
        if (idx < 0 || idx >= processors.size()) return null;
        return processors.get(idx);
    }

    public int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < processors.size(); i++) {
            if (id.equals(processors.get(i).getId())) return i;
        }
        return -1;
    }

    /**
     * Index of the processor that serves a given rack entry.
     *
     * <p>Rack-local effects are matched by their instance key, so two copies
     * of the same effect stay separate. The legacy stages have no instance
     * key and are matched by their processor id.
     */
    public int indexOfInstance(String instanceKey, String fallbackId) {
        if (instanceKey == null) {
            return fallbackId == null ? -1 : indexOf(fallbackId);
        }
        for (int i = 0; i < processors.size(); i++) {
            if (instanceKey.equals(processors.get(i).getInstanceKey())) return i;
        }
        return -1;
    }

    public int size() {
        return processors.size();
    }

    public boolean isEmpty() {
        return processors.isEmpty();
    }

    public void clear() {
        processors.clear();
    }

    public List<AudioProcessor> processors() {
        return processors;
    }

    /** True when any processor would alter audio. */
    public boolean isActive() {
        for (AudioProcessor p : processors) {
            if (p.isAudible()) return true;
        }
        return false;
    }

    public void reset() {
        for (AudioProcessor p : processors) p.reset();
    }

    /** Runs the optional two-pass analysis hook on every enabled processor. */
    public void prepareOffline(java.io.File raw, long startFrame,
                               long endFrame, int sampleRate, int channels)
            throws Exception {
        for (AudioProcessor p : processors) {
            if (!p.isEnabled() || !p.isAudible()) continue;
            p.prepareOffline(raw, startFrame, endFrame, sampleRate, channels);
        }
    }

    /** Longest tail (frames) reported by any enabled processor. */
    public int getTailFrames() {
        int max = 0;
        for (AudioProcessor p : processors) {
            if (!p.isEnabled()) continue;
            if (p.getTailFrames() > max) max = p.getTailFrames();
        }
        return max;
    }

    /** Total algorithmic latency (frames) summed across active processors. */
    public int getLatencyFrames() {
        int total = 0;
        for (AudioProcessor p : processors) {
            if (!p.isEnabled() || p.isOfflineOnly() || !p.isAudible()) continue;
            if (p.getWetDryMix() <= 0.0f && !(p instanceof ProLimiterProcessor
                    && ((ProLimiterProcessor) p).isSafetyOn())) {
                continue;
            }
            total += Math.max(0, p.getLatencyFrames());
        }
        return total;
    }

    /**
     * Runs every enabled, audible processor in rack order. A processor at
     * full mix is applied in place; a partial mix is applied to a scratch
     * copy and blended back, so 0.5 mix is exactly half dry / half wet.
     * When the brickwall limiter's safety ceiling is armed, the post-blend
     * output is still clamped to the -0.2 dBFS ceiling so upstream gain
     * stages can never clip the output even if the limiter's drive mix is
     * reduced.
     */
    public void processInPlace(float[] buf, int frames) {
        if (buf == null || frames <= 0) return;
        int n = frames * channels;
        if (n > buf.length) n = buf.length;
        final DspChain.EffectType firstTone = this.splitToneFirstType;
        final String splitAfterId = this.splitToneAfterProcessorId;
        final String splitAfterKey = this.splitToneAfterInstanceKey;
        ProToneProcessor toneProcRef = null;
        ProLimiterProcessor safetyLimRef = null;
        if (firstTone != null && splitAfterId != null) {
            int ti = indexOf("tone");
            if (ti >= 0 && processors.get(ti) instanceof ProToneProcessor) {
                toneProcRef = (ProToneProcessor) processors.get(ti);
            }
            int li = indexOf("limiter");
            if (li >= 0 && processors.get(li) instanceof ProLimiterProcessor) {
                safetyLimRef = (ProLimiterProcessor) processors.get(li);
            }
        }

        boolean splitSecondDone = false;
        for (AudioProcessor p : processors) {
            if (!p.isEnabled() || p.isOfflineOnly()) {
                continue;
            }
            boolean splitAnchor = toneProcRef != null && splitAfterId != null
                    && (splitAfterId.equals(p.getId())
                        || (splitAfterKey != null
                            && splitAfterKey.equals(p.getInstanceKey())));
            if (!p.isAudible()) {
                if (splitAnchor && !splitSecondDone) {
                    runSplitToneSecondStage(toneProcRef, firstTone,
                            buf, frames, channels);
                    splitSecondDone = true;
                    if (safetyLimRef != null && safetyLimRef.isSafetyOn()) {
                        safetyLimRef.enforceSafetyCeiling(
                                buf, frames, channels);
                    }
                }
                continue;
            }
            if (toneProcRef != null && p == toneProcRef) {
                if (firstTone == DspChain.EffectType.WARMTH_BODY) {
                    toneProcRef.processWarmthOnly(
                            buf, frames, channels, splitToneFirstMix);
                } else {
                    toneProcRef.processPresenceOnly(
                            buf, frames, channels, splitToneFirstMix);
                }
            } else {
                float mix = p.getWetDryMix();
                if (mix >= 1.0f || (mix > 0.0f && p.handlesOwnWetDryMix())) {
                    p.processInPlace(buf, frames, channels);
                } else if (mix > 0.0f) {
                    if (scratch.length < buf.length) {
                        scratch = new float[Math.max(buf.length, n)];
                    }
                    System.arraycopy(buf, 0, scratch, 0, n);
                    p.processInPlace(scratch, frames, channels);
                    float w = mix;
                    float d = 1.0f - mix;
                    for (int i = 0; i < n; i++) {
                        buf[i] = buf[i] * d + scratch[i] * w;
                    }
                    if (p instanceof ProLimiterProcessor
                            && ((ProLimiterProcessor) p).isSafetyOn()) {
                        ((ProLimiterProcessor) p).enforceSafetyCeiling(
                                buf, frames, channels);
                    }
                } else if (p instanceof ProLimiterProcessor
                        && ((ProLimiterProcessor) p).isSafetyOn()) {
                    ((ProLimiterProcessor) p).enforceSafetyCeiling(
                            buf, frames, channels);
                }
            }

            if (splitAnchor) {
                runSplitToneSecondStage(toneProcRef, firstTone,
                        buf, frames, channels);
                splitSecondDone = true;
                if (safetyLimRef != null && safetyLimRef.isSafetyOn()) {
                    safetyLimRef.enforceSafetyCeiling(buf, frames, channels);
                }
            }
        }
        if (toneProcRef != null && !splitSecondDone) {
            runSplitToneSecondStage(toneProcRef, firstTone,
                    buf, frames, channels);
            if (safetyLimRef != null && safetyLimRef.isSafetyOn()) {
                safetyLimRef.enforceSafetyCeiling(buf, frames, channels);
            }
        }
    }

    /** Test/diagnostic access to the processor that anchors the split point. */
    String getSplitToneAfterId() {
        return splitToneAfterProcessorId;
    }

    private void runSplitToneSecondStage(ProToneProcessor tone,
                                         DspChain.EffectType firstTone,
                                         float[] buf, int frames, int channels) {
        if (firstTone == DspChain.EffectType.WARMTH_BODY) {
            tone.processPresenceOnly(buf, frames, channels, splitToneSecondMix);
        } else {
            tone.processWarmthOnly(buf, frames, channels, splitToneSecondMix);
        }
    }

    public void setSampleRate(int sr) {
        if (sr <= 0) return;
        this.sampleRate = sr;
        for (AudioProcessor p : processors) p.prepare(sampleRate, channels);
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("channels", channels);
            JSONArray a = new JSONArray();
            for (AudioProcessor p : processors) a.put(p.saveState());
            o.put("processors", a);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Rebuilds a rack from {@link #toJson()} output using the registry.
     * Unknown processor ids are skipped so a rack saved by a newer build
     * still loads.
     */
    public static FxRackChain fromJson(int channels, JSONObject json) {
        FxRackChain rack = new FxRackChain(channels);
        if (json == null) return rack;
        JSONArray a = json.optJSONArray("processors");
        if (a == null) return rack;
        for (int i = 0; i < a.length(); i++) {
            JSONObject po = a.optJSONObject(i);
            if (po == null) continue;
            AudioProcessor p = createProcessor(po.optString("id", ""));
            if (p == null) continue;
            p.loadState(po);
            rack.addProcessor(p);
        }
        return rack;
    }

    /**
     * Builds the rack that reproduces the legacy {@code DspChain} stage order
     * (HPF, noise, gate, tone, de-esser, compressor, limiter) for a config.
     * The combined warmth+presence stage stays a single processor because the
     * original engine applied them in one per-sample loop.
     */
    public static FxRackChain fromLegacyConfig(int channels, DspChain.Config cfg) {
        FxRackChain rack = new FxRackChain(channels);
        ProHpfProcessor hpf = new ProHpfProcessor();
        NoiseSuppressionProcessor noise = new NoiseSuppressionProcessor();
        ProGateProcessor gate = new ProGateProcessor();
        ProToneProcessor tone = new ProToneProcessor();
        ProDeEsserProcessor deess = new ProDeEsserProcessor();
        ProCompressorProcessor comp = new ProCompressorProcessor();
        ProLimiterProcessor limiter = new ProLimiterProcessor();
        rack.addProcessor(hpf);
        rack.addProcessor(noise);
        rack.addProcessor(gate);
        rack.addProcessor(tone);
        rack.addProcessor(deess);
        rack.addProcessor(comp);
        rack.addProcessor(limiter);
        rack.applyLegacyConfig(cfg);
        return rack;
    }

    /** Maps a {@link DspChain.EffectType} to its rack processor ID. */
    static String processorIdForSlotType(DspChain.EffectType type) {
        if (type == null) return null;
        switch (type) {
            case LOW_CUT_HPF:
                return "hpf";
            case NOISE_SUPPRESSION:
                return "noise";
            case ROOM_GATE:
                return "gate";
            case WARMTH_BODY:
            case VOCAL_PRESENCE:
                return "tone";
            case DE_ESSER:
                return "deess";
            case COMPRESSOR:
                return "comp";
            case BRICKWALL_LIMITER:
                return "limiter";
            default:
                return type.id;
        }
    }

    /**
     * Synchronizes the rack's processor execution order with the user's
     * {@code cfg.slots} order while preserving existing {@link AudioProcessor}
     * instances and their internal filter/envelope state. If only one of
     * {@code WARMTH_BODY} or {@code VOCAL_PRESENCE} is enabled, the enabled
     * slot determines where {@code "tone"} sits (so an unchecked sibling slot
     * earlier in the list never traps the active tone stage). If both are
     * enabled and separated by another active processor in {@code cfg.slots},
     * records the split point so {@link #processInPlace} runs the second tone
     * stage at its exact downstream slot position.
     */
    private void syncOrderFromSlots(DspChain.Config cfg) {
        this.splitToneFirstType = null;
        this.splitToneAfterProcessorId = null;
        this.splitToneAfterInstanceKey = null;
        this.splitToneFirstMix = 1.0f;
        this.splitToneSecondMix = 1.0f;
        if (cfg == null || cfg.slots == null || cfg.slots.isEmpty()) return;

        boolean anyToneEnabled = false;
        DspChain.EffectSlot firstToneSlot = null;
        DspChain.EffectSlot secondToneSlot = null;
        String lastEnabledBetween = null;
        String lastEnabledBetweenKey = null;
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s == null || s.type == null || !s.enabled) {
                continue;
            }
            if (s.type.isLegacyStage() && s.value <= 0) {
                continue;
            }
            if (s.type == DspChain.EffectType.WARMTH_BODY
                    || s.type == DspChain.EffectType.VOCAL_PRESENCE) {
                anyToneEnabled = true;
                if (firstToneSlot == null) {
                    firstToneSlot = s;
                } else if (secondToneSlot == null && s.type != firstToneSlot.type) {
                    secondToneSlot = s;
                }
            } else if (firstToneSlot != null && secondToneSlot == null) {
                String pid = processorIdForSlotType(s.type);
                if (pid != null && !"tone".equals(pid)) {
                    // The anchor is tracked by instance key so two copies of
                    // the same effect cannot be confused with each other.
                    lastEnabledBetween = pid;
                    lastEnabledBetweenKey = s.type.isLegacyStage()
                            ? null : DspChain.slotInstanceKey(s);
                }
            }
        }
        if (firstToneSlot != null && secondToneSlot != null
                && lastEnabledBetween != null) {
            this.splitToneFirstType = firstToneSlot.type;
            this.splitToneAfterProcessorId = lastEnabledBetween;
            this.splitToneAfterInstanceKey = lastEnabledBetweenKey;
            this.splitToneFirstMix = firstToneSlot.wetDry;
            this.splitToneSecondMix = secondToneSlot.wetDry;
        }

        Set<String> desiredIds = new LinkedHashSet<>();
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s == null || s.type == null) continue;
            if ((s.type == DspChain.EffectType.WARMTH_BODY
                    || s.type == DspChain.EffectType.VOCAL_PRESENCE)
                    && anyToneEnabled && (!s.enabled || s.value <= 0)) {
                // Do not let a disabled tone slot trap the enabled sibling's
                // rack position.
                continue;
            }
            String pid = processorIdForSlotType(s.type);
            if (pid == null) continue;
            // Rack-local effects are ordered by their instance key so two
            // copies of the same effect keep their own positions.
            String key = DspChain.slotInstanceKey(s);
            int idx = indexOfInstance(key, pid);
            if (idx >= 0) {
                desiredIds.add(key);
            } else if (indexOf(pid) >= 0) {
                desiredIds.add(pid);
            }
        }
        for (String canonId : CANONICAL_LEGACY_ORDER) {
            if (indexOf(canonId) >= 0) {
                desiredIds.add(canonId);
            }
        }
        for (AudioProcessor p : processors) {
            String key = p.getInstanceKey();
            if (key != null) {
                desiredIds.add(key);
            } else {
                desiredIds.add(p.getId());
            }
        }
        int targetIdx = 0;
        for (String key : desiredIds) {
            int curIdx = indexOfInstance(key, null);
            if (curIdx < 0) curIdx = indexOf(key);
            if (curIdx >= 0 && curIdx != targetIdx) {
                moveProcessor(curIdx, targetIdx);
            }
            if (curIdx >= 0) {
                targetIdx++;
            }
        }
    }

    /**
     * Pushes a legacy {@code DspChain.Config} into the already-built legacy
     * rack in place, preserving filter/envelope state across live edits (the
     * original engine only changed amounts, never rebuilt the stages) and
     * synchronizing processor order with {@code cfg.slots}.
     */
    void applyLegacyConfig(DspChain.Config cfg) {
        if (cfg == null) {
            this.splitToneFirstType = null;
            this.splitToneAfterProcessorId = null;
            this.splitToneAfterInstanceKey = null;
            for (AudioProcessor p : processors) p.setEnabled(false);
            return;
        }
        cfg.syncLegacyFieldsFromSlots();
        syncOrderFromSlots(cfg);
        for (AudioProcessor p : processors) {
            if (p instanceof ProHpfProcessor) {
                ((ProHpfProcessor) p).setLegacyState(
                        cfg.hpfEnabled, cfg.hpfCutoffHz);
            } else if (p instanceof NoiseSuppressionProcessor) {
                ((NoiseSuppressionProcessor) p).setLegacyAmount(cfg.noisePct);
                p.setEnabled(cfg.noisePct > 0);
            } else if (p instanceof ProGateProcessor) {
                ((ProGateProcessor) p).setLegacyAmount(cfg.gatePct);
                p.setEnabled(cfg.gatePct > 0);
            } else if (p instanceof ProToneProcessor) {
                ((ProToneProcessor) p).setLegacyAmounts(
                        cfg.warmthPct, cfg.presencePct);
                p.setEnabled(cfg.warmthPct > 0 || cfg.presencePct > 0);
            } else if (p instanceof ProDeEsserProcessor) {
                ((ProDeEsserProcessor) p).setLegacyAmount(cfg.deEssPct);
                p.setEnabled(cfg.deEssPct > 0);
            } else if (p instanceof ProCompressorProcessor) {
                ((ProCompressorProcessor) p).setLegacyAmount(cfg.compPct);
                p.setEnabled(cfg.compPct > 0);
            } else if (p instanceof ProLimiterProcessor) {
                // The limiter doubles as the safety ceiling for every
                // gain-producing stage, exactly as the original engine did.
                boolean safety = cfg.compPct > 0 || cfg.warmthPct > 0
                        || cfg.presencePct > 0;
                ((ProLimiterProcessor) p).setLegacyState(
                        cfg.limiterPct, safety);
                p.setEnabled(cfg.limiterPct > 0 || safety);
            }
        }
    }
}
