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
    FOGBOUND
}
