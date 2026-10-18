package com.example.hello;

/**
 * Coarse grouping for the FX rack, mirroring the eight effect-catalog
 * categories in the pro-audio roadmap (EQ/Filters, Dynamics, Restoration,
 * Spatial, Time, Creative, Timeline tools, Analyzers).
 */
public enum FxCategory {
    EQUALIZER("EQ & Filters"),
    DYNAMICS("Dynamics & Mastering"),
    RESTORATION("Noise Removal & Restoration"),
    SPATIAL("Delay, Reverb & Spatial"),
    TIME("Pitch, Speed & Time"),
    CREATIVE("Distortion, Modulation & Creative"),
    TOOLS("Fades & Timeline Tools"),
    ANALYZER("Generators & Analyzers");

    public final String label;

    FxCategory(String label) {
        this.label = label;
    }
}
