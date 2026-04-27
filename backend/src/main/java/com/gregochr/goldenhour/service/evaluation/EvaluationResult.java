package com.gregochr.goldenhour.service.evaluation;

/**
 * Sealed result type returned by {@link EvaluationService#evaluateNow}.
 *
 * <p>The synchronous path is intentionally lossy at this seam: the concrete
 * {@code payload} type for {@link Scored} is determined by the caller's task type
 * (a {@code SunsetEvaluation} for {@link EvaluationTask.Forecast},
 * a {@code List<AuroraForecastScore>} for {@link EvaluationTask.Aurora}). Callers
 * who care about the typed payload pattern-match on their known task type and cast.
 *
 * <p>Errors do not throw out of {@link EvaluationService#evaluateNow} — they're
 * surfaced as {@link Errored} so the call site can decide what to do (retry, log,
 * fall back). This keeps the engine's contract uniform between transports.
 */
public sealed interface EvaluationResult
        permits EvaluationResult.Scored, EvaluationResult.Errored {

    /**
     * Successful evaluation. {@code payload} is the per-task-type parsed result
     * (e.g. {@code SunsetEvaluation} for forecast, {@code List<AuroraForecastScore>}
     * for aurora). The {@link ResultHandler} for the task type has already executed
     * its writes by the time this is returned.
     *
     * @param payload parsed result; cast on the caller side based on task type
     */
    record Scored(Object payload) implements EvaluationResult {
    }

    /**
     * The Anthropic call failed or its response could not be parsed. The handler's
     * error path has already executed its writes (typically {@code api_call_log} with
     * {@code succeeded=false}).
     *
     * @param errorType short classification (e.g. {@code "overloaded_error"},
     *                  {@code "parse_error"})
     * @param message   human-readable detail (truncated upstream when persisted)
     */
    record Errored(String errorType, String message) implements EvaluationResult {
    }
}
