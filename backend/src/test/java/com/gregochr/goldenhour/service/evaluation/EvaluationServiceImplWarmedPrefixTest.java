package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.WoodlandVerdictEvaluator;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end (service plus real {@link BatchRequestFactory}) pin of when a batch sky request carries
 * the one-hour cache lifetime: only through {@link EvaluationServiceImpl#submitWarmed} and only for
 * a warmed prefix. Expected requests are built by hand, never through the method under test.
 */
class EvaluationServiceImplWarmedPrefixTest {

    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final String INLAND_KEY = EvaluationModel.HAIKU.getModelId() + "|inland";

    private final PromptBuilder inlandBuilder = new PromptBuilder();
    private final CoastalPromptBuilder coastalBuilder = new CoastalPromptBuilder();
    private BatchSubmissionService batchSubmissionService;
    private EvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        batchSubmissionService = mock(BatchSubmissionService.class);
        BatchRequestFactory factory = new BatchRequestFactory(inlandBuilder, coastalBuilder,
                new BluebellPromptBuilder(), new WoodlandPromptBuilder(new WoodlandVerdictEvaluator()));
        service = new EvaluationServiceImpl(batchSubmissionService, factory,
                mock(AnthropicApiClient.class), mock(ClaudeAuroraInterpreter.class),
                mock(JobRunService.class), List.of(),
                Clock.fixed(Instant.parse("2026-04-14T12:00:00Z"), ZoneOffset.UTC),
                mock(ForecastPromptStore.class));
        when(batchSubmissionService.submit(org.mockito.ArgumentMatchers.anyList(), eq(BatchType.FORECAST),
                org.mockito.ArgumentMatchers.any(BatchTriggerSource.class), anyString(),
                org.mockito.ArgumentMatchers.<Long>any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new BatchSubmitResult(1L, "msgbatch_x", 2));
    }

    @Test
    void warmedPrefixGetsOneHour_otherPrefixGetsTheDefault() {
        EvaluationTask.Forecast inland = task(42L, false);
        EvaluationTask.Forecast coastal = task(43L, true);

        service.submitWarmed(List.of(inland, coastal), BatchTriggerSource.SCHEDULED, 9L,
                Set.of(INLAND_KEY));

        List<BatchCreateParams.Request> sent = sentRequests(BatchTriggerSource.SCHEDULED, 9L, false);
        assertThat(sent).containsExactly(
                expected(inlandBuilder, "fc-42-2026-04-16-SUNRISE", inland, true),
                expected(coastalBuilder, "fc-43-2026-04-16-SUNRISE", coastal, false));
    }

    @Test
    void scheduledSubmitWithNothingWarmed_everyRequestIsTheDefault() {
        EvaluationTask.Forecast inland = task(42L, false);
        EvaluationTask.Forecast coastal = task(43L, true);

        service.submit(List.of(inland, coastal), BatchTriggerSource.SCHEDULED, 9L);

        assertThat(sentRequests(BatchTriggerSource.SCHEDULED, 9L, false)).containsExactly(
                expected(inlandBuilder, "fc-42-2026-04-16-SUNRISE", inland, false),
                expected(coastalBuilder, "fc-43-2026-04-16-SUNRISE", coastal, false));
    }

    @Test
    void forceJfdiAndRetrySubmissions_alwaysTheDefault() {
        EvaluationTask.Forecast inland = task(42L, false);
        BatchCreateParams.Request defaultRequest =
                expected(inlandBuilder, "fc-42-2026-04-16-SUNRISE", inland, false);

        service.submit(List.of(inland), BatchTriggerSource.FORCE);
        service.submit(List.of(inland), BatchTriggerSource.JFDI);
        service.submit(List.of(inland), BatchTriggerSource.SCHEDULED, 9L, true);

        assertThat(sentRequests(BatchTriggerSource.FORCE, null, false)).containsExactly(defaultRequest);
        assertThat(sentRequests(BatchTriggerSource.JFDI, null, false)).containsExactly(defaultRequest);
        assertThat(sentRequests(BatchTriggerSource.SCHEDULED, 9L, true)).containsExactly(defaultRequest);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private List<BatchCreateParams.Request> sentRequests(BatchTriggerSource trigger, Long runId,
            boolean isRetry) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchCreateParams.Request>> captor = ArgumentCaptor.forClass(List.class);
        verify(batchSubmissionService).submit(captor.capture(), eq(BatchType.FORECAST), eq(trigger),
                anyString(), eq(runId), eq(isRetry));
        return captor.getValue();
    }

    /** The request main sends for this task, built by hand: default or one-hour cache lifetime. */
    private static BatchCreateParams.Request expected(PromptBuilder builder, String customId,
            EvaluationTask.Forecast task, boolean oneHour) {
        CacheControlEphemeral.Builder control = CacheControlEphemeral.builder();
        if (oneHour) {
            control.ttl(CacheControlEphemeral.Ttl.TTL_1H);
        }
        return BatchCreateParams.Request.builder()
                .customId(customId)
                .params(BatchCreateParams.Request.Params.builder()
                        .model(task.model().getModelId())
                        .maxTokens(task.model().getMaxTokens())
                        .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                                .text(builder.getSystemPrompt())
                                .cacheControl(control.build())
                                .build()))
                        .outputConfig(builder.buildOutputConfig())
                        .addUserMessage(builder.buildUserMessage(task.data()))
                        .build())
                .build();
    }

    private static EvaluationTask.Forecast task(long locationId, boolean coastal) {
        LocationEntity location = new LocationEntity();
        location.setId(locationId);
        location.setName("Location " + locationId);
        var data = TestAtmosphericData.builder().locationName("Location " + locationId)
                .solarEventTime(LocalDateTime.of(2026, 4, 16, 5, 30))
                .targetType(TargetType.SUNRISE);
        if (coastal) {
            data.tide(new TideSnapshot(TideState.MID, LocalDateTime.of(2026, 6, 21, 19, 30),
                    new BigDecimal("4.20"), LocalDateTime.of(2026, 6, 21, 13, 15),
                    new BigDecimal("1.10"), false, LocalDateTime.of(2026, 6, 21, 19, 30),
                    LocalDateTime.of(2026, 6, 21, 13, 15), LunarTideType.REGULAR_TIDE, "First Quarter",
                    false, TideStatisticalSize.EXTRA_HIGH));
        }
        AtmosphericData atmospheric = data.build();
        return new EvaluationTask.Forecast(location, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU,
                atmospheric, EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
    }
}
