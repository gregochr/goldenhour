package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.model.BackfillBatch;
import com.gregochr.goldenhour.model.BackfillStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the cloud-verification backfill in the background, one committed batch at a time.
 *
 * <p>The backlog runs to tens of thousands of evaluations. A synchronous request over it is killed
 * by the reverse proxy long before it finishes, and the caller cannot then distinguish a timeout
 * from a failure — the work carries on server-side regardless, which is worse than useless as
 * feedback. So the run happens here and callers poll {@link #status()}.
 *
 * <p><strong>Deliberately a separate bean from {@link CloudVerificationService}.</strong> Calling
 * {@code backfill(...)} from inside that service would be a self-invocation and bypass its own
 * proxies — neither {@code @Async} nor {@code @Transactional} would apply, so the run would block
 * the request thread and each batch would lose its transaction. Crossing a bean boundary keeps
 * both.
 *
 * <p>Each batch commits independently, so a crash or restart mid-run loses only the batch in
 * flight. Candidate selection is an anti-join, so restarting simply resumes.
 */
@Component
public class CloudVerificationBackfillRunner {

    private static final Logger LOG =
            LoggerFactory.getLogger(CloudVerificationBackfillRunner.class);

    /** Evaluations per committed batch — small enough to bound a transaction, big enough to batch. */
    static final int BATCH_SIZE = 100;

    /** Safety stop for a manual run, so a bug that never drains the queue cannot loop forever. */
    static final int MAX_BATCHES = 1000;

    /**
     * Archive calls a scheduled tick may spend before it stops starting new batches.
     *
     * <p>Open-Meteo charges one call per <em>location</em> in a multi-coordinate request, not one per
     * request, and the free archive allows 10,000 calls a day (5,000 an hour). Each evaluation samples
     * five points (cone x3, observer, far-solar), and every evaluation of the same sunrise or sunset
     * samples the same five, so the service fetches each distinct point once and candidates are
     * ordered to keep an event's evaluations in one batch. A batch of 100 evaluations is then about
     * 18 events, roughly 93 calls (without that it was about 500).
     *
     * <p>A day has about 420 distinct events, so ~420 x 5 = ~2,100 calls after de-duplication, about
     * 21% of the daily allowance. The backlog grows once a day, at 00:00 UTC, when a new target date
     * passes the archive lag, so a single tick normally drains it. 2,500 is half the hourly cap: the
     * tick is checked before each batch, so it overshoots by at most one batch, and a spent
     * allowance ends the run as a pause rather than a failure.
     */
    static final int SCHEDULED_CALL_BUDGET_PER_RUN = 2_500;

    /** Summary and status text when Open-Meteo's archive allowance is spent. */
    static final String PAUSED_TEXT =
            "paused: Open-Meteo archive allowance used up; resumes when it resets";

    private final CloudVerificationService verificationService;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<BackfillStatus> status =
            new AtomicReference<>(BackfillStatus.idle());

    /**
     * Constructs the runner.
     *
     * @param verificationService     the service performing each batch
     * @param dynamicSchedulerService  scheduler the hourly tick registers with
     * @param clock                   UTC clock for run timestamps
     */
    public CloudVerificationBackfillRunner(CloudVerificationService verificationService,
            DynamicSchedulerService dynamicSchedulerService, Clock clock) {
        this.verificationService = verificationService;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
    }

    /**
     * Registers the hourly backfill tick with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(
                "cloud_verification_backfill", this::runScheduled);
    }

    /**
     * One scheduled tick: works a bounded slice of the backlog, then yields until the next hour.
     *
     * <p>Exists because a large backlog cannot be drained without spending Open-Meteo's archive
     * allowance, so the tick stops once it has spent {@link #SCHEDULED_CALL_BUDGET_PER_RUN} calls.
     * Ticking hourly turns that into steady unattended progress instead of something a person has
     * to sit and re-trigger.
     *
     * <p>A no-op once the backlog is clear: the first batch finds no candidates and returns
     * immediately, so leaving the job enabled costs nothing.
     */
    void runScheduled() {
        if (!running.compareAndSet(false, true)) {
            LOG.info("[CLOUD VERIFY] scheduled tick skipped — a run is already in progress");
            SchedulerFireOutcome.report(SchedulerFireStatus.SKIPPED, "Already running — skipped");
            return;
        }
        try {
            verificationService.clearIncompleteVerifications();
        } catch (Exception e) {
            LOG.warn("[CLOUD VERIFY] could not clear incomplete rows before tick: {}",
                    e.getMessage());
        }
        status.set(BackfillStatus.started(LocalDateTime.now(clock), remainingOrNull()));
        runBounded(MAX_BATCHES, SCHEDULED_CALL_BUDGET_PER_RUN);
    }

    /**
     * Returns the current or most recent run's progress.
     *
     * @return the status snapshot
     */
    public BackfillStatus status() {
        return status.get();
    }

    /**
     * Starts a background backfill unless one is already running.
     *
     * <p>Refusing a concurrent start matters: two runs would select overlapping candidates and
     * race to insert the same {@code forecast_evaluation_id}, which the unique constraint would
     * reject — turning a duplicate request into a batch of failures.
     *
     * @return {@code true} if a run was started, {@code false} if one was already in progress
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        // Clear incomplete rows first so a previously throttled run heals itself rather than
        // leaving those evaluations permanently masked by the anti-join.
        try {
            verificationService.clearIncompleteVerifications();
        } catch (Exception e) {
            LOG.warn("[CLOUD VERIFY] could not clear incomplete rows before run: {}",
                    e.getMessage());
        }
        status.set(BackfillStatus.started(LocalDateTime.now(clock), remainingOrNull()));
        run();
        return true;
    }

    /**
     * Works through the backlog in committed batches until it is drained or a batch fails.
     *
     * <p>Stops on the first failing batch rather than grinding through the remainder: the usual
     * cause is the archive rate-limiting us, and continuing would write thousands of rows with no
     * observations that the anti-join would then never revisit.
     */
    @Async("forecastExecutor")
    void run() {
        runBounded(MAX_BATCHES, Integer.MAX_VALUE);
    }

    /**
     * Works through the backlog for at most {@code maxBatches} committed batches, starting no new
     * batch once {@code callBudget} archive calls have been spent.
     *
     * @param maxBatches the batch ceiling for this run
     * @param callBudget archive calls after which no further batch is started
     */
    @Async("forecastExecutor")
    void runBounded(int maxBatches, int callBudget) {
        String error = null;
        boolean paused = false;
        long callsSpent = 0;
        try {
            for (int i = 0; i < maxBatches && callsSpent < callBudget; i++) {
                BackfillBatch batch = verificationService.backfill(BATCH_SIZE);
                if (batch.quotaExhausted()) {
                    // Open-Meteo's allowance is spent: count the rows that did get data, then pause
                    // rather than fail. Checked before the blank test, which it would also satisfy.
                    if (batch.withObservations() > 0) {
                        status.updateAndGet(s -> s.withBatch(batch.withObservations(), remainingOrNull()));
                    }
                    paused = true;
                    error = PAUSED_TEXT;
                    LOG.warn("[CLOUD VERIFY] {}", PAUSED_TEXT);
                    break;
                }
                if (batch.rowsWritten() == 0) {
                    break;
                }
                if (batch.isBlank()) {
                    // Rows were written but carry no observations — the archive is throttling or
                    // down. Stopping is the whole point: continuing would blank the rest of the
                    // backlog, and the anti-join would never revisit any of it.
                    error = "archive returned no observations — stopped after "
                            + status.get().batches() + " batch(es); re-run to resume";
                    LOG.warn("[CLOUD VERIFY] {}", error);
                    break;
                }
                status.updateAndGet(s -> s.withBatch(batch.withObservations(), remainingOrNull()));
                callsSpent += batch.archiveCalls();
            }
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOG.error("[CLOUD VERIFY] backfill run failed: {}", error, e);
        } finally {
            final String captured = error;
            final boolean pausedOnQuota = paused;
            BackfillStatus finalStatus =
                    status.updateAndGet(s -> s.finished(LocalDateTime.now(clock), captured));
            // Clear the guard last: until it flips, a concurrent start() is correctly refused.
            running.set(false);
            LOG.info("[CLOUD VERIFY] backfill run complete — verified={} batches={} error={}",
                    finalStatus.verified(), finalStatus.batches(), captured);
            reportRun(finalStatus, captured, pausedOnQuota);
        }
    }

    /**
     * Reports how a run ended: clean is SUCCEEDED with the verified and remaining counts, stopped with
     * progress made is PARTIAL, stopped with none is FAILED. A spent Open-Meteo allowance is a pause,
     * not a failure: SKIPPED when nothing was verified this run, PARTIAL otherwise.
     */
    private static void reportRun(BackfillStatus finalStatus, String error, boolean pausedOnQuota) {
        String verified = finalStatus.verified() + " verified";
        if (pausedOnQuota) {
            SchedulerFireOutcome.report(
                    finalStatus.verified() > 0 ? SchedulerFireStatus.PARTIAL : SchedulerFireStatus.SKIPPED,
                    verified + " · " + PAUSED_TEXT);
        } else if (error == null) {
            SchedulerFireOutcome.report(SchedulerFireStatus.SUCCEEDED, verified
                    + (finalStatus.remaining() != null ? " · " + finalStatus.remaining() + " remaining" : ""));
        } else {
            SchedulerFireOutcome.report(
                    finalStatus.verified() > 0 ? SchedulerFireStatus.PARTIAL : SchedulerFireStatus.FAILED,
                    verified + " · stopped: " + error);
        }
    }

    /**
     * Returns the count of evaluations still awaiting verification, or {@code null} on failure.
     *
     * <p>Progress reporting must never be able to abort the run, so a failed count degrades to
     * "unknown" rather than propagating.
     *
     * @return the remaining count, or {@code null}
     */
    private Integer remainingOrNull() {
        try {
            return (int) verificationService.countRemaining();
        } catch (Exception e) {
            LOG.debug("[CLOUD VERIFY] remaining count unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the archive-lag cutoff date used for candidate selection.
     *
     * @return the cutoff
     */
    LocalDate cutoff() {
        return LocalDate.now(clock).minusDays(CloudVerificationService.ARCHIVE_LAG_DAYS);
    }
}
