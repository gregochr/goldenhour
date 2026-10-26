package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One forecast paired with the rating a reader gave the same slot.
 *
 * <p>Produced by a JPQL constructor expression that joins {@code forecast_evaluation} to
 * {@code outcome_rating} on the row id the rating resolved at write time, so each rating yields
 * exactly one pair. A slot may yield several pairs, one per {@code daysAhead}, which is the point:
 * it lets accuracy be scored per forecast horizon rather than blended across all of them.
 *
 * <p>The only admission requirement is that both ratings are present.
 *
 * @param locationName        the location this slot belongs to
 * @param targetDate          the date of the solar event
 * @param targetType          SUNRISE or SUNSET
 * @param daysAhead           how far ahead this evaluation was made (0 = same day)
 * @param forecastRunAt       when the evaluation was produced, used to pick the latest per slot
 * @param evaluationModel     the model that produced the evaluation, or {@code null}
 * @param predictedRating     the forecast 1–5 star rating
 * @param predictedFierySky   the forecast fiery-sky score (0–100), or {@code null}
 * @param predictedGoldenHour the forecast golden-hour score (0–100), or {@code null}
 * @param actualRating        the recorded 1–5 star rating
 * @param actualFierySky      the recorded fiery-sky score (0–100), or {@code null}
 * @param actualGoldenHour    the recorded golden-hour score (0–100), or {@code null}
 * @param wentOut             whether the photographer travelled to the location, or {@code null}
 * @param outcomeKey          which outcome this pair scores: {@code "rating:" + userId} for a
 *                            reader's {@code outcome_rating}; it keeps two readers' ratings of one
 *                            slot apart
 * @param appBuild            the release that scored the forecast row (V182), or {@code null} for a
 *                            row written before it was recorded
 * @param promptHash          the system-prompt fingerprint the forecast row's request carried
 *                            (V182), or {@code null} when none was recorded
 * @param mistPotential       the band Claude echoed for valley mist on the forecast row
 *                            ({@code forecast_evaluation.mist_potential}: NONE, POSSIBLE, LIKELY or
 *                            FOGBOUND), or {@code null} for a site that is not mist-prone and every
 *                            row scored before V186
 * @param seen                the tag the reader gave their rating (V187), or {@code null} when they
 *                            gave none
 */
public record CalibrationPair(
        String locationName,
        LocalDate targetDate,
        TargetType targetType,
        Integer daysAhead,
        LocalDateTime forecastRunAt,
        EvaluationModel evaluationModel,
        Integer predictedRating,
        Integer predictedFierySky,
        Integer predictedGoldenHour,
        Integer actualRating,
        Integer actualFierySky,
        Integer actualGoldenHour,
        Boolean wentOut,
        String outcomeKey,
        String appBuild,
        String promptHash,
        String mistPotential,
        SeenTag seen) {

    /** The {@link #mistPotential} band the mist-prone calibration bucket selects. */
    public static final String MIST_LIKELY = MistPotential.LIKELY.name();

    /**
     * Builds a pair with no mist band and no tag - the shape every pair had before valley mist.
     *
     * @param locationName        the location this slot belongs to
     * @param targetDate          the date of the solar event
     * @param targetType          SUNRISE or SUNSET
     * @param daysAhead           how far ahead this evaluation was made
     * @param forecastRunAt       when the evaluation was produced
     * @param evaluationModel     the model that produced the evaluation, or {@code null}
     * @param predictedRating     the forecast 1-5 star rating
     * @param predictedFierySky   the forecast fiery-sky score, or {@code null}
     * @param predictedGoldenHour the forecast golden-hour score, or {@code null}
     * @param actualRating        the recorded 1-5 star rating
     * @param actualFierySky      the recorded fiery-sky score, or {@code null}
     * @param actualGoldenHour    the recorded golden-hour score, or {@code null}
     * @param wentOut             whether the photographer travelled, or {@code null}
     * @param outcomeKey          which outcome this pair scores
     * @param appBuild            the release that scored the forecast row, or {@code null}
     * @param promptHash          the forecast row's system-prompt fingerprint, or {@code null}
     */
    public CalibrationPair(String locationName, LocalDate targetDate, TargetType targetType,
            Integer daysAhead, LocalDateTime forecastRunAt, EvaluationModel evaluationModel,
            Integer predictedRating, Integer predictedFierySky, Integer predictedGoldenHour,
            Integer actualRating, Integer actualFierySky, Integer actualGoldenHour,
            Boolean wentOut, String outcomeKey, String appBuild, String promptHash) {
        this(locationName, targetDate, targetType, daysAhead, forecastRunAt, evaluationModel,
                predictedRating, predictedFierySky, predictedGoldenHour, actualRating,
                actualFierySky, actualGoldenHour, wentOut, outcomeKey, appBuild, promptHash, null, null);
    }

    /**
     * Builds a pair from a reader's {@code outcome_rating} with no mist band and no tag - the shape
     * the projection had before valley mist.
     *
     * @param locationName        the location this slot belongs to
     * @param targetDate          the date of the solar event
     * @param targetType          SUNRISE or SUNSET
     * @param daysAhead           the resolved forecast row's horizon
     * @param forecastRunAt       the resolved forecast row's run time
     * @param evaluationModel     the resolved forecast row's model, or {@code null}
     * @param predictedRating     the star the reader saw, captured when they rated
     * @param predictedFierySky   the resolved row's fiery-sky score, or {@code null}
     * @param predictedGoldenHour the resolved row's golden-hour score, or {@code null}
     * @param actualRating        the reader's 1-5 star rating
     * @param userId              the rating reader's id
     * @param appBuild            the release that scored the resolved forecast row, or {@code null}
     * @param promptHash          the resolved row's system-prompt fingerprint, or {@code null}
     */
    public CalibrationPair(String locationName, LocalDate targetDate, TargetType targetType,
            Integer daysAhead, LocalDateTime forecastRunAt, EvaluationModel evaluationModel,
            Integer predictedRating, Integer predictedFierySky, Integer predictedGoldenHour,
            Integer actualRating, Long userId, String appBuild, String promptHash) {
        this(locationName, targetDate, targetType, daysAhead, forecastRunAt, evaluationModel,
                predictedRating, predictedFierySky, predictedGoldenHour, actualRating, userId,
                appBuild, promptHash, null, null);
    }

    /**
     * Builds a pair from a reader's {@code outcome_rating} — the projection
     * {@code ForecastEvaluationRepository#findCalibrationPairsFromRatings} selects. That record
     * has no fiery-sky or golden-hour score and no trip flag, so those stay {@code null}
     * ({@code wentOut} in particular, so no reader infers a trip).
     *
     * @param locationName        the location this slot belongs to
     * @param targetDate          the date of the solar event
     * @param targetType          SUNRISE or SUNSET
     * @param daysAhead           the resolved forecast row's horizon
     * @param forecastRunAt       the resolved forecast row's run time
     * @param evaluationModel     the resolved forecast row's model, or {@code null}
     * @param predictedRating     the star the reader saw, captured when they rated
     * @param predictedFierySky   the resolved row's fiery-sky score, or {@code null}
     * @param predictedGoldenHour the resolved row's golden-hour score, or {@code null}
     * @param actualRating        the reader's 1–5 star rating
     * @param userId              the rating reader's id
     * @param appBuild            the release that scored the resolved forecast row, or {@code null}
     * @param promptHash          the resolved row's system-prompt fingerprint, or {@code null}
     * @param mistPotential       the resolved row's valley-mist band, or {@code null}
     * @param seen                the tag the reader gave the rating, or {@code null}
     */
    public CalibrationPair(String locationName, LocalDate targetDate, TargetType targetType,
            Integer daysAhead, LocalDateTime forecastRunAt, EvaluationModel evaluationModel,
            Integer predictedRating, Integer predictedFierySky, Integer predictedGoldenHour,
            Integer actualRating, Long userId, String appBuild, String promptHash,
            String mistPotential, SeenTag seen) {
        this(locationName, targetDate, targetType, daysAhead, forecastRunAt, evaluationModel,
                predictedRating, predictedFierySky, predictedGoldenHour, actualRating,
                null, null, null, "rating:" + userId, appBuild, promptHash, mistPotential, seen);
    }

    /**
     * Returns the key identifying the slot and horizon this pair scores.
     *
     * <p>Several evaluation runs can exist for the same slot at the same horizon (a nightly batch
     * and an intraday refresh, say). Grouping on this key lets the newest win, so one forecast
     * horizon contributes exactly one pair. The key carries the outcome's identity too, so only
     * RUNS of one forecast scored against one outcome collapse: two readers' ratings of one slot
     * are separate observations and stay so.
     *
     * @return the dedup key
     */
    public String slotHorizonKey() {
        return locationName + "|" + targetDate + "|" + targetType + "|" + daysAhead + "|" + outcomeKey;
    }

    /**
     * Whether the forecast carried Claude's LIKELY valley-mist band - the pairs the mist-prone
     * calibration bucket selects. The echo is only ever written for a {@code mist_prone} site, so
     * this is "a mist-prone site the forecast called likely".
     *
     * @return true when {@link #mistPotential} is LIKELY
     */
    public boolean mistLikely() {
        return MIST_LIKELY.equals(mistPotential);
    }

    /**
     * Returns the signed rating error — positive when the forecast was optimistic.
     *
     * @return predicted minus actual rating
     */
    public int ratingError() {
        return predictedRating - actualRating;
    }
}
