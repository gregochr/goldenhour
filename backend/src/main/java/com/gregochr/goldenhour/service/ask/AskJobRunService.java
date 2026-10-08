package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.service.CostCalculator;
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
 * <p><b>Fail closed, twice.</b> {@link #dailyRunId()} propagates a database failure, and the engine
 * calls it before the first model turn: if the run cannot be found or made, no money is spent. And a
 * paid call's cost is never lost (see below).
 *
 * <p><b>What the spend cap reads.</b> {@link #typedSpendTodayMicroDollars()} sums {@code api_call_log}
 * rows (joined to {@code ASK} runs), <em>not</em> {@code job_run.total_cost_micro_dollars}. So the two
 * {@code job_run} increments ({@link #recordCost}, {@link #recordQuestion}) are display-only for
 * Operations and stay best effort: a failure there is logged at ERROR and cannot make the cap
 * under-count. The cap's own input is the row write, and that is what is protected here.
 *
 * <p><b>Unrecorded cost.</b> If the {@code api_call_log} insert for a turn fails after Anthropic has
 * answered, {@link #recordTurn} keeps the turn (run, model, tokens, duration, status, and the cost it
 * is priced at) in a bounded in-memory holder and <em>latches</em>:
 * <ul>
 *   <li>{@link #typedSpendTodayMicroDollars()} adds the unrecorded typed cost to the persisted sum on
 *       every read, bypassing the 30-second memo, so the very next cap check sees it. A flush
 *       invalidates the memo in the same critical section that removes the entries, so the figure
 *       before and after a flush is the same (never counted both as unrecorded and as persisted).</li>
 *   <li>{@link #accountingAvailable()} is called by the engine before every model turn, for every
 *       conversation, typed or Ready. While anything is unrecorded it first tries to write the held
 *       turns; only if all are written does it clear the latch and answer true. Otherwise the engine
 *       makes no further call. The conversation in which the failure happened therefore stops before
 *       its next turn (each further turn is more spend that cannot be recorded), but a turn that
 *       carried the {@code submit_answer} has already been paid for and is still returned.</li>
 *   <li>The holder keeps at most {@value UnrecordedTurnHolder#UNRECORDED_CAP} turns in detail. A further turn is folded
 *       into one per-run total, so cost is never dropped, only per-turn detail; that total is written
 *       as one summary row when the database recovers, and the latch holds until it is.</li>
 * </ul>
 * <b>The one way spend goes unrecorded:</b> the holder is in memory, so a process restart loses
 * whatever was in it. The latch bounds that to the turns of conversations already in flight when the
 * first write failed. A retried row is stamped with the time of the retry, not of the call; an insert
 * that failed after committing would be written twice, which over-counts (the safe direction).
 * One lock guards the holder, the memo and the flush, so two conversations can never write the same
 * held turn twice.
 */
@Service
public class AskJobRunService {

    private static final Logger LOG = LoggerFactory.getLogger(AskJobRunService.class);

    /** How long {@link #typedSpendTodayMicroDollars()} reuses a figure. */
    static final int SPEND_MEMO_SECONDS = 30;

    /** The {@code request_url} tag of a typed turn's row. */
    static final String URL_TYPED = "ask";

    /** The {@code request_url} tag of a Ready turn's row. */
    static final String URL_READY = "ask-ready";

    private final JobRunService jobRunService;
    private final JobRunRepository jobRunRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final CostCalculator costCalculator;
    private final AskProperties properties;
    private final Clock clock;

    /**
     * Guards {@link #cachedDay} and {@link #cachedRunId}. Built as a lock when {@code synchronized} pinned
     * a virtual thread; on Java 25 (JEP 491) it no longer does, and the lock is left as it was.
     */
    private final ReentrantLock runLock = new ReentrantLock();
    private LocalDate cachedDay;
    private long cachedRunId;

    /**
     * The unrecorded-turn holder and latch. Its lock also guards the three spend-memo fields below, so
     * the memo's invalidation and a held turn's removal are one critical section.
     */
    private final UnrecordedTurnHolder holder = new UnrecordedTurnHolder(new HolderLedger());
    private Instant spendMemoAt;
    private LocalDate spendMemoDay;
    private long spendMemo;

    /**
     * Creates the service.
     *
     * @param jobRunService        starts and completes runs, and writes {@code api_call_log} rows
     * @param jobRunRepository     the scoped updates and the day lookup
     * @param apiCallLogRepository the spend sum, and the summary row of an overflowed holder
     * @param costCalculator       prices a turn, exactly as {@code JobRunService.logApiCall} does
     * @param properties           the Ask settings (the model a new daily run is labelled with)
     * @param clock                the application clock: the UK civil day and the memo
     */
    public AskJobRunService(JobRunService jobRunService, JobRunRepository jobRunRepository,
            ApiCallLogRepository apiCallLogRepository, CostCalculator costCalculator,
            AskProperties properties, Clock clock) {
        this.jobRunService = jobRunService;
        this.jobRunRepository = jobRunRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.costCalculator = costCalculator;
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
                            ForecastHorizon.ukDayStartUtc(today))
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
     * Adds a logged call's cost to a run, for the Operations view only. Display-only: the spend cap
     * sums {@code api_call_log}, not this column, so a failure here is logged at ERROR and swallowed
     * and cannot make the cap under-count.
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
     * Counts one finished question on a run, for the Operations view only (display-only, like
     * {@link #recordCost}: the spend cap does not read it).
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
     * since UK midnight (reused for {@value #SPEND_MEMO_SECONDS} seconds) <em>plus</em> the cost of
     * every typed turn still unrecorded, which is never memoised. {@code ASK_READY} spend is not in it.
     * The typed spend cap ({@link AskSpendGuard}) compares this with {@link AskProperties#dailySpendCapMicroDollars()}.
     * Unrecorded cost from before UK midnight is counted too: it cannot be attributed to a day, and
     * counting it is the safe direction.
     *
     * @return micro-dollars spent today
     */
    public long typedSpendTodayMicroDollars() {
        return holder.typedSpend(this::memoisedPersistedSpend);
    }

    /** The persisted typed spend, reused for {@value #SPEND_MEMO_SECONDS} seconds. Runs under the holder's lock. */
    private long memoisedPersistedSpend() {
        Instant now = clock.instant();
        LocalDate today = ForecastHorizon.today(clock);
        if (spendMemoAt == null || !today.equals(spendMemoDay)
                || Duration.between(spendMemoAt, now).compareTo(Duration.ofSeconds(SPEND_MEMO_SECONDS)) >= 0) {
            spendMemo = apiCallLogRepository.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK,
                    ForecastHorizon.ukDayStartUtc(today));
            spendMemoAt = now;
            spendMemoDay = today;
        }
        return spendMemo;
    }

    // -- unrecorded cost --------------------------------------------------------------------

    /**
     * One model turn to be recorded.
     *
     * @param runId      the job run it is billed to
     * @param ready      true for a Ready (user-less) conversation, whose spend is not typed spend
     * @param model      the model that answered
     * @param durationMs how long the call took
     * @param status     the HTTP status, or null when none was received
     * @param succeeded  whether the turn was a usable one
     * @param error      why not, or null
     * @param usage      the tokens the API reported, or null when it reported none
     * @param cacheDiagnostics the prompt-cache diagnostics the response carried, or null
     */
    public record Turn(long runId, boolean ready, EvaluationModel model, long durationMs, Integer status,
            boolean succeeded, String error, TokenUsage usage, CacheDiagnostics cacheDiagnostics) {

        /**
         * A turn whose response carried no cache diagnostics.
         *
         * @param runId      the job run it is billed to
         * @param ready      true for a Ready (user-less) conversation
         * @param model      the model that answered
         * @param durationMs how long the call took
         * @param status     the HTTP status, or null when none was received
         * @param succeeded  whether the turn was a usable one
         * @param error      why not, or null
         * @param usage      the tokens the API reported, or null
         */
        public Turn(long runId, boolean ready, EvaluationModel model, long durationMs, Integer status,
                boolean succeeded, String error, TokenUsage usage) {
            this(runId, ready, model, durationMs, status, succeeded, error, usage, null);
        }
    }

    /**
     * Records a model turn: one {@code api_call_log} row and, for a typed turn, the run's cost
     * increment. Never throws. If the row cannot be written the turn is held and the latch is set (see
     * the class Javadoc); the cost is already known because it is priced here, with the same
     * calculation {@code JobRunService.logApiCall} uses, before any write is attempted.
     *
     * @param turn the turn
     */
    public void recordTurn(Turn turn) {
        long cost = costCalculator.calculateCostMicroDollars(turn.model(), turn.usage(), false);
        try {
            ApiCallLogEntity row = writeRow(turn);
            if (!turn.ready()) {
                Long recorded = row == null ? null : row.getCostMicroDollars();
                recordCost(turn.runId(), recorded == null ? cost : recorded);
            }
        } catch (RuntimeException e) {
            holder.hold(turn, cost, e);
        }
    }

    private ApiCallLogEntity writeRow(Turn t) {
        return jobRunService.logApiCall(t.runId(), ServiceName.ANTHROPIC, "POST",
                t.ready() ? URL_READY : URL_TYPED, null, t.durationMs(), t.status(), null, t.succeeded(),
                t.error(), t.model(), t.usage(), t.cacheDiagnostics());
    }

    /** The holder's window onto this service: the retried writes, and the memo drop on settle. */
    private final class HolderLedger implements UnrecordedTurnHolder.Ledger {

        @Override
        public void writeTurn(Turn turn) {
            writeRow(turn);
        }

        @Override
        public void writeOverflow(long runId, boolean typed, int turns, long cost) {
            apiCallLogRepository.save(summaryRow(runId, typed, turns, cost));
        }

        @Override
        public void settled(long runId, boolean ready, long cost) {
            // The persisted sum now includes this row, and it has left the holder in the same critical
            // section, so a reader sees it once.
            spendMemoAt = null;
            if (!ready) {
                recordCost(runId, cost);
            }
        }
    }

    private static ApiCallLogEntity summaryRow(long runId, boolean typed, int turns, long cost) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return ApiCallLogEntity.builder().jobRunId(runId).service(ServiceName.ANTHROPIC).calledAt(now)
                .completedAt(now).createdAt(now).durationMs(0L).requestMethod("POST")
                .requestUrl(typed ? URL_TYPED : URL_READY).succeeded(false)
                .errorMessage(turns + " turns' detail lost: the unrecorded-turn holder overflowed")
                .costMicroDollars(cost).build();
    }

    /**
     * Whether the engine may start another model turn: true when nothing is unrecorded, or when every
     * held turn has just been written (see {@link UnrecordedTurnHolder#available()}). Otherwise false
     * and the engine must make no call, for any conversation.
     *
     * @return true when every paid call is on record
     */
    public boolean accountingAvailable() {
        return holder.available();
    }
}
