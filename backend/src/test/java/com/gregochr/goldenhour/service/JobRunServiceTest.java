package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JobRunService}.
 */
@ExtendWith(MockitoExtension.class)
class JobRunServiceTest {

    @Mock
    private JobRunRepository jobRunRepository;

    @Mock
    private ApiCallLogRepository apiCallLogRepository;

    @Mock
    private CostCalculator costCalculator;

    @Mock
    private ExchangeRateService exchangeRateService;

    @InjectMocks
    private JobRunService jobRunService;

    @Test
    @DisplayName("startRun() creates a job run with correct run type, model, and exchange rate")
    void startRun_createsJobRunWithRunTypeAndExchangeRate() {
        when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
        JobRunEntity mockEntity = JobRunEntity.builder()
                .id(1L)
                .runType(RunType.SHORT_TERM)
                .evaluationModel(EvaluationModel.SONNET)
                .exchangeRateGbpPerUsd(0.79)
                .build();
        when(jobRunRepository.save(any())).thenReturn(mockEntity);

        JobRunEntity result = jobRunService.startRun(RunType.SHORT_TERM, false, EvaluationModel.SONNET);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getRunType()).isEqualTo(RunType.SHORT_TERM);
        assertThat(result.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
        assertThat(result.getExchangeRateGbpPerUsd()).isEqualTo(0.79);
        verify(jobRunRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("logAnthropicApiCall() records API call with token fields and micro-dollar cost")
    void logAnthropicApiCall_recordsTokensAndCost() {
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(apiCallLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(costCalculator.calculateCost(ServiceName.ANTHROPIC, EvaluationModel.SONNET)).thenReturn(13);
        when(costCalculator.calculateCostMicroDollars(eq(EvaluationModel.SONNET),
                any(TokenUsage.class), eq(false))).thenReturn(5400L);

        TokenUsage usage = new TokenUsage(400, 80, 200, 100);
        jobRunService.logAnthropicApiCall(
                1L, 250L, 200, null, true, null,
                EvaluationModel.SONNET, usage, false,
                LocalDate.of(2026, 3, 2), TargetType.SUNSET);

        verify(apiCallLogRepository, times(1)).save(captor.capture());
        ApiCallLogEntity logged = captor.getValue();
        assertThat(logged.getJobRunId()).isEqualTo(1L);
        assertThat(logged.getService()).isEqualTo(ServiceName.ANTHROPIC);
        assertThat(logged.getDurationMs()).isEqualTo(250L);
        assertThat(logged.getSucceeded()).isTrue();
        assertThat(logged.getCostPence()).isEqualTo(13);
        assertThat(logged.getCostMicroDollars()).isEqualTo(5400L);
        assertThat(logged.getInputTokens()).isEqualTo(400L);
        assertThat(logged.getOutputTokens()).isEqualTo(80L);
        assertThat(logged.getCacheCreationInputTokens()).isEqualTo(200L);
        assertThat(logged.getCacheReadInputTokens()).isEqualTo(100L);
        assertThat(logged.getIsBatch()).isFalse();
    }

    @Test
    @DisplayName("completeRun() aggregates both legacy pence and micro-dollar costs")
    void completeRun_aggregatesBothCostTypes() {
        LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.SECONDS);
        JobRunEntity jobRun = JobRunEntity.builder()
                .id(1L)
                .runType(RunType.SHORT_TERM)
                .startedAt(startTime)
                .build();
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                ApiCallLogEntity.builder().costPence(13).costMicroDollars(5400L).build(),
                ApiCallLogEntity.builder().costPence(13).costMicroDollars(5400L).build()
        ));

        jobRunService.completeRun(jobRun, 5, 2);

        ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
        verify(jobRunRepository, times(1)).save(captor.capture());
        JobRunEntity completed = captor.getValue();
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getDurationMs()).isGreaterThan(0L);
        assertThat(completed.getSucceeded()).isEqualTo(5);
        assertThat(completed.getFailed()).isEqualTo(2);
        assertThat(completed.getTotalCostPence()).isEqualTo(26);
        assertThat(completed.getTotalCostMicroDollars()).isEqualTo(10800L);
    }

    @Test
    @DisplayName("logApiCall() for non-Anthropic service uses flat micro-dollar cost")
    void logApiCall_nonAnthropic_usesFlatCost() {
        ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
        when(apiCallLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(costCalculator.calculateCost(ServiceName.WORLD_TIDES, null)).thenReturn(2);
        when(costCalculator.calculateFlatCostMicroDollars(ServiceName.WORLD_TIDES)).thenReturn(3000L);

        jobRunService.logApiCall(1L, ServiceName.WORLD_TIDES, "GET",
                "https://www.worldtides.info/api/v3", null,
                100L, 200, null, true, null);

        verify(apiCallLogRepository, times(1)).save(captor.capture());
        ApiCallLogEntity logged = captor.getValue();
        assertThat(logged.getCostPence()).isEqualTo(2);
        assertThat(logged.getCostMicroDollars()).isEqualTo(3000L);
        assertThat(logged.getInputTokens()).isNull();
    }

    @Test
    @DisplayName("getRecentRuns() returns paginated results for run type")
    void getRecentRuns_returnsPaginatedResults() {
        when(jobRunRepository.findByRunTypeOrderByStartedAtDesc(eq(RunType.WEATHER), any()))
                .thenReturn(List.of(
                        JobRunEntity.builder().id(1L).runType(RunType.WEATHER).build(),
                        JobRunEntity.builder().id(2L).runType(RunType.WEATHER).build()
                ));

        List<JobRunEntity> result = jobRunService.getRecentRuns(RunType.WEATHER, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRunType()).isEqualTo(RunType.WEATHER);
    }

    @Test
    @DisplayName("getApiCallsForRun() returns all calls for a job run")
    void getApiCallsForRun_returnsAllCallsForJobRun() {
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L))
                .thenReturn(List.of(
                        ApiCallLogEntity.builder()
                                .jobRunId(1L)
                                .service(ServiceName.OPEN_METEO_FORECAST)
                                .build(),
                        ApiCallLogEntity.builder()
                                .jobRunId(1L)
                                .service(ServiceName.ANTHROPIC)
                                .build()
                ));

        List<ApiCallLogEntity> result = jobRunService.getApiCallsForRun(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getService()).isEqualTo(ServiceName.OPEN_METEO_FORECAST);
    }
}
