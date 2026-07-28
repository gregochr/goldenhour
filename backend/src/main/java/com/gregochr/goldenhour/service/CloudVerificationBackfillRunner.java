package com.gregochr.goldenhour.service;

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
     * Batches attempted per scheduled tick.
     *
     * <p>Sized to sit inside Open-Meteo's hourly allowance rather than to finish quickly. Each
     * batch is 100 evaluations at four sample points, chunked 20 to a request — about 20 requests.
     * A dozen batches is therefore ~240 requests an hour, which walks ~25k evaluations down over
     * roughly a day unattended. Overshooting is not harmful (a throttled batch stops the tick and
     * is cleared on the next one), but it wastes an hour's progress, so this errs low.
     */
    static final int SCHEDULED_BATCHES_PER_RUN = 12;

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
     * <p>Exists because the backlog cannot be drained in a single pass. Roughly 25,000 evaluations
     * at four archive points each is ~5,000 requests — far beyond an hour's allowance — so an
     * unbounded run simply burns the quota, hits a blank batch and stops. Ticking hourly turns that
     * into steady unattended progress instead of something a person has to sit and re-trigger.
     *
     * <p>A no-op once the backlog is clear: the first batch finds no candidates and returns
     * immediately, so leaving the job enabled costs nothing.
     */
    void runScheduled() {
        if (!running.compareAndSet(false, true)) {
            LOG.info("[CLOUD VERIFY] scheduled tick skipped — a run is already in progress");
            return;
        }
        try {
            verificationService.clearBlankVerifications();
        } catch (Exception e) {
            LOG.warn("[CLOUD VERIFY] could not clear blank rows before tick: {}", e.getMessage());
        }
        status.set(BackfillStatus.started(LocalDateTime.now(clock), remainingOrNull()));
        runBounded(SCHEDULED_BATCHES_PER_RUN);
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
        // Clear blanks first so a previously throttled run heals itself rather than leaving
        // those evaluations permanently masked by the anti-join.
        try {
            verificationService.clearBlankVerifications();
        } catch (Exception e) {
            LOG.warn("[CLOUD VERIFY] could not clear blank rows before run: {}", e.getMessage());
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
        runBounded(MAX_BATCHES);
    }

    /**
     * Works through the backlog for at most {@code maxBatches} committed batches.
     *
     * @param maxBatches the batch ceiling for this run
     */
    @Async("forecastExecutor")
    void runBounded(int maxBatches) {
        String error = null;
        try {
            for (int i = 0; i < maxBatches; i++) {
                BackfillBatch batch = verificationService.backfill(BATCH_SIZE);
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
            }
        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOG.error("[CLOUD VERIFY] backfill run failed: {}", error, e);
        } finally {
            final String captured = error;
            BackfillStatus finalStatus =
                    status.updateAndGet(s -> s.finished(LocalDateTime.now(clock), captured));
            // Clear the guard last: until it flips, a concurrent start() is correctly refused.
            running.set(false);
            LOG.info("[CLOUD VERIFY] backfill run complete — verified={} batches={} error={}",
                    finalStatus.verified(), finalStatus.batches(), captured);
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
