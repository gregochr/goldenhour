package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The unified hot-topic read surface's composite, for one {@code (location, date, event_type)}.
 *
 * <p>This is the return shape of {@code SurvivorSignalReader} — the ONE read model the survivor-
 * signal hot-topic detectors consult. It deliberately carries scores and readings as their own
 * correctly-shaped sub-records ({@link Scores}, {@link Readings}) rather than flattening them into
 * one homogeneous row: a flat row would be the single-physical-table sprawl that V107/V108 deleted,
 * wearing a read-model hat. Unified <em>access</em>, correctly-shaped <em>data</em>.
 *
 * <p>⚠️ <b>"Survivor" is historical for the readings half.</b> Backed by two tables —
 * {@code forecast_score} (scores) and {@code survivor_atmosphere} (readings). {@code forecast_score}
 * remains genuinely survivor-only: it is written only from a completed Claude evaluation.
 * {@code survivor_atmosphere} is not, since the "record conditions for every place" change (Phase 1,
 * owner decision 2026-09-30) — it now holds a row for every candidate whose weather was fetched,
 * triaged-out and Gate-4-stood-down candidates included. A composite with populated
 * {@link Readings} and {@code EMPTY} {@link Scores} is therefore an ordinary, expected shape, not a
 * survivor by construction.
 *
 * @param location  the candidate's location (region fetched, for grouping)
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
     * <p>⚠️ <b>{@link #inversion()} is Claude's echo, and is no longer the PRIMARY read path for
     * the inversion hot topic or the Coming up "Valley inversions" condition's forward peak
     * (Phase 2, owner decision 2026-09-30).</b> Both of those now read
     * {@link Readings#inversionScore()} first — the deterministic calculator's own score, populated
     * for every inversion-eligible candidate regardless of triage or Gate 4, and correct from the
     * first cycle after deploy for anything forward-looking, since a forward slot is upserted every
     * cycle. ⚠️ <b>The Coming up condition's TRAILING-HISTORY occurrence list is the one place this
     * field still has a live, permanent, production reader</b> — {@code ComingUpConditionsBuilder
     * .trailingInversionScore} falls back to this field whenever a past date's
     * {@link Readings#inversionScore()} is null, which every pre-V158 {@code survivor_atmosphere}
     * row is forever (a past date is never re-evaluated, so there is no later cycle to backfill a
     * reading for it) — see that method's own javadoc for the fallback's full justification (a
     * Codex finding against PR #948's first V158 cut). {@code TopicDailyLogJob}'s future-population
     * log reads {@code forecast_score} directly, bypassing this composite entirely — see that
     * class's own javadoc.
     *
     * @param inversion       cloud inversion score 0–10 (Claude's echo), or null
     * @param inversionBand   the inversion row's stored classification (NONE/MODERATE/STRONG), or
     *                        null — rides the INVERSION row's {@code summary} column, so rows
     *                        written before it was plumbed through read as null
     * @param bluebell        bluebell conditions score 1–5, or null
     * @param bluebellSummary the bluebell component's prose clause, or null
     */
    public record Scores(Integer inversion, String inversionBand, Integer bluebell,
            String bluebellSummary) {

        /** The all-absent scores, used for a key that has only readings. */
        public static final Scores EMPTY = new Scores(null, null, null, null);
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
     * @param inversionScore            cloud inversion likelihood score (0–10,
     *                                  {@code InversionScoreCalculator}), or null when the location
     *                                  was not inversion-eligible. V158 (Phase 2 of "record
     *                                  conditions for every place", owner decision 2026-09-30) — the
     *                                  deterministic calculator's own score, populated for every
     *                                  inversion-eligible candidate whatever the triage verdict or
     *                                  Gate 4 decision, unlike {@link Scores#inversion()} which is
     *                                  Claude's echo and only exists for a completed evaluation
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
            Double temperatureCelsius,
            Double inversionScore) {

        /** The all-absent readings, used for a key that has only scores. */
        public static final Readings EMPTY = new Readings(
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
