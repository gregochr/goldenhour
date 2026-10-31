package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunListStatus;
import com.gregochr.goldenhour.service.RunCompletion;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * The one place the Runs list decides a row's group, display name, status and note.
 *
 * <p>Pure functions of the stored rows, so the client never works any of them out. Every {@link RunType}
 * maps to a group: the switches below have no default branch, so adding a run type without mapping it
 * stops the build.
 *
 * <p>The ratio rule in {@link #classifyJob} is a <em>display</em> rule for the list. It is independent
 * of each job's own serve threshold (the briefing, for instance, is served at 50% or more).
 */
public final class RunClassifier {

    /** A stale batch is one past this: Anthropic expires a batch at 24 hours, plus slack. */
    static final Duration BATCH_STALE_CEILING = Duration.ofHours(26);

    /** Every other job run is stale past this. */
    static final Duration DEFAULT_STALE_CEILING = Duration.ofHours(6);

    /** The note served for a job run that never closed. */
    static final String DID_NOT_FINISH = "Did not finish";

    /** Numerator of the share of requests that must succeed for a run to be PARTIAL rather than FAILED. */
    private static final int PARTIAL_NUMERATOR = 4;

    /** Denominator of the same share (four fifths, 80%). */
    private static final int PARTIAL_DENOMINATOR = 5;

    /** The strategy name a JFDI batch records in {@code active_strategies}. */
    private static final String JFDI_STRATEGY = "EVALUATE_ALL";

    /** Notes {@link RunCompletion} writes when it aborts a run, which mark a 0/0 run as failed. */
    private static final Set<String> ABORT_REASONS = Set.of(
            RunCompletion.REASON_RUN_STOPPED,
            RunCompletion.REASON_EVALUATION_FAILED,
            RunCompletion.REASON_NOT_FINISHED,
            RunCompletion.REASON_UNEXPECTED);

    private RunClassifier() {
    }

    /**
     * What the server serves for a row's status, note and the note's tone.
     *
     * @param status the status
     * @param note   the one-line note, or null
     * @param tone   the note's tone, null when there is no note
     */
    public record Outcome(RunListStatus status, String note, NoteTone tone) {
    }

    /**
     * The group of a job run.
     *
     * @param runType   the run type
     * @param batchType the type of the run's Anthropic batch, or null when it has none
     * @return the group
     */
    public static RunGroup groupOf(RunType runType, BatchType batchType) {
        return switch (runType) {
            case SCHEDULED_BATCH -> batchType == BatchType.AURORA ? RunGroup.AURORA : RunGroup.BATCHES;
            case AURORA_EVALUATION -> RunGroup.AURORA;
            case TIDE -> RunGroup.TIDE;
            case BRIEFING -> RunGroup.BRIEFING;
            // Never written today: BATCH_NEAR_TERM, BATCH_FAR_TERM, BRIEFING_GLOSS, BRIEFING_BEST_BET,
            // AURORA_GLOSS, BLUEBELL_GLOSS are model_selection keys only. They sit in OTHER for completeness.
            case WEATHER, LIGHT_POLLUTION, ASK, ASK_READY, BATCH_NEAR_TERM, BATCH_FAR_TERM,
                 BRIEFING_GLOSS, BRIEFING_BEST_BET, AURORA_GLOSS, BLUEBELL_GLOSS -> RunGroup.OTHER;
        };
    }

    /**
     * The display name of a job run.
     *
     * @param runType          the run type
     * @param batchType        the type of the run's Anthropic batch, or null
     * @param activeStrategies the run's recorded strategies, or null
     * @return the name
     */
    public static String jobName(RunType runType, BatchType batchType, String activeStrategies) {
        return switch (runType) {
            case SCHEDULED_BATCH -> batchType == BatchType.AURORA ? "Aurora batch"
                    : isJfdi(activeStrategies) ? "JFDI batch" : "Scheduled batch";
            case BATCH_NEAR_TERM -> "Near-term batch";
            case BATCH_FAR_TERM -> "Far-term batch";
            case AURORA_EVALUATION -> "Aurora forecast";
            case AURORA_GLOSS -> "Aurora gloss";
            case TIDE -> "Tide data";
            case BRIEFING -> "Daily briefing";
            case BRIEFING_GLOSS -> "Briefing gloss";
            case BRIEFING_BEST_BET -> "Briefing best bet";
            case BLUEBELL_GLOSS -> "Bluebell gloss";
            case WEATHER -> "Wildlife comfort";
            case LIGHT_POLLUTION -> "Light pollution";
            case ASK -> "Ask PhotoCast";
            case ASK_READY -> "Ask ready answers";
        };
    }

    /**
     * The display name of a cycle.
     *
     * @param cycleType the cycle type
     * @return the name
     */
    public static String cycleName(CycleType cycleType) {
        return switch (cycleType) {
            case NIGHTLY -> "Overnight cycle";
            case INTRADAY -> "Afternoon refresh";
            case EVENING -> "Evening sunrise refresh";
        };
    }

    /**
     * Whether a run type's duration is meaningless: Ask runs are closed at creation and incremented all day.
     *
     * @param runType the run type
     * @return true for ASK and ASK_READY
     */
    public static boolean hasNoDuration(RunType runType) {
        return runType == RunType.ASK || runType == RunType.ASK_READY;
    }

    /**
     * How long a job run may stay open before it is served as not finished.
     *
     * @param runType the run type
     * @return 26 hours for a scheduled batch, 6 hours for everything else
     */
    public static Duration staleCeiling(RunType runType) {
        return runType == RunType.SCHEDULED_BATCH ? BATCH_STALE_CEILING : DEFAULT_STALE_CEILING;
    }

    /**
     * The longest stale ceiling of any run type, so one query can bound every candidate for "running".
     *
     * @return the longest ceiling
     */
    public static Duration longestStaleCeiling() {
        return BATCH_STALE_CEILING;
    }

    /**
     * Classifies a job run. Rules, in order:
     * <ol>
     *   <li>No {@code completedAt}: RUNNING while younger than its ceiling, else FAILED "Did not finish".</li>
     *   <li>0/0 with a {@link RunCompletion} reason as its notes: FAILED, and the note is that reason.</li>
     *   <li>0/0 otherwise: DONE.</li>
     *   <li>Else by the success ratio: all succeeded is DONE, at least 80% PARTIAL, below that FAILED.</li>
     * </ol>
     *
     * @param run    the job run
     * @param nowUtc the current time as a naive UTC time (the base of {@code job_run.started_at})
     * @return the served status, note and tone
     */
    public static Outcome classifyJob(JobRunEntity run, LocalDateTime nowUtc) {
        if (run.getCompletedAt() == null) {
            boolean stale = !nowUtc.isBefore(run.getStartedAt().plus(staleCeiling(run.getRunType())));
            return stale
                    ? new Outcome(RunListStatus.FAILED, DID_NOT_FINISH, NoteTone.ERROR)
                    : new Outcome(RunListStatus.RUNNING, null, null);
        }
        int succeeded = run.getSucceeded() == null ? 0 : run.getSucceeded();
        int failed = run.getFailed() == null ? 0 : run.getFailed();
        int total = succeeded + failed;
        if (total == 0) {
            return run.getNotes() != null && ABORT_REASONS.contains(run.getNotes())
                    ? new Outcome(RunListStatus.FAILED, run.getNotes(), NoteTone.ERROR)
                    : new Outcome(RunListStatus.DONE, null, null);
        }
        if (failed == 0) {
            return new Outcome(RunListStatus.DONE, null, null);
        }
        boolean partial = (long) succeeded * PARTIAL_DENOMINATOR >= (long) total * PARTIAL_NUMERATOR;
        String note = failed + " requests errored";
        return partial
                ? new Outcome(RunListStatus.PARTIAL, note, NoteTone.WARN)
                : new Outcome(RunListStatus.FAILED, note, NoteTone.ERROR);
    }

    /**
     * Classifies a cycle. Its note is what it is waiting on while running, else its failure reason.
     *
     * @param run the pipeline run
     * @return the served status, note and tone
     */
    public static Outcome classifyCycle(PipelineRunEntity run) {
        String reason = blankToNull(run.getFailureReason());
        return switch (run.getStatus()) {
            case RUNNING -> cycleOutcome(RunListStatus.RUNNING, NoteTone.INFO, blankToNull(run.getWaitingOn()));
            case COMPLETED -> new Outcome(RunListStatus.DONE, null, null);
            case DEGRADED -> cycleOutcome(RunListStatus.DEGRADED, NoteTone.WARN, reason);
            case FAILED -> cycleOutcome(RunListStatus.FAILED, NoteTone.ERROR, reason);
        };
    }

    private static Outcome cycleOutcome(RunListStatus status, NoteTone tone, String note) {
        return new Outcome(status, note, note == null ? null : tone);
    }

    private static boolean isJfdi(String activeStrategies) {
        if (activeStrategies == null) {
            return false;
        }
        for (String strategy : activeStrategies.split(",")) {
            if (JFDI_STRATEGY.equals(strategy.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
