package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.CoastalMistResult;

/**
 * Bands the event-slot model for a light sea mist at a coastal site (coastal-mist-plan Phase 1).
 *
 * <p><b>Every threshold is a prior, set from one site and one season of evidence</b> (1,000 Boulmer sunrise
 * mornings against the forecast model, 2023-2025, {@code docs/engineering/coastal-mist-data/}), and each is
 * cheap to be wrong about until outcome ratings recalibrate it (plan Phase 3):
 * <ul>
 *   <li><b>Model visibility separates the three states.</b> Medians were 21.5 km on clear mornings, about
 *       7.5 km in light mist and about 2 km in fog. Below {@value #LIGHT_MIN_VISIBILITY_M} m the model is
 *       unreliable as "fret" (under 1.5 km 71 % was fog, 1.5-3 km 27 %, 3-5 km the worst slice for light mist),
 *       and that is the same line where triage already stands a slot down, so the feature shows nothing below
 *       it: {@link CoastalMistBand#THICK} is stored and logged for tuning only.</li>
 *   <li><b>5 to 10 km is the light-mist band.</b> {@value #LIGHT_MIN_VISIBILITY_M} to
 *       {@value #LIGHT_MAX_VISIBILITY_M} m inclusive, with the temperature-dew point gap at or under
 *       {@value #LIGHT_MAX_GAP_C} C (rounded to 0.1 C first, as valley mist does). At 25.7 mornings a year
 *       per site and 56 % precision this is the band the prompt line speaks to; no threshold change
 *       (3/4/5 km low, 8/9/10/12 km high, gap 1.0/1.5/2.0) beat it.</li>
 *   <li><b>Low cloud is the biggest lever</b> (precision 56 % with no gate, 63 % under 80 %, 68 % under 50 %)
 *       but it does not move the band. It is carried on the result for the log, the prompt's "grey lid"
 *       wording ({@value #GREY_LID_LOW_CLOUD_PERCENT} % or more) and the Phase 2 hot topic.</li>
 *   <li><b>Event slot, not the window's minimum.</b> Phase 1a measured both: the minimum over sunrise +/- 1 h
 *       was clearly worse (LIGHT precision 36 % against 56 %).</li>
 * </ul>
 *
 * <p><b>This class never reads {@code mist_prone} or the tide snapshot.</b> The gate is
 * {@link #appliesTo(LocationEntity)}, used by {@code ForecastDataAugmentor.augmentWithCoastalMist}; this
 * class never touches {@code ValleyMistCalculator} either, so valley mist cannot move with it.
 */
public final class CoastalMistCalculator {

    /** Event-slot visibility (m) at or above which the light-mist band starts; below it is THICK. */
    static final int LIGHT_MIN_VISIBILITY_M = 5_000;

    /** Event-slot visibility (m) at or under which the light-mist band holds; above it is clear air. */
    static final int LIGHT_MAX_VISIBILITY_M = 10_000;

    /** Temperature minus dew point (C, rounded to 0.1) at or under which the air is near-saturated. */
    static final double LIGHT_MAX_GAP_C = 1.5;

    /** Low cloud (%) at or above which the prompt line adds "under a grey lid". */
    public static final int GREY_LID_LOW_CLOUD_PERCENT = 80;

    /** Gap readings are rounded to this many steps per degree (0.1 C, the source's precision). */
    private static final double GAP_STEPS_PER_DEGREE = 10.0;

    private CoastalMistCalculator() {
    }

    /**
     * Whether a site is coastal for this signal: it has tide types and is not {@code mist_prone}. Never
     * {@code data.tide() != null}: a failed tide fetch would silently drop the band.
     *
     * @param location the location, or null
     * @return true when the band should be computed for the location
     */
    public static boolean appliesTo(LocationEntity location) {
        return location != null && location.getTideType() != null && !location.getTideType().isEmpty()
                && !location.isMistProne();
    }

    /**
     * Bands the event slot.
     *
     * @param data the atmospheric data at event time; its weather, comfort and cloud may each be null
     * @return the band and the readings it came from, never null
     */
    public static CoastalMistResult calculate(AtmosphericData data) {
        Integer visibility = data == null || data.weather() == null ? null : data.weather().visibilityMetres();
        Double temperature = data == null || data.comfort() == null ? null : data.comfort().temperatureCelsius();
        Double dewPoint = data == null || data.weather() == null ? null : data.weather().dewPointCelsius();
        Integer lowCloud = data == null || data.cloud() == null ? null : data.cloud().lowCloudPercent();
        Double gap = temperature == null || dewPoint == null ? null : gap(temperature, dewPoint);
        Double precipitation = data == null || data.weather() == null || data.weather().precipitationMm() == null
                ? null : data.weather().precipitationMm().doubleValue();
        return new CoastalMistResult(band(visibility, gap), visibility, gap, lowCloud, precipitation);
    }

    /**
     * Bands a visibility and gap.
     *
     * @param visibilityMetres the event-slot visibility in metres, or null
     * @param gapC             temperature minus dew point rounded to 0.1 C, or null
     * @return THICK under 5 km, LIGHT from 5 to 10 km (inclusive) with a gap of 1.5 C or less, else NONE
     */
    static CoastalMistBand band(Integer visibilityMetres, Double gapC) {
        if (visibilityMetres == null) {
            return CoastalMistBand.NONE;
        }
        if (visibilityMetres < LIGHT_MIN_VISIBILITY_M) {
            return CoastalMistBand.THICK;
        }
        if (visibilityMetres <= LIGHT_MAX_VISIBILITY_M && gapC != null && gapC <= LIGHT_MAX_GAP_C) {
            return CoastalMistBand.LIGHT;
        }
        return CoastalMistBand.NONE;
    }

    /**
     * Temperature minus dew point, rounded to 0.1 C exactly as {@code ValleyMistCalculator} rounds its gaps.
     *
     * @param temperatureC the 2 m temperature in C
     * @param dewPointC    the 2 m dew point in C
     * @return the gap rounded to 0.1 C
     */
    static double gap(double temperatureC, double dewPointC) {
        return Math.round((temperatureC - dewPointC) * GAP_STEPS_PER_DEGREE) / GAP_STEPS_PER_DEGREE;
    }
}
