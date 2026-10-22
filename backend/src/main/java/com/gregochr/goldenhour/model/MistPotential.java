package com.gregochr.goldenhour.model;

/**
 * How likely photographable valley mist is at a {@code mist_prone} site, banded from
 * {@code ValleyMistCalculator}'s 0–10 score (valley-mist-plan M1a).
 *
 * <p>The sibling of {@code PromptBuilder.InversionPotential}, with one band the inversion has no
 * analogue for: {@link #FOGBOUND}, where the model's own visibility already says the site is in
 * fog rather than beside mist.
 */
public enum MistPotential {

    /** The set-up is absent or vetoed (windy, overcast); no hot topic, no triage exemption. */
    NONE,

    /** Some of the radiative set-up is present; worth a mention nowhere yet. */
    POSSIBLE,

    /** The set-up is there: clear, calm, near-saturated, cooling. The band M1b's topic keys on. */
    LIKELY,

    /**
     * Event-hour visibility under 300 m with no clearing in the trend: a white frame, not mist
     * with a view through it. Reported as its own band so it is never mistaken for LIKELY.
     */
    FOGBOUND;

    /**
     * The integer 0&ndash;10 stand-in stored as the {@code score} of a {@code forecast_score}
     * {@link com.gregochr.goldenhour.entity.ForecastType#MIST} row, which has a non-null score
     * column (valley-mist-plan M2). Claude returns a band, not a number, so the row carries the
     * midpoint of the calculator's score range for that band: NONE (below 4) 2, POSSIBLE (4 to 6)
     * 5, LIKELY (6 to 10) 8, and FOGBOUND, which the score scale cannot express (it is decided by
     * visibility), 10. <b>Readers consume the band</b> (the row's {@code summary}), never this
     * figure; it exists to satisfy the column and to sort rows sensibly.
     *
     * @return 2, 5, 8 or 10
     */
    public int echoScore() {
        return switch (this) {
            case NONE -> 2;
            case POSSIBLE -> 5;
            case LIKELY -> 8;
            case FOGBOUND -> 10;
        };
    }
}
