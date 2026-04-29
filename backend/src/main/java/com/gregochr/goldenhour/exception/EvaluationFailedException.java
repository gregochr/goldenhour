package com.gregochr.goldenhour.exception;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;

/**
 * Thrown when a Claude evaluation returns an error outcome from
 * {@code EvaluationService.evaluateNow}.
 *
 * <p>The new engine surfaces failures via {@code EvaluationResult.Errored} rather
 * than throwing the underlying Anthropic SDK exception. Callers that need the
 * surrounding orchestration (e.g. {@code submitParallel} in
 * {@code ForecastCommandExecutor}) to mark the slot failed translate the
 * {@code Errored} result into this exception so the existing per-task catch
 * block continues to apply.
 *
 * <p>Carries enough context (errorType, location, target type, date) for
 * observability without depending on the engine's internal error vocabulary.
 */
public class EvaluationFailedException extends RuntimeException {

    private final String errorType;
    private final String locationName;
    private final TargetType targetType;
    private final LocalDate date;

    /**
     * Constructs a new {@code EvaluationFailedException}.
     *
     * @param errorType    short classification from the engine (e.g.
     *                     {@code "overloaded_error"}, {@code "parse_error"})
     * @param message      human-readable detail surfaced from the engine
     * @param locationName the location whose evaluation failed
     * @param targetType   SUNRISE / SUNSET
     * @param date         the evaluation date
     */
    public EvaluationFailedException(String errorType, String message,
            String locationName, TargetType targetType, LocalDate date) {
        super("Evaluation failed for " + locationName + " " + targetType + " "
                + date + " — " + errorType + ": " + message);
        this.errorType = errorType;
        this.locationName = locationName;
        this.targetType = targetType;
        this.date = date;
    }

    /**
     * Returns the short error classification from the engine.
     *
     * @return the error type
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * Returns the location name whose evaluation failed.
     *
     * @return the location name
     */
    public String getLocationName() {
        return locationName;
    }

    /**
     * Returns the target type that failed.
     *
     * @return SUNRISE or SUNSET
     */
    public TargetType getTargetType() {
        return targetType;
    }

    /**
     * Returns the evaluation date that failed.
     *
     * @return the date
     */
    public LocalDate getDate() {
        return date;
    }
}
