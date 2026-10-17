package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pro Multi-Effect Rack & Real-Time/Offline 48 kHz Float DSP Engine.
 *
 * <p>Supports a dynamic, user-customizable list of effect pills ({@link EffectSlot})
 * with per-effect enable/disable checkmarks, collapsible settings, deletion,
 * searchable effect catalog ({@link EffectType}), and premade templates
 * ({@link Preset}), while retaining full backward compatibility with
 * {@link Config} for existing unit and device tests.
 */
public final class DspChain {

    private static final int SAMPLE_RATE = 48000;
    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;

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
        LOW_CUT_HPF("hpf", "80 Hz Low-Cut Filter",
                "2nd-order Butterworth high-pass filter for mic rumble & plosives",
                "Cutoff", 40, 160, 10, 80, " Hz"),
        VOCAL_PRESENCE("presence", "Vocal Presence & Air",
                "Clarity boost in the 3.5 kHz articulation band",
                "Boost", 0, 100, 5, 35, "%"),
        WARMTH_BODY("warmth", "Warmth & Chest Body",
                "Rich low-mid contour around 180 Hz for thin mics",
                "Warmth", 0, 100, 5, 30, "%"),
        ROOM_GATE("gate", "Expander / Room Gate",
                "Attenuates room echo and breaths between phrases",
                "Threshold", 0, 100, 5, 35, "%"),
        BRICKWALL_LIMITER("limiter", "Brickwall Ceiling Limiter",
                "Zero-overshoot soft-clip ceiling to prevent digital peaking",
                "Drive", 0, 100, 5, 50, "%");

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
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 40, false));
                    slots.add(new EffectSlot(EffectType.ROOM_GATE, true, 35, false));
                    slots.add(new EffectSlot(EffectType.WARMTH_BODY, true, 35, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 50, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 55, false));
                    slots.add(new EffectSlot(EffectType.BRICKWALL_LIMITER, true, 60, false));
                    break;
                case NOISY_ROOM:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 100, false));
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 75, true));
                    slots.add(new EffectSlot(EffectType.ROOM_GATE, true, 50, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 40, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 35, false));
                    break;
                case WARM_VOCAL:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 70, false));
                    slots.add(new EffectSlot(EffectType.WARMTH_BODY, true, 45, true));
                    slots.add(new EffectSlot(EffectType.VOCAL_PRESENCE, true, 30, false));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 40, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 45, false));
                    break;
                case CRISP_CLARITY:
                    slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 90, false));
                    slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, 35, false));
                    slots.add(new EffectSlot(EffectType.VOCAL_PRESENCE, true, 55, true));
                    slots.add(new EffectSlot(EffectType.DE_ESSER, true, 55, false));
                    slots.add(new EffectSlot(EffectType.COMPRESSOR, true, 40, false));
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
            if (!isActive()) {
                mode = MODE_OFF;
            } else if (hpfEnabled && noisePct == 50 && deEssPct == 45 && compPct == 40
                    && presencePct == 0 && warmthPct == 0 && gatePct == 0 && limiterPct == 0) {
                mode = MODE_NOISE_SUPPRESSION;
            } else {
                mode = MODE_CUSTOM;
            }
        }

        private void syncSlotsFromLegacyFields() {
            slots.clear();
            if (hpfEnabled) {
                slots.add(new EffectSlot(EffectType.LOW_CUT_HPF, true, 80, false));
            }
            if (noisePct > 0) {
                slots.add(new EffectSlot(EffectType.NOISE_SUPPRESSION, true, noisePct, false));
            }
            if (deEssPct > 0) {
                slots.add(new EffectSlot(EffectType.DE_ESSER, true, deEssPct, false));
            }
            if (compPct > 0) {
                slots.add(new EffectSlot(EffectType.COMPRESSOR, true, compPct, false));
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
                this.noisePct = 50;
                this.deEssPct = 45;
                this.compPct = 40;
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

        public boolean isActive() {
            return hpfEnabled || noisePct > 0 || deEssPct > 0 || compPct > 0
                    || presencePct > 0 || warmthPct > 0 || gatePct > 0 || limiterPct > 0;
        }

        public int enabledSlotCount() {
            int n = 0;
            for (EffectSlot s : slots) {
                if (s.enabled && s.value > 0) n++;
            }
            return n;
        }

        public String badgeSummary() {
            if (!isActive() || mode == MODE_OFF) return "FX Off";
            if (mode == MODE_NOISE_SUPPRESSION
                    || (hpfEnabled && noisePct == 50 && deEssPct == 45 && compPct == 40
                    && presencePct == 0 && warmthPct == 0 && gatePct == 0 && limiterPct == 0)) {
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
                        c.slots.add(new EffectSlot(t, en, val, exp));
                    }
                    c.syncLegacyFieldsFromSlots();
                } else {
                    c.syncSlotsFromLegacyFields();
                    if (c.slots.isEmpty()) {
                        // Seed default starter pills (unchecked if mode is OFF)
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
                    arr.put(o);
                }
                ctx.getSharedPreferences("takeone", Context.MODE_PRIVATE).edit()
                        .putInt("dsp_mode", mode)
                        .putBoolean("dsp_hpf", hpfEnabled)
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
    private volatile boolean hpfOn;
    private volatile float noiseAmt;
    private volatile float deEssAmt;
    private volatile float compAmt;
    private volatile float presenceAmt;
    private volatile float warmthAmt;
    private volatile float gateAmt;
    private volatile float limiterAmt;

    // Stage 1: 80 Hz 2nd-order Butterworth High-Pass Filter state
    private final float hpfB0, hpfB1, hpfB2, hpfA1, hpfA2;
    private final float[] hpfZ1 = new float[2];
    private final float[] hpfZ2 = new float[2];

    // Stage 2: 3-Band Minimum-Statistics Adaptive Noise Suppressor state
    private static final float NS_ALPHA_LOW = 0.02839f; // 220 Hz 1-pole crossover
    private static final float NS_ALPHA_HIGH = 0.3919f; // 3800 Hz 1-pole crossover
    private final float[] nsLp1 = new float[2];
    private final float[] nsLp2 = new float[2];
    private final float[] nsEnv = {1e-5f, 1e-5f, 1e-5f};
    private final float[] nsFloor = {1e-6f, 1e-6f, 1e-6f};
    private final float[] nsGain = {1.0f, 1.0f, 1.0f};
    private final float[] nsTargetGain = {1.0f, 1.0f, 1.0f};
    private final float[] nsBandAcc = new float[3];
    private final float[] ScratchLow = new float[DECIM * 2];
    private final float[] ScratchMid = new float[DECIM * 2];
    private final float[] ScratchHigh = new float[DECIM * 2];

    // Stage 3: Split-Band Relative Sidechain De-Esser (6800 Hz bandpass, Q=1.5)
    private final float deB0, deB1, deB2, deA1, deA2;
    private final float[] deZ1 = new float[2];
    private final float[] deZ2 = new float[2];
    private float deSibEnv = 1e-7f;
    private float deFullEnv = 1e-6f;
    private float deCutCur = 0.0f;
    private float deCutTarget = 0.0f;
    private final float[] ScratchSib = new float[DECIM * 2];

    // Stage 4: Warmth (180 Hz) & Vocal Presence (3500 Hz) state
    private final float[] warmLp = new float[2];
    private final float[] presLp = new float[2];
    private static final float WARM_ALPHA = 0.0233f;  // ~180 Hz
    private static final float PRES_ALPHA = 0.3678f;  // ~3500 Hz

    // Stage 5: Room Gate + Soft-Knee Compressor + Auto-Makeup + Brickwall Limiter
    private float compEnvDb = -60.0f;
    private float compGainCur = 1.0f;
    private float compGainTarget = 1.0f;

    public DspChain(int channels, Config cfg) {
        this.channels = Math.max(1, Math.min(2, channels));
        double w0 = 2.0 * Math.PI * 80.0 / SAMPLE_RATE;
        double cosW0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * 0.70710678);
        double a0Inv = 1.0 / (1.0 + alpha);
        this.hpfB0 = (float) ((1.0 + cosW0) * 0.5 * a0Inv);
        this.hpfB1 = (float) (-(1.0 + cosW0) * a0Inv);
        this.hpfB2 = this.hpfB0;
        this.hpfA1 = (float) (-2.0 * cosW0 * a0Inv);
        this.hpfA2 = (float) ((1.0 - alpha) * a0Inv);

        double dw0 = 2.0 * Math.PI * 6800.0 / SAMPLE_RATE;
        double dCos = Math.cos(dw0);
        double dAlpha = Math.sin(dw0) / (2.0 * 1.5);
        double da0Inv = 1.0 / (1.0 + dAlpha);
        this.deB0 = (float) (dAlpha * da0Inv);
        this.deB1 = 0.0f;
        this.deB2 = (float) (-dAlpha * da0Inv);
        this.deA1 = (float) (-2.0 * dCos * da0Inv);
        this.deA2 = (float) ((1.0 - dAlpha) * da0Inv);

        updateConfig(cfg);
    }

    public void updateConfig(Config cfg) {
        if (cfg == null) {
            this.hpfOn = false;
            this.noiseAmt = 0f;
            this.deEssAmt = 0f;
            this.compAmt = 0f;
            this.presenceAmt = 0f;
            this.warmthAmt = 0f;
            this.gateAmt = 0f;
            this.limiterAmt = 0f;
            return;
        }
        this.hpfOn = cfg.hpfEnabled;
        this.noiseAmt = cfg.noisePct / 100.0f;
        this.deEssAmt = cfg.deEssPct / 100.0f;
        this.compAmt = cfg.compPct / 100.0f;
        this.presenceAmt = cfg.presencePct / 100.0f;
        this.warmthAmt = cfg.warmthPct / 100.0f;
        this.gateAmt = cfg.gatePct / 100.0f;
        this.limiterAmt = cfg.limiterPct / 100.0f;
    }

    public boolean isActive() {
        return hpfOn || noiseAmt > 0.001f || deEssAmt > 0.001f || compAmt > 0.001f
                || presenceAmt > 0.001f || warmthAmt > 0.001f
                || gateAmt > 0.001f || limiterAmt > 0.001f;
    }

    public void reset() {
        for (int c = 0; c < 2; c++) {
            hpfZ1[c] = hpfZ2[c] = 0f;
            nsLp1[c] = nsLp2[c] = 0f;
            deZ1[c] = deZ2[c] = 0f;
            warmLp[c] = presLp[c] = 0f;
        }
        for (int b = 0; b < 3; b++) {
            nsEnv[b] = 1e-5f;
            nsFloor[b] = 1e-6f;
            nsGain[b] = nsTargetGain[b] = 1.0f;
        }
        deSibEnv = 1e-7f;
        deFullEnv = 1e-6f;
        deCutCur = deCutTarget = 0f;
        compEnvDb = -60.0f;
        compGainCur = compGainTarget = 1.0f;
    }

    public void processInPlace(float[] buf, int frames) {
        if (!isActive() || frames <= 0) return;
        final int ch = this.channels;
        final boolean doHpf = this.hpfOn;
        final float nAmt = Math.max(this.noiseAmt, this.gateAmt * 0.65f);
        final float dAmt = this.deEssAmt;
        final float cAmt = Math.max(this.compAmt, this.limiterAmt * 0.5f);
        final float wAmt = this.warmthAmt;
        final float pAmt = this.presenceAmt;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 1: 80 Hz Butterworth High-Pass Filter
            if (doHpf) {
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        float y = hpfB0 * x + hpfZ1[c];
                        hpfZ1[c] = hpfB1 * x - hpfA1 * y + hpfZ2[c];
                        hpfZ2[c] = hpfB2 * x - hpfA2 * y;
                        buf[idx + c] = y;
                    }
                }
            }

            // Stage 2: 3-Band Adaptive Noise Suppressor & Room Gate
            if (nAmt > 0.001f) {
                nsBandAcc[0] = nsBandAcc[1] = nsBandAcc[2] = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        nsLp1[c] += NS_ALPHA_LOW * (x - nsLp1[c]);
                        nsLp2[c] += NS_ALPHA_HIGH * (x - nsLp2[c]);
                        float bLow = nsLp1[c];
                        float bMid = nsLp2[c] - bLow;
                        float bHigh = x - nsLp2[c];
                        ScratchLow[sIdx + c] = bLow;
                        ScratchMid[sIdx + c] = bMid;
                        ScratchHigh[sIdx + c] = bHigh;
                        nsBandAcc[0] += bLow * bLow;
                        nsBandAcc[1] += bMid * bMid;
                        nsBandAcc[2] += bHigh * bHigh;
                    }
                }
                float invSamples = 1.0f / (blk * ch);
                for (int b = 0; b < 3; b++) {
                    float e = nsBandAcc[b] * invSamples;
                    nsEnv[b] = 0.60f * nsEnv[b] + 0.40f * e;
                    if (nsEnv[b] < nsFloor[b]) {
                        nsFloor[b] = 0.80f * nsFloor[b] + 0.20f * nsEnv[b];
                    } else {
                        nsFloor[b] = nsFloor[b] * 1.00018f + 1e-10f;
                    }
                    float snr = nsEnv[b] / (nsFloor[b] + 1e-10f);
                    float overSub = 1.0f + 2.5f * nAmt;
                    float g = (snr - overSub) / Math.max(snr, 1e-6f);
                    float floorG = (b == 1)
                            ? (1.0f - 0.75f * nAmt)
                            : (1.0f - 0.88f * nAmt);
                    g = Math.max(floorG, Math.min(1.0f, g));
                    float smooth = g > nsTargetGain[b] ? 0.35f
                            : (b == 2 ? 0.08f : 0.035f);
                    nsTargetGain[b] += smooth * (g - nsTargetGain[b]);
                }
                float dG0 = (nsTargetGain[0] - nsGain[0]) * INV_DECIM;
                float dG1 = (nsTargetGain[1] - nsGain[1]) * INV_DECIM;
                float dG2 = (nsTargetGain[2] - nsGain[2]) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    nsGain[0] += dG0;
                    nsGain[1] += dG1;
                    nsGain[2] += dG2;
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] = nsGain[0] * ScratchLow[sIdx + c]
                                + nsGain[1] * ScratchMid[sIdx + c]
                                + nsGain[2] * ScratchHigh[sIdx + c];
                    }
                }
            }

            // Stage 3: Warmth & Vocal Presence Contour
            if (wAmt > 0.001f || pAmt > 0.001f) {
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        warmLp[c] += WARM_ALPHA * (x - warmLp[c]);
                        presLp[c] += PRES_ALPHA * (x - presLp[c]);
                        float hi = x - presLp[c];
                        buf[idx + c] = x + (wAmt * 0.45f) * warmLp[c]
                                + (pAmt * 0.55f) * hi;
                    }
                }
            }

            // Stage 4: Split-Band Relative Sidechain De-Esser
            if (dAmt > 0.001f) {
                float sibEnergy = 0f;
                float fullEnergy = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        float x = buf[idx + c];
                        float sib = deB0 * x + deZ1[c];
                        deZ1[c] = deB1 * x - deA1 * sib + deZ2[c];
                        deZ2[c] = deB2 * x - deA2 * sib;
                        ScratchSib[sIdx + c] = sib;
                        sibEnergy += sib * sib;
                        fullEnergy += x * x;
                    }
                }
                float invN = 1.0f / (blk * ch);
                deSibEnv = 0.50f * deSibEnv + 0.50f * (sibEnergy * invN);
                deFullEnv = 0.70f * deFullEnv + 0.30f * (fullEnergy * invN);
                float ratioDb = fastLinToDb(deSibEnv + 1e-10f) * 0.5f
                        - fastLinToDb(deFullEnv + 1e-9f) * 0.5f;
                float threshDb = 1.5f - 10.0f * dAmt;
                float wantCut = 0f;
                if (ratioDb > threshDb && deFullEnv > 1e-7f) {
                    float overDb = Math.min(12.0f, (ratioDb - threshDb) * 1.2f);
                    wantCut = 1.0f - fastDbToLin(-overDb);
                }
                float alphaDe = wantCut > deCutTarget ? 0.45f : 0.10f;
                deCutTarget += alphaDe * (wantCut - deCutTarget);
                float dCut = (deCutTarget - deCutCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    deCutCur += dCut;
                    int idx = (offset + f) * ch;
                    int sIdx = f * ch;
                    for (int c = 0; c < ch; c++) {
                        buf[idx + c] -= deCutCur * ScratchSib[sIdx + c];
                    }
                }
            }

            // Stage 5: Soft-Knee Compressor + Auto-Makeup + Brickwall Limiter
            if (cAmt > 0.001f) {
                float peak = 0f;
                float sumSq = 0f;
                for (int f = 0; f < blk; f++) {
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float a = Math.abs(buf[idx + c]);
                        if (a > peak) peak = a;
                        sumSq += a * a;
                    }
                }
                float rms = (float) Math.sqrt(sumSq / (blk * ch));
                float det = Math.max(1e-6f, 0.7f * peak + 0.3f * rms);
                float inDb = fastLinToDb(det);
                float alphaC = inDb > compEnvDb ? 0.08f : 0.0035f;
                compEnvDb += alphaC * (inDb - compEnvDb);

                float threshDb = -12.0f - 14.0f * cAmt;
                float ratio = 1.0f + 3.0f * cAmt;
                float slope = 1.0f - (1.0f / ratio);
                float knee = 6.0f;
                float over = compEnvDb - threshDb;
                float grDb;
                if (2.0f * over < -knee) {
                    grDb = 0.0f;
                } else if (2.0f * Math.abs(over) <= knee) {
                    float t = over + knee * 0.5f;
                    grDb = -slope * (t * t) / (2.0f * knee);
                } else {
                    grDb = -slope * over;
                }
                float makeupDb = (-threshDb * slope) * 0.45f;
                compGainTarget = fastDbToLin(grDb + makeupDb);
                float dG = (compGainTarget - compGainCur) * INV_DECIM;
                for (int f = 0; f < blk; f++) {
                    compGainCur += dG;
                    int idx = (offset + f) * ch;
                    for (int c = 0; c < ch; c++) {
                        float y = buf[idx + c] * compGainCur;
                        float ay = Math.abs(y);
                        if (ay > 0.85f) {
                            float d = ay - 0.85f;
                            float lim = 0.85f + 0.135f * (d / (0.135f + d));
                            y = y >= 0f ? lim : -lim;
                        }
                        buf[idx + c] = y;
                    }
                }
            }

            offset += blk;
        }
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


