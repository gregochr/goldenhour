package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.JobRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AskJobRunService}: the daily run, the cost increments and the spend sum. */
class AskJobRunServiceTest {

    /** Midday UTC on a BST day (UK midnight was 23:00 UTC the evening before). */
    private static final Instant BST_NOON = Instant.parse("2026-10-05T12:00:00Z");

    private final JobRunService jobRunService = mock(JobRunService.class);
    private final JobRunRepository jobRuns = mock(JobRunRepository.class);
    private final ApiCallLogRepository apiCalls = mock(ApiCallLogRepository.class);
    private final AskProperties properties = new AskProperties();
    private MutableClock clock;
    private AskJobRunService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BST_NOON);
        service = new AskJobRunService(jobRunService, jobRuns, apiCalls, mock(CostCalculator.class), properties, clock);
    }

    private static JobRunEntity run(long id) {
        return JobRunEntity.builder().id(id).runType(RunType.ASK).startedAt(LocalDateTime.of(2026, 10, 5, 8, 0))
                .build();
    }

    private void noRunToday() {
        when(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    // -- the daily run ----------------------------------------------------------------------

    @Test
    @DisplayName("the first call of a UK day creates an ASK run (manual, the configured model), completes it "
            + "at once, and every later call that day reuses it without another lookup")
    void createsOnceAndCaches() {
        properties.setModel(EvaluationModel.SONNET);
        noRunToday();
        JobRunEntity created = run(41);
        when(jobRunService.startRun(RunType.ASK, true, EvaluationModel.SONNET)).thenReturn(created);

        assertThat(service.dailyRunId()).isEqualTo(41L);
        assertThat(service.dailyRunId()).isEqualTo(41L);
        assertThat(service.dailyRunId()).isEqualTo(41L);

        verify(jobRunService, times(1)).startRun(RunType.ASK, true, EvaluationModel.SONNET);
        verify(jobRunService).completeRun(created, 0, 0);
        verify(jobRuns, times(1)).findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any());
    }

    @Test
    @DisplayName("a run already started today (before a restart, say) is found, not duplicated")
    void findsTodaysExistingRun() {
        when(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenReturn(Optional.of(run(7)));

        assertThat(service.dailyRunId()).isEqualTo(7L);

        verify(jobRunService, never()).startRun(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("the lookup is for runs started since the START of the UK day: 23:00 UTC the evening before "
            + "in BST, 00:00 UTC in GMT")
    void sinceIsTheStartOfTheUkDay() {
        noRunToday();
        when(jobRunService.startRun(any(), anyBoolean(), any())).thenReturn(run(1));
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);

        service.dailyRunId();
        clock.set(Instant.parse("2026-12-01T12:00:00Z"));
        service.dailyRunId();

        verify(jobRuns, times(2)).findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(
                any(), since.capture());
        assertThat(since.getAllValues()).containsExactly(LocalDateTime.of(2026, 10, 4, 23, 0),
                LocalDateTime.of(2026, 12, 1, 0, 0));
    }

    @Test
    @DisplayName("the UK day rolls over at UK midnight, not UTC midnight: 22:59:59 UTC is still the same BST "
            + "day, 23:00:00 UTC is the next one")
    void aNewUkDayGetsANewRun() {
        noRunToday();
        when(jobRunService.startRun(any(), anyBoolean(), any()))
                .thenReturn(run(1), run(2));

        clock.set(Instant.parse("2026-10-05T22:59:59Z"));
        assertThat(service.dailyRunId()).isEqualTo(1L);
        assertThat(service.dailyRunId()).isEqualTo(1L);

        clock.set(Instant.parse("2026-10-05T23:00:00Z"));
        assertThat(service.dailyRunId()).isEqualTo(2L);
        verify(jobRunService, times(2)).startRun(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("fail closed: a database that cannot find or make the run propagates, so no model call is made "
            + "against it (and nothing is cached, so the next call tries again)")
    void failureCreatingTheRunPropagates() {
        when(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Optional.of(run(9)));

        assertThatThrownBy(service::dailyRunId).isInstanceOf(IllegalStateException.class);
        assertThat(service.dailyRunId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("two first questions at once create ONE run: the second request runs on another thread, "
            + "so the lock it needs is genuinely contended")
    void concurrentFirstQuestionsCreateOneRun() throws Exception {
        AtomicReference<JobRunEntity> stored = new AtomicReference<>();
        AtomicInteger created = new AtomicInteger();
        CountDownLatch insideCreate = new CountDownLatch(1);
        when(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(jobRunService.startRun(any(), anyBoolean(), any())).thenAnswer(inv -> {
            created.incrementAndGet();
            insideCreate.countDown();
            Thread.sleep(300);
            JobRunEntity run = run(100);
            stored.set(run);
            return run;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> ask = service::dailyRunId;
            Future<Long> first = pool.submit(ask);
            assertThat(insideCreate.await(5, TimeUnit.SECONDS)).as("the first thread is creating").isTrue();
            Future<Long> second = pool.submit(ask);

            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(100L);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(100L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(created.get()).as("runs created").isEqualTo(1);
    }

    // -- cost and counts --------------------------------------------------------------------

    @Test
    @DisplayName("a call's cost is added to its run; zero and negative costs are ignored")
    void recordCost() {
        service.recordCost(5, 1_234);
        service.recordCost(5, 0);
        service.recordCost(5, -1);

        verify(jobRuns).addCostMicroDollars(5L, 1_234L);
        verify(jobRuns, times(1)).addCostMicroDollars(anyLong(), anyLong());
    }

    @Test
    @DisplayName("a failure adding cost or counting a question is logged and swallowed: the call already "
            + "happened and is in api_call_log, so the answer is not thrown away")
    void incrementFailuresAreSwallowed() {
        doThrow(new IllegalStateException("db")).when(jobRuns).addCostMicroDollars(anyLong(), anyLong());
        doThrow(new IllegalStateException("db")).when(jobRuns).addQuestionOutcome(anyLong(),
                anyInt(), anyInt());

        service.recordCost(5, 10);
        service.recordQuestion(5, true);
    }

    @Test
    @DisplayName("an answered question counts as succeeded, a failed one as failed")
    void recordQuestion() {
        service.recordQuestion(5, true);
        service.recordQuestion(6, false);

        verify(jobRuns).addQuestionOutcome(5L, 1, 0);
        verify(jobRuns).addQuestionOutcome(6L, 0, 1);
    }

    // -- the spend sum ----------------------------------------------------------------------

    @Test
    @DisplayName("typed spend is the ASK sum since the start of the UK day, and is reused for 30 seconds: "
            + "29.999s reuses it, 30s asks again")
    void typedSpendIsMemoisedFor30Seconds() {
        when(apiCalls.sumCostMicroDollarsByRunTypeStartedSince(any(), any())).thenReturn(1_000L, 2_000L, 3_000L);

        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(1_000L);
        clock.advance(Duration.ofMillis(29_999));
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(1_000L);
        clock.advance(Duration.ofMillis(1));
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(2_000L);
        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(2_000L);

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(apiCalls, times(2)).sumCostMicroDollarsByRunTypeStartedSince(
                eq(RunType.ASK), since.capture());
        assertThat(since.getAllValues()).containsOnly(LocalDateTime.of(2026, 10, 4, 23, 0));
    }

    @Test
    @DisplayName("a new UK day never reuses yesterday's figure, even inside the 30 seconds")
    void typedSpendIsNotCarriedAcrossUkMidnight() {
        when(apiCalls.sumCostMicroDollarsByRunTypeStartedSince(any(), any())).thenReturn(900_000L, 0L);
        clock.set(Instant.parse("2026-10-05T22:59:50Z"));

        assertThat(service.typedSpendTodayMicroDollars()).isEqualTo(900_000L);
        clock.set(Instant.parse("2026-10-05T23:00:05Z"));
        assertThat(service.typedSpendTodayMicroDollars()).isZero();
    }

    @Test
    @DisplayName("construction touches nothing")
    void constructionIsInert() {
        verifyNoInteractions(jobRunService, jobRuns, apiCalls);
    }
}
