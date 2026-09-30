package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The unified hot-topic read surface's composite, for one {@code (location, date, event_type)}.
 *
 * <p>This is the return shape of {@code SlotSignalReader} — the ONE read model the slot-
 * signal hot-topic detectors consult. It deliberately carries scores and readings as their own
 * correctly-shaped sub-records ({@link Scores}, {@link Readings}) rather than flattening them into
 * one homogeneous row: a flat row would be the single-physical-table sprawl that V107/V108 deleted,
 * wearing a read-model hat. Unified <em>access</em>, correctly-shaped <em>data</em>.
 *
 * <p>⚠️ <b>This class and {@code SlotSignalReader} were named {@code SurvivorSignals}/
 * {@code SurvivorSignalReader} until V159 (2026-09-30) — "survivor" was already historical for the
 * readings half by then.</b> Backed by two tables — {@code forecast_score} (scores) and
 * {@code slot_atmosphere} (readings). {@code forecast_score} remains genuinely survivor-only: it is
 * written only from a completed Claude evaluation. {@code slot_atmosphere} is not, since the
 * "record conditions for every place" change (Phase 1, owner decision 2026-09-30) — it now holds a
 * row for every candidate whose weather was fetched, triaged-out and Gate-4-stood-down candidates
 * included. A composite with populated {@link Readings} and {@code EMPTY} {@link Scores} is
 * therefore an ordinary, expected shape, not a survivor by construction — which is exactly why
 * V159 renamed the class and its reader away from "survivor".
 *
 * @param location  the candidate's location (region fetched, for grouping)
 * @param date      the forecast date
 * @param eventType SUNRISE or SUNSET
 * @param scores    the score-shaped slot signals (inversion, bluebell), never null
 * @param readings  the reading-shaped slot signals (aerosol, surge, snow), never null
 */
public record SlotSignals(
        LocationEntity location,
        LocalDate date,
        TargetType eventType,
        Scores scores,
        Readings readings) {

    /**
     * Returns the effective cloud inversion score for this key — the ONE rule every reader of
     * inversion likelihood uses, forward or trailing: {@link Readings#inversionScore()} (the
     * deterministic calculator's own score, null included) when {@link Readings#inversionScored()}
     * says this row's writer ran the calculator's eligibility check THIS cycle, else
     * {@link Scores#inversion()} (Claude's {@code forecast_score} echo of the identical 0–10
     * scale) as a stand-in for a slot the calculator has not yet reached.
     *
     * <p>⚠️ <b>Round 3 of Phase 2 (owner decision 2026-09-30, a Codex P1 against PR #948's second
     * cut): there is no forward/trailing split any more — one rule, used everywhere.</b> The
     * second cut assumed a forward slot is upserted every cycle, so the calculator's reading is
     * always current there and the fallback was needed only for the trailing history (a past date
     * that can never be re-evaluated). That assumption is false: {@code BriefingCandidateCollector}
     * skips a region with a fresh {@code cached_evaluation} entry — {@code SKIPPED_CACHED}, around
     * lines 204–227 — <em>before {@code fetchWeatherAndTriage} ever runs</em>, and
     * {@code FreshnessProperties.settledHours} defaults to 36 with no horizon cap at T+2 and beyond
     * ({@code FreshnessResolver.horizonCap} returns null there), so a SETTLED forward slot can go
     * up to 36 hours without a fresh {@code slot_atmosphere} write at all — the exact gap the
     * forward-only design assumed could not happen. Splitting the rule by window direction was
     * therefore the wrong shape: what actually varies is not "forward vs trailing" but "has the
     * calculator scored this slot THIS cycle or not", which both windows can independently answer
     * either way.
     *
     * <p>⚠️ <b>A fourth round (a further Codex P1) found this method itself treated every null
     * reading as "the calculator has not reached this slot yet" — wrong, because a FRESH write can
     * also produce a null reading.</b> {@code InversionScoreCalculator.calculate} returns null for
     * an eligible location when required weather inputs are missing (a null dew point or surface
     * temperature), and {@code ForecastDataAugmentor.augmentWithInversionScore} leaves the score
     * null for an ineligible location too — either way {@code SlotAtmosphereWriter} still
     * writes the row this cycle. Without a flag, this method could not tell that authoritative null
     * apart from an absent (never-written-since-the-column-existed) one, and {@code
     * ForecastScoreWriter} leaves a stale INVERSION echo in {@code forecast_score} in place
     * indefinitely whenever a later evaluation carries no score of its own — so falling back on
     * every null reading could revive a STRONG rating the current cycle's own data no longer
     * supports. {@link Readings#inversionScored()} (V158's second column) is the fix: it is true on
     * every row a post-V158 writer produced, whatever the resulting score, and false only for a row
     * that predates the flag. This method now consults the echo ONLY when {@code inversionScored}
     * is false — a scored row's null is returned as-is, never overridden.
     *
     * <p><b>This does not retreat from the owner's decision that the calculator governs.</b> Where
     * the calculator HAS scored a slot THIS cycle — {@code inversionScored} true, whatever the
     * score — the calculator decides, full stop, including a deliberate null. The echo is only
     * ever a stand-in for a slot the calculator has not yet reached at all, on the identical 0–10
     * scale with the identical STRONG cut (9), so this rule can never make the topic fire on a
     * slot the calculator would itself have refused; it only ever fills a gap the calculator has
     * not had the chance to fill yet.
     *
     * <p>Used by {@code InversionHotTopicStrategy.detect}/{@code attachFacts} and by both of
     * {@code ComingUpConditionsBuilder.buildInversion}'s reads (trailing history and forward peak
     * alike) — the single shared helper a helper-level test and both readers' own tests pin
     * against identical fixtures, so the two can never disagree.
     *
     * @return the effective 0–10 inversion score, or null when the calculator scored this slot
     *         and found nothing, or when neither surface has anything for this key at all
     */
    public Double effectiveInversionScore() {
        if (readings.inversionScored()) {
            return readings.inversionScore();
        }
        Integer echoed = scores.inversion();
        return echoed == null ? null : echoed.doubleValue();
    }

    /**
     * Score-shaped slot signals from {@code forecast_score} — each nullable when that score was
     * not written for the key (ineligible location, out of season, or eval not yet returned).
     *
     * <p>⚠️ <b>{@link #inversion()} is Claude's echo — read only through {@link #effectiveInversionScore()}
     * now, never directly, by the inversion hot topic or the Coming up "Valley inversions"
     * condition (Phase 2, owner decision 2026-09-30; unified onto one rule in round 3 after a
     * Codex P1 — see that method's own javadoc for the full history, including why "forward slot,
     * calculator-only" was itself found wrong).</b> This field is a genuine, permanent, live
     * production input via that shared helper: it is what {@code effectiveInversionScore()} falls
     * back to whenever {@link Readings#inversionScore()} is null, for EITHER window — a past date
     * whose reading was never written (every pre-V158 row, forever), or a forward date whose
     * {@code slot_atmosphere} write has not happened yet this cycle (a region skipped by
     * {@code BriefingCandidateCollector}'s {@code SKIPPED_CACHED} gate before
     * {@code fetchWeatherAndTriage} ever runs, which can hold for up to 36 hours on a SETTLED
     * region at T+2 or beyond). {@code TopicDailyLogJob}'s future-population log reads
     * {@code forecast_score} directly, bypassing this composite entirely — see that class's own
     * javadoc.
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
     * Reading-shaped slot signals from {@code slot_atmosphere} — each nullable (inland has
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
     *                                  {@code InversionScoreCalculator}), or null when either the
     *                                  location was not inversion-eligible or the calculator itself
     *                                  returned null for an eligible one (missing weather inputs).
     *                                  V158 (Phase 2 of "record conditions for every place", owner
     *                                  decision 2026-09-30) — the deterministic calculator's own
     *                                  score, populated (or left null) for every candidate whatever
     *                                  the triage verdict or Gate 4 decision, unlike
     *                                  {@link Scores#inversion()} which is Claude's echo and only
     *                                  exists for a completed evaluation. ⚠️ Never read this field
     *                                  alone to decide "unscored" — always pair it with
     *                                  {@link #inversionScored()}, or use
     *                                  {@link SlotSignals#effectiveInversionScore()} directly
     * @param inversionScored           true when this row's writer ran the calculator's
     *                                  eligibility check THIS cycle, whatever the resulting score —
     *                                  false only for a row written before V158 round 4 added this
     *                                  column (default {@code FALSE}). A fresh {@code true} row's
     *                                  null {@code inversionScore} is an authoritative answer (an
     *                                  ineligible location, or an eligible one the calculator could
     *                                  not score for want of weather inputs) and must not fall back
     *                                  to Claude's echo the way a {@code false} row's does — see
     *                                  {@link SlotSignals#effectiveInversionScore()}'s own
     *                                  javadoc for the full history (round 4, a Codex P1 against
     *                                  round 3's own unify-onto-one-rule fix)
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
            Double inversionScore,
            boolean inversionScored) {

        /** The all-absent readings, used for a key that has only scores. */
        public static final Readings EMPTY = new Readings(
                null, null, null, null, null, null, null, null, null, null, null, null, false);
    }
}
