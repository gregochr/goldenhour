package com.gregochr.goldenhour.service.batch;

import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.BatchCachePrimerProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.BluebellPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import com.gregochr.goldenhour.service.evaluation.WoodlandPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchCachePrimerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);

    /** A clock the fake sleeper advances, so the wait is measured without sleeping for real. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-10-05T01:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    private AnthropicBatchClient batchClient;
    private BatchRequestFactory factory;
    private BatchCachePrimerProperties properties;
    private MutableClock clock;
    private List<Duration> sleeps;
    private BatchCachePrimer primer;

    @BeforeEach
    void setUp() {
        batchClient = mock(AnthropicBatchClient.class);
        properties = new BatchCachePrimerProperties();
        properties.setEnabled(true);
        properties.setWaitSeconds(30);
        properties.setPollSeconds(10);
        factory = new BatchRequestFactory(new PromptBuilder(), new CoastalPromptBuilder(),
                new BluebellPromptBuilder(), new WoodlandPromptBuilder(new WoodlandVerdictEvaluator()),
                properties);
        clock = new MutableClock();
        sleeps = new java.util.ArrayList<>();
        primer = new BatchCachePrimer(factory, batchClient, properties, clock, d -> {
            sleeps.add(d);
            clock.advance(d);
        });
    }

    // ── prefix selection ─────────────────────────────────────────────────────

    @Test
    void nearAndFarInlandOnOneModel_onePrimer() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(),
                List.of(task(EvaluationModel.HAIKU, false)), List.of()));

        verify(batchClient, times(1)).createPrimerBatch(any());
        verify(batchClient, never()).createBatch(any());
    }

    @Test
    void inlandAndCoastal_twoPrimers() {
        stubCreate("b1", "b2");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b2", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        verify(batchClient, times(2)).createPrimerBatch(any());
    }

    @Test
    void mixedModels_onePrimerPerModelAndBuilderPair() {
        stubCreate("b1", "b2", "b3");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b2", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b3", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(List.of(List.of(task(EvaluationModel.SONNET, false)),
                List.of(task(EvaluationModel.SONNET, true)),
                List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, false))));

        ArgumentCaptor<BatchCreateParams> sent = ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchClient, times(3)).createPrimerBatch(sent.capture());
        assertThat(sent.getAllValues().stream()
                .map(p -> p.requests().get(0).params().model().asString()).toList())
                .containsExactly(EvaluationModel.SONNET.getModelId(), EvaluationModel.SONNET.getModelId(),
                        EvaluationModel.HAIKU.getModelId());
    }

    @Test
    void onlyNonSkyTasks_noPrimerAndNoClientCall() {
        primer.prime(List.of(
                List.of(task(EvaluationModel.HAIKU, false, EvaluationTask.Forecast.PromptKind.BLUEBELL)),
                List.of(task(EvaluationModel.HAIKU, false, EvaluationTask.Forecast.PromptKind.WOODLAND)),
                List.of(), List.of()));

        verifyNoInteractions(batchClient);
    }

    @Test
    void allBucketsEmpty_noClientCall() {
        primer.prime(List.of(List.of(), List.of(), List.of(), List.of()));

        verifyNoInteractions(batchClient);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void disabled_noClientCallAtAll() {
        properties.setEnabled(false);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of()));

        verifyNoInteractions(batchClient);
    }

    // ── what is sent ─────────────────────────────────────────────────────────

    @Test
    void primerRequest_sharesSystemBlockAndOutputConfigWithARealRequest_andIsNotAForecastId() {
        EvaluationTask.Forecast task = task(EvaluationModel.HAIKU, true);
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(List.of(List.of(), List.of(task), List.of(), List.of()));

        ArgumentCaptor<BatchCreateParams> sent = ArgumentCaptor.forClass(BatchCreateParams.class);
        verify(batchClient).createPrimerBatch(sent.capture());
        assertThat(sent.getValue().requests()).hasSize(1);
        BatchCreateParams.Request primerRequest = sent.getValue().requests().get(0);
        BatchCreateParams.Request real = factory.buildForecastRequestAndPrompt(
                "fc-42-2026-10-05-SUNRISE", task.model(), task.data(), task.model().getMaxTokens())
                .request();
        assertThat(primerRequest.params().system()).isEqualTo(real.params().system());
        assertThat(primerRequest.params().outputConfig()).isEqualTo(real.params().outputConfig());
        assertThat(primerRequest.params().model()).isEqualTo(real.params().model());
        assertThat(primerRequest.customId()).isEqualTo("pw-0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CustomIdFactory.parse(primerRequest.customId()));
    }

    // ── waiting ──────────────────────────────────────────────────────────────

    @Test
    void endsEarlyWhenAllPrimersHaveEnded() {
        stubCreate("b1", "b2");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b2", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        assertThat(sleeps).isEmpty();
        verify(batchClient, times(1)).retrieveBatch("b1");
        verify(batchClient, times(1)).retrieveBatch("b2");
    }

    @Test
    void keepsPollingUntilTheBatchEnds() {
        stubCreate("b1");
        MessageBatch running = batch(MessageBatch.ProcessingStatus.IN_PROGRESS, 0);
        MessageBatch ended = batch(MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.retrieveBatch("b1")).thenReturn(running, running, ended);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of()));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
        verify(batchClient, times(3)).retrieveBatch("b1");
    }

    @Test
    void stopsAtTheCapWhenAPrimerNeverEnds() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of()));

        assertThat(sleeps).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-05T01:00:30Z"));
        verify(batchClient, times(4)).retrieveBatch("b1");
    }

    @Test
    void lastSleepIsShortenedToTheTimeThatRemains() {
        properties.setWaitSeconds(25);
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of()));

        assertThat(sleeps).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }

    @Test
    void aPrimerWhoseStatusCannotBeReadDoesNotBlockTheOthers() {
        stubCreate("b1", "b2");
        when(batchClient.retrieveBatch("b1")).thenThrow(new IllegalStateException("503"));
        stubStatus("b2", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        assertThat(sleeps).isEmpty();
        verify(batchClient, times(1)).retrieveBatch("b1");
    }

    @Test
    void aCancelledOrExpiredPrimerDoesNotBlock() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 0);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of()));

        assertThat(sleeps).isEmpty();
        verify(batchClient, times(1)).retrieveBatch("b1");
    }

    @Test
    void aSubmitFailureForOnePrimerStillWaitsForTheOthers() {
        MessageBatch second = created("b2");
        when(batchClient.createPrimerBatch(any()))
                .thenThrow(new IllegalStateException("429"))
                .thenReturn(second);
        MessageBatch running = batch(MessageBatch.ProcessingStatus.IN_PROGRESS, 0);
        MessageBatch ended = batch(MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.retrieveBatch("b2")).thenReturn(running, ended);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
        verify(batchClient, times(2)).retrieveBatch("b2");
    }

    @Test
    void everySubmitFailing_returnsWithoutWaitingOrThrowing() {
        when(batchClient.createPrimerBatch(any())).thenThrow(new IllegalStateException("down"));

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        assertThat(sleeps).isEmpty();
        verify(batchClient, times(2)).createPrimerBatch(any());
        verify(batchClient, never()).retrieveBatch(any());
    }

    @Test
    void anUnexpectedExceptionNeverEscapes() {
        BatchRequestFactory broken = mock(BatchRequestFactory.class);
        when(broken.cachePrefixKey(any(), any())).thenThrow(new IllegalStateException("boom"));
        BatchCachePrimer brokenPrimer = new BatchCachePrimer(broken, batchClient, properties, clock,
                d -> { });

        brokenPrimer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(), List.of(), List.of()));

        verifyNoInteractions(batchClient);
    }

    @Test
    void interruptDuringTheWaitRestoresTheFlagAndReturns() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);
        BatchCachePrimer interrupted = new BatchCachePrimer(factory, batchClient, properties, clock,
                d -> {
                    throw new InterruptedException("stop");
                });

        try {
            interrupted.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                    List.of(), List.of(), List.of()));

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(batchClient, times(1)).retrieveBatch("b1");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aZeroPollIntervalIsTreatedAsOneSecond() {
        properties.setPollSeconds(0);
        properties.setWaitSeconds(2);
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);

        primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of()));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private void stubCreate(String... ids) {
        MessageBatch[] batches = new MessageBatch[ids.length];
        for (int i = 0; i < ids.length; i++) {
            batches[i] = created(ids[i]);
        }
        MessageBatch[] rest = java.util.Arrays.copyOfRange(batches, 1, batches.length);
        when(batchClient.createPrimerBatch(any())).thenReturn(batches[0], rest);
    }

    private static MessageBatch created(String id) {
        MessageBatch batch = mock(MessageBatch.class);
        when(batch.id()).thenReturn(id);
        return batch;
    }

    private void stubStatus(String id, MessageBatch.ProcessingStatus status, long succeeded) {
        MessageBatch batch = batch(status, succeeded);
        when(batchClient.retrieveBatch(eq(id))).thenReturn(batch);
    }

    private static MessageBatch batch(MessageBatch.ProcessingStatus status, long succeeded) {
        MessageBatch batch = mock(MessageBatch.class);
        MessageBatchRequestCounts counts = mock(MessageBatchRequestCounts.class);
        when(batch.processingStatus()).thenReturn(status);
        when(batch.requestCounts()).thenReturn(counts);
        when(counts.succeeded()).thenReturn(succeeded);
        when(counts.errored()).thenReturn(0L);
        when(counts.canceled()).thenReturn(0L);
        when(counts.expired()).thenReturn(0L);
        return batch;
    }

    private static EvaluationTask.Forecast task(EvaluationModel model, boolean coastal) {
        return task(model, coastal, EvaluationTask.Forecast.PromptKind.SKY);
    }

    private static EvaluationTask.Forecast task(EvaluationModel model, boolean coastal,
            EvaluationTask.Forecast.PromptKind kind) {
        LocationEntity location = new LocationEntity();
        location.setId(42L);
        location.setName("Durham UK");
        var data = TestAtmosphericData.builder()
                .locationName("Durham UK")
                .solarEventTime(LocalDateTime.of(2026, 10, 5, 6, 30))
                .targetType(TargetType.SUNRISE);
        if (coastal) {
            data.tide(new TideSnapshot(TideState.MID, LocalDateTime.of(2026, 6, 21, 19, 30),
                    new BigDecimal("4.20"), LocalDateTime.of(2026, 6, 21, 13, 15),
                    new BigDecimal("1.10"), false, LocalDateTime.of(2026, 6, 21, 19, 30),
                    LocalDateTime.of(2026, 6, 21, 13, 15), LunarTideType.REGULAR_TIDE, "First Quarter",
                    false, TideStatisticalSize.EXTRA_HIGH));
        }
        AtmosphericData atmospheric = data.build();
        return new EvaluationTask.Forecast(location, DATE, TargetType.SUNRISE, model, atmospheric,
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE, kind);
    }
}
