package com.gregochr.goldenhour.model;

/**
 * The coastal mist carrier on an {@link AtmosphericData} (coastal-mist-plan Phase 1): the band the calculator
 * found and the readings it was found from.
 *
 * <p>Appended to {@code AtmosphericData} as ONE nullable field, last, so mist-side code that never looks at it
 * cannot be moved by it. Null on the data means the check did not run (switch off, inland, or a
 * {@code mist_prone} site); a non-null carrier means it ran, whatever the band.
 *
 * @param band             the band, never null
 * @param visibilityMetres the event-slot model visibility in metres, or null when the weather was missing
 * @param gapC             temperature minus dew point rounded to 0.1 C, or null when either was missing
 * @param lowCloudPct      the event-slot low cloud cover in percent, or null when the cloud data was missing
 * @param precipitationMm  the event-slot precipitation in mm, or null when the weather was missing (Phase 2: the
 *                         Sea mist hot topic needs a dry slot)
 */
public record CoastalMistResult(
        CoastalMistBand band,
        Integer visibilityMetres,
        Double gapC,
        Integer lowCloudPct,
        Double precipitationMm) {

    /**
     * The Phase 1 shape, carrying no precipitation reading (it reads as absent).
     *
     * @param band             the band, never null
     * @param visibilityMetres the event-slot model visibility in metres, or null
     * @param gapC             temperature minus dew point rounded to 0.1 C, or null
     * @param lowCloudPct      the event-slot low cloud cover in percent, or null
     */
    public CoastalMistResult(CoastalMistBand band, Integer visibilityMetres, Double gapC, Integer lowCloudPct) {
        this(band, visibilityMetres, gapC, lowCloudPct, null);
    }
}
