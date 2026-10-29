package com.example.hello;

/**
 * Cascade of up to {@link Dsp} biquad sections with per-channel direct-form
 * II transposed state. Shared by every Stage 3 filter effect: processors
 * rebuild the {@code float[][]} section list from their parameters and hand
 * it here; the filter keeps the history so live parameter edits never click.
 */
public final class BiquadFilter {

    private float[][] sections = new float[0][];
    private float[] z1 = new float[0];
    private float[] z2 = new float[0];
    private int channels = 1;

    /**
     * Installs new coefficients, preserving state for all surviving sections
     * even when the cascade section count changes (e.g. live slope/order edits).
     */
    public void setSections(float[][] newSections, int channels) {
        if (newSections == null) newSections = new float[0][];
        int ch = Math.max(1, Math.min(2, channels));
        boolean chChanged = this.channels != ch;
        int common = chChanged ? 0 : Math.min(this.sections.length, newSections.length);
        if (!chChanged) {
            for (int s = 0; s < common; s++) {
                if (isIdentity(newSections[s]) && !isIdentity(this.sections[s])) {
                    for (int c = 0; c < ch; c++) {
                        z1[s * ch + c] = 0f;
                        z2[s * ch + c] = 0f;
                    }
                }
            }
        }
        if (chChanged || this.sections.length != newSections.length
                || z1.length != newSections.length * ch) {
            float[] newZ1 = new float[newSections.length * ch];
            float[] newZ2 = new float[newSections.length * ch];
            if (common > 0) {
                int copyLen = Math.min(z1.length, common * ch);
                System.arraycopy(z1, 0, newZ1, 0, copyLen);
                System.arraycopy(z2, 0, newZ2, 0, copyLen);
            }
            z1 = newZ1;
            z2 = newZ2;
        }
        this.sections = newSections;
        this.channels = ch;
    }

    private static boolean isIdentity(float[] c) {
        if (c == null || c.length < 5) return false;
        return Math.abs(c[0] - 1f) < 1e-6f
                && Math.abs(c[1]) < 1e-6f
                && Math.abs(c[2]) < 1e-6f
                && Math.abs(c[3]) < 1e-6f
                && Math.abs(c[4]) < 1e-6f;
    }

    public int sectionCount() {
        return sections.length;
    }

    public void reset() {
        for (int i = 0; i < z1.length; i++) {
            z1[i] = 0f;
            z2[i] = 0f;
        }
    }

    /** Processes interleaved frames in place. */
    public void process(float[] buf, int frames, int ch) {
        process(buf, frames, ch, 0);
    }

    /**
     * Processes interleaved frames in place, starting at {@code frameOffset}.
     * The offset variant lets a caller sweep the coefficients over a block
     * without copying the samples out of the buffer.
     */
    public void process(float[] buf, int frames, int ch, int frameOffset) {
        if (buf == null || frames <= 0 || sections.length == 0) return;
        final int c = Math.max(1, Math.min(2, Math.min(ch, channels)));
        final int secs = sections.length;
        for (int f = 0; f < frames; f++) {
            int idx = (f + frameOffset) * c;
            if (idx + c > buf.length) break;
            for (int chn = 0; chn < c; chn++) {
                float x = buf[idx + chn];
                for (int s = 0; s < secs; s++) {
                    float[] co = sections[s];
                    int k = s * c + chn;
                    float y = co[0] * x + z1[k];
                    z1[k] = co[1] * x - co[3] * y + z2[k];
                    z2[k] = co[2] * x - co[4] * y;
                    x = y;
                }
                buf[idx + chn] = x;
            }
        }
    }
}
