package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Where Ask's spend is recorded (plan §2.3 <em>Cost</em>, D-9).
 *
 * <p>Every typed question and admin dry-run is logged against <b>one {@code ASK} job run per UK
 * civil day</b>, found or created under an in-JVM lock (single instance, the
 * {@code LocationFailureService} precedent), so the day's Ask spend is one row in Operations and a
 * sum over a handful of runs is cheap. The Ready precompute is a different run type
 * ({@code ASK_READY}, one run per precompute, started by its own caller) and is never counted as
 * typed spend.
 *
 * <p><b>Why a scoped increment.</b> {@link JobRunService#completeRun} sums a run's cost once, when
 * it completes, and this run is completed the moment it is created (there is nothing to wait for
 * across a day of independent questions). Without more, Operations would show £0 for the day
 * forever. So each logged call is followed by {@link JobRunRepository#addCostMicroDollars}, and
 * each finished question by {@link JobRunRepository#addQuestionOutcome}; the Job Runs view reads
 * those same columns it reads for every other run (cost, processed, succeeded, failed).
 *
 * <p><b>Fail closed.</b> {@link #dailyRunId()} propagates a database failure, and the engine calls
 * it before the first model turn: if the run cannot be found or made, no money is spent.
 */
@Service
public class AskJobRunService {

    private static final Logger LOG = LoggerFactory.getLogger(AskJobRunService.class);

    /** How long {@link #typedSpendTodayMicroDollars()} reuses a figure. */
    static final int SPEND_MEMO_SECONDS = 30;

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final JobRunService jobRunService;
    private final JobRunRepository jobRunRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final AskProperties properties;
    private final Clock clock;

    /** Guards {@link #cachedDay} and {@link #cachedRunId}. A lock, not {@code synchronized}: virtual threads. */
    private final ReentrantLock runLock = new ReentrantLock();
    private LocalDate cachedDay;
    private long cachedRunId;

    private final ReentrantLock spendLock = new ReentrantLock();
    private Instant spendMemoAt;
    private LocalDate spendMemoDay;
    private long spendMemo;

    /**
     * Creates the service.
     *
     * @param jobRunService        starts and completes runs
     * @param jobRunRepository     the scoped updates and the day lookup
     * @param apiCallLogRepository the spend sum
     * @param properties           the Ask settings (the model a new daily run is labelled with)
     * @param clock                the application clock: the UK civil day and the memo
     */
    public AskJobRunService(JobRunService jobRunService, JobRunRepository jobRunRepository,
            ApiCallLogRepository apiCallLogRepository, AskProperties properties, Clock clock) {
        this.jobRunService = jobRunService;
        this.jobRunRepository = jobRunRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The id of today's {@code ASK} run, creating (and completing) it on the day's first call.
     * Concurrent first calls create exactly one.
     *
     * @return the run id
     */
    public long dailyRunId() {
        LocalDate today = ForecastHorizon.today(clock);
        runLock.lock();
        try {
            if (today.equals(cachedDay)) {
                return cachedRunId;
            }
            long id = jobRunRepository
                    .findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(RunType.ASK,
                            ukDayStartUtc(today))
                    .map(JobRunEntity::getId)
                    .orElseGet(() -> createDailyRun().getId());
            cachedDay = today;
            cachedRunId = id;
            return id;
        } finally {
            runLock.unlock();
        }
    }

    private JobRunEntity createDailyRun() {
        // Manual: a person asked. Completed at once; the scoped increments keep its figures true.
        JobRunEntity run = jobRunService.startRun(RunType.ASK, true, properties.getModel());
        jobRunService.completeRun(run, 0, 0);
        LOG.info("[ASK] Started the daily ASK job run {}", run.getId());
        return run;
    }

    /**
     * Adds a logged call's cost to a run, for the Operations view. A failure is logged at ERROR and
     * swallowed: the call has already happened and been written to {@code api_call_log}, which is
     * what the spend cap sums, so the answer must not be thrown away over a display column.
     *
     * @param runId the run
     * @param micro the call's cost in micro-dollars; a non-positive figure is ignored
     */
    public void recordCost(long runId, long micro) {
        if (micro <= 0) {
            return;
        }
        try {
            jobRunRepository.addCostMicroDollars(runId, micro);
        } catch (RuntimeException e) {
            LOG.error("[ASK] Could not add {} micro-dollars to job run {}: {}", micro, runId,
                    e.getMessage());
        }
    }

    /**
     * Counts one finished question on a run.
     *
     * @param runId    the run
     * @param answered true when the engine produced an answer (including an honest "can't"); false
     *                 when it failed
     */
    public void recordQuestion(long runId, boolean answered) {
        try {
            jobRunRepository.addQuestionOutcome(runId, answered ? 1 : 0, answered ? 0 : 1);
        } catch (RuntimeException e) {
            LOG.error("[ASK] Could not count a question on job run {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Today's typed spend: the recorded cost of every call logged against an {@code ASK} run started
     * since UK midnight, reused for {@value #SPEND_MEMO_SECONDS} seconds. {@code ASK_READY} spend is
     * not in it. The B4 spend cap compares this with
     * {@link AskProperties#dailySpendCapMicroDollars()}.
     *
     * @return micro-dollars spent today
     */
    public long typedSpendTodayMicroDollars() {
        Instant now = clock.instant();
        LocalDate today = ForecastHorizon.today(clock);
        spendLock.lock();
        try {
            if (spendMemoAt != null && today.equals(spendMemoDay)
                    && Duration.between(spendMemoAt, now).compareTo(
                            Duration.ofSeconds(SPEND_MEMO_SECONDS)) < 0) {
                return spendMemo;
            }
            spendMemo = apiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK,
                    ukDayStartUtc(today));
            spendMemoAt = now;
            spendMemoDay = today;
            return spendMemo;
        } finally {
            spendLock.unlock();
        }
    }

    /**
     * The start of a UK civil day as the UTC {@code LocalDateTime} job runs are stamped with.
     *
     * @param day the UK civil date
     * @return its midnight, in UTC
     */
    static LocalDateTime ukDayStartUtc(LocalDate day) {
        return day.atStartOfDay(LONDON).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
