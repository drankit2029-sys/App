package com.example.hello;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Base class for every effect in the rack. A processor owns its {@link
 * FxParameter} list, its enable state and its wet/dry mix, and implements the
 * actual DSP in {@link #processInPlace}. This replaces the old single-integer
 * {@code DspChain.EffectSlot} model: each effect can now expose any number of
 * typed parameters, and the FX inspector renders them generically.
 *
 * <p>Subclasses that are 2-pass or change duration override
 * {@link #isOfflineOnly()} so the realtime path skips them; effects with a
 * tail (reverb/delay) report {@link #getTailFrames()}.
 */
public abstract class AudioProcessor {

    private volatile boolean enabled = true;
    private volatile float wetDryMix = 1.0f;
    private final List<FxParameter> parameters = new ArrayList<>();
    /**
     * Identity of the rack entry this processor belongs to. Rack-local
     * effects (the Stage 3+ suite) use the slot's instance id so the same
     * effect can be inserted twice and each copy keeps its own state; the
     * legacy stages leave it null and are matched by {@link #getId()}.
     */
    private volatile String instanceKey;

    public String getInstanceKey() {
        return instanceKey;
    }

    public void setInstanceKey(String key) {
        this.instanceKey = key;
    }

    public abstract String getId();

    public abstract String getTitle();

    public abstract String getDescription();

    public abstract FxCategory getCategory();

    /** Configures channel count / sample rate; called before the first block. */
    public abstract void prepare(int sampleRate, int channels);

    /** Clears all filter/envelope state (start of a new edit region). */
    public abstract void reset();

    /**
     * Optional offline pre-scan over the exact range about to be rendered.
     * Level tools (amplify, normalize, loudness) use this to measure peak,
     * DC offset or integrated loudness before the first sample is written.
     * The default is a no-op, so real-time processors need no extra work.
     */
    public void prepareOffline(java.io.File raw, long startFrame,
                               long endFrame, int sampleRate, int channels)
            throws Exception {
    }

    /** Processes {@code frames} interleaved frames in place. */
    public abstract void processInPlace(float[] interleaved, int frames,
                                        int channels);

    /** True for processors that need a second pass or alter duration. */
    public boolean isOfflineOnly() {
        return false;
    }

    /**
     * Renders an entire region offline and returns the interleaved result.
     *
     * <p>The returned array may be longer or shorter than the input when the
     * effect changes duration (Change Tempo, Change Speed, Paulstretch,
     * Repeat, Truncate Silence). {@code null} means "this processor has no
     * offline renderer", and the caller then keeps the region untouched.
     */
    public float[] processOfflineRegion(float[] in, int frames, int channels) {
        return null;
    }

    /** Latency/tail in frames after the input ends (reverb, delay). */
    public int getTailFrames() {
        return 0;
    }

    /**
     * Algorithmic group delay / lookahead latency in frames (e.g. {@code taps / 2}
     * for linear-phase FIR filters). Offline range commits shift and drain by
     * this offset so processed audio stays sample-aligned with dry boundaries.
     */
    public int getLatencyFrames() {
        return 0;
    }

    /**
     * True when the processor blends its own {@link #getWetDryMix()} internally
     * (e.g. phase-aligned linear-phase FIR blending) so {@link FxRackChain}
     * does not blend undelayed dry samples against delayed wet samples.
     */
    public boolean handlesOwnWetDryMix() {
        return false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean en) {
        this.enabled = en;
    }

    public float getWetDryMix() {
        return wetDryMix;
    }

    public void setWetDryMix(float mix0to1) {
        this.wetDryMix = Math.max(0f, Math.min(1f, mix0to1));
    }

    public List<FxParameter> getParameters() {
        return parameters;
    }

    protected FxParameter addParameter(FxParameter p) {
        if (p != null) parameters.add(p);
        return p;
    }

    public FxParameter getParam(String id) {
        for (FxParameter p : parameters) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /**
     * True when this processor would alter audio if it ran (amount above the
     * engine's 0.001 gate). Used by chains to decide activeness.
     */
    public boolean isAudible() {
        return enabled && wetDryMix > 0.001f;
    }

    /** Serializes enable, mix and every parameter's raw value. */
    public JSONObject saveState() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", getId());
            o.put("enabled", enabled);
            o.put("mix", (double) wetDryMix);
            JSONArray a = new JSONArray();
            for (FxParameter p : parameters) a.put(p.toJson());
            o.put("params", a);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Restores enable, mix and parameters by id. Unknown ids are ignored so a
     * rack saved by a newer build still loads.
     */
    public void loadState(JSONObject json) {
        if (json == null) return;
        enabled = json.optBoolean("enabled", enabled);
        float m = (float) json.optDouble("mix", (double) wetDryMix);
        wetDryMix = Math.max(0f, Math.min(1f, m));
        JSONArray a = json.optJSONArray("params");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject po = a.optJSONObject(i);
            if (po == null) continue;
            FxParameter mine = getParam(po.optString("id", ""));
            if (mine == null) continue;
            mine.setCurrentRaw(po.optInt("cur", mine.getCurrentRaw()));
        }
    }
}
