package com.example.hello;

import java.util.Locale;

/**
 * Stage 7 spectrum analyser: computes a Welch periodogram over a region and
 * reports the dominant peak frequency together with the nearest musical
 * note.
 *
 * <p>The analysis is pure maths (no Android dependency) so it can be unit
 * tested; the dialog that presents it lives in the studio UI.
 */
final class PlotSpectrumDialog {

    private PlotSpectrumDialog() {}

    /** Result of a spectrum analysis. */
    static final class Result {
        /** Average power per bin (length fftSize / 2 + 1). */
        final float[] power;
        final int fftSize;
        final int sampleRate;
        /** Frequency of the strongest bin in Hz. */
        final float peakHz;
        /** The strongest bin's magnitude in dBFS. */
        final float peakDb;
        /** Nearest equal-tempered note name, e.g. "A4". */
        final String peakNote;
        /** True when the peak bin is close enough to the note to name it. */
        final boolean noteIsClose;

        Result(float[] power, int fftSize, int sampleRate, float peakHz,
               float peakDb, String peakNote, boolean noteIsClose) {
            this.power = power;
            this.fftSize = fftSize;
            this.sampleRate = sampleRate;
            this.peakHz = peakHz;
            this.peakDb = peakDb;
            this.peakNote = peakNote;
            this.noteIsClose = noteIsClose;
        }
    }

    /** Analyses the given interleaved region. */
    static Result analyze(float[] buf, int frames, int channels, int sampleRate,
                          int fftSize) {
        int n = Math.max(64, Integer.highestOneBit(Math.max(64, fftSize)));
        float[] power = StftProcessor.welchSpectrum(buf, frames, channels, n, 1f);
        int peakBin = 0;
        float peak = 0f;
        for (int b = 1; b < power.length; b++) {
            if (power[b] > peak) {
                peak = power[b];
                peakBin = b;
            }
        }
        float hz = peakBin * (float) sampleRate / n;
        float db = Dsp.linToDb((float) Math.sqrt(Math.max(0.0, peak)));
        String[] noteInfo = nearestNote(hz);
        return new Result(power, n, sampleRate, hz, db, noteInfo[0],
                Boolean.parseBoolean(noteInfo[1]));
    }

    /**
     * Returns the nearest equal-tempered note name and whether the input is
     * within 25 cents of it.
     */
    static String[] nearestNote(float hz) {
        if (hz <= 0f) return new String[]{"—", "false"};
        // MIDI note number: A4 (440 Hz) is note 69.
        double midi = 12.0 * (Math.log(hz / 440.0) / Math.log(2.0)) + 69.0;
        int rounded = (int) Math.round(midi);
        double cents = (midi - rounded) * 100.0;
        int index = ((rounded % 12) + 12) % 12;
        int octave = rounded / 12 - 1;
        String name = NOTE_NAMES[index] + octave;
        boolean close = Math.abs(cents) <= 25.0;
        return new String[]{name, String.valueOf(close)};
    }

    /** Equal-tempered note names, index 0 = C. */
    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F",
            "F#", "G", "G#", "A", "A#", "B"};

    /** Human-readable summary used by the studio dialog. */
    static String summarize(Result r) {
        return String.format(Locale.US,
                "Peak: %.1f Hz (%s) • %.1f dBFS • FFT %d @ %d Hz",
                r.peakHz, r.peakNote, r.peakDb, r.fftSize, r.sampleRate);
    }
}
