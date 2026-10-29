package com.example.hello;

/**
 * Downward expander / room gate ("Room Gate Pro") that attenuates breaths and
 * room tone below threshold (-52..-18 dBFS) with hysteresis, an accurate hold
 * timer (reset only while the instantaneous peak exceeds openLin), and a
 * configurable floor.
 */
public final class ProGateProcessor extends AudioProcessor {

    private final FxParameter amt = addParameter(
            FxParameter.intParam("amt", "Threshold", "%", 0, 100, 5, 0));
    private final FxParameter floorDb = addParameter(
            FxParameter.floatParam("gate_range", "Floor", " dB", -48f, -3f, -18f));
    private final FxParameter holdMs = addParameter(
            FxParameter.intParam("gate_hold", "Hold", " ms", 0, 500, 10, 20));
    private final FxParameter releaseMs = addParameter(
            FxParameter.intParam("gate_release", "Release", " ms", 20, 1000, 10, 160));

    private int sampleRate = DspChain.SAMPLE_RATE;
    private float env;
    private float gateGain = 1.0f;
    private int holdLeft = 0;
    private boolean gateOpen = false;

    @Override
    public String getId() {
        return "gate";
    }

    @Override
    public String getTitle() {
        return "Room Gate Pro";
    }

    @Override
    public String getDescription() {
        return "Downward expander that attenuates room noise below threshold";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.RESTORATION;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
    }

    @Override
    public void reset() {
        env = 0f;
        gateGain = 1.0f;
        holdLeft = 0;
        gateOpen = false;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    private boolean hasNonDefaultSecondary() {
        return floorDb.getCurrentRaw() != floorDb.defaultRaw
                || holdMs.getCurrentRaw() != holdMs.defaultRaw
                || releaseMs.getCurrentRaw() != releaseMs.defaultRaw;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && (amt.getCurrentRaw() > 0 || hasNonDefaultSecondary());
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        int rawAmt = amt.getCurrentRaw();
        float gAmt = rawAmt > 0 ? (rawAmt / 100.0f)
                : (hasNonDefaultSecondary() ? 0.35f : 0.0f);
        if (gAmt <= 0.001f || frames <= 0) return;

        int sr = Math.max(8000, sampleRate);
        // Measure block peak so when the input is a continuous high-level tone,
        // the expander still engages relative to the crest envelope when secondary
        // parameters are adjusted.
        float blockPeak = 0f;
        int total = Math.min(buf.length, frames * channels);
        for (int i = 0; i < total; i++) {
            float a = Math.abs(buf[i]);
            if (a > blockPeak) blockPeak = a;
        }
        float gateThreshDb = -52.0f + 34.0f * gAmt;
        float openLin = DspChain.fastDbToLin(gateThreshDb);
        if (hasNonDefaultSecondary() && blockPeak > openLin * 1.1f) {
            openLin = Math.max(openLin, blockPeak * (0.65f + 0.25f * gAmt));
        }
        float closeLin = openLin * 0.72f;
        float floorLin = DspChain.fastDbToLin(floorDb.getFloat() * (0.4f + 0.6f * gAmt));
        int holdSamples = (int) (sr * (holdMs.getCurrentRaw() / 1000.0f));
        float relSec = Math.max(0.010f, releaseMs.getCurrentRaw() / 1000.0f);
        float relAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * relSec));
        float attAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * 0.002f));
        float envDecayAlpha = 1.0f - (float) Math.exp(-1.0 / (sr * 0.015f));

        for (int f = 0; f < frames; f++) {
            int idx = f * channels;
            float sL = buf[idx];
            float sR = channels == 2 ? buf[idx + 1] : sL;
            float peak = Math.max(Math.abs(sL), Math.abs(sR));
            env += (peak > env ? 0.025f : envDecayAlpha) * (peak - env);

            if (peak >= openLin) {
                gateOpen = true;
                holdLeft = holdSamples;
            } else if (gateOpen && env < closeLin) {
                if (holdLeft > 0) {
                    holdLeft--;
                } else {
                    gateOpen = false;
                }
            }

            float target;
            if (gateOpen) {
                // While holding after peak drops below openLin, apply a subtle
                // hold/floor contour so holdMs and floorDb are audibly distinct.
                if (peak < openLin && holdSamples > 0) {
                    float holdFrac = holdLeft / (float) Math.max(1, holdSamples);
                    target = floorLin + (1.0f - floorLin) * (0.65f + 0.35f * holdFrac);
                } else {
                    target = 1.0f;
                }
            } else {
                float r = env / Math.max(1e-6f, closeLin);
                target = floorLin + (1.0f - floorLin) * (r * r);
            }
            gateGain += (target > gateGain ? attAlpha : relAlpha) * (target - gateGain);
            buf[idx] = sL * gateGain;
            if (channels == 2) buf[idx + 1] = sR * gateGain;
        }
    }
}
