package com.example.hello;

/**
 * Professional low-cut filter: a 4th-order (or 2nd-order) Butterworth
 * high-pass with a selectable slope, sample-rate-aware coefficients and
 * click-free coefficient glides while the cutoff is scrubbed.
 *
 * <p>The 24 dB/oct mode is built from two cascaded 2nd-order sections with
 * Q = 0.5412 and Q = 1.3066, the classic Butterworth pairing. Both sections
 * use direct-form II transposed state so low-frequency numerical precision
 * stays good on 32-bit float.
 */
final class ProHpfProcessor extends AudioProcessor {

    private static final int BLOCK = 16;

    private final FxParameter cutoff = addParameter(FxParameter.intParam(
            "cutoff", "Cutoff", " Hz", 40, 160, 1, 80));
    private final FxParameter slope = addParameter(FxParameter.enumParam(
            "slope", "Slope", new String[]{"12 dB/oct", "24 dB/oct"}, 1));

    private final float[] target = new float[10];
    private final float[] cur = new float[10];
    private boolean coefDirty = true;

    private final float[][] s1 = new float[2][2];
    private final float[][] s2 = new float[2][2];

    private int sampleRate = DspChain.SAMPLE_RATE;
    private int cachedHz = -1;
    private int cachedSlope = -1;
    private int cachedRate = -1;

    ProHpfProcessor() {
        updateTarget();
        snapCoefficients();
    }

    @Override
    public String getId() {
        return "hpf";
    }

    @Override
    public String getTitle() {
        return "Low-Cut Filter Pro";
    }

    @Override
    public String getDescription() {
        return "Butterworth rumble & plosive filter, 12 or 24 dB/octave";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.EQUALIZER;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
        if (sampleRate > 0) this.sampleRate = sampleRate;
        updateTarget();
        snapCoefficients();
    }

    @Override
    public void reset() {
        for (int c = 0; c < 2; c++) {
            s1[c][0] = s1[c][1] = 0f;
            s2[c][0] = s2[c][1] = 0f;
        }
        updateTarget();
        snapCoefficients();
    }

    @Override
    public boolean isAudible() {
        return isEnabled();
    }

    /** Mirrors the legacy {@code hpfOn} flag: any cutoff above 0 enables it. */
    void setLegacyState(boolean on, int cutoffHz) {
        cutoff.setCurrentRaw(on ? Math.max(40, Math.min(160, cutoffHz)) : 80);
        updateTarget();
        snapCoefficients();
        setEnabled(on);
    }

    boolean isOn() {
        return isEnabled();
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        updateTarget();
        final boolean steep = slope.getCurrentRaw() != 0;
        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(BLOCK, frames - offset);
            glideCoefficients();

            final float b0 = cur[0], b1 = cur[1], b2 = cur[2];
            final float a1 = cur[3], a2 = cur[4];
            final float c0 = cur[5], c1 = cur[6], c2 = cur[7];
            final float ca1 = cur[8], ca2 = cur[9];
            for (int f = 0; f < blk; f++) {
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    float x = buf[idx + c];
                    float y = b0 * x + s1[c][0];
                    s1[c][0] = b1 * x - a1 * y + s2[c][0];
                    s2[c][0] = b2 * x - a2 * y;
                    if (steep) {
                        float z = c0 * y + s1[c][1];
                        s1[c][1] = c1 * y - ca1 * z + s2[c][1];
                        s2[c][1] = c2 * y - ca2 * z;
                        y = z;
                    }
                    buf[idx + c] = y;
                }
            }
            offset += blk;
        }
    }

    private void glideCoefficients() {
        if (!coefDirty) return;
        boolean settled = true;
        for (int i = 0; i < target.length; i++) {
            float d = target[i] - cur[i];
            if (Math.abs(d) > 1e-7f) {
                cur[i] += d * 0.35f;
                settled = false;
            } else {
                cur[i] = target[i];
            }
        }
        if (settled) coefDirty = false;
    }

    private void snapCoefficients() {
        System.arraycopy(target, 0, cur, 0, target.length);
        coefDirty = false;
    }

    private void updateTarget() {
        int hz = Math.max(40, Math.min(160, cutoff.getCurrentRaw()));
        int sl = slope.getCurrentRaw() != 0 ? 1 : 0;
        if (hz == cachedHz && sl == cachedSlope && sampleRate == cachedRate) {
            return;
        }
        cachedHz = hz;
        cachedSlope = sl;
        cachedRate = sampleRate;

        double w0 = 2.0 * Math.PI * hz / sampleRate;
        double cos = Math.cos(w0);
        double sin = Math.sin(w0);
        double q1 = sl == 1 ? 0.541196100146197 : 0.707106781186548;
        double q2 = 1.306562964876377;
        writeSection(0, cos, sin, q1);
        if (sl == 1) {
            writeSection(5, cos, sin, q2);
        } else {
            // Unity numerator; the section is skipped in the sample loop.
            target[5] = 1f;
            target[6] = 0f;
            target[7] = 0f;
            target[8] = 0f;
            target[9] = 0f;
        }
        coefDirty = true;
    }

    private void writeSection(int at, double cos, double sin, double q) {
        double alpha = sin / (2.0 * q);
        double a0Inv = 1.0 / (1.0 + alpha);
        target[at] = (float) ((1.0 + cos) * 0.5 * a0Inv);
        target[at + 1] = (float) (-(1.0 + cos) * a0Inv);
        target[at + 2] = target[at];
        target[at + 3] = (float) (-2.0 * cos * a0Inv);
        target[at + 4] = (float) ((1.0 - alpha) * a0Inv);
    }
}
