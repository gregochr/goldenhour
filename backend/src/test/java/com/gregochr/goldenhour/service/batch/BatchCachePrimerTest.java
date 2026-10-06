package com.gregochr.goldenhour.service.batch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchRequestCounts;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.BatchCachePrimerProperties;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CacheDiagnosticsFixtures;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import com.gregochr.goldenhour.service.batch.BatchCachePrimer.Outcome;
import com.gregochr.goldenhour.service.batch.BatchCachePrimer.PrimeResult;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.BluebellPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.CoastalPromptBuilder;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.evaluation.PrimerMessageIds;
import com.gregochr.goldenhour.service.evaluation.PromptBuilder;
import com.gregochr.goldenhour.service.evaluation.WoodlandPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatchCachePrimerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 5);
    private static final String HAIKU = EvaluationModel.HAIKU.getModelId();
    private static final String SONNET = EvaluationModel.SONNET.getModelId();
    private static final Duration CALL = BatchCachePrimer.CALL_TIMEOUT;

    /** A clock the fake sleeper (and slow calls) advance, so waits are measured without sleeping. */
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
                new BluebellPromptBuilder(), new WoodlandPromptBuilder(new WoodlandVerdictEvaluator()));
        clock = new MutableClock();
        sleeps = new ArrayList<>();
        primer = new BatchCachePrimer(factory, batchClient, properties, clock, d -> {
            sleeps.add(d);
            clock.advance(d);
        });
    }

    // ── prefix selection and what is sent ────────────────────────────────────

    @Test
    void nearAndFarInlandOnOneModel_onePrimer_withInlandSystemTextAndOneHourLifetime() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);

        PrimeResult result = primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(), List.of(task(EvaluationModel.HAIKU, false)), List.of()));

        List<BatchCreateParams> sent = sentPrimers(1);
        BatchCreateParams.Request request = sent.get(0).requests().get(0);
        assertThat(request.params().model().asString()).isEqualTo(HAIKU);
        assertThat(systemText(request)).isEqualTo(new PromptBuilder().getSystemPrompt());
        assertThat(ttlOf(request)).contains(CacheControlEphemeral.Ttl.TTL_1H);
        assertThat(request.customId()).isEqualTo("pw-0");
        assertThatIllegalArgumentException().isThrownBy(() -> CustomIdFactory.parse(request.customId()));
        assertThat(result.warmedPrefixes()).containsExactly(HAIKU + "|inland");
        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.ENDED);
        verify(batchClient, never()).createBatch(sent.get(0));
    }

    @Test
    void inlandAndCoastal_twoPrimers_eachWithItsOwnBuildersSystemText() {
        stubCreate("b1", "b2");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b2", MessageBatch.ProcessingStatus.ENDED, 1);

        PrimeResult result = primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        List<BatchCreateParams> sent = sentPrimers(2);
        assertThat(systemText(sent.get(0).requests().get(0)))
                .isEqualTo(new PromptBuilder().getSystemPrompt());
        assertThat(systemText(sent.get(1).requests().get(0)))
                .isEqualTo(new CoastalPromptBuilder().getSystemPrompt());
        assertThat(ttlOf(sent.get(1).requests().get(0))).contains(CacheControlEphemeral.Ttl.TTL_1H);
        assertThat(result.warmedPrefixes())
                .containsExactlyInAnyOrder(HAIKU + "|inland", HAIKU + "|coastal");
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

        List<BatchCreateParams> sent = sentPrimers(3);
        assertThat(sent.stream().map(p -> p.requests().get(0).params().model().asString()).toList())
                .containsExactly(SONNET, SONNET, HAIKU);
        assertThat(sent.stream().map(p -> systemText(p.requests().get(0))).toList())
                .containsExactly(new PromptBuilder().getSystemPrompt(),
                        new CoastalPromptBuilder().getSystemPrompt(),
                        new PromptBuilder().getSystemPrompt());
    }

    @Test
    void aBucketHoldingTwoModels_getsAPrimerForEach() {
        stubCreate("b1", "b2");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b2", MessageBatch.ProcessingStatus.ENDED, 1);

        PrimeResult result = primer.prime(java.util.Arrays.asList(
                List.of(task(EvaluationModel.HAIKU, false), task(EvaluationModel.SONNET, false)),
                List.of(), List.of(), List.of()));

        assertThat(sentPrimers(2).stream()
                .map(p -> p.requests().get(0).params().model().asString()).toList())
                .containsExactly(HAIKU, SONNET);
        assertThat(result.warmedPrefixes())
                .containsExactlyInAnyOrder(HAIKU + "|inland", SONNET + "|inland");
    }

    @Test
    void aNonSkyTaskBeforeASkyTask_doesNotHideThePrefix() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);

        PrimeResult result = primer.prime(List.of(
                List.of(task(EvaluationModel.HAIKU, false, EvaluationTask.Forecast.PromptKind.BLUEBELL),
                        task(EvaluationModel.SONNET, false)),
                List.of(), List.of(), List.of()));

        assertThat(sentPrimers(1).get(0).requests().get(0).params().model().asString())
                .isEqualTo(SONNET);
        assertThat(result.warmedPrefixes()).containsExactly(SONNET + "|inland");
    }

    @Test
    void aNullBucketIsIgnored() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);

        PrimeResult result = primer.prime(Arrays.asList(null, List.of(task(EvaluationModel.HAIKU, false)),
                null, List.of()));

        assertThat(result.warmedPrefixes()).containsExactly(HAIKU + "|inland");
    }

    @Test
    void onlyNonSkyTasks_noPrimerAndNoClientCall() {
        PrimeResult result = primer.prime(List.of(
                List.of(task(EvaluationModel.HAIKU, false, EvaluationTask.Forecast.PromptKind.BLUEBELL)),
                List.of(task(EvaluationModel.HAIKU, false, EvaluationTask.Forecast.PromptKind.WOODLAND)),
                List.of(), List.of()));

        assertThat(result.warmedPrefixes()).isEmpty();
        verifyNoInteractions(batchClient);
    }

    @Test
    void allBucketsEmpty_noClientCall() {
        PrimeResult result = primer.prime(List.of(List.of(), List.of(), List.of(), List.of()));

        assertThat(result.warmedPrefixes()).isEmpty();
        verifyNoInteractions(batchClient);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void disabled_noClientCallAtAll() {
        properties.setEnabled(false);

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(result.warmedPrefixes()).isEmpty();
        verifyNoInteractions(batchClient);
    }

    @Test
    void waitSecondsZero_meansDoNotPrime() {
        properties.setWaitSeconds(0);

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(result.warmedPrefixes()).isEmpty();
        verifyNoInteractions(batchClient);
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
        verify(batchClient, times(1)).retrieveBatch("b1", CALL);
        verify(batchClient, times(1)).retrieveBatch("b2", CALL);
    }

    @Test
    void keepsPollingUntilTheBatchEnds() {
        stubCreate("b1");
        MessageBatch running = batch(MessageBatch.ProcessingStatus.IN_PROGRESS, 0);
        MessageBatch ended = batch(MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.retrieveBatch(anyString(), any(Duration.class)))
                .thenReturn(running, running, ended);

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertThat(readTimeouts("b1", 3)).containsExactly(CALL, CALL, Duration.ofSeconds(10));
        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.ENDED);
    }

    @Test
    void stopsAtTheCap_timedOut_andNothingIsWarmed() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(sleeps).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-05T01:00:30Z"));
        // reads at t0, t10, t20 (the last shortened to the 10s left); none at t30, the deadline
        assertThat(readTimeouts("b1", 3)).containsExactly(CALL, CALL, Duration.ofSeconds(10));
        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.TIMED_OUT);
        assertThat(result.warmedPrefixes()).isEmpty();
    }

    @Test
    void lastSleepAndLastCallTimeoutAreShortenedToTheTimeThatRemains() {
        properties.setWaitSeconds(25);
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);

        primer.prime(oneInlandBucket());

        assertThat(sleeps).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5));
        assertThat(readTimeouts("b1", 3)).containsExactly(CALL, CALL, Duration.ofSeconds(5));
    }

    @Test
    void callTimeoutNeverExceedsTheTimeLeft() {
        properties.setWaitSeconds(8);
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);

        primer.prime(oneInlandBucket());

        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(batchClient).createPrimerBatch(sentCaptor().capture(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(Duration.ofSeconds(8));
        verify(batchClient).retrieveBatch("b1", Duration.ofSeconds(8));
    }

    @Test
    void aReadThatThrowsTwiceThenReportsEnded_isEndedAndWarmed() {
        stubCreate("b1");
        MessageBatch ended = batch(MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.retrieveBatch(anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("429"))
                .thenThrow(new IllegalStateException("503"))
                .thenReturn(ended);

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.ENDED);
        assertThat(result.warmedPrefixes()).containsExactly(HAIKU + "|inland");
        assertThat(result.unreadablePolls()).isEqualTo(2);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void aReadThatThrowsUntilTheDeadline_isTimedOut() {
        stubCreate("b1");
        when(batchClient.retrieveBatch(anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("503"));

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.TIMED_OUT);
        assertThat(result.warmedPrefixes()).isEmpty();
        assertThat(result.unreadablePolls()).isEqualTo(3);
    }

    @Test
    void aCancelledOrExpiredPrimer_isFailed_notWarmed_andDoesNotBlock() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 0);

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.FAILED);
        assertThat(result.warmedPrefixes()).isEmpty();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void noCreateOrReadIsIssuedAfterTheDeadline() {
        properties.setWaitSeconds(30);
        // each create takes 20 seconds
        when(batchClient.createPrimerBatch(any(BatchCreateParams.class), any(Duration.class)))
                .thenAnswer(inv -> {
                    clock.advance(Duration.ofSeconds(20));
                    return created("b" + clock.instant().getEpochSecond());
                });

        PrimeResult result = primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(task(EvaluationModel.SONNET, false)),
                List.of()));

        // t0 create (to t20), t20 create (to t40); the third prefix finds the cap passed
        ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
        verify(batchClient, times(2)).createPrimerBatch(sentCaptor().capture(), timeouts.capture());
        assertThat(timeouts.getAllValues()).containsExactly(CALL, Duration.ofSeconds(10));
        verify(batchClient, never()).retrieveBatch(anyString(), eq(CALL));
        verify(batchClient, never()).retrieveBatch(anyString(), eq(Duration.ofSeconds(10)));
        assertThat(result.outcomes()).containsEntry(SONNET + "|inland", Outcome.TIMED_OUT);
        assertThat(result.warmedPrefixes()).isEmpty();
    }

    @Test
    void aSubmitFailureForOnePrimerStillWaitsForTheOthers() {
        MessageBatch second = created("b2");
        when(batchClient.createPrimerBatch(any(BatchCreateParams.class), any(Duration.class)))
                .thenThrow(new IllegalStateException("429"))
                .thenReturn(second);
        MessageBatch running = batch(MessageBatch.ProcessingStatus.IN_PROGRESS, 0);
        MessageBatch ended = batch(MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.retrieveBatch(eq("b2"), any(Duration.class))).thenReturn(running, ended);

        PrimeResult result = primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(10));
        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.FAILED)
                .containsEntry(HAIKU + "|coastal", Outcome.ENDED);
        assertThat(result.warmedPrefixes()).containsExactly(HAIKU + "|coastal");
    }

    @Test
    void everySubmitFailing_returnsFailedWithoutWaitingOrThrowing() {
        when(batchClient.createPrimerBatch(any(BatchCreateParams.class), any(Duration.class)))
                .thenThrow(new IllegalStateException("down"));

        PrimeResult result = primer.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

        assertThat(sleeps).isEmpty();
        assertThat(result.warmedPrefixes()).isEmpty();
        assertThat(result.outcomes().values()).containsExactly(Outcome.FAILED, Outcome.FAILED);
        verify(batchClient, never()).retrieveBatch(anyString(), eq(CALL));
    }

    @Test
    void anUnexpectedExceptionNeverEscapes_andNothingIsWarmed() {
        BatchRequestFactory broken = mock(BatchRequestFactory.class);
        when(broken.cachePrefixKey(any(EvaluationModel.class), any(AtmosphericData.class)))
                .thenThrow(new IllegalStateException("boom"));
        BatchCachePrimer brokenPrimer = new BatchCachePrimer(broken, batchClient, properties, clock,
                d -> { });

        PrimeResult result = brokenPrimer.prime(oneInlandBucket());

        assertThat(result.warmedPrefixes()).isEmpty();
        verifyNoInteractions(batchClient);
    }

    @Test
    void interruptDuringTheWait_isInterrupted_restoresTheFlag_andWarmsNothing() {
        stubCreate("b1", "b2");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        stubStatus("b2", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);
        BatchCachePrimer interrupted = new BatchCachePrimer(factory, batchClient, properties, clock,
                d -> {
                    throw new InterruptedException("stop");
                });

        try {
            PrimeResult result = interrupted.prime(List.of(List.of(task(EvaluationModel.HAIKU, false)),
                    List.of(task(EvaluationModel.HAIKU, true)), List.of(), List.of()));

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.ENDED)
                    .containsEntry(HAIKU + "|coastal", Outcome.INTERRUPTED);
            assertThat(result.warmedPrefixes()).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    // ── configuration bounds ─────────────────────────────────────────────────

    @Test
    void outOfRangeSettingsFailFast() {
        BatchCachePrimerProperties p = new BatchCachePrimerProperties();

        assertThatThrownBy(() -> p.setWaitSeconds(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wait-seconds must be 0..600 but was -1");
        assertThatThrownBy(() -> p.setWaitSeconds(601)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.setPollSeconds(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-seconds must be 1..60 but was 0");
        assertThatThrownBy(() -> p.setPollSeconds(61)).isInstanceOf(IllegalArgumentException.class);
        p.setWaitSeconds(0);
        p.setWaitSeconds(600);
        p.setPollSeconds(1);
        p.setPollSeconds(60);
        assertThat(p.getWaitSeconds()).isEqualTo(600);
        assertThat(p.getPollSeconds()).isEqualTo(60);
        assertThat(new BatchCachePrimerProperties().getWaitSeconds()).isEqualTo(180);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private List<List<EvaluationTask.Forecast>> oneInlandBucket() {
        return List.of(List.of(task(EvaluationModel.HAIKU, false)), List.of(), List.of(), List.of());
    }

    private List<Duration> readTimeouts(String batchId, int expectedReads) {
        ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
        verify(batchClient, times(expectedReads)).retrieveBatch(eq(batchId), timeouts.capture());
        return timeouts.getAllValues();
    }

    private static ArgumentCaptor<BatchCreateParams> sentCaptor() {
        return ArgumentCaptor.forClass(BatchCreateParams.class);
    }

    private List<BatchCreateParams> sentPrimers(int expected) {
        ArgumentCaptor<BatchCreateParams> sent = sentCaptor();
        ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
        verify(batchClient, times(expected)).createPrimerBatch(sent.capture(), timeouts.capture());
        assertThat(timeouts.getAllValues()).containsOnly(CALL);
        for (BatchCreateParams params : sent.getAllValues()) {
            assertThat(params.requests()).hasSize(1);
        }
        return sent.getAllValues();
    }

    private static String systemText(BatchCreateParams.Request request) {
        return request.params().system().get().asTextBlockParams().get(0).text();
    }

    private static java.util.Optional<CacheControlEphemeral.Ttl> ttlOf(BatchCreateParams.Request request) {
        return request.params().system().get().asTextBlockParams().get(0).cacheControl().get().ttl();
    }

    // ── what a primer's own response says ────────────────────────────────────

    private ListAppender<ILoggingEvent> captureLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(BatchCachePrimer.class)).addAppender(appender);
        return appender;
    }

    private static void releaseLog(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(BatchCachePrimer.class)).detachAppender(appender);
    }

    @Test
    void anEndedPrimersResultIsReadAndItsCacheWriteAndDiagnosticsAreOnTheSummaryLine() {
        ListAppender<ILoggingEvent> log = captureLog();
        try {
            stubCreate("b1");
            stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
            when(batchClient.readFirstSucceededMessage("b1", CALL)).thenReturn(Optional.of(
                    CacheDiagnosticsFixtures.message("msg_primer", Optional.empty(), "{}", 4726, 4726, 0)));

            primer.prime(oneInlandBucket());

            assertThat(log.list).filteredOn(e -> e.getLevel() == Level.INFO)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement().asString()
                    .contains("1 warmed")
                    .contains("cache results: {" + HAIKU + "|inland=msg_primer wrote 4726 tokens "
                            + "(4726 at 1h), read 0, diagnostics none}");
        } finally {
            releaseLog(log);
        }
    }

    @Test
    void aPrimersDiagnosticsAppearInTheSummaryLineWhenTheApiReportedAny() {
        ListAppender<ILoggingEvent> log = captureLog();
        try {
            stubCreate("b1");
            stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
            when(batchClient.readFirstSucceededMessage("b1", CALL)).thenReturn(Optional.of(
                    CacheDiagnosticsFixtures.message("msg_primer",
                            Optional.of(CacheDiagnosticsFixtures.MESSAGES_CHANGED), "{}", 0, 0, 4726)));

            primer.prime(oneInlandBucket());

            assertThat(log.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("read 4726, diagnostics messages_changed (1234 tokens)"));
        } finally {
            releaseLog(log);
        }
    }

    @Test
    void theMessageIdsAreHandedToTheRegistryByPrefixAndReplacedEveryCycle() {
        PrimerMessageIds ids = new PrimerMessageIds();
        BatchCachePrimer withRegistry = new BatchCachePrimer(factory, batchClient, properties, clock,
                d -> clock.advance(d), ids);
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.readFirstSucceededMessage("b1", CALL)).thenReturn(Optional.of(
                CacheDiagnosticsFixtures.message("msg_primer", Optional.empty(), "{}", 4726, 4726, 0)));

        withRegistry.prime(oneInlandBucket());

        assertThat(ids.forPrefix(HAIKU + "|inland")).contains("msg_primer");
        assertThat(ids.forPrefix(HAIKU + "|coastal")).isEmpty();

        when(batchClient.readFirstSucceededMessage("b1", CALL)).thenReturn(Optional.empty());
        withRegistry.prime(oneInlandBucket());

        assertThat(ids.forPrefix(HAIKU + "|inland")).as("a cycle that read nothing clears the last one's ids")
                .isEmpty();
    }

    @Test
    void aResultThatCannotBeReadChangesNothingElse_stillWarmed() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.ENDED, 1);
        when(batchClient.readFirstSucceededMessage("b1", CALL)).thenThrow(new RuntimeException("429"));

        PrimeResult result = primer.prime(oneInlandBucket());

        assertThat(result.warmedPrefixes()).containsExactly(HAIKU + "|inland");
        assertThat(result.outcomes()).containsEntry(HAIKU + "|inland", Outcome.ENDED);
    }

    @Test
    void aPrimerThatDidNotEndIsNeverRead() {
        stubCreate("b1");
        stubStatus("b1", MessageBatch.ProcessingStatus.IN_PROGRESS, 0);

        primer.prime(oneInlandBucket());

        verify(batchClient, never()).readFirstSucceededMessage(anyString(), any(Duration.class));
    }

    private void stubCreate(String... ids) {
        MessageBatch[] batches = new MessageBatch[ids.length];
        for (int i = 0; i < ids.length; i++) {
            batches[i] = created(ids[i]);
        }
        MessageBatch[] rest = Arrays.copyOfRange(batches, 1, batches.length);
        when(batchClient.createPrimerBatch(any(BatchCreateParams.class), any(Duration.class)))
                .thenReturn(batches[0], rest);
    }

    private static MessageBatch created(String id) {
        MessageBatch batch = mock(MessageBatch.class);
        when(batch.id()).thenReturn(id);
        return batch;
    }

    private void stubStatus(String id, MessageBatch.ProcessingStatus status, long succeeded) {
        MessageBatch batch = batch(status, succeeded);
        when(batchClient.retrieveBatch(eq(id), any(Duration.class))).thenReturn(batch);
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
