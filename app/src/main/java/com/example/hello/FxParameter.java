package com.example.hello;

import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Strongly typed, serializable parameter descriptor for an
 * {@link AudioProcessor}. A processor declares the parameters it exposes and
 * the FX inspector renders one control per parameter, so multi-knob effects
 * need no bespoke UI.
 *
 * &lt;p&gt;Values are stored as integer &quot;raw&quot; counts so that stepping, clamping and
 * persistence are exact; {@link #getFloat()} rescales by
 * {@link #scaleDivisor} (e.g. raw 350 with divisor 100 is 3.50). Display
 * formatting is scale-aware (dB suffix, enum labels) and can be overridden
 * with a {@link ValueFormatter}.
 */
public final class FxParameter {

    public enum Type { FLOAT, INT, BOOLEAN, ENUM, ACTION, CURVE_NODES }

    public enum Scale { LINEAR, LOGARITHMIC, DECIBEL, PIECEWISE_FREQ }

    /** Optional display override; when set, {@link #formatDisplay()} uses it. */
    public interface ValueFormatter {
        String format(FxParameter p, int rawValue);
    }

    public final String id;
    public final String name;
    public final String unit;
    public final Type type;
    public final Scale scale;
    public final int minRaw;
    public final int maxRaw;
    public final int stepRaw;
    public final int defaultRaw;
    public final int scaleDivisor;
    public final String[] enumLabels;

    private volatile int currentRaw;
    private volatile ValueFormatter formatter;

    private FxParameter(String id, String name, String unit, Type type,
                        Scale scale, int minRaw, int maxRaw, int stepRaw,
                        int defaultRaw, int scaleDivisor, String[] enumLabels) {
        this.id = id;
        this.name = name;
        this.unit = unit == null ? "" : unit;
        this.type = type;
        this.scale = scale;
        this.minRaw = minRaw;
        this.maxRaw = maxRaw;
        this.stepRaw = Math.max(1, stepRaw);
        this.scaleDivisor = Math.max(1, scaleDivisor);
        this.enumLabels = enumLabels;
        this.defaultRaw = Math.max(minRaw, Math.min(maxRaw, defaultRaw));
        this.currentRaw = clampRaw(defaultRaw);
    }

    /** Integer parameter in {@code unit} (e.g. " Hz", "%"). */
    public static FxParameter intParam(String id, String name, String unit,
                                       int min, int max, int step, int def) {
        return new FxParameter(id, name, unit, Type.INT, Scale.LINEAR,
                min, max, step, def, 1, null);
    }

    /** Fractional parameter stored scaled by 100 (two decimal places). */
    public static FxParameter floatParam(String id, String name, String unit,
                                         float min, float max, float def) {
        return new FxParameter(id, name, unit, Type.FLOAT, Scale.LINEAR,
                Math.round(min * 100f), Math.round(max * 100f), 1,
                Math.round(def * 100f), 100, null);
    }

    /** Decibel parameter (displays a " dB" suffix). */
    public static FxParameter dbParam(String id, String name,
                                      float minDb, float maxDb, float defDb) {
        FxParameter p = new FxParameter(id, name, " dB", Type.FLOAT,
                Scale.DECIBEL, Math.round(minDb * 100f), Math.round(maxDb * 100f),
                1, Math.round(defDb * 100f), 100, null);
        return p;
    }

    public static FxParameter boolParam(String id, String name, boolean def) {
        return new FxParameter(id, name, "", Type.BOOLEAN, Scale.LINEAR,
                0, 1, 1, def ? 1 : 0, 1, null);
    }

    public static FxParameter enumParam(String id, String name,
                                        String[] labels, int defIndex) {
        return new FxParameter(id, name, "", Type.ENUM, Scale.LINEAR,
                0, Math.max(0, labels.length - 1), 1, defIndex, 1, labels);
    }

    /** One-shot command parameter (renders as a button, holds no value). */
    public static FxParameter actionParam(String id, String name) {
        return new FxParameter(id, name, "", Type.ACTION, Scale.LINEAR,
                0, 0, 1, 0, 1, null);
    }

    public int getCurrentRaw() {
        return currentRaw;
    }

    public void setCurrentRaw(int raw) {
        this.currentRaw = clampRaw(raw);
    }

    public void resetToDefault() {
        this.currentRaw = clampRaw(defaultRaw);
    }

    public int clampRaw(int raw) {
        int v = Math.max(minRaw, Math.min(maxRaw, raw));
        if (type == Type.ENUM || type == Type.BOOLEAN || type == Type.ACTION) {
            return v;
        }
        if (stepRaw > 1 && v < maxRaw) {
            int steps = Math.round((v - minRaw) / (float) stepRaw);
            v = minRaw + steps * stepRaw;
            v = Math.max(minRaw, Math.min(maxRaw, v));
        }
        return v;
    }

    public float getFloat() {
        return currentRaw / (float) scaleDivisor;
    }

    public void setFloat(float v) {
        this.currentRaw = clampRaw(Math.round(v * scaleDivisor));
    }

    public boolean getBoolean() {
        return currentRaw != 0;
    }

    public void setBoolean(boolean b) {
        this.currentRaw = b ? 1 : 0;
    }

    public int getEnumIndex() {
        return currentRaw;
    }

    public void setEnumIndex(int idx) {
        this.currentRaw = clampRaw(idx);
    }

    public String getEnumLabel() {
        if (enumLabels == null || currentRaw < 0
                || currentRaw >= enumLabels.length) {
            return "";
        }
        return enumLabels[currentRaw];
    }

    public void setValueFormatter(ValueFormatter f) {
        this.formatter = f;
    }

    /** Human readable value: enum label, or scaled number plus unit. */
    public String formatDisplay() {
        if (formatter != null) {
            return formatter.format(this, currentRaw);
        }
        if (type == Type.BOOLEAN) {
            return currentRaw != 0 ? "On" : "Off";
        }
        if (type == Type.ENUM) {
            return getEnumLabel();
        }
        if (type == Type.ACTION) {
            return name;
        }
        if (scaleDivisor <= 1) {
            return unit.isEmpty() ? String.valueOf(currentRaw)
                    : currentRaw + unit;
        }
        double d = currentRaw / (double) scaleDivisor;
        String num;
        if (scale == Scale.DECIBEL) {
            if (scaleDivisor >= 100 && (Math.abs(currentRaw) % 10) != 0) {
                num = String.format(Locale.US, "%+.2f", d);
            } else {
                num = String.format(Locale.US, "%+.1f", d);
            }
        } else if (Math.abs(d) >= 10 || currentRaw % scaleDivisor == 0) {
            num = trimTrailingZeros(String.format(Locale.US, "%.1f", d));
        } else {
            num = trimTrailingZeros(String.format(Locale.US, "%.2f", d));
        }
        return unit.isEmpty() ? num : num + unit;
    }

    private static String trimTrailingZeros(String s) {
        if (!s.contains(".")) return s;
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("unit", unit);
            o.put("type", type.name());
            o.put("scale", scale.name());
            o.put("min", minRaw);
            o.put("max", maxRaw);
            o.put("step", stepRaw);
            o.put("def", defaultRaw);
            o.put("div", scaleDivisor);
            o.put("cur", currentRaw);
            if (enumLabels != null) {
                JSONArray a = new JSONArray();
                for (String l : enumLabels) a.put(l);
                o.put("enums", a);
            }
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Rebuilds a parameter from {@link #toJson()} output. */
    public static FxParameter fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        String[] enums = null;
        JSONArray ea = o.optJSONArray("enums");
        if (ea != null && ea.length() > 0) {
            enums = new String[ea.length()];
            for (int i = 0; i < enums.length; i++) enums[i] = ea.optString(i, "");
        }
        Type t;
        Scale sc;
        try {
            t = Type.valueOf(o.optString("type", Type.INT.name()));
        } catch (Exception e) {
            t = Type.INT;
        }
        try {
            sc = Scale.valueOf(o.optString("scale", Scale.LINEAR.name()));
        } catch (Exception e) {
            sc = Scale.LINEAR;
        }
        FxParameter p = new FxParameter(id, o.optString("name", ""),
                o.optString("unit", ""), t, sc,
                o.optInt("min", 0), o.optInt("max", 100),
                o.optInt("step", 1), o.optInt("def", 0),
                o.optInt("div", 1), enums);
        p.currentRaw = p.clampRaw(o.optInt("cur", p.defaultRaw));
        return p;
    }
}
