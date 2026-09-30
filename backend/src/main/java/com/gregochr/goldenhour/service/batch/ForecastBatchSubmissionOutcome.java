package com.gregochr.goldenhour.service.batch;

/**
 * Result of {@code ScheduledBatchEvaluationService#submitForecastBatchForPipelineRun(Long,
 * CandidateCollectionStrategy, EligibilityPolicy, boolean, java.util.function.Consumer)} — whether
 * this call's own submission ran at all, and if it did, how many of its buckets actually reached
 * Anthropic.
 *
 * <p>Replaces that method's previous plain {@code boolean} return. {@link #submitted()} keeps the
 * exact meaning the boolean had — the caller (the orchestrator) still must not dispatch the
 * wait/brief tail when it reads {@code false}, since that means another run already held the
 * submission guard and this cycle submitted nothing of its own. {@link #summary()} is new: it lets
 * a caller that DID submit tell "every bucket reached Anthropic" apart from "one or more did not",
 * which {@code com.gregochr.goldenhour.service.pipeline.PipelineOrchestrator}'s
 * FORECAST_BATCH_SUBMIT phase result and, ultimately, the run's {@code PipelineRunStatus} depend
 * on.
 *
 * @param submitted {@code true} if this call acquired the submission guard and ran collection +
 *                  submission (whether or not every bucket succeeded); {@code false} if another
 *                  submission already held the guard and this call was dropped entirely
 * @param summary   this call's own per-bucket submission accounting, or {@code null} when
 *                  {@code submitted} is {@code false} (nothing was attempted)
 */
public record ForecastBatchSubmissionOutcome(boolean submitted, BatchSubmissionSummary summary) {

    /** @return the outcome for a dropped submission (guard already held elsewhere). */
    public static ForecastBatchSubmissionOutcome dropped() {
        return new ForecastBatchSubmissionOutcome(false, null);
    }

    /**
     * @param summary this call's own per-bucket accounting
     * @return the outcome for a submission that ran (whether or not every bucket succeeded)
     */
    public static ForecastBatchSubmissionOutcome ran(BatchSubmissionSummary summary) {
        return new ForecastBatchSubmissionOutcome(true, summary);
    }
}
