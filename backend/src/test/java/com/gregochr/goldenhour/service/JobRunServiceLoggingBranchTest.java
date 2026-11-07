package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.PrefetchCall;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Logging and anchor-run branches of {@link JobRunService}: metered calls, the service-dependent cost of
 * the Anthropic overloads, a diagnostics serialisation failure, prefetch failures with no error text and an
 * exchange-rate failure on a disposition anchor run.
 */
@ExtendWith(MockitoExtension.class)
class JobRunServiceLoggingBranchTest {

    @Mock
    private JobRunRepository jobRunRepository;
    @Mock
    private ApiCallLogRepository apiCallLogRepository;
    @Mock
    private ForecastBatchRepository forecastBatchRepository;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private ObjectMapper objectMapper;

    private JobRunService jobRunService;

    @BeforeEach
    void setUp() {
        jobRunService = new JobRunService(jobRunRepository, apiCallLogRepository, forecastBatchRepository,
                costCalculator, exchangeRateService, objectMapper, "v2.9.0");
    }

    @Test
    @DisplayName("a metered call is priced from the credits the provider reported and saved")
    void logMeteredApiCall_pricedFromCredits() {
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(costCalculator.calculateMeteredCostMicroDollars(ServiceName.WORLD_TIDES, 14)).thenReturn(14_000L);
        when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ApiCallLogEntity result = jobRunService.logMeteredApiCall(5L, ServiceName.WORLD_TIDES, "GET",
                "https://tides.example/api", 320L, 200, true, null, 14);

        ApiCallLogEntity saved = captor.getValue();
        assertThat(result).isSameAs(saved);
        assertThat(saved.getJobRunId()).isEqualTo(5L);
        assertThat(saved.getService()).isEqualTo(ServiceName.WORLD_TIDES);
        assertThat(saved.getRequestMethod()).isEqualTo("GET");
        assertThat(saved.getRequestUrl()).isEqualTo("https://tides.example/api");
        assertThat(saved.getDurationMs()).isEqualTo(320L);
        assertThat(saved.getStatusCode()).isEqualTo(200);
        assertThat(saved.getSucceeded()).isTrue();
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getCostMicroDollars()).isEqualTo(14_000L);
    }

    @Test
    @DisplayName("the legacy overload records an Anthropic call at zero cost without asking the calculator")
    void logApiCall_legacyAnthropic_zeroCost() {
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        jobRunService.logApiCall(5L, ServiceName.ANTHROPIC, "POST", "messages", "{}", 90L, 200, null, true, null,
                EvaluationModel.HAIKU, java.time.LocalDate.of(2026, 3, 2),
                com.gregochr.goldenhour.entity.TargetType.SUNSET);

        assertThat(captor.getValue().getCostMicroDollars()).isEqualTo(0L);
        assertThat(captor.getValue().getService()).isEqualTo(ServiceName.ANTHROPIC);
    }

    @Test
    @DisplayName("the legacy overload prices a non-Anthropic call at the flat per-call cost")
    void logApiCall_legacyFlatService_flatCost() {
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(costCalculator.calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_FORECAST)).thenReturn(7L);
        when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        jobRunService.logApiCall(5L, ServiceName.OPEN_METEO_FORECAST, "GET", "forecast", null, 90L, 200, null, true,
                null,
                null, java.time.LocalDate.of(2026, 3, 2), com.gregochr.goldenhour.entity.TargetType.SUNRISE);

        assertThat(captor.getValue().getCostMicroDollars()).isEqualTo(7L);
    }

    @Test
    @DisplayName("the token-usage overload prices a non-Anthropic call at the flat per-call cost")
    void logApiCall_tokenOverloadFlatService_flatCost() {
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(costCalculator.calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_FORECAST)).thenReturn(7L);
        when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        jobRunService.logApiCall(5L, ServiceName.OPEN_METEO_FORECAST, "GET", "forecast", null, 90L, 200, null, true,
                null,
                null, new TokenUsage(0, 0, 0, 0), CacheDiagnostics.EMPTY);

        assertThat(captor.getValue().getCostMicroDollars()).isEqualTo(7L);
        assertThat(captor.getValue().getCacheDiagnostics()).isNull();
    }

    @Test
    @DisplayName("a diagnostics value that cannot be serialised is dropped; the call is still logged")
    void logApiCall_diagnosticsSerialisationFails_loggedWithoutDiagnostics() throws Exception {
        CacheDiagnostics diagnostics = new CacheDiagnostics(CacheDiagnostics.Status.MISS, "prefix_changed", 1200L);
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(objectMapper.writeValueAsString(diagnostics)).thenThrow(new JsonProcessingException("boom") { });
        when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        jobRunService.logApiCall(5L, ServiceName.ANTHROPIC, "POST", "messages", "{}", 90L, 200, null, true, null,
                EvaluationModel.HAIKU, null, diagnostics);

        assertThat(captor.getValue().getCacheDiagnostics()).isNull();
        assertThat(captor.getValue().getCostMicroDollars()).isEqualTo(0L);
    }

    @Test
    @DisplayName("an anchor run still saves when the exchange rate cannot be fetched, with a null rate")
    void startDispositionAnchorRun_exchangeRateFails_nullRate() {
        when(exchangeRateService.getCurrentRate()).thenThrow(new IllegalStateException("rate service down"));
        ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
        when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> {
            JobRunEntity run = inv.getArgument(0);
            run.setId(77L);
            return run;
        });

        Long id = jobRunService.startDispositionAnchorRun(12, "Weather pre-fetch aborted the cycle");

        JobRunEntity saved = captor.getValue();
        assertThat(id).isEqualTo(77L);
        assertThat(saved.getExchangeRateGbpPerUsd()).isNull();
        assertThat(saved.getRunType()).isEqualTo(RunType.SCHEDULED_BATCH);
        assertThat(saved.getLocationsProcessed()).isEqualTo(12);
        assertThat(saved.getNotes()).isEqualTo("Weather pre-fetch aborted the cycle");
        assertThat(saved.getAppVersion()).isEqualTo("v2.9.0");
    }

    @Test
    @DisplayName("a failed prefetch call with no error text is logged as 'failed' with its chunk and attempts")
    @SuppressWarnings("unchecked")
    void logPrefetchCalls_failureWithoutError_saysFailed() {
        LocalDateTime calledAt = LocalDateTime.of(2026, 3, 2, 5, 0);
        PrefetchCall failed = new PrefetchCall(ServiceName.OPEN_METEO_FORECAST, "forecast chunk 2/4", 100, 0, calledAt,
                500L,
                503, false, null, 1);
        PrefetchCall retried = new PrefetchCall(ServiceName.OPEN_METEO_FORECAST, "forecast", 50, 10, calledAt, 800L,
                null, false, "timeout", 3);
        when(costCalculator.calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_FORECAST)).thenReturn(2L);

        jobRunService.logPrefetchCalls(9L, List.of(failed, retried));

        ArgumentCaptor<List<ApiCallLogEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(apiCallLogRepository).saveAll(captor.capture());
        List<ApiCallLogEntity> rows = captor.getValue();
        assertThat(rows.get(0).getErrorMessage()).isEqualTo("2/4: 100 coords, 0 returned, 1 attempt — failed");
        assertThat(rows.get(0).getStatusCode()).isEqualTo(503);
        assertThat(rows.get(1).getErrorMessage()).isEqualTo("50 coords, 10 returned, 3 attempts — timeout");
    }
}
