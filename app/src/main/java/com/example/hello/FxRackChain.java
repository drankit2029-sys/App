package com.example.hello;

import java.util.ArrayList;
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

    private static final String[] CANONICAL_LEGACY_ORDER = {
            "hpf", "noise", "gate", "tone", "deess", "comp", "limiter"
    };

    static {
        register("hpf", LegacyHpfProcessor::new);
        register("noise", LegacyNoiseProcessor::new);
        register("gate", LegacyGateProcessor::new);
        register("tone", LegacyToneProcessor::new);
        register("deess", LegacyDeEsserProcessor::new);
        register("comp", LegacyCompressorProcessor::new);
        register("limiter", LegacyLimiterProcessor::new);
    }

    /** Registers (or overrides) a processor id for JSON round-trips. */
    public static void register(String id, ProcessorFactory factory) {
        if (id == null || id.isEmpty() || factory == null) return;
        REGISTRY.put(id, factory);
    }

    /** Instantiates a registered processor, or null when unknown. */
    public static AudioProcessor createProcessor(String id) {
        ProcessorFactory f = REGISTRY.get(id);
        return f == null ? null : f.create();
    }

    private final CopyOnWriteArrayList<AudioProcessor> processors =
            new CopyOnWriteArrayList<>();
    private final int channels;
    private int sampleRate = 48000;
    private float[] scratch = new float[0];

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

    /** Longest tail (frames) reported by any enabled processor. */
    public int getTailFrames() {
        int max = 0;
        for (AudioProcessor p : processors) {
            if (!p.isEnabled()) continue;
            if (p.getTailFrames() > max) max = p.getTailFrames();
        }
        return max;
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
        for (AudioProcessor p : processors) {
            if (!p.isEnabled() || p.isOfflineOnly() || !p.isAudible()) {
                continue;
            }
            float mix = p.getWetDryMix();
            if (mix >= 1.0f) {
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
                if (p instanceof LegacyLimiterProcessor
                        && ((LegacyLimiterProcessor) p).isSafetyOn()) {
                    ((LegacyLimiterProcessor) p).enforceSafetyCeiling(
                            buf, frames, channels);
                }
            } else if (p instanceof LegacyLimiterProcessor
                    && ((LegacyLimiterProcessor) p).isSafetyOn()) {
                ((LegacyLimiterProcessor) p).enforceSafetyCeiling(
                        buf, frames, channels);
            }
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
        LegacyHpfProcessor hpf = new LegacyHpfProcessor();
        LegacyNoiseProcessor noise = new LegacyNoiseProcessor();
        LegacyGateProcessor gate = new LegacyGateProcessor();
        LegacyToneProcessor tone = new LegacyToneProcessor();
        LegacyDeEsserProcessor deess = new LegacyDeEsserProcessor();
        LegacyCompressorProcessor comp = new LegacyCompressorProcessor();
        LegacyLimiterProcessor limiter = new LegacyLimiterProcessor();
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
     * instances and their internal filter/envelope state. Unreferenced
     * processors keep their canonical relative order (with the safety limiter
     * anchored at the end unless explicitly moved earlier in {@code cfg.slots}).
     */
    private void syncOrderFromSlots(DspChain.Config cfg) {
        if (cfg == null || cfg.slots == null || cfg.slots.isEmpty()) return;
        Set<String> desiredIds = new LinkedHashSet<>();
        for (DspChain.EffectSlot s : cfg.slots) {
            if (s == null || s.type == null) continue;
            String pid = processorIdForSlotType(s.type);
            if (pid != null && indexOf(pid) >= 0) {
                desiredIds.add(pid);
            }
        }
        for (String canonId : CANONICAL_LEGACY_ORDER) {
            if (indexOf(canonId) >= 0) {
                desiredIds.add(canonId);
            }
        }
        for (AudioProcessor p : processors) {
            desiredIds.add(p.getId());
        }
        int targetIdx = 0;
        for (String pid : desiredIds) {
            int curIdx = indexOf(pid);
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
            for (AudioProcessor p : processors) p.setEnabled(false);
            return;
        }
        cfg.syncLegacyFieldsFromSlots();
        syncOrderFromSlots(cfg);
        for (AudioProcessor p : processors) {
            if (p instanceof LegacyHpfProcessor) {
                ((LegacyHpfProcessor) p).setLegacyState(
                        cfg.hpfEnabled, cfg.hpfCutoffHz);
            } else if (p instanceof LegacyNoiseProcessor) {
                ((LegacyNoiseProcessor) p).setLegacyAmount(cfg.noisePct);
                p.setEnabled(cfg.noisePct > 0);
            } else if (p instanceof LegacyGateProcessor) {
                ((LegacyGateProcessor) p).setLegacyAmount(cfg.gatePct);
                p.setEnabled(cfg.gatePct > 0);
            } else if (p instanceof LegacyToneProcessor) {
                ((LegacyToneProcessor) p).setLegacyAmounts(
                        cfg.warmthPct, cfg.presencePct);
                p.setEnabled(cfg.warmthPct > 0 || cfg.presencePct > 0);
            } else if (p instanceof LegacyDeEsserProcessor) {
                ((LegacyDeEsserProcessor) p).setLegacyAmount(cfg.deEssPct);
                p.setEnabled(cfg.deEssPct > 0);
            } else if (p instanceof LegacyCompressorProcessor) {
                ((LegacyCompressorProcessor) p).setLegacyAmount(cfg.compPct);
                p.setEnabled(cfg.compPct > 0);
            } else if (p instanceof LegacyLimiterProcessor) {
                // The limiter doubles as the safety ceiling for every
                // gain-producing stage, exactly as the original engine did.
                boolean safety = cfg.compPct > 0 || cfg.warmthPct > 0
                        || cfg.presencePct > 0;
                ((LegacyLimiterProcessor) p).setLegacyState(
                        cfg.limiterPct, safety);
                p.setEnabled(cfg.limiterPct > 0 || safety);
            }
        }
    }
}
