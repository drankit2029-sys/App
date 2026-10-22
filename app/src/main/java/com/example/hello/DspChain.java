package com.example.hello;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
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
 *   &lt;li&gt;&lt;b&gt;Noise Suppression Pro (0–100%)&lt;/b&gt;: latency-compensated STFT
 *       spectral subtraction against a noise profile learned from a selected
 *       noise-only region (adaptive per-bin fallback when no profile exists).</li>
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
        NOISE_SUPPRESSION("noise", "Noise Suppression Pro",
                "Learned-profile spectral denoiser: analyze a noise-only selection, then apply",
                "Intensity", 0, 100, 5, 50, "%"),
        DE_ESSER("deess", "De-Esser Pro",
                "4th-order split/broadband sibilance control",
                "Reduction", 0, 100, 5, 45, "%"),
        COMPRESSOR("comp", "Compressor Pro",
                "Soft-knee leveler with ratio, attack, release and knee",
                "Compression", 0, 100, 5, 40, "%"),
        LOW_CUT_HPF("hpf", "Low-Cut Filter Pro",
                "Butterworth rumble & plosive filter, 12/24 dB per octave",
                "Cutoff", 40, 160, 10, 80, " Hz"),
        VOCAL_PRESENCE("presence", "Vocal Presence & Air",
                "Clarity boost in the 3.5 kHz articulation band",
                "Boost", 0, 100, 5, 45, "%"),
        WARMTH_BODY("warmth", "Warmth & Chest Body",
                "Rich low-mid contour around 180 Hz for thin mics",
                "Warmth", 0, 100, 5, 40, "%"),
        ROOM_GATE("gate", "Room Gate Pro",
                "Hysteresis expander with hold and musical release",
                "Threshold", 0, 100, 5, 40, "%"),
        BRICKWALL_LIMITER("limiter", "Brickwall Limiter Pro",
                "True-peak ceiling, drive and programme release",
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
                "Cutoff", 20, 20000, 10, 1000, " Hz"),

        // ---- Stage 4 dynamics & leveling suite ----
        AMPLIFY("amplify", "Amplify",
                "Two-pass gain with an automatic non-clipping peak scan",
                "Gain", -5000, 5000, 100, 0, " dB"),
        NORMALIZE("normalize", "Normalize",
                "Peak or RMS normalization with DC-offset removal",
                "Target", -6000, 0, 100, -100, " dBFS"),
        LOUDNESS_NORMALIZATION("loudness", "Loudness Normalization",
                "EBU R128 K-weighted LUFS normalization with true-peak ceiling",
                "Target", -7000, 0, 100, -1600, " LUFS"),
        DYNAMIC_COMPRESSOR("dyncomp", "Compressor",
                "Soft-knee dynamics compressor with noise-floor freeze",
                "Threshold", -6000, -100, 100, -1800, " dB"),
        MULTIBAND_COMPRESSOR("mbcomp", "Multiband Compressor",
                "3-band Linkwitz-Riley vocal leveler",
                "Amount", 0, 100, 1, 40, " %"),
        BRICKWALL_LIMITER_STAGE4("bricklim", "Brickwall Limiter",
                "3 ms look-ahead limiter with soft/hard ceiling",
                "Ceiling", -1500, -5, 5, -30, " dBFS"),
        NOISE_GATE_EXPANDER("gateexp", "Gate / Expander",
                "Hysteresis gate and downward expander with sidechain HPF",
                "Threshold", -9600, -600, 100, -4200, " dB"),
        DEESSER_SPLIT("deesssplit", "De-Esser (Split Band)",
                "Relative sibilance detection across the 3.5-12 kHz band",
                "Threshold", -4800, 0, 100, -1800, " dB"),
        AUTO_DUCK("autoduck", "Auto Duck",
                "Speech-triggered ducking envelope with pause bridging",
                "Duck", -3600, 0, 100, -1200, " dB"),

        // ---- Stage 5 restoration suite ----
        SPECTRAL_NOISE_REDUCTION("specnr", "Spectral Noise Reduction",
                "STFT spectral subtraction against a learned noise profile",
                "Reduction", 0, 4800, 100, 1200, " dB"),
        CLICK_REMOVAL("declick", "Click Removal",
                "Detects and repairs isolated digital clicks and pops",
                "Threshold", 1, 900, 1, 200, ""),
        CLIP_FIX("clipfix", "Clip Fix",
                "Cubic-spline de-clipper with headroom attenuation",
                "Threshold", 80, 100, 1, 95, " %"),
        REPAIR("repair", "Repair",
                "LPC short-glitch interpolator for dropouts and damage",
                "Order", 8, 32, 1, 16, ""),
        DE_HUM("dehum", "De-Hum",
                "Notch cascade at the mains frequency and its harmonics",
                "Frequency", 45, 65, 1, 50, " Hz"),
        PLOSIVE_SUPPRESSOR("plosive", "Plosive & Breath Suppressor",
                "Dynamic sub-140 Hz plosive tamer with breath attenuation",
                "Sensitivity", 0, 100, 1, 50, " %"),

        // ---- Stage 6 spatial, distortion & modulation suite ----
        FREEVERB_REVERB("reverb", "Studio Reverb",
                "Freeverb-style stereo reverb with damping and width",
                "Room Size", 0, 100, 1, 55, " %"),
        MULTI_DELAY("multidelay", "Multi-Echo Delay",
                "Regular, bouncing-ball or reverse multi-tap echo",
                "Delay", 10, 3000, 10, 300, " ms"),
        ECHO("echo", "Echo",
                "Simple feedback echo with adjustable decay",
                "Delay", 10, 5000, 10, 500, " ms"),
        STEREO_IMAGING("stereoimg", "Stereo Imaging",
                "Pan, mid/side width and phase controls",
                "Pan", -100, 100, 1, 0, " %"),
        DISTORTION("distort", "Distortion",
                "11-mode waveshaper with oversampling and tone control",
                "Drive", 0, 100, 1, 50, " %"),
        PHASER("phaser", "Phaser",
                "Multi-stage all-pass phaser with LFO and feedback",
                "Mix", 0, 100, 1, 50, " %"),
        CHORUS_FLANGER("chorus", "Chorus & Flanger",
                "Modulated delay with cubic Hermite fractional reads",
                "Mix", 0, 100, 1, 50, " %"),
        TREMOLO("tremolo", "Tremolo",
                "Amplitude modulation with five LFO waveforms",
                "Depth", 0, 100, 1, 40, " %"),
        WAHWAH("wah", "Wahwah",
                "Swept resonant band-pass with envelope auto-wah",
                "Depth", 0, 100, 1, 70, " %"),
        VOCODER("vocoder", "Vocoder",
                "Band-filterbank vocoder with an internal carrier",
                "Pitch", 50, 400, 1, 110, " Hz"),

        // ---- Stage 7 time, utility, generator & analyzer suite ----
        CHANGE_PITCH("changepitch", "Change Pitch",
                "WSOLA pitch shift that keeps the tempo",
                "Semitones", -2400, 2400, 50, 0, ""),
        CHANGE_TEMPO("tempo", "Change Tempo",
                "WSOLA time stretch without changing the pitch",
                "Change", -7500, 30000, 100, 0, " %"),
        CHANGE_SPEED_AND_PITCH("speedpitch", "Change Speed and Pitch",
                "Varispeed resampler: duration and pitch change together",
                "Speed", 25, 400, 5, 100, " %"),
        SLIDING_STRETCH("slidestretch", "Sliding Stretch",
                "Continuous tempo and pitch ramp across the region",
                "Initial", -7500, 30000, 100, 0, " %"),
        PAULSTRETCH("paulstretch", "Paulstretch",
                "Extreme random-phase ambient time stretch",
                "Factor", 150, 10000, 50, 800, " %"),
        FADE("fade", "Fade",
                "Fade in, fade out, studio fade or custom curve",
                "Length", 1, 100, 1, 20, " %"),
        TIMELINE_UTILITY("timeline", "Timeline Utility",
                "Invert polarity, reverse the region or repeat it",
                "Repeats", 1, 50, 1, 2, ""),
        TRUNCATE_SILENCE("truncsil", "Truncate Silence",
                "Automatic pause shortener with truncate or compress",
                "Threshold", -8000, -2000, 100, -4000, " dB"),
        SPECTRAL_EDIT("spectraledit", "Spectral Edit",
                "Parametric, shelf or notch EQ applied in the STFT domain",
                "Gain", -3600, 2400, 100, 0, " dB"),
        AUDIO_GENERATOR("generator", "Audio Generator",
                "Tone, chirp, noise, rhythm or DTMF generator",
                "Amplitude", 0, 100, 1, 50, " %");

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

        /**
         * True for the Stage 3 EQ and filter suite. Together with
         * {@link #isLegacyStage()} this covers every effect that was in the
         * catalog before Stages 4-7; the newer families each need their own
         * engagement (some are offline-only, some are level tools that need
         * a pre-scan), so they are covered by their own suites.
         */
        public boolean isStage3Suite() {
            switch (this) {
                case PARAMETRIC_EQ6:
                case GRAPHIC_EQ:
                case FILTER_CURVE_EQ:
                case BASS_TREBLE:
                case HIGH_PASS_FILTER:
                case LOW_PASS_FILTER:
                case NOTCH_FILTER:
                case SHELF_FILTER:
                case CLASSIC_FILTERS:
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
        /**
         * Stable identity of this rack entry, so the same effect can be
         * inserted twice and each copy keeps its own processor, parameters
         * and learned profile. Persisted with the slot.
         */
        public final long instanceId;
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
        /**
         * Learned noise power spectrum (linear, one value per FFT bin) for the
         * Noise Suppression Pro stage; null means adaptive estimation. Captured
         * from a noise-only waveform selection and persisted with the slot.
         */
        public float[] noiseProfile;

        /** Monotonic source of unique rack-entry identities. */
        private static final java.util.concurrent.atomic.AtomicLong
                NEXT_INSTANCE_ID = new java.util.concurrent.atomic.AtomicLong(1);

        public EffectSlot(EffectType type, boolean enabled, int value, boolean expanded) {
            this(type, enabled, value, expanded, NEXT_INSTANCE_ID.getAndIncrement());
        }

        public EffectSlot(EffectType type, boolean enabled, int value,
                          boolean expanded, long instanceId) {
            this.type = type;
            this.enabled = enabled;
            this.value = Math.max(type.minVal, Math.min(type.maxVal, value));
            this.expanded = expanded;
            this.instanceId = instanceId;
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

        /**
         * Adds a rack entry for {@code type}.
         *
         * <p>Every modern effect gets its own slot, so the same effect can be
         * inserted twice (or more) and each copy keeps its own parameters,
         * wet/dry mix and learned profile.
         *
         * <p>The eight legacy voice-chain stages are the exception: they are
         * one fixed percentage chain, so a second copy of one of them would
         * fight over the same shared field. Adding a legacy stage therefore
         * focuses the existing entry instead.
         */
        public EffectSlot addOrFocusSlot(EffectType type) {
            if (type != null && type.isLegacyStage()) {
                for (EffectSlot s : slots) {
                    if (s.type == type) {
                        s.enabled = true;
                        s.expanded = true;
                        activePresetId = Preset.CUSTOM.id;
                        syncLegacyFieldsFromSlots();
                        return s;
                    }
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

        /**
         * Empties the rack after Apply FX so the next edit starts from a clean
         * slate. The audio already carries the baked chain; keeping the slots
         * enabled would let a later preview or save process those samples a
         * second time.
         */
        public void clearRack() {
            slots.clear();
            activePresetId = Preset.CLEAR_ALL.id;
            syncLegacyFieldsFromSlots();
            mode = MODE_OFF;
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

            List<EffectSlot> previous = new ArrayList<>(slots);
            slots.clear();
            // Preserve the user's relative ordering, bypassed (unchecked)
            // pills, and per-slot wetDry/extraParams for every slot already
            // in the rack. Modern effects keep every copy: two slots of the
            // same effect are two independent rack entries.
            java.util.Set<EffectType> seenLegacy = new java.util.HashSet<>();
            for (EffectSlot kept : previous) {
                if (kept == null || kept.type == null) continue;
                if (kept.type.isLegacyStage()) {
                    if (!seenLegacy.add(kept.type)) continue;
                    Integer val = activeValues.remove(kept.type);
                    if (val != null) {
                        kept.enabled = true;
                        kept.value = Math.max(kept.type.minVal,
                                Math.min(kept.type.maxVal, val));
                    } else {
                        kept.enabled = false;
                    }
                }
                slots.add(kept);
            }
            // Append any newly enabled legacy stages in canonical stage order.
            for (Map.Entry<EffectType, Integer> e : activeValues.entrySet()) {
                EffectType t = e.getKey();
                int val = e.getValue();
                slots.add(new EffectSlot(t, true, val, false));
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
                        && !s.type.isLegacyStage() && s.wetDry > 0.001f) {
                    if (isStage3SlotAudible(s)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * True when a Stage 3 slot's current parameters actually alter the
         * signal. A flat EQ / 0 dB shelf is enabled but inaudible; treating it
         * as active would arm save-time baking and push undo state that
         * {@link DspChain#isActive()} (via the processors' isAudible())
         * refuses to record.
         */
        private static boolean isStage3SlotAudible(EffectSlot s) {
            switch (s.type) {
                case PARAMETRIC_EQ6:
                    if (s.value != 0) return true;
                    if (s.extraParams.getOrDefault("b1f", 80) > 20) return true;
                    if (s.extraParams.getOrDefault("b6f", 20000) < 20000) return true;
                    return s.extraParams.getOrDefault("b2g", 0) != 0
                            || s.extraParams.getOrDefault("b3g", 0) != 0
                            || s.extraParams.getOrDefault("b4g", 0) != 0
                            || s.extraParams.getOrDefault("b5g", 0) != 0;
                case GRAPHIC_EQ:
                    if (s.value != 0) return true;
                    int count = s.extraParams.getOrDefault("bands", 0) == 1 ? 31 : 10;
                    for (int i = 0; i < count; i++) {
                        if (s.extraParams.getOrDefault("g" + i, 0) != 0) return true;
                    }
                    return false;
                case FILTER_CURVE_EQ:
                    if (s.value != 0) return true;
                    int n = Math.max(2, Math.min(FilterCurveEqProcessor.MAX_NODES,
                            s.extraParams.getOrDefault("npts", 8)));
                    for (int i = 0; i < n; i++) {
                        if (s.extraParams.getOrDefault("pg" + i, 0) != 0) return true;
                    }
                    return false;
                case BASS_TREBLE:
                    if (s.value != 0 || s.extraParams.getOrDefault("vol", 0) != 0) {
                        return true;
                    }
                    boolean linked = s.extraParams.getOrDefault("link", 0) != 0;
                    return !linked && s.extraParams.getOrDefault("treble", 0) != 0;
                case SHELF_FILTER:
                    return s.value != 0;
                default:
                    return isProcessorAudible(s);
            }
        }

        /**
         * Asks the effect's own processor whether its current settings would
         * change the audio. A newly added effect sitting on its neutral
         * defaults (0 dB gain, 0 % depth, ...) must not count as an active
         * stage, otherwise the rack would look armed before the user touched
         * anything.
         */
        private static boolean isProcessorAudible(EffectSlot s) {
            AudioProcessor p = FxRackChain.createProcessor(s.type.id);
            if (p == null) return true;
            p.setEnabled(s.enabled);
            String primaryId = primaryParamId(s.type);
            if (primaryId != null) {
                FxParameter fp = p.getParam(primaryId);
                if (fp != null) fp.setCurrentRaw(s.value);
            }
            for (Map.Entry<String, Integer> e : s.extraParams.entrySet()) {
                FxParameter fp = p.getParam(e.getKey());
                if (fp != null) fp.setCurrentRaw(e.getValue());
            }
            return p.isAudible();
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
                        long iid = o.optLong("iid", 0L);
                        EffectSlot slot = iid > 0
                                ? new EffectSlot(t, en, val, exp, iid)
                                : new EffectSlot(t, en, val, exp);
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
                        slot.noiseProfile = NoiseSuppressionProcessor
                                .profileFromJson(o.optJSONArray("prof"));
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
                    o.put("iid", s.instanceId);
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
                    if (s.noiseProfile != null && s.noiseProfile.length > 0) {
                        o.put("prof", NoiseSuppressionProcessor
                                .profileToJson(s.noiseProfile));
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
    /** Processor id after which the second tone stage runs (split mode). */
    String getSplitToneAfterId() {
        return rack.getSplitToneAfterId();
    }

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
     * Creates or drops rack processors so every non-legacy slot owns exactly
     * one processor and deleted slots leave no orphan behind.
     *
     * <p>Processors are matched by the slot's instance key, so the same
     * effect can sit in the rack twice with independent parameters. Runs
     * before {@code applyLegacyConfig} so slot order can include them.
     */
    private void ensureRackSlots(Config cfg) {
        if (cfg == null) return;
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (EffectSlot s : cfg.slots) {
            if (s == null || s.type == null || s.type.isLegacyStage()) continue;
            String pid = FxRackChain.processorIdForSlotType(s.type);
            if (pid == null) continue;
            String key = slotInstanceKey(s);
            wanted.add(key);
            int idx = rack.indexOfInstance(key, pid);
            AudioProcessor p = idx >= 0 ? rack.getProcessor(idx) : null;
            if (p == null) {
                p = FxRackChain.createProcessor(pid);
                if (p == null) continue;
                p.setInstanceKey(key);
                rack.addProcessor(p);
            }
            if (p != null) p.setEnabled(s.enabled);
        }
        for (int i = rack.size() - 1; i >= 0; i--) {
            AudioProcessor p = rack.getProcessor(i);
            if (p == null || isLegacyProcessor(p)) continue;
            String key = p.getInstanceKey();
            if (key == null || !wanted.contains(key)) rack.removeProcessor(i);
        }
    }

    /** Stable instance key of a rack entry, used to bind it to a processor. */
    static String slotInstanceKey(EffectSlot s) {
        return "slot:" + s.type.id + ":" + s.instanceId;
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
            if (p instanceof NoiseSuppressionProcessor) {
                ((NoiseSuppressionProcessor) p).setNoiseProfile(null);
            }
            if (p instanceof SpectralNoiseReductionProcessor) {
                ((SpectralNoiseReductionProcessor) p).setNoiseProfile(null);
            }
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
            AudioProcessor p = processorForSlot(s);
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
            if (s.type == EffectType.NOISE_SUPPRESSION
                    && p instanceof NoiseSuppressionProcessor) {
                ((NoiseSuppressionProcessor) p).setNoiseProfile(
                        s.noiseProfile);
            }
            if (s.type == EffectType.SPECTRAL_NOISE_REDUCTION
                    && p instanceof SpectralNoiseReductionProcessor) {
                ((SpectralNoiseReductionProcessor) p).setNoiseProfile(
                        s.noiseProfile);
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
            // Stage 4 dynamics suite.
            case AMPLIFY:
                return "amp_gain";
            case NORMALIZE:
                return "norm_target";
            case LOUDNESS_NORMALIZATION:
                return "loud_target";
            case DYNAMIC_COMPRESSOR:
                return "dyncomp_thresh";
            case MULTIBAND_COMPRESSOR:
                return "mb_amount";
            case BRICKWALL_LIMITER_STAGE4:
                return "lim_ceiling";
            case NOISE_GATE_EXPANDER:
                return "gate_thresh";
            case DEESSER_SPLIT:
                return "deess_thresh";
            case AUTO_DUCK:
                return "duck_amount";
            // Stage 5 restoration suite.
            case SPECTRAL_NOISE_REDUCTION:
                return "snr_reduction";
            case CLICK_REMOVAL:
                return "click_thresh";
            case CLIP_FIX:
                return "clip_thresh";
            case REPAIR:
                return "repair_order";
            case DE_HUM:
                return "hum_freq";
            case PLOSIVE_SUPPRESSOR:
                return "plosive_sens";
            // Stage 6 spatial / creative suite.
            case FREEVERB_REVERB:
                return "rv_size";
            case MULTI_DELAY:
                return "md_time";
            case ECHO:
                return "echo_time";
            case STEREO_IMAGING:
                return "st_pan";
            case DISTORTION:
                return "dist_drive";
            case PHASER:
                return "ph_mix";
            case CHORUS_FLANGER:
                return "ch_mix";
            case TREMOLO:
                return "tr_depth";
            case WAHWAH:
                return "wah_depth";
            case VOCODER:
                return "vc_pitch";
            // Stage 7 time / utility / generator suite.
            case CHANGE_PITCH:
                return "pitch_semitones";
            case CHANGE_TEMPO:
                return "tempo_percent";
            case CHANGE_SPEED_AND_PITCH:
                return "speed_mult";
            case SLIDING_STRETCH:
                return "slide_init";
            case PAULSTRETCH:
                return "paul_factor";
            case FADE:
                return "fade_len";
            case TIMELINE_UTILITY:
                return "tl_repeats";
            case TRUNCATE_SILENCE:
                return "ts_thresh";
            case SPECTRAL_EDIT:
                return "se_gain";
            case AUDIO_GENERATOR:
                return "gen_amp";
            default:
                return null;
        }
    }

    /**
     * The processor that serves a rack entry.
     *
     * <p>The legacy stages are a fixed chain, so they share one processor
     * each. Every other effect is bound to its own rack entry by instance
     * key, which is what lets the same effect appear in the rack twice.
     */
    private AudioProcessor processorForSlot(EffectSlot slot) {
        if (slot == null || slot.type == null) return null;
        EffectType type = slot.type;
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
            default: {
                // Rack-local effects: bound to this slot's own processor.
                String pid = FxRackChain.processorIdForSlotType(type);
                if (pid == null) return null;
                int idx = rack.indexOfInstance(slotInstanceKey(slot), pid);
                return idx >= 0 ? rack.getProcessor(idx) : null;
            }
        }
    }

    public boolean isActive() {
        return rack.isActive();
    }

    public void reset() {
        rack.reset();
    }

    /** Total algorithmic latency (frames) of the active rack. */
    public int getLatencyFrames() {
        return rack.getLatencyFrames();
    }

    /**
     * The catalog category an effect belongs to. Derived from the
     * registered processor so the "+ Add Effect" dialog can group the whole
     * catalog without a second hand-maintained table.
     */
    public static FxCategory categoryOf(EffectType type) {
        if (type == null) return FxCategory.EQUALIZER;
        AudioProcessor p = FxRackChain.getPrototypeProcessor(type.id);
        if (p != null) return p.getCategory();
        // Legacy stages share a handful of processors.
        switch (type) {
            case NOISE_SUPPRESSION:
            case ROOM_GATE:
            case DE_ESSER:
                return FxCategory.RESTORATION;
            case COMPRESSOR:
            case BRICKWALL_LIMITER:
                return FxCategory.DYNAMICS;
            case LOW_CUT_HPF:
            case VOCAL_PRESENCE:
            case WARMTH_BODY:
                return FxCategory.EQUALIZER;
            default:
                return FxCategory.EQUALIZER;
        }
    }

    /** Longest tail (frames) reported by any enabled processor. */
    public int getTailFrames() {
        return rack.getTailFrames();
    }

    /**
     * Runs the two-pass analysis hook on every enabled processor so the
     * level tools can measure the exact range that is about to be rendered.
     */
    public void prepareOffline(File raw, long startFrame, long endFrame,
                               int sampleRate, int channels) throws Exception {
        rack.prepareOffline(raw, startFrame, endFrame, sampleRate, channels);
    }

    /** The first enabled processor that renders its region offline. */
    public AudioProcessor findOfflineProcessor() {
        for (AudioProcessor p : rack.processors()) {
            if (!p.isEnabled()) continue;
            if (!p.isOfflineOnly()) continue;
            return p;
        }
        return null;
    }

    /** Every enabled processor that renders its region offline, in order. */
    public java.util.List<AudioProcessor> offlineProcessors() {
        java.util.List<AudioProcessor> out = new java.util.ArrayList<>();
        for (AudioProcessor p : rack.processors()) {
            if (!p.isEnabled() || !p.isOfflineOnly()) continue;
            out.add(p);
        }
        return out;
    }

    /** True when the rack still has processors that run in place. */
    public boolean hasInPlaceProcessors() {
        for (AudioProcessor p : rack.processors()) {
            if (!p.isEnabled() || p.isOfflineOnly()) continue;
            if (p.isAudible()) return true;
        }
        return false;
    }

    /**
     * Disables every offline processor so the remaining chain can be applied
     * in place over a region that an offline effect already rewrote.
     */
    public void clearOfflineProcessors() {
        for (AudioProcessor p : rack.processors()) {
            if (p.isOfflineOnly()) p.setEnabled(false);
        }
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
