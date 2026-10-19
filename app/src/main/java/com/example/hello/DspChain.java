package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pro Multi-Effect Rack & Real-Time/Offline 48 kHz Float DSP Engine.
 *
 * &lt;p&gt;All 8 effects in {@link EffectType} are 100% active, parameter-driven,
 * and audibly distinct:
 * &lt;ol&gt;
 *   &lt;li&gt;&lt;b&gt;Low-Cut Filter (40–160 Hz)&lt;/b&gt;: Dynamic 2nd-order Butterworth HPF
 *       whose biquad coefficients recompute live from the user's Hz setting.</li>
 *   &lt;li&gt;&lt;b&gt;Noise Suppression (0–100%)&lt;/b&gt;: 3-band adaptive Wiener floor gate
 *       with instant first-block noise calibration and up to -24 dB cut.</li>
 *   &lt;li&gt;&lt;b&gt;Expander / Room Gate (0–100%)&lt;/b&gt;: Dedicated downward expander that
 *       attenuates room noise and breaths below threshold (-52..-18 dBFS).</li>
 *   &lt;li&gt;&lt;b&gt;Warmth &amp; Chest Body (0–100%)&lt;/b&gt;: 180 Hz low-mid contour boost (up
 *       to +9 dB) for rich broadcast fullness.</li>
 *   &lt;li&gt;&lt;b&gt;Vocal Presence &amp; Air (0–100%)&lt;/b&gt;: 3.5 kHz articulation &amp; air shelf
 *       boost (up to +10 dB) for crisp intelligibility.</li>
 *   &lt;li&gt;&lt;b&gt;De-Esser (4.5–9.5 kHz)&lt;/b&gt;: Split-band relative sibilance suppressor
 *       (up to -14 dB cut on harsh 'S'/'T' transients).</li>
 *   &lt;li&gt;&lt;b&gt;Compressor &amp; Leveler (0–100%)&lt;/b&gt;: Soft-knee vocal compressor (up to
 *       6:1 ratio) with automatic makeup gain.</li>
 *   &lt;li&gt;&lt;b&gt;Brickwall Ceiling Limiter (0–100%)&lt;/b&gt;: Dedicated gain drive (up to
 *       +12 dB) into a zero-overshoot -0.2 dBFS soft-clip ceiling.</li>
 * &lt;/ol&gt;
 */
public final class DspChain {

    /** Session sample rate every legacy stage is tuned for. */
    public static final int SAMPLE_RATE = 48000;

    /** Catalog of available effects for the searchable "+ Add Effect" menu. */
    public enum EffectType {
        NOISE_SUPPRESSION("noise", "Noise Suppression",
                "3-band adaptive floor gate for hiss & background hum",
                "Intensity", 0, 100, 5, 50, "%"),
        DE_ESSER("deess", "De-Esser (4.5–9.5 kHz)",
                "Split-band sibilance control for harsh 'S' & 'T' sounds",
                "Reduction", 0, 100, 5, 45, "%"),
        COMPRESSOR("comp", "Compressor & Leveler",
                "Soft-knee vocal leveler with auto makeup gain",
                "Compression", 0, 100, 5, 40, "%"),
        LOW_CUT_HPF("hpf", "Low-Cut Rumble Filter",
                "2nd-order Butterworth high-pass filter for mic rumble & plosives",
                "Cutoff", 40, 160, 10, 80, " Hz"),
        VOCAL_PRESENCE("presence", "Vocal Presence & Air",
                "Clarity boost in the 3.5 kHz articulation band",
                "Boost", 0, 100, 5, 45, "%"),
        WARMTH_BODY("warmth", "Warmth & Chest Body",
                "Rich low-mid contour around 180 Hz for thin mics",
                "Warmth", 0, 100, 5, 40, "%"),
        ROOM_GATE("gate", "Expander / Room Gate",
                "Attenuates room echo and breaths between phrases",
                "Threshold", 0, 100, 5, 40, "%"),
        BRICKWALL_LIMITER("limiter", "Brickwall Ceiling Limiter",
                " Loudness drive + zero-overshoot ceiling to prevent peaking",
                "Drive", 0, 100, 5, 50, "%"),

        // ---- Stage 3 EQ & filter suite (ids match the processor ids) ----
        PARAMETRIC_EQ6("eq6", "6-Band Parametric EQ",
                "Console EQ: low/high cut plus four musical bell/shelf bands",
                "Output", -1800, 1800, 50, 0, " dB"),
        GRAPHIC_EQ("geq", "Graphic EQ",
                "10-band ISO or 31-band 1/3-octave sliders",
                "Output", -1800, 1800, 50, 0, " dB"),
        FILTER_CURVE_EQ("fcurve", "Filter Curve EQ",
                "Draw an arbitrary response curve; linear-phase FIR match",
                "Output", -1800, 1800, 50, 0, " dB"),
        BASS_TREBLE("basstreble", "Bass & Treble",
                "Classic 250 Hz / 3.5 kHz tone stack with volume",
                "Bass", -3000, 3000, 100, 0, " dB"),
        HIGH_PASS_FILTER("highpass", "High-Pass Filter",
                "Rumble & plosive removal, 6-48 dB/oct Butterworth",
                "Cutoff", 20, 20000, 10, 80, " Hz"),
        LOW_PASS_FILTER("lowpass", "Low-Pass Filter",
                "Hiss & harshness taming, 6-48 dB/oct Butterworth",
                "Cutoff", 20, 20000, 10, 12000, " Hz"),
        NOTCH_FILTER("notch", "Notch Filter",
                "Surgical null for mains hum and resonances",
                "Frequency", 20, 20000, 10, 60, " Hz"),
        SHELF_FILTER("shelf", "Shelf Filter",
                "Broad high/low tonal shelf with adjustable slope",
                "Gain", -2400, 2400, 10, 0, " dB"),
        CLASSIC_FILTERS("classic", "Classic Filters",
                "Butterworth / Chebyshev I / Chebyshev II / Bessel",
                "Cutoff", 20, 20000, 10, 1000, " Hz");

        public final String id;
        public final String title;
        public final String description;
        public final String paramLabel;
        public final int minVal;
        public final int maxVal;
        public final int stepVal;
        public final int defaultVal;
        public final String unit;

        EffectType(String id, String title, String description,
                   String paramLabel, int minVal, int maxVal, int stepVal,
                   int defaultVal, String unit) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.paramLabel = paramLabel;
            this.minVal = minVal;
            this.maxVal = maxVal;
            this.stepVal = stepVal;
            this.defaultVal = defaultVal;
            this.unit = unit;
        }

        public static EffectType byId(String id) {
            for (EffectType t : values()) {
                if (t.id.equals(id)) return t;
            }
            return NOISE_SUPPRESSION;
        }

        /**
         * True for the eight legacy voice-chain stages whose state lives in
         * the {@code Config} percentage fields; Stage 3 suite entries are
         * rack-local and keep their full state in the slot itself.
         */
        public boolean isLegacyStage() {
            switch (this) {
                case NOISE_SUPPRESSION:
                case DE_ESSER:
                case COMPRESSOR:
                case LOW_CUT_HPF:
                case VOCAL_PRESENCE:
                case WARMTH_BODY:
                case ROOM_GATE:
                case BRICKWALL_LIMITER:
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Premade templates of effects and their settings for the Presets dropdown. */
    public enum Preset {
        CUSTOM("custom", "Custom Rack"),
        VOICE_POLISH("voice_polish", "Voice Polish (Default Pro)"),
        PODCAST_BROADCAST("podcast", "Podcast Broadcast Master"),
        NOISY_ROOM("noisy_room", "Noisy Room Cleanup"),
        WARM_VOCAL("warm_vocal", "Warm Studio Vocal"),
        CRISP_CLARITY("crisp", "Crisp Articulation"),
        CLEAR_ALL("clear", "Flat / No Effects");

        public final String id;
        public final String title;

        Preset(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** One effect pill in the user's scrollable FX rack. */
    public static final class EffectSlot {
        public final EffectType type;
        /** Checkmark state: only applied when enabled == true. */
        public boolean enabled;
        /** Current parameter setting (e.g. 0..100% or 40..160 Hz). */
        public int value;
        /** UI state: whether the collapsible settings drawer is expanded. */
        public boolean expanded;
        /** Per-slot wet/dry mix, 0..1 (1 = fully wet, the legacy default). */
        public float wetDry = 1f;
        /**
         * Secondary processor parameters persisted per slot, keyed by
         * {@link FxParameter#id} (Stage 3+ effects; the legacy stages have
         * only their primary knob, so this stays empty for them).
         */
        public final Map<String, Integer> extraParams = new LinkedHashMap<>();

        public EffectSlot(EffectType type, boolean enabled, int value, boolean expanded) {
            this.type = type;
            this.enabled = enabled;
            this.value = Math.max(type.minVal, Math.min(type.maxVal, value));
            this.expanded = expanded;
        }
    }

    /** Configuration holding both the Pro EffectSlot list and legacy fields. */
    public static final class Config {
        public static final int MODE_OFF = 0;
        public static final int MODE_NOISE_SUPPRESSION = 1;
        public static final int MODE_CUSTOM = 2;

        public int mode = MODE_OFF;
        public boolean hpfEnabled = false;
        public int hpfCutoffHz = 80;
        public int noisePct = 0;
        public int deEssPct = 0;
        public int compPct = 0;
        public int presencePct = 0;
        public int warmthPct = 0;
        public int gatePct = 0;
        public int limiterPct = 0;

        public boolean customHpf = true;
        public int customNoisePct = 35;
        public int customDeEssPct = 30;
        public int customCompPct = 30;

        public String activePresetId = Preset.VOICE_POLISH.id;
        public final List<EffectSlot> slots = new ArrayList<>();

        public Config() {
            syncSlotsFromLegacyFields();
        }

        public Config(boolean hpf, int noise, int deEss, int comp) {
            this.hpfEnabled = hpf;
            this.hpfCutoffHz = 80;
            this.noisePct = clamp(noise);
            this.deEssPct = clamp(deEss);
            this.compPct = clamp(comp);
            if (!isActive()) {
                this.mode = MODE_OFF;
            } else if (hpf && this.noisePct == 50
                    && this.deEssPct == 45 && this.compPct == 40) {
                this.mode = MODE_NOISE_SUPPRESSION;
            } else {
                this.mode = MODE_CUSTOM;
                this.customHpf = hpf;
                this.customNoisePct = this.noisePct;
                this.customDeEssPct = this.deEssPct;
                this.customCompPct = this.compPct;
            }
            syncSlotsFromLegacyFields();
        }

        /** Populates the rack with a premade template of effects and settings. */
        public void applyPreset(Preset preset) {
            slots.clear();
            activePresetId = preset.id;
            switch (preset) {
                case VOICE_POLISH:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 80, false));
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 50, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 45, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 40, false));
                    break;
                case PODCAST_BROADCAST:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 90, false));
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 45, false));
                    slots.add(new EffectSlot(EffectType.ROOM_GATE, true, 40, false));
                    slots.add(new EffectSlot(EffectType.WARMTH_BODY, true, 45, false));
                    slots.add(new EffectSlot(EffectType.VOCAL_PRESENCE, true, 35, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 50, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 55, false));
                    slots.add(new EffectSlot(EffectType.BRICKWALL_LIMITER, true, 55, false));
                    break;
                case NOISY_ROOM:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 110, false));
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 80, true));
                    slots.add(new EffectSlot(EffectType.ROOM_GATE, true, 55, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 40, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 35, false));
                    break;
                case WARM_VOCAL:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 70, false));
                    slots.add(new EffectSlot(EffectType.WARMTH_BODY, true, 55, true));
                    slots.add(new EffectSlot(EffectType.VOCAL_PRESENCE, true, 35, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 40, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 45, false));
                    break;
                case CRISP_CLARITY:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 90, false));
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 35, false));
                    slots.add(new EffectSlot(EffectType.VOCAL_PRESENCE, true, 65, true));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 55, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 45, false));
                    break;
                case CLEAR_ALL:
                    break;
                case CUSTOM:
                default:
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, customNoisePct, true));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, customDeEssPct, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, customCompPct, false));
                    break;
            }
            syncLegacyFieldsFromSlots();
        }

        public EffectSlot addOrFocusSlot(EffectType type) {
            for (EffectSlot s : slots) {
                if (s.type == type) {
                    s.enabled = true;
                    s.expanded = true;
                    activePresetId = Preset.CUSTOM.id;
                    syncLegacyFieldsFromSlots();
                    return s;
                }
            }
            EffectSlot created = new EffectSlot(type, true, type.defaultVal, true);
            slots.add(created);
            activePresetId = Preset.CUSTOM.id;
            syncLegacyFieldsFromSlots();
            return created;
        }

        public void removeSlot(EffectSlot slot) {
            slots.remove(slot);
            activePresetId = Preset.CUSTOM.id;
            syncLegacyFieldsFromSlots();
        }

        public void syncLegacyFieldsFromSlots() {
            hpfEnabled = false;
            hpfCutoffHz = 80;
            noisePct = 0;
            deEssPct = 0;
            compPct = 0;
            presencePct = 0;
            warmthPct = 0;
            gatePct = 0;
            limiterPct = 0;
            for (EffectSlot s : slots) {
                if (!s.enabled) continue;
                switch (s.type) {
                    case LOW_CUT_HPF:
                        hpfEnabled = s.value > 0;
                        hpfCutoffHz = Math.max(40, Math.min(160, s.value));
                        break;
                    case NOISE_SUPPRESSION:
                        noisePct = clamp(s.value);
                        break;
                    case DE_ESSER:
                        deEssPct = clamp(s.value);
                        break;
                    case COMPRESSOR:
                        compPct = clamp(s.value);
                        break;
                    case VOCAL_PRESENCE:
                        presencePct = clamp(s.value);
                        break;
                    case WARMTH_BODY:
                        warmthPct = clamp(s.value);
                        break;
                    case ROOM_GATE:
                        gatePct = clamp(s.value);
                        break;
                    case BRICKWALL_LIMITER:
                        limiterPct = clamp(s.value);
                        break;
                }
            }
            boolean anyStage3Active = false;
            for (EffectSlot s : slots) {
                if (s != null && s.enabled && s.type != null
                        && !s.type.isLegacyStage()) {
                    anyStage3Active = true;
                    break;
                }
            }
            if (!isActive()) {
                mode = MODE_OFF;
            } else if (!anyStage3Active && hpfEnabled && hpfCutoffHz == 80
                    && noisePct == 50 && deEssPct == 45 && compPct == 40
                    && presencePct == 0 && warmthPct == 0 && gatePct == 0 && limiterPct == 0) {
                mode = MODE_NOISE_SUPPRESSION;
            } else {
                mode = MODE_CUSTOM;
            }
        }

        private void syncSlotsFromLegacyFields() {
            Map<EffectType, EffectSlot> existingByType =
                    new EnumMap<>(EffectType.class);
            List<EffectType> previousOrder = new ArrayList<>(slots.size());
            for (EffectSlot s : slots) {
                if (s != null && s.type != null
                        && !existingByType.containsKey(s.type)) {
                    existingByType.put(s.type, s);
                    previousOrder.add(s.type);
                }
            }
            Map<EffectType, Integer> activeValues =
                    new LinkedHashMap<>();
            if (hpfEnabled) {
                activeValues.put(EffectType.LOW_CUT_HPF,
                        Math.max(40, Math.min(160, hpfCutoffHz)));
            }
            if (noisePct > 0) {
                activeValues.put(EffectType.NOISE_SUPPRESSION, noisePct);
            }
            if (gatePct > 0) {
                activeValues.put(EffectType.ROOM_GATE, gatePct);
            }
            if (warmthPct > 0) {
                activeValues.put(EffectType.WARMTH_BODY, warmthPct);
            }
            if (presencePct > 0) {
                activeValues.put(EffectType.VOCAL_PRESENCE, presencePct);
            }
            if (deEssPct > 0) {
                activeValues.put(EffectType.DE_ESSER, deEssPct);
            }
            if (compPct > 0) {
                activeValues.put(EffectType.COMPRESSOR, compPct);
            }
            if (limiterPct > 0) {
                activeValues.put(EffectType.BRICKWALL_LIMITER, limiterPct);
            }

            slots.clear();
            // Preserve user's relative ordering, bypassed (unchecked) pills,
            // and per-slot wetDry/extraParams for all slots already in the rack.
            for (EffectType t : previousOrder) {
                Integer val = activeValues.remove(t);
                EffectSlot kept = existingByType.get(t);
                if (val != null) {
                    kept.enabled = true;
                    kept.value = Math.max(t.minVal, Math.min(t.maxVal, val));
                    slots.add(kept);
                } else if (kept != null) {
                    // Stage 3 suite slots are rack-local: their state must
                    // survive every legacy-field resync untouched.
                    if (t.isLegacyStage()) kept.enabled = false;
                    slots.add(kept);
                }
            }
            // Append any newly enabled legacy stages in canonical stage order.
            for (Map.Entry<EffectType, Integer> e : activeValues.entrySet()) {
                EffectType t = e.getKey();
                int val = e.getValue();
                EffectSlot kept = existingByType.get(t);
                if (kept != null) {
                    kept.enabled = true;
                    kept.value = Math.max(t.minVal, Math.min(t.maxVal, val));
                    slots.add(kept);
                } else {
                    slots.add(new EffectSlot(t, true, val, false));
                }
            }
        }

        public void applyMode(int targetMode) {
            if (targetMode == MODE_OFF) {
                this.mode = MODE_OFF;
                this.hpfEnabled = false;
                this.noisePct = 0;
                this.deEssPct = 0;
                this.compPct = 0;
                this.presencePct = 0;
                this.warmthPct = 0;
                this.gatePct = 0;
                this.limiterPct = 0;
                for (EffectSlot s : slots) s.enabled = false;
            } else if (targetMode == MODE_NOISE_SUPPRESSION) {
                this.mode = MODE_NOISE_SUPPRESSION;
                this.hpfEnabled = true;
                this.hpfCutoffHz = 80;
                this.noisePct = 50;
                this.deEssPct = 45;
                this.compPct = 40;
                this.presencePct = 0;
                this.warmthPct = 0;
                this.gatePct = 0;
                this.limiterPct = 0;
                for (EffectSlot s : slots) {
                    if (s != null && s.type != null && !s.type.isLegacyStage()) {
                        s.enabled = false;
                    }
                }
                syncSlotsFromLegacyFields();
            } else {
                this.mode = MODE_CUSTOM;
                if (!customHpf && customNoisePct <= 0
                        && customDeEssPct <= 0 && customCompPct <= 0) {
                    customHpf = true;
                    customNoisePct = 35;
                    customDeEssPct = 30;
                    customCompPct = 30;
                }
                this.hpfEnabled = customHpf;
                this.noisePct = clamp(customNoisePct);
                this.deEssPct = clamp(customDeEssPct);
                this.compPct = clamp(customCompPct);
                syncSlotsFromLegacyFields();
            }
        }

        public void onManualAdjustment() {
            onManualAdjustment(hpfEnabled, noisePct, deEssPct, compPct);
        }

        public void onManualAdjustment(boolean hpf, int noise, int deEss, int comp) {
            this.hpfEnabled = hpf;
            this.noisePct = clamp(noise);
            this.deEssPct = clamp(deEss);
            this.compPct = clamp(comp);
            if (!isActive()) {
                this.mode = MODE_OFF;
            } else {
                this.mode = MODE_CUSTOM;
                this.customHpf = this.hpfEnabled;
                this.customNoisePct = this.noisePct;
                this.customDeEssPct = this.deEssPct;
                this.customCompPct = this.compPct;
            }
            syncSlotsFromLegacyFields();
        }

        /**
         * True when both {@link EffectType#WARMTH_BODY} and
         * {@link EffectType#VOCAL_PRESENCE} are enabled in the rack at once.
         */
        public boolean hasBothToneSlotsEnabled() {
            boolean w = false;
            boolean p = false;
            for (EffectSlot s : slots) {
                if (s == null || !s.enabled || s.value <= 0) continue;
                if (s.type == EffectType.WARMTH_BODY) w = true;
                if (s.type == EffectType.VOCAL_PRESENCE) p = true;
            }
            return w && p;
        }

        public boolean isActive() {
            if (hpfEnabled || noisePct > 0 || deEssPct > 0 || compPct > 0
                    || presencePct > 0 || warmthPct > 0 || gatePct > 0
                    || limiterPct > 0) {
                return true;
            }
            for (EffectSlot s : slots) {
                if (s != null && s.enabled && s.type != null
                        && !s.type.isLegacyStage()) {
                    return true;
                }
            }
            return false;
        }

        public int enabledSlotCount() {
            int n = 0;
            for (EffectSlot s : slots) {
                if (s.enabled
                        && (s.value > 0 || !s.type.isLegacyStage())) n++;
            }
            return n;
        }

        public String badgeSummary() {
            if (!isActive() || mode == MODE_OFF) return "FX Off";
            boolean anyStage3Active = false;
            for (EffectSlot s : slots) {
                if (s != null && s.enabled && s.type != null
                        && !s.type.isLegacyStage()) {
                    anyStage3Active = true;
                    break;
                }
            }
            if (!anyStage3Active && (mode == MODE_NOISE_SUPPRESSION
                    || (hpfEnabled && hpfCutoffHz == 80
                    && noisePct == 50 && deEssPct == 45 && compPct == 40
                    && presencePct == 0 && warmthPct == 0 && gatePct == 0 && limiterPct == 0))) {
                return "FX • Polish";
            }
            return "FX • Custom";
        }

        public static Config voicePolish() {
            Config c = new Config(true, 50, 45, 40);
            c.mode = MODE_NOISE_SUPPRESSION;
            return c;
        }

        public static Config load(Context ctx) {
            Config c = new Config();
            if (ctx == null) return c;
            try {
                SharedPreferences p = ctx.getSharedPreferences("takeone",
                        Context.MODE_PRIVATE);
                c.hpfEnabled = p.getBoolean("dsp_hpf", false);
                c.hpfCutoffHz = Math.max(40, Math.min(160, p.getInt("dsp_hpf_hz", 80)));
                c.noisePct = clamp(p.getInt("dsp_noise", 0));
                c.deEssPct = clamp(p.getInt("dsp_deess", 0));
                c.compPct = clamp(p.getInt("dsp_comp", 0));
                c.customHpf = p.getBoolean("dsp_custom_hpf", true);
                c.customNoisePct = clamp(p.getInt("dsp_custom_noise", 35));
                c.customDeEssPct = clamp(p.getInt("dsp_custom_deess", 30));
                c.customCompPct = clamp(p.getInt("dsp_custom_comp", 30));
                c.activePresetId = p.getString("dsp_preset_id", Preset.VOICE_POLISH.id);

                String slotsJson = p.getString("dsp_slots_json", null);
                if (slotsJson != null && !slotsJson.isEmpty()) {
                    c.slots.clear();
                    JSONArray arr = new JSONArray(slotsJson);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        EffectType t = EffectType.byId(o.optString("id", "noise"));
                        boolean en = o.optBoolean("en", true);
                        int val = o.optInt("val", t.defaultVal);
                        boolean exp = o.optBoolean("exp", false);
                        EffectSlot slot = new EffectSlot(t, en, val, exp);
                        slot.wetDry = Math.max(0f, Math.min(1f,
                                (float) o.optDouble("mix", 1.0)));
                        JSONObject pj = o.optJSONObject("params");
                        if (pj != null) {
                            java.util.Iterator<String> keys = pj.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                slot.extraParams.put(k, pj.optInt(k));
                            }
                        }
                        c.slots.add(slot);
                    }
                    c.syncLegacyFieldsFromSlots();
                } else {
                    c.syncSlotsFromLegacyFields();
                    if (c.slots.isEmpty()) {
                        // Seed default starter pills for the UI rack
                        c.slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, false, 50, false));
                        c.slots.add(new EffectSlot(EffectType.DE_ESSER, false, 45, false));
                        c.slots.add(new EffectSlot(EffectType.COMPRESSOR, false, 40, false));
                        c.slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, false, 80, false));
                    }
                }
                int savedMode = p.getInt("dsp_mode", -1);
                if (savedMode >= MODE_OFF && savedMode <= MODE_CUSTOM) {
                    c.mode = savedMode;
                    if (!c.isActive()) c.mode = MODE_OFF;
                }
            } catch (Exception ignored) {}
            return c;
        }

        public void save(Context ctx) {
            if (ctx == null) return;
            try {
                JSONArray arr = new JSONArray();
                for (EffectSlot s : slots) {
                    JSONObject o = new JSONObject();
                    o.put("id", s.type.id);
                    o.put("en", s.enabled);
                    o.put("val", s.value);
                    o.put("exp", s.expanded);
                    o.put("mix", (double) s.wetDry);
                    if (!s.extraParams.isEmpty()) {
                        JSONObject p = new JSONObject();
                        for (Map.Entry<String, Integer> e
                                : s.extraParams.entrySet()) {
                            p.put(e.getKey(), e.getValue());
                        }
                        o.put("params", p);
                    }
                    arr.put(o);
                }
                ctx.getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                        .putInt("dsp_mode", mode)
                        .putBoolean("dsp_hpf", hpfEnabled)
                        .putInt("dsp_hpf_hz", hpfCutoffHz)
                        .putInt("dsp_noise", clamp(noisePct))
                        .putInt("dsp_deess", clamp(deEssPct))
                        .putInt("dsp_comp", clamp(compPct))
                        .putBoolean("dsp_custom_hpf", customHpf)
                        .putInt("dsp_custom_noise", clamp(customNoisePct))
                        .putInt("dsp_custom_deess", clamp(customDeEssPct))
                        .putInt("dsp_custom_comp", clamp(customCompPct))
                        .putString("dsp_preset_id", activePresetId)
                        .putString("dsp_slots_json", arr.toString())
                        .apply();
            } catch (Exception ignored) {}
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(100, v));
        }
    }

    private final int channels;
    private final FxRackChain rack;

    public DspChain(int channels, Config cfg) {
        this.channels = Math.max(1, Math.min(2, channels));
        this.rack = FxRackChain.fromLegacyConfig(this.channels, cfg);
        ensureRackSlots(cfg);
        rack.applyLegacyConfig(cfg);
        applySlotExtras(cfg);
    }

    /**
     * The modular processor rack backing this chain (Stage 2 architecture).
     * The legacy engine's eight stages are now {@link AudioProcessor}s in an
     * ordered rack; this chain is a backwards-compatible facade.
     */
    public FxRackChain getRack() {
        return rack;
    }

    public void updateConfig(Config cfg) {
        resetProcessorSlotExtras();
        ensureRackSlots(cfg);
        rack.applyLegacyConfig(cfg);
        applySlotExtras(cfg);
    }

    /**
     * Creates or drops Stage 3 suite processors so every non-legacy slot owns
     * exactly one rack processor and deleted slots leave no orphan behind.
     * Runs before {@code applyLegacyConfig} so slot order can include them.
     */
    private void ensureRackSlots(Config cfg) {
        if (cfg == null) return;
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (EffectSlot s : cfg.slots) {
            if (s == null || s.type == null || s.type.isLegacyStage()) continue;
            String pid = FxRackChain.processorIdForSlotType(s.type);
            if (pid == null) continue;
            wanted.add(pid);
            AudioProcessor p = rack.getProcessor(rack.indexOf(pid));
            if (p == null) {
                p = FxRackChain.createProcessor(pid);
                if (p != null) rack.addProcessor(p);
            }
            if (p != null) p.setEnabled(s.enabled);
        }
        for (int i = rack.size() - 1; i >= 0; i--) {
            AudioProcessor p = rack.getProcessor(i);
            if (p == null || isLegacyProcessor(p)) continue;
            if (!wanted.contains(p.getId())) rack.removeProcessor(i);
        }
    }

    private static boolean isLegacyProcessor(AudioProcessor p) {
        String id = p.getId();
        return "hpf".equals(id) || "noise".equals(id) || "gate".equals(id)
                || "tone".equals(id) || "deess".equals(id)
                || "comp".equals(id) || "limiter".equals(id);
    }

    /**
     * Resets per-slot wet/dry mixes and secondary parameters on all rack
     * processors before applying the active {@link Config} so removed or
     * reset slots never leave stale state behind.
     */
    private void resetProcessorSlotExtras() {
        for (AudioProcessor p : rack.processors()) {
            p.setWetDryMix(1.0f);
            for (FxParameter fp : p.getParameters()) {
                if (!isPrimaryLegacyParam(fp.id)
                        && !isStage3PrimaryParam(p.getId(), fp.id)) {
                    fp.resetToDefault();
                }
            }
        }
    }

    private static boolean isPrimaryLegacyParam(String paramId) {
        return "amt".equals(paramId)
                || "cutoff".equals(paramId)
                || "drive".equals(paramId)
                || "warmth".equals(paramId)
                || "presence".equals(paramId);
    }

    private static boolean isStage3PrimaryParam(String procId, String paramId) {
        if (procId == null || paramId == null) return false;
        return paramId.equals(primaryParamId(EffectType.byId(procId)));
    }

    /**
     * Pushes per-slot wet/dry mixes and any persisted secondary parameters
     * into the rack. When both {@code WARMTH_BODY} and {@code VOCAL_PRESENCE}
     * are enabled together in the combined tone stage, the tone processor
     * stays fully wet (1.0f); when only one of the two is active, its slot
     * {@code wetDry} mix drives the tone processor directly.
     */
    private void applySlotExtras(Config cfg) {
        if (cfg == null) return;
        boolean bothToneEnabled = cfg.hasBothToneSlotsEnabled();
        for (DspChain.EffectSlot s : cfg.slots) {
            AudioProcessor p = processorForSlot(s.type);
            if (p == null) continue;
            if (s.type == EffectType.WARMTH_BODY
                    || s.type == EffectType.VOCAL_PRESENCE) {
                if (!bothToneEnabled && s.enabled && s.value > 0) {
                    p.setWetDryMix(s.wetDry);
                }
            } else if (s.type != EffectType.BRICKWALL_LIMITER || s.enabled) {
                // When the Brickwall Limiter slot is unchecked, it may still
                // run as the automatic safety ceiling for upstream gain
                // stages; keep its mix at 100% when disabled so an unchecked
                // slot's mix never weakens safety limiting.
                p.setWetDryMix(s.wetDry);
            }
            if (!s.type.isLegacyStage()) {
                p.setEnabled(s.enabled);
                FxParameter fp = p.getParam(primaryParamId(s.type));
                if (fp != null) fp.setCurrentRaw(s.value);
            }
            String primaryId = primaryParamId(s.type);
            for (Map.Entry<String, Integer> e : s.extraParams.entrySet()) {
                String key = e.getKey();
                if (isPrimaryLegacyParam(key)) continue;
                if (primaryId != null && primaryId.equals(key)) continue;
                FxParameter fp = p.getParam(key);
                if (fp != null) fp.setCurrentRaw(e.getValue());
            }
        }
    }

    /** Processor parameter driven by the slot card's main dial. */
    static String primaryParamId(EffectType type) {
        switch (type) {
            case PARAMETRIC_EQ6:
            case GRAPHIC_EQ:
            case FILTER_CURVE_EQ:
                return "out";
            case BASS_TREBLE:
                return "bass";
            case HIGH_PASS_FILTER:
            case LOW_PASS_FILTER:
            case NOTCH_FILTER:
            case CLASSIC_FILTERS:
                return "freq";
            case SHELF_FILTER:
                return "gain";
            default:
                return null;
        }
    }

    private AudioProcessor processorForSlot(EffectType type) {
        switch (type) {
            case LOW_CUT_HPF:
                return rack.getProcessor(rack.indexOf("hpf"));
            case NOISE_SUPPRESSION:
                return rack.getProcessor(rack.indexOf("noise"));
            case ROOM_GATE:
                return rack.getProcessor(rack.indexOf("gate"));
            case DE_ESSER:
                return rack.getProcessor(rack.indexOf("deess"));
            case COMPRESSOR:
                return rack.getProcessor(rack.indexOf("comp"));
            case BRICKWALL_LIMITER:
                return rack.getProcessor(rack.indexOf("limiter"));
            case WARMTH_BODY:
            case VOCAL_PRESENCE:
                return rack.getProcessor(rack.indexOf("tone"));
            default:
                // Stage 3 suite ids match their processor ids exactly.
                return rack.getProcessor(rack.indexOf(type.id));
        }
    }

    public boolean isActive() {
        return rack.isActive();
    }

    public void reset() {
        rack.reset();
    }

    public void processInPlace(float[] buf, int frames) {
        rack.processInPlace(buf, frames);
    }

    public static float fastLinToDb(float x) {
        if (x <= 1e-7f) return -140.0f;
        int bits = Float.floatToRawIntBits(x);
        int exp = ((bits >>> 23) & 0xFF) - 127;
        float m = Float.intBitsToFloat((bits & 0x007FFFFF) | 0x3F800000);
        float log2m = -0.34484843f * m * m + 2.02466578f * m - 1.67487759f;
        return (exp + log2m) * 6.0205999f;
    }

    public static float fastDbToLin(float db) {
        if (db <= -140.0f) return 0.0f;
        if (db > 36.0f) db = 36.0f;
        float p = db * 0.16609640f;
        int exp = (int) Math.floor(p);
        float f = p - exp;
        float mant = 0.33971f * f * f + 0.66029f * f + 1.0f;
        int bits = Float.floatToRawIntBits(mant);
        bits += (exp << 23);
        return Float.intBitsToFloat(bits);
    }
}
