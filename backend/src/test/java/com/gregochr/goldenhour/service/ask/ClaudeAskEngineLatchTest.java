package com.gregochr.goldenhour.service.ask;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.CostCalculator;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine, the real {@link AnthropicApiClient} (un-proxied, so its gate is exercised exactly as in
 * production) and the real {@link AskJobRunService}, with only the SDK and the database mocked. Proves
 * the check-then-act gap is closed: a conversation that PASSED the engine's early accounting check
 * must still not make its first paid call if another conversation latches in between.
 */
class ClaudeAskEngineLatchTest {

    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final MessageService messages = mock(MessageService.class);
    private final JobRunService jobRunService = mock(JobRunService.class);
    private final JobRunRepository jobRunRepository = mock(JobRunRepository.class);
    private final ApiCallLogRepository apiCalls = mock(ApiCallLogRepository.class);
    private final AtomicBoolean databaseUp = new AtomicBoolean(true);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private AskJobRunService accounting;
    private ClaudeAskEngine engine;

    @BeforeEach
    void setUp() {
        AnthropicClient shared = mock(AnthropicClient.class);
        AnthropicClient derived = mock(AnthropicClient.class);
        when(shared.withOptions(any())).thenReturn(derived);
        when(derived.messages()).thenReturn(messages);
        CostCalculator costCalculator = mock(CostCalculator.class);
        when(costCalculator.calculateCostMicroDollars(any(), any(), anyBoolean())).thenReturn(1_000L);
        when(jobRunService.logApiCall(anyLong(), any(), any(), any(), any(), anyLong(), any(), any(),
                anyBoolean(), any(), any(), nullable(TokenUsage.class),
                nullable(CacheDiagnostics.class))).thenAnswer(inv -> {
                    if (!databaseUp.get()) {
                        throw new IllegalStateException("db down");
                    }
                    return ApiCallLogEntity.builder().costMicroDollars(1_000L).build();
                });
        MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));
        accounting = new AskJobRunService(jobRunService, jobRunRepository, apiCalls, costCalculator,
                new AskProperties(), clock);
        engine = new ClaudeAskEngine(new AnthropicApiClient(shared), new AskProperties(), accounting,
                mock(DriveTimeResolver.class), mock(RegionRepository.class), new AskAnswerValidator(),
                new AskPromptBuilder(), new ObjectMapper(), clock);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
        pool.shutdownNow();
    }

    private static AskSnapshot snapshot() {
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(AskFixtures.TODAY, null,
                AskFixtures.region("Northumberland", true, AskFixtures.slot(1L, "Bamburgh", 5)))), List.of()));
    }

    private static AskQuestion question() {
        return new AskQuestion("Best spot tonight?", "best spot tonight", null, List.of(), "plan");
    }

    private static AskJobRunService.Turn failingTurn() {
        return new AskJobRunService.Turn(5L, false, EvaluationModel.HAIKU, 10, 200, true, null,
                new TokenUsage(10, 5, 0, 0));
    }

    @Test
    @DisplayName("A passes the early check, B's write fails and latches, and A's first call is NOT made: the gate "
            + "inside the call refuses it and A returns the accounting reason")
    void aConversationThatPassedTheEarlyCheckStillMakesNoCall() throws Exception {
        CountDownLatch aIsPastTheEarlyCheck = new CountDownLatch(1);
        CountDownLatch bHasLatched = new CountDownLatch(1);
        // A's daily-run lookup comes after the early check and before the call: hold it there.
        when(jobRunRepository.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenAnswer(inv -> {
                    aIsPastTheEarlyCheck.countDown();
                    assertThat(bHasLatched.await(10, TimeUnit.SECONDS)).isTrue();
                    return Optional.of(JobRunEntity.builder().id(7L).runType(RunType.ASK)
                            .startedAt(LocalDateTime.of(2026, 10, 5, 8, 0)).build());
                });

        Future<AskRun> a = pool.submit(() -> engine.run(question(), snapshot(), USER, AskRunOptions.none()));
        assertThat(aIsPastTheEarlyCheck.await(10, TimeUnit.SECONDS)).isTrue();

        databaseUp.set(false);
        accounting.recordTurn(failingTurn());
        bHasLatched.countDown();

        AskRun run = a.get(10, TimeUnit.SECONDS);
        assertThat(run.accountingUnavailable()).isTrue();
        assertThat(run.outcome().turns()).isZero();
        verify(messages, never()).create(any(MessageCreateParams.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("the unlatched path makes exactly the expected calls: a tool turn then the answer is two "
            + "requests")
    void unlatchedPathMakesTheExpectedCalls() {
        when(jobRunRepository.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenReturn(Optional.of(JobRunEntity.builder().id(7L).runType(RunType.ASK)
                        .startedAt(LocalDateTime.of(2026, 10, 5, 8, 0)).build()));
        when(messages.create(any(MessageCreateParams.class), any(RequestOptions.class))).thenReturn(
                AskMessages.toolTurn(AskMessages.tool("t", "list_windows", Map.of())),
                AskMessages.submit(Map.of("answerable", false, "summary", "Can't tell.")));

        AskRun run = engine.run(question(), snapshot(), USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(run.outcome().turns()).isEqualTo(2);
        verify(messages, times(2)).create(any(MessageCreateParams.class), any(RequestOptions.class));
    }

    @Test
    @DisplayName("a turn whose own write fails stops the NEXT turn of the same conversation: it is refused "
            + "before a second request is made")
    void theNextTurnOfTheSameConversationIsGated() {
        when(jobRunRepository.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(any(), any()))
                .thenReturn(Optional.of(JobRunEntity.builder().id(7L).runType(RunType.ASK)
                        .startedAt(LocalDateTime.of(2026, 10, 5, 8, 0)).build()));
        when(messages.create(any(MessageCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            // The first answer arrives, and its row cannot be written.
            databaseUp.set(false);
            return AskMessages.toolTurn(AskMessages.tool("t", "list_windows", Map.of()));
        });

        AskRun run = engine.run(question(), snapshot(), USER, AskRunOptions.none());

        assertThat(run.accountingUnavailable()).isTrue();
        assertThat(run.outcome().turns()).isEqualTo(1);
        verify(messages, times(1)).create(any(MessageCreateParams.class), any(RequestOptions.class));
        assertThat(accounting.typedSpendTodayMicroDollars()).isEqualTo(1_000L);
    }
}
