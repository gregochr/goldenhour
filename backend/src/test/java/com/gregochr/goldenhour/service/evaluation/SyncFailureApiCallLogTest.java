package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SpaceWeatherData;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.ForecastDataAugmentor;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.ClaudeAuroraInterpreter;
import com.gregochr.goldenhour.service.aurora.TriggerType;
import com.gregochr.goldenhour.service.aurora.WeatherTriageService;
import com.gregochr.goldenhour.service.batch.BatchSubmissionService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.visitor.RatingCombiner;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a synchronous Claude call writes to {@code api_call_log}, end to end through the real
 * {@link EvaluationServiceImpl} and the real {@link ForecastResultHandler} /
 * {@link AuroraResultHandler}: only the Claude client, the {@link JobRunService} that persists the row
 * and the collaborators the failure path never touches are mocked, so the status and error type asserted
 * here are the ones that reach the row, not ones a unit stubbed in.
 *
 * <p>A failure used to be written with a hard-coded status 500 and no error type, whatever happened.
 * Now the status is the HTTP status Anthropic answered with, or {@code null} when the failure had none
 * (a connection failure, an unreadable reply, a refusal, a breaker refusing the call), and the error type
 * is {@code EvaluationFailure}'s vocabulary.
 */
@ExtendWith(MockitoExtension.class)
class SyncFailureApiCallLogTest {

    private static final long JOB_RUN_ID = 99L;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-16T09:00:00Z"), ZoneOffset.UTC);
    private static final SpaceWeatherData SPACE_WEATHER = new SpaceWeatherData(
            List.of(), List.of(), null, List.of(), List.of());

    @Mock
    private BatchSubmissionService batchSubmissionService;
    @Mock
    private BatchRequestFactory batchRequestFactory;
    @Mock
    private AnthropicApiClient anthropicApiClient;
    @Mock
    private ClaudeAuroraInterpreter claudeAuroraInterpreter;
    @Mock
    private JobRunService jobRunService;
    @Mock
    private BriefingEvaluationService briefingEvaluationService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private RatingCombiner ratingCombiner;
    @Mock
    private ForecastDataAugmentor forecastDataAugmentor;
    @Mock
    private ForecastScoreWriter forecastScoreWriter;
    @Mock
    private SunsetEvaluationParser parser;
    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock
    private SupersedingDispositionService supersedingDispositionService;
    @Mock
    private AuroraStateCache auroraStateCache;
    @Mock
    private WeatherTriageService weatherTriageService;
    @Mock
    private LocationRepository locationRepository;

    private EvaluationServiceImpl service;
    private final ArgumentCaptor<Long> duration = ArgumentCaptor.forClass(Long.class);

    @BeforeEach
    void setUp() {
        ForecastResultHandler forecastHandler = new ForecastResultHandler(
                briefingEvaluationService, jobRunService, objectMapper, ratingCombiner,
                forecastDataAugmentor, forecastScoreWriter, parser, forecastEvaluationRepository,
                supersedingDispositionService);
        AuroraResultHandler auroraHandler = new AuroraResultHandler(
                claudeAuroraInterpreter, auroraStateCache, weatherTriageService, locationRepository,
                new AuroraProperties(), jobRunService);
        service = new EvaluationServiceImpl(batchSubmissionService, batchRequestFactory,
                anthropicApiClient, claudeAuroraInterpreter, jobRunService,
                List.of(forecastHandler, auroraHandler), CLOCK);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Matches the request the engine sends. It cannot be named exactly: {@code evaluateNow} builds the
     * {@link MessageCreateParams} itself from the prompt builder, the model and the task's weather, so the
     * test has no instance to pass. What this test asserts is how a failure of that call is LOGGED, and the
     * request's content is asserted where it is built ({@code EvaluationServiceImplTest}); the matcher still
     * pins the argument's type. It is only ever used in stubbing, never in a {@code verify}.
     */
    private static MessageCreateParams anyRequest() {
        return any(MessageCreateParams.class);
    }

    private static AnthropicServiceException serviceError(int status, String message) {
        AnthropicServiceException svc = mock(AnthropicServiceException.class);
        when(svc.statusCode()).thenReturn(status);
        when(svc.getMessage()).thenReturn(message);
        return svc;
    }

    private EvaluationTask.Forecast forecastTask() {
        LocationEntity loc = new LocationEntity();
        loc.setId(42L);
        loc.setName("Castlerigg");
        RegionEntity region = new RegionEntity();
        region.setName("Lake District");
        loc.setRegion(region);
        return new EvaluationTask.Forecast(
                loc, DATE, TargetType.SUNRISE, EvaluationModel.HAIKU, TestAtmosphericData.defaults(),
                EvaluationTask.Forecast.WriteTarget.NONE);
    }

    private EvaluationTask.Aurora auroraTask() {
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("X");
        return new EvaluationTask.Aurora(AlertLevel.MODERATE, DATE, EvaluationModel.HAIKU,
                List.of(loc), Map.of(loc, 30), SPACE_WEATHER, TriggerType.REALTIME, null);
    }

    private JobRunEntity stubJobRun(RunType runType) {
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(JOB_RUN_ID);
        when(jobRunService.startRun(eq(runType), eq(false), eq(EvaluationModel.HAIKU))).thenReturn(jobRun);
        return jobRun;
    }

    /** Runs the forecast sync path against a Claude call that throws {@code failure}. */
    private void forecastCallThrows(Throwable failure) {
        stubJobRun(RunType.SHORT_TERM);
        EvaluationTask.Forecast task = forecastTask();
        when(batchRequestFactory.selectBuilder(eq(task.data()))).thenReturn(new PromptBuilder());
        when(anthropicApiClient.createMessage(anyRequest())).thenThrow((RuntimeException) failure);

        EvaluationResult result = service.evaluateNow(task, BatchTriggerSource.ADMIN);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
    }

    /** Verifies the one failed row the forecast path wrote, with exactly this status and error type. */
    private void assertFailedRow(Integer status, String errorType, String message) {
        verify(jobRunService).logAnthropicApiCall(
                eq(JOB_RUN_ID), duration.capture(), eq(status),
                eq(message), eq(false), eq(message),
                eq(EvaluationModel.HAIKU), eq(TokenUsage.EMPTY),
                eq(false),
                eq(DATE), eq(TargetType.SUNRISE), eq(errorType));
        assertThat(duration.getValue()).isNotNegative();
    }

    // ── forecast path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a 401 is logged with status 401 and error type anthropic_401, not 500 with none")
    void forecast_rejectedKey_logsItsStatusAndType() {
        forecastCallThrows(serviceError(401, "invalid x-api-key"));

        assertFailedRow(401, "anthropic_401", "invalid x-api-key");
    }

    @Test
    @DisplayName("a 529 is logged with status 529 and error type anthropic_529")
    void forecast_overloaded_logsItsStatusAndType() {
        forecastCallThrows(serviceError(529, "Overloaded"));

        assertFailedRow(529, "anthropic_529", "Overloaded");
    }

    @Test
    @DisplayName("a connection failure has no HTTP status: logged with a null status and the SDK's I/O exception "
            + "name as its error type")
    void forecast_connectionFailure_logsNullStatus() {
        forecastCallThrows(new AnthropicIoException("timed out"));

        assertFailedRow(null, "AnthropicIoException", "timed out");
    }

    @Test
    @DisplayName("a reply with no text block has no HTTP status: logged with a null status and reply_unreadable")
    void forecast_unreadableReply_logsNullStatus() {
        stubJobRun(RunType.SHORT_TERM);
        EvaluationTask.Forecast task = forecastTask();
        when(batchRequestFactory.selectBuilder(eq(task.data()))).thenReturn(new PromptBuilder());
        Message noText = mock(Message.class);
        when(noText.stopReason()).thenReturn(Optional.empty());
        when(noText.content()).thenReturn(List.of());
        when(anthropicApiClient.createMessage(anyRequest())).thenReturn(noText);

        service.evaluateNow(task, BatchTriggerSource.ADMIN);

        assertFailedRow(null, "reply_unreadable", "Claude returned no text");
    }

    @Test
    @DisplayName("a call the circuit breaker refused has no HTTP status: logged with a null status and circuit_open")
    void forecast_openBreaker_logsNullStatus() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("anthropic");
        breaker.transitionToOpenState();
        CallNotPermittedException refused = CallNotPermittedException.createCallNotPermittedException(breaker);

        forecastCallThrows(refused);

        assertFailedRow(null, "circuit_open", refused.getMessage());
    }

    // ── aurora path (the same JobRunService method) ──────────────────────────

    @Test
    @DisplayName("aurora: a 401 is logged with status 401 and error type anthropic_401 as well")
    void aurora_rejectedKey_logsItsStatusAndType() {
        stubJobRun(RunType.AURORA_EVALUATION);
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        AnthropicServiceException rejected = serviceError(401, "invalid x-api-key");
        when(anthropicApiClient.createMessage(anyRequest())).thenThrow(rejected);

        EvaluationResult result = service.evaluateNow(auroraTask(), BatchTriggerSource.SCHEDULED);

        assertThat(result).isInstanceOf(EvaluationResult.Errored.class);
        verify(jobRunService).logAnthropicApiCall(
                eq(JOB_RUN_ID), duration.capture(), eq(401),
                eq("invalid x-api-key"), eq(false), eq("invalid x-api-key"),
                eq(EvaluationModel.HAIKU), eq(TokenUsage.EMPTY),
                eq(false),
                eq(null), eq(null), eq("anthropic_401"));
    }

    @Test
    @DisplayName("aurora: a connection failure is logged with a null status and the I/O exception name")
    void aurora_connectionFailure_logsNullStatus() {
        stubJobRun(RunType.AURORA_EVALUATION);
        when(claudeAuroraInterpreter.buildUserMessage(any(), any(), any(), any(), any(), any()))
                .thenReturn("user-message");
        when(anthropicApiClient.createMessage(anyRequest())).thenThrow(new AnthropicIoException("timed out"));

        service.evaluateNow(auroraTask(), BatchTriggerSource.SCHEDULED);

        verify(jobRunService).logAnthropicApiCall(
                eq(JOB_RUN_ID), duration.capture(), eq(null),
                eq("timed out"), eq(false), eq("timed out"),
                eq(EvaluationModel.HAIKU), eq(TokenUsage.EMPTY),
                eq(false),
                eq(null), eq(null), eq("AnthropicIoException"));
    }
}
