package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One forecast paired with the outcome a photographer actually recorded for the same slot.
 *
 * <p>Produced by a JPQL constructor expression from one of two joins. The old one joins
 * {@code forecast_evaluation} to {@code actual_outcome} on (location, date, target type) — the
 * natural key both tables share — so a slot may yield several pairs, one per {@code daysAhead},
 * which is the point: it lets accuracy be scored per forecast horizon rather than blended across
 * all of them. The new one joins {@code forecast_evaluation} to {@code outcome_rating} on the row
 * id the rating resolved at write time, so each rating yields exactly one pair.
 *
 * <p>{@code wentOut} is carried but not filtered on — a photographer can rate a sky they watched
 * from a window. The only admission requirement is that both ratings are present.
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
 * @param outcomeKey          which outcome this pair scores: {@code "outcome:" + id} for an
 *                            {@code actual_outcome} row, {@code "rating:" + userId} for a reader's
 *                            {@code outcome_rating}; it keeps two outcomes of one slot apart
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
        String outcomeKey) {

    /**
     * Builds a pair from an {@code actual_outcome} row — the projection
     * {@code ForecastEvaluationRepository#findCalibrationPairs} selects.
     *
     * @param locationName        the location this slot belongs to
     * @param targetDate          the date of the solar event
     * @param targetType          SUNRISE or SUNSET
     * @param daysAhead           how far ahead this evaluation was made
     * @param forecastRunAt       when the evaluation was produced
     * @param evaluationModel     the model that produced the evaluation, or {@code null}
     * @param predictedRating     the forecast 1–5 star rating
     * @param predictedFierySky   the forecast fiery-sky score, or {@code null}
     * @param predictedGoldenHour the forecast golden-hour score, or {@code null}
     * @param actualRating        the recorded 1–5 star rating
     * @param actualFierySky      the recorded fiery-sky score, or {@code null}
     * @param actualGoldenHour    the recorded golden-hour score, or {@code null}
     * @param wentOut             whether the photographer travelled, or {@code null}
     * @param outcomeId           the {@code actual_outcome} row's id
     */
    public CalibrationPair(String locationName, LocalDate targetDate, TargetType targetType,
            Integer daysAhead, LocalDateTime forecastRunAt, EvaluationModel evaluationModel,
            Integer predictedRating, Integer predictedFierySky, Integer predictedGoldenHour,
            Integer actualRating, Integer actualFierySky, Integer actualGoldenHour,
            Boolean wentOut, Long outcomeId) {
        this(locationName, targetDate, targetType, daysAhead, forecastRunAt, evaluationModel,
                predictedRating, predictedFierySky, predictedGoldenHour, actualRating,
                actualFierySky, actualGoldenHour, wentOut, "outcome:" + outcomeId);
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
     */
    public CalibrationPair(String locationName, LocalDate targetDate, TargetType targetType,
            Integer daysAhead, LocalDateTime forecastRunAt, EvaluationModel evaluationModel,
            Integer predictedRating, Integer predictedFierySky, Integer predictedGoldenHour,
            Integer actualRating, Long userId) {
        this(locationName, targetDate, targetType, daysAhead, forecastRunAt, evaluationModel,
                predictedRating, predictedFierySky, predictedGoldenHour, actualRating,
                null, null, null, "rating:" + userId);
    }

    /**
     * Returns the key identifying the slot and horizon this pair scores.
     *
     * <p>Several evaluation runs can exist for the same slot at the same horizon (a nightly batch
     * and an intraday refresh, say). Grouping on this key lets the newest win, so one forecast
     * horizon contributes exactly one pair. The key carries the outcome's identity too, so only
     * RUNS of one forecast scored against one outcome collapse: two readers' ratings of one slot,
     * or a rating beside an {@code actual_outcome} row, are separate observations and stay so.
     *
     * @return the dedup key
     */
    public String slotHorizonKey() {
        return locationName + "|" + targetDate + "|" + targetType + "|" + daysAhead + "|" + outcomeKey;
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
