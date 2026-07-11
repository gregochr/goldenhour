package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The unified survivor read surface's composite, for one {@code (location, date, event_type)}.
 *
 * <p>This is the return shape of {@code SurvivorSignalReader} — the ONE read model the survivor-
 * signal hot-topic detectors consult. It deliberately carries scores and readings as their own
 * correctly-shaped sub-records ({@link Scores}, {@link Readings}) rather than flattening them into
 * one homogeneous row: a flat row would be the single-physical-table sprawl that V107/V108 deleted,
 * wearing a read-model hat. Unified <em>access</em>, correctly-shaped <em>data</em>.
 *
 * <p>Backed by two survivor-only tables — {@code forecast_score} (scores) and
 * {@code survivor_atmosphere} (readings) — so any composite the reader returns is, by construction,
 * a survivor. A detector reading through this model cannot sample the triaged rejects.
 *
 * @param location  the survivor location (region fetched, for grouping)
 * @param date      the forecast date
 * @param eventType SUNRISE or SUNSET
 * @param scores    the score-shaped survivor signals (inversion, bluebell), never null
 * @param readings  the reading-shaped survivor signals (aerosol, surge, snow), never null
 */
public record SurvivorSignals(
        LocationEntity location,
        LocalDate date,
        TargetType eventType,
        Scores scores,
        Readings readings) {

    /**
     * Score-shaped survivor signals from {@code forecast_score} — each nullable when that score was
     * not written for the key (ineligible location, out of season, or eval not yet returned).
     *
     * @param inversion       cloud inversion score 0–10, or null
     * @param bluebell        bluebell conditions score 1–5, or null
     * @param bluebellSummary the bluebell component's prose clause, or null
     */
    public record Scores(Integer inversion, Integer bluebell, String bluebellSummary) {

        /** The all-absent scores, used for a key that has only readings. */
        public static final Scores EMPTY = new Scores(null, null, null);
    }

    /**
     * Reading-shaped survivor signals from {@code survivor_atmosphere} — each nullable (inland has
     * no surge, summer has no snow).
     *
     * @param aerosolOpticalDepth aerosol optical depth, or null
     * @param dust                surface dust µg/m³, or null
     * @param pm25                PM2.5 µg/m³, or null
     * @param surgeRiskLevel      storm surge risk classification name, or null
     * @param snowDepthMetres           lying snow depth in metres, or null
     * @param freezingLevelMetres       0 °C isotherm altitude in metres, or null
     * @param humidity                  relative humidity percent, or null
     * @param surgeTotalMetres          total storm surge (pressure + wind) in metres, or null
     * @param surgeWindSpeedMs          surge-time 10 m wind speed in m/s, or null
     * @param surgeWindDirectionDegrees surge-time wind direction (degrees FROM), or null
     * @param temperatureCelsius        2 m air temperature in °C, or null; gates the SNOW_MIST
     *                                  freezing-fog / hoar-frost facts
     */
    public record Readings(
            BigDecimal aerosolOpticalDepth,
            BigDecimal dust,
            BigDecimal pm25,
            String surgeRiskLevel,
            Double snowDepthMetres,
            Double freezingLevelMetres,
            Integer humidity,
            Double surgeTotalMetres,
            Double surgeWindSpeedMs,
            Double surgeWindDirectionDegrees,
            Double temperatureCelsius) {

        /** The all-absent readings, used for a key that has only scores. */
        public static final Readings EMPTY =
                new Readings(null, null, null, null, null, null, null, null, null, null, null);
    }
}
