package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One forecast paired with the outcome a photographer actually recorded for the same slot.
 *
 * <p>Produced by a JPQL constructor expression joining {@code forecast_evaluation} to
 * {@code actual_outcome} on (location, date, target type) — the natural key both tables share.
 * A slot may yield several pairs, one per {@code daysAhead}, which is the point: it lets accuracy
 * be scored per forecast horizon rather than blended across all of them.
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
        Boolean wentOut) {

    /**
     * Returns the key identifying the slot and horizon this pair scores.
     *
     * <p>Several evaluation runs can exist for the same slot at the same horizon (a nightly batch
     * and an intraday refresh, say). Grouping on this key lets the newest win, so one forecast
     * horizon contributes exactly one pair.
     *
     * @return the dedup key
     */
    public String slotHorizonKey() {
        return locationName + "|" + targetDate + "|" + targetType + "|" + daysAhead;
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
