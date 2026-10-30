package com.gregochr.goldenhour.model;

/**
 * The v2 inversion carrier on an {@link AtmosphericData} (inversion-v2-plan Phase 1b): the valley profile
 * the slot is scored from and, once scored, what the calculator found and what was stored.
 *
 * <p>Appended to {@code AtmosphericData} as ONE nullable field, so mist-side code that never looks at it
 * cannot be moved by it. A carrier with only {@code profile} set means "a profile was attached and not yet
 * (or could not be) scored"; the score fields are null in that case.
 *
 * @param profile           the valley profile sliced to the event slot and its night side
 * @param v1Score           the old calculator's score for the slot, or null when it could not score
 * @param v2Raw             the v2 score before the raise cap, or null when v2 could not score
 * @param stored            the value stored as the slot's inversion score once the rules in
 *                          {@code ForecastDataAugmentor} were applied, or null when v2 did not score
 * @param regime            the warm layer's kind, or null when v2 did not score
 * @param baseMetres        the warm layer's base height in metres, or null with no layer
 * @param topMetres         the warm layer's top height in metres, or null with no layer
 * @param strengthCelsius   top temperature minus base temperature, rounded to 0.1, or null with no layer
 * @param valleyFloorMetres the valley floor h0 the walk started from
 * @param viewpointMetres   the viewpoint elevation E
 * @param clearance         where the viewpoint stands against the layer, or null when v2 did not score
 * @param caps              the caps that applied, for the log line (for example {@code inside(6),weak(8)})
 * @param v2Drove           true when v2's value is the one stored on the slot
 */
public record InversionV2Result(
        InversionProfile profile,
        Double v1Score,
        Double v2Raw,
        Double stored,
        InversionRegime regime,
        Double baseMetres,
        Double topMetres,
        Double strengthCelsius,
        Integer valleyFloorMetres,
        Integer viewpointMetres,
        InversionClearance clearance,
        String caps,
        boolean v2Drove) {

    /**
     * A carrier holding only a profile, before scoring.
     *
     * @param profile the sliced valley profile
     * @return the carrier
     */
    public static InversionV2Result ofProfile(InversionProfile profile) {
        return new InversionV2Result(profile, null, null, null, null, null, null, null, null, null, null,
                null, false);
    }

    /**
     * Returns a copy with the storage decision filled in.
     *
     * @param old    the old calculator's score, or null
     * @param value  the value stored on the slot
     * @param drove  whether v2's value is the one stored
     * @return the updated carrier
     */
    public InversionV2Result withStorage(Double old, Double value, boolean drove) {
        return new InversionV2Result(profile, old, v2Raw, value, regime, baseMetres, topMetres,
                strengthCelsius, valleyFloorMetres, viewpointMetres, clearance, caps, drove);
    }

    /**
     * Returns a copy with the caps text replaced (the augmentor appends the raise cap).
     *
     * @param capsText the new caps text
     * @return the updated carrier
     */
    public InversionV2Result withCaps(String capsText) {
        return new InversionV2Result(profile, v1Score, v2Raw, stored, regime, baseMetres, topMetres,
                strengthCelsius, valleyFloorMetres, viewpointMetres, clearance, capsText, v2Drove);
    }
}
