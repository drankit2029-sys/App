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

    /** Installs new coefficients, preserving state when the count matches. */
    public void setSections(float[][] newSections, int channels) {
        if (newSections == null) newSections = new float[0][];
        int ch = Math.max(1, Math.min(2, channels));
        boolean realloc = this.sections.length != newSections.length
                || this.channels != ch
                || z1.length != newSections.length * ch;
        if (!realloc) {
            int n = Math.min(this.sections.length, newSections.length);
            for (int s = 0; s < n; s++) {
                if (isIdentity(newSections[s]) && !isIdentity(this.sections[s])) {
                    for (int c = 0; c < ch; c++) {
                        z1[s * ch + c] = 0f;
                        z2[s * ch + c] = 0f;
                    }
                }
            }
        }
        this.sections = newSections;
        this.channels = ch;
        if (realloc) {
            z1 = new float[newSections.length * ch];
            z2 = new float[newSections.length * ch];
        }
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
