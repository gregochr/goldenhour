package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fail closed on cost logging: a paid call whose {@code api_call_log} write fails is held, counted in
 * the typed spend at once, latches every conversation closed, and is written (exactly once) when the
 * database recovers. A small in-memory "database" stands behind the mocks so the figure the cap reads
 * can be compared before and after a flush.
 */
class AskUnrecordedCostTest {

    private static final long COST = 1_000L;
    private static final long RUN = 5L;
    private static final long READY_RUN = 9L;

    private final JobRunService jobRunService = mock(JobRunService.class);
    private final JobRunRepository jobRuns = mock(JobRunRepository.class);
    private final ApiCallLogRepository apiCalls = mock(ApiCallLogRepository.class);
    private final CostCalculator costCalculator = mock(CostCalculator.class);
    private final AtomicBoolean databaseUp = new AtomicBoolean(true);
    /** What the database holds for ASK runs: the figure the persisted sum returns. */
    private final AtomicLong persistedTyped = new AtomicLong();
    private final AtomicInteger rowsWritten = new AtomicInteger();
    private MutableClock clock;
    private AskJobRunService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
        service = new AskJobRunService(jobRunService, jobRuns, apiCalls, costCalculator, new AskProperties(),
                clock);
        when(costCalculator.calculateCostMicroDollars(any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1) == null ? 0L : COST);
        when(jobRunService.logApiCall(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(),
                anyBoolean(), any(), any(), any())).thenAnswer(inv -> {
                    if (!databaseUp.get()) {
                        throw new IllegalStateException("db down");
                    }
                    long cost = inv.getArgument(11) == null ? 0L : COST;
                    String tag = inv.getArgument(3);
                    rowsWritten.incrementAndGet();
                    if ("ask".equals(tag)) {
                        persistedTyped.addAndGet(cost);
                    }
                    return ApiCallLogEntity.builder().costMicroDollars(cost).build();
                });
        when(apiCalls.sumCostMicroDollarsByRunTypeStartedSince(eq(RunType.ASK), any()))
                .thenAnswer(inv -> persistedTyped.get());
        when(apiCalls.save(any(ApiCallLogEntity.class))).thenAnswer(inv -> {
            if (!databaseUp.get()) {
                throw new IllegalStateException("db down");
            }
            ApiCallLogEntity row = inv.getArgument(0);
            if ("ask".equals(row.getRequestUrl())) {
                persistedTyped.addAndGet(row.getCostMicroDollars());
            }
            rowsWritten.incrementAndGet();
            return row;
        });
    }

    private static AskJobRunService.Turn typed() {
        return new AskJobRunService.Turn(RUN, false, EvaluationModel.HAIKU, 12, 200, true, null,
                new TokenUsage(10, 5, 0, 0));
    }

    private static AskJobRunService.Turn typedNoUsage() {
        return new AskJobRunService.Turn(RUN, false, EvaluationModel.HAIKU, 12, null, false, "timeout", null);
    }

    private static AskJobRunService.Turn ready() {
        return new AskJobRunService.Turn(READY_RUN, true, EvaluationModel.HAIKU, 12, 200, true, null,
                new TokenUsage(10, 5, 0, 0));
    }

    // -- the normal path ---------------------------------------------------------------------

    @Test
    @DisplayName("a turn that writes is on record, bumps the run's cost for display, and latches nothing")
    void normalWrite() {
        service.recordTurn(typed());

        assertThat(service.accountingAvailable()).isTrue();
        assertThat(rowsWritten.get()).isEqualTo(1);
        verify(jobRuns).addCostMicroDollars(RUN, COST);
    }

    @Test
    @DisplayName("a Ready turn that writes does not touch any job run's cost column")
    void readyWriteDoesNotIncrement() {
        service.recordTurn(ready());

        verify(jobRuns, never()).addCostMicroDollars(anyLong(), anyLong());
    }

    // -- a failed write ---------------------------------------------------------------------

    @Test
    @DisplayName("a failed write is in the typed spend at once, memo bypassed: the figure was memoised a moment "
            + "ago and still moves by the full cost")
    void failedWriteIsCountedImmediately() {
        persistedTyped.set(5_000);
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(5_000L);

        databaseUp.set(false);
        service.recordTurn(typed());

        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(5_000L + COST);
        assertThat(service.accountingAvailable()).as("latched, and the database is still down").isFalse();
        verify(jobRuns, never()).addCostMicroDollars(anyLong(), anyLong());
    }

    @Test
    @DisplayName("when the database comes back the next check writes the held turn exactly once, clears the latch, "
            + "and the cap's figure is the same before and after (never counted as both held and persisted)")
    void flushWritesOnceAndTheFigureDoesNotMove() {
        persistedTyped.set(5_000);
        service.typedSpendTodayMicroDollars();
        databaseUp.set(false);
        service.recordTurn(typed());
        long before = service.typedSpendTodayMicroDollars();
        databaseUp.set(true);
        int rowsBefore = rowsWritten.get();

        assertThat(service.accountingAvailable()).isTrue();
        assertThat(service.accountingAvailable()).isTrue();

        assertThat(rowsWritten.get() - rowsBefore).as("rows written by the flush").isEqualTo(1);
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(before).isEqualTo(5_000L + COST);
        verify(jobRuns, times(1)).addCostMicroDollars(RUN, COST);
    }

    @Test
    @DisplayName("while the database stays down every check says no, the held cost is counted once however many "
            + "checks fail, and nothing is written")
    void keepsFailingStaysLatched() {
        databaseUp.set(false);
        service.recordTurn(typed());

        for (int i = 0; i < 5; i++) {
            assertThat(service.accountingAvailable()).isFalse();
        }

        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(COST);
        assertThat(rowsWritten.get()).isZero();
    }

    @Test
    @DisplayName("a partial flush writes what it can in order, stays latched, and the figure never moves: "
            + "three held, one written, two still held")
    void partialFlush() {
        databaseUp.set(false);
        for (int i = 0; i < 3; i++) {
            service.recordTurn(typed());
        }
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(3 * COST);
        AtomicInteger allowed = new AtomicInteger(1);
        doAnswer(inv -> {
            if (allowed.getAndDecrement() <= 0) {
                throw new IllegalStateException("db down again");
            }
            persistedTyped.addAndGet(COST);
            rowsWritten.incrementAndGet();
            return ApiCallLogEntity.builder().costMicroDollars(COST).build();
        }).when(jobRunService).logApiCall(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(),
                anyBoolean(), any(), any(), any());

        assertThat(service.accountingAvailable()).isFalse();

        assertThat(rowsWritten.get()).isEqualTo(1);
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(3 * COST);
        verify(jobRuns, times(1)).addCostMicroDollars(RUN, COST);

        allowed.set(10);
        assertThat(service.accountingAvailable()).isTrue();
        assertThat(rowsWritten.get()).isEqualTo(3);
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(3 * COST);
    }

    @Test
    @DisplayName("a zero-cost turn (no usage came back) is held too, latches, adds nothing to the spend, and is "
            + "written on recovery")
    void zeroCostTurn() {
        databaseUp.set(false);
        service.recordTurn(typedNoUsage());

        assertThat(service.typedSpendTodayMicroDollars()).isZero();
        assertThat(service.accountingAvailable()).isFalse();

        databaseUp.set(true);
        assertThat(service.accountingAvailable()).isTrue();
        assertThat(rowsWritten.get()).isEqualTo(1);
        verify(jobRuns, never()).addCostMicroDollars(anyLong(), anyLong());
    }

    // -- the Ready path ---------------------------------------------------------------------

    @Test
    @DisplayName("an unrecordable Ready call latches the same latch, is not typed spend, and on recovery is "
            + "written without any typed increment")
    void readyPath() {
        databaseUp.set(false);
        service.recordTurn(ready());

        assertThat(service.typedSpendTodayMicroDollars()).isZero();
        assertThat(service.accountingAvailable()).isFalse();

        databaseUp.set(true);
        assertThat(service.accountingAvailable()).isTrue();
        assertThat(rowsWritten.get()).isEqualTo(1);
        assertThat(persistedTyped.get()).isZero();
        verify(jobRuns, never()).addCostMicroDollars(anyLong(), anyLong());
    }

    // -- the holder's bound ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} held")
    @ValueSource(ints = {AskJobRunService.UNRECORDED_CAP - 1, AskJobRunService.UNRECORDED_CAP})
    @DisplayName("the holder keeps 100 turns in detail: one under the cap and at it, every turn is its own row "
            + "on recovery and no summary row is needed")
    void holderBelowAndAtTheCap(int held) {
        databaseUp.set(false);
        for (int i = 0; i < held; i++) {
            service.recordTurn(typed());
        }
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(held * COST);

        databaseUp.set(true);
        assertThat(service.accountingAvailable()).isTrue();

        assertThat(rowsWritten.get()).isEqualTo(held);
        verify(apiCalls, never()).save(any(ApiCallLogEntity.class));
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(held * COST);
    }

    @Test
    @DisplayName("one over the cap and well over: cost is never dropped, only per-turn detail; the overflow is "
            + "one summary row carrying the summed cost, and the latch holds until it is written")
    void holderOverflowKeepsTheSum() {
        databaseUp.set(false);
        int total = AskJobRunService.UNRECORDED_CAP + 1;
        for (int i = 0; i < total; i++) {
            service.recordTurn(typed());
        }
        for (int i = 0; i < 5; i++) {
            service.recordTurn(ready());
        }
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(total * COST);

        // Detail recovers but the summary write still fails: stays latched, the sum is still counted.
        databaseUp.set(true);
        doThrow(new IllegalStateException("summary fails")).when(apiCalls).save(any(ApiCallLogEntity.class));
        assertThat(service.accountingAvailable()).isFalse();
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(total * COST);

        doAnswer(inv -> {
            ApiCallLogEntity row = inv.getArgument(0);
            if ("ask".equals(row.getRequestUrl())) {
                persistedTyped.addAndGet(row.getCostMicroDollars());
            }
            return row;
        }).when(apiCalls).save(any(ApiCallLogEntity.class));
        assertThat(service.accountingAvailable()).isTrue();
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(total * COST);
        // One failed attempt on the typed summary, then the typed and the Ready summaries.
        verify(apiCalls, times(3)).save(any(ApiCallLogEntity.class));
    }

    @Test
    @DisplayName("the latch is reported at ERROR once per latch, not once per failed write or per refused "
            + "request; a later latch is reported again")
    void errorIsLoggedOncePerLatch() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(AskJobRunService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            databaseUp.set(false);
            for (int i = 0; i < 4; i++) {
                service.recordTurn(typed());
                service.accountingAvailable();
            }
            assertThat(errors(appender)).isEqualTo(1);

            databaseUp.set(true);
            assertThat(service.accountingAvailable()).isTrue();
            databaseUp.set(false);
            service.recordTurn(typed());
            service.recordTurn(typed());

            assertThat(errors(appender)).as("a second latch is reported once more").isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long errors(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        return a.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                && e.getFormattedMessage().contains("paid model turn")).count();
    }

    // -- display-only increments ------------------------------------------------------------

    @Test
    @DisplayName("the job_run increments are display-only: a failure there neither latches nor changes what the "
            + "cap reads")
    void jobRunIncrementFailuresCannotUndercountTheCap() {
        doThrow(new IllegalStateException("job_run locked")).when(jobRuns).addCostMicroDollars(anyLong(), anyLong());
        doThrow(new IllegalStateException("job_run locked")).when(jobRuns).addQuestionOutcome(anyLong(),
                anyInt(), anyInt());

        service.recordTurn(typed());
        service.recordQuestion(RUN, true);

        assertThat(service.accountingAvailable()).isTrue();
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(COST);
    }

    // -- concurrency ------------------------------------------------------------------------

    @Test
    @DisplayName("a second thread's conversation sees the latch the first thread's failed write set, and when "
            + "the database recovers two threads checking at once write the held turn exactly once")
    void latchIsSharedAcrossThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            databaseUp.set(false);
            pool.submit(() -> service.recordTurn(typed())).get(10, TimeUnit.SECONDS);

            Future<Boolean> other = pool.submit(service::accountingAvailable);
            assertThat(other.get(10, TimeUnit.SECONDS)).as("another thread must see the latch").isFalse();

            databaseUp.set(true);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> checks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                checks.add(pool.submit(() -> {
                    go.await();
                    return service.accountingAvailable();
                }));
            }
            go.countDown();
            for (Future<Boolean> check : checks) {
                assertThat(check.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowsWritten.get()).as("the held turn is written once, not once per thread").isEqualTo(1);
        verify(jobRuns, times(1)).addCostMicroDollars(RUN, COST);
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(COST);
    }

    @Test
    @DisplayName("a thread asking for the figure while another is mid-flush waits for it: it sees the held turn "
            + "or the persisted row, never both and never neither")
    void spendReadDuringAFlushIsConsistent() throws Exception {
        databaseUp.set(false);
        service.recordTurn(typed());
        databaseUp.set(true);
        CountDownLatch insideWrite = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        doAnswer(inv -> {
            insideWrite.countDown();
            finishWrite.await(10, TimeUnit.SECONDS);
            persistedTyped.addAndGet(COST);
            rowsWritten.incrementAndGet();
            return ApiCallLogEntity.builder().costMicroDollars(COST).build();
        }).when(jobRunService).logApiCall(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(),
                anyBoolean(), any(), any(), any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> flush = pool.submit(service::accountingAvailable);
            assertThat(insideWrite.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Long> read = pool.submit(service::typedSpendTodayMicroDollars);
            Thread.sleep(Duration.ofMillis(200));
            assertThat(read.isDone()).as("the reader waits for the flush").isFalse();

            finishWrite.countDown();
            assertThat(flush.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(read.get(10, TimeUnit.SECONDS)).isEqualTo(COST);
        } finally {
            finishWrite.countDown();
            pool.shutdownNow();
        }
    }
}
