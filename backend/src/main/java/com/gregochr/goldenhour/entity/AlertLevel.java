package com.gregochr.goldenhour.entity;

/**
 * Geomagnetic alert levels derived from NOAA SWPC data (Kp index + OVATION probability).
 *
 * <p>Levels are in ascending severity order. Colours follow the convention used by
 * aurora photography apps and the existing UI palette.
 */
public enum AlertLevel {

    /** Kp 0–3 — no significant geomagnetic activity. */
    QUIET(0, "#33ff33", "No significant activity"),

    /** Kp 4 — minor geomagnetic activity, aurora unlikely from northern England. */
    MINOR(1, "#ffff00", "Minor geomagnetic activity"),

    /** Kp 5–6 (G1–G2) — moderate storm, aurora possible from northern England/Scotland. */
    MODERATE(2, "#ff9900", "Moderate storm — aurora possible from northern England"),

    /** Kp 7+ (G3+) — strong storm, aurora likely across the UK. */
    STRONG(3, "#ff0000", "Strong storm — aurora likely across the UK");

    private final int severity;
    private final String hexColour;
    private final String description;

    AlertLevel(int severity, String hexColour, String description) {
        this.severity = severity;
        this.hexColour = hexColour;
        this.description = description;
    }

    /**
     * Numeric severity for ordering — higher value = more severe.
     *
     * @return severity index
     */
    public int severity() {
        return severity;
    }

    /**
     * Hex colour for this alert level (UI display).
     *
     * @return hex colour string, e.g. {@code "#ff9900"}
     */
    public String hexColour() {
        return hexColour;
    }

    /**
     * Human-readable description of this alert level.
     *
     * @return description string
     */
    public String description() {
        return description;
    }

    /**
     * Returns {@code true} for levels that warrant aurora notifications (MODERATE or STRONG).
     *
     * @return {@code true} if this level is actionable
     */
    public boolean isAlertWorthy() {
        return this == MODERATE || this == STRONG;
    }

    /**
     * Derives the alert level from a NOAA Kp index value.
     *
     * @param kp Kp index (0–9)
     * @return corresponding {@link AlertLevel}
     */
    public static AlertLevel fromKp(double kp) {
        if (kp >= 7) {
            return STRONG;
        }
        if (kp >= 5) {
            return MODERATE;
        }
        if (kp >= 4) {
            return MINOR;
        }
        return QUIET;
    }

    /**
     * Maps a NOAA Kp index to its geomagnetic storm G-scale label.
     *
     * <p>NOAA mapping: G1 = Kp5, G2 = Kp6, G3 = Kp7, G4 = Kp8, G5 = Kp9. Returns
     * {@code null} below the G1 storm threshold (Kp &lt; 5) or when {@code kp} is
     * {@code null}, so the UI shows the level word alone with no empty scale.
     *
     * @param kp Kp index (0–9), or {@code null} when unknown
     * @return uppercase G-scale label {@code "G1"}–{@code "G5"}, or {@code null}
     */
    public static String gScaleFromKp(Double kp) {
        if (kp == null || kp < 5.0) {
            return null;
        }
        if (kp < 6.0) {
            return "G1";
        }
        if (kp < 7.0) {
            return "G2";
        }
        if (kp < 8.0) {
            return "G3";
        }
        if (kp < 9.0) {
            return "G4";
        }
        return "G5";
    }
}
