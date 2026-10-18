package com.example.hello;

/**
 * Stage 6 of the legacy voice chain: soft-knee compressor with auto-makeup
 * gain. Body is a verbatim extraction of the original {@code DspChain} stage
 * so output stays bit-identical.
 */
final class LegacyCompressorProcessor extends AudioProcessor {

    private static final int DECIM = 16;
    private static final float INV_DECIM = 1.0f / DECIM;

    private final FxParameter amt = addParameter(FxParameter.intParam(
            "amt", "Compression", "%", 0, 100, 1, 40));

    private float envDb = -60.0f;
    private float gainCur = 1.0f;
    private float gainTarget = 1.0f;

    @Override
    public String getId() {
        return "comp";
    }

    @Override
    public String getTitle() {
        return "Compressor & Leveler";
    }

    @Override
    public String getDescription() {
        return "Soft-knee vocal leveler with auto makeup gain";
    }

    @Override
    public FxCategory getCategory() {
        return FxCategory.DYNAMICS;
    }

    @Override
    public void prepare(int sampleRate, int channels) {
    }

    @Override
    public void reset() {
        envDb = -60.0f;
        gainCur = 1.0f;
        gainTarget = 1.0f;
    }

    @Override
    public boolean isAudible() {
        return isEnabled() && amt01() > 0.001f;
    }

    void setLegacyAmount(int pct) {
        amt.setCurrentRaw(pct);
    }

    /** Raw amount is a percent; gain math needs a 0..1 fraction. */
    private float amt01() {
        return amt.getCurrentRaw() / 100f;
    }

    @Override
    public void processInPlace(float[] buf, int frames, int channels) {
        if (buf == null || frames <= 0) return;
        final int ch = Math.max(1, Math.min(2, channels));
        final float cAmt = amt01();
        if (cAmt <= 0.001f) return;

        int offset = 0;
        while (offset < frames) {
            int blk = Math.min(DECIM, frames - offset);

            // Stage 6: Soft-Knee Compressor + Auto-Makeup
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
            float inDb = DspChain.fastLinToDb(det);
            float alphaC = inDb > envDb ? 0.10f : 0.004f;
            envDb += alphaC * (inDb - envDb);

            float threshDb = -10.0f - 18.0f * cAmt; // -10 .. -28 dBFS
            float ratio = 1.0f + 5.0f * cAmt;       // 1:1 .. 6:1
            float slope = 1.0f - (1.0f / ratio);
            float knee = 6.0f;
            float over = envDb - threshDb;
            float grDb;
            if (2.0f * over < -knee) {
                grDb = 0.0f;
            } else if (2.0f * Math.abs(over) <= knee) {
                float t = over + knee * 0.5f;
                grDb = -slope * (t * t) / (2.0f * knee);
            } else {
                grDb = -slope * over;
            }
            float makeupDb = (-threshDb * slope) * 0.55f;
            gainTarget = DspChain.fastDbToLin(grDb + makeupDb);
            float dG = (gainTarget - gainCur) * INV_DECIM;
            for (int f = 0; f < blk; f++) {
                gainCur += dG;
                int idx = (offset + f) * ch;
                for (int c = 0; c < ch; c++) {
                    buf[idx + c] *= gainCur;
                }
            }

            offset += blk;
        }
    }
}
