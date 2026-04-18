package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private ForecastBatchRepository forecastBatchRepository;

    @Mock
    private CostCalculator costCalculator;

    @Mock
    private ExchangeRateService exchangeRateService;

    private JobRunService jobRunService;

    @BeforeEach
    void setUp() {
        jobRunService = new JobRunService(
                jobRunRepository, apiCallLogRepository, forecastBatchRepository,
                costCalculator, exchangeRateService, "v2.8.19");
    }

    @Nested
    @DisplayName("startRun()")
    class StartRunTests {

        @Test
        @DisplayName("creates a job run with correct run type, model, and exchange rate")
        void startRun_createsJobRunWithRunTypeAndExchangeRate() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            JobRunEntity result = jobRunService.startRun(RunType.SHORT_TERM, false, EvaluationModel.SONNET);

            JobRunEntity saved = captor.getValue();
            assertThat(saved.getRunType()).isEqualTo(RunType.SHORT_TERM);
            assertThat(saved.getEvaluationModel()).isEqualTo(EvaluationModel.SONNET);
            assertThat(saved.getExchangeRateGbpPerUsd()).isEqualTo(0.79);
            assertThat(saved.getTriggeredManually()).isFalse();
            assertThat(saved.getSucceeded()).isEqualTo(0);
            assertThat(saved.getFailed()).isEqualTo(0);
            assertThat(result).isSameAs(saved);
        }

        @Test
        @DisplayName("stamps app version from constructor on every new run")
        void startRun_stampsAppVersion() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startRun(RunType.VERY_SHORT_TERM, true, EvaluationModel.HAIKU);

            assertThat(captor.getValue().getAppVersion()).isEqualTo("v2.8.19");
        }

        @Test
        @DisplayName("stamps same app version when strategies overload is used")
        void startRun_withStrategies_stampsAppVersion() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startRun(RunType.SHORT_TERM, false, EvaluationModel.SONNET, "SKIP_LOW_RATED,FORCE_IMMINENT");

            JobRunEntity saved = captor.getValue();
            assertThat(saved.getAppVersion()).isEqualTo("v2.8.19");
            assertThat(saved.getActiveStrategies()).isEqualTo("SKIP_LOW_RATED,FORCE_IMMINENT");
        }

        @Test
        @DisplayName("stores null exchange rate and still stamps version when rate fetch fails")
        void startRun_exchangeRateFails_savesNullRateWithVersion() {
            when(exchangeRateService.getCurrentRate()).thenThrow(new RuntimeException("timeout"));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startRun(RunType.TIDE, false, null);

            JobRunEntity saved = captor.getValue();
            assertThat(saved.getExchangeRateGbpPerUsd()).isNull();
            assertThat(saved.getAppVersion()).isEqualTo("v2.8.19");
        }

        @Test
        @DisplayName("marks run as manually triggered when triggeredManually=true")
        void startRun_manualTrigger_setsFlag() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startRun(RunType.LONG_TERM, true, EvaluationModel.OPUS);

            assertThat(captor.getValue().getTriggeredManually()).isTrue();
        }

        @Test
        @DisplayName("startedAt is set to a time close to now (UTC)")
        void startRun_setsStartedAtCloseToNow() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

            jobRunService.startRun(RunType.SHORT_TERM, false, EvaluationModel.HAIKU);

            LocalDateTime startedAt = captor.getValue().getStartedAt();
            assertThat(startedAt).isAfterOrEqualTo(before);
            assertThat(startedAt).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        }

        @Test
        @DisplayName("succeeded and failed counters are initialised to zero")
        void startRun_initialCountersAreZero() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startRun(RunType.WEATHER, false, null);

            JobRunEntity saved = captor.getValue();
            assertThat(saved.getSucceeded()).isEqualTo(0);
            assertThat(saved.getFailed()).isEqualTo(0);
            assertThat(saved.getTotalCostPence()).isEqualTo(0);
        }

        @Test
        @DisplayName("null evaluationModel is stored as null on the entity")
        void startRun_nullModel_storesTooNull() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startRun(RunType.WEATHER, false, null);

            assertThat(captor.getValue().getEvaluationModel()).isNull();
        }
    }

    @Nested
    @DisplayName("logAnthropicApiCall()")
    class LogAnthropicApiCallTests {

        @Test
        @DisplayName("records API call with token fields and micro-dollar cost")
        void logAnthropicApiCall_recordsTokensAndCost() {
            ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
            when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
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
    }

    @Nested
    @DisplayName("completeRun()")
    class CompleteRunTests {

        @Test
        @DisplayName("aggregates both legacy pence and micro-dollar costs")
        void completeRun_aggregatesBothCostTypes() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.SECONDS);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(1L)
                    .runType(RunType.SHORT_TERM)
                    .startedAt(startTime)
                    .build();
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                    ApiCallLogEntity.builder().costPence(13).costMicroDollars(5400L).build(),
                    ApiCallLogEntity.builder().costPence(13).costMicroDollars(5400L).build()
            ));

            jobRunService.completeRun(jobRun, 5, 2);

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
        @DisplayName("with date list sets minTargetDate and maxTargetDate correctly")
        void completeRun_withDates_setsMinAndMaxTargetDate() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.SECONDS);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(2L)
                    .runType(RunType.LONG_TERM)
                    .startedAt(startTime)
                    .build();
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(2L)).thenReturn(List.of());

            jobRunService.completeRun(jobRun, 3, 0,
                    List.of(LocalDate.of(2026, 4, 15),
                            LocalDate.of(2026, 4, 12),
                            LocalDate.of(2026, 4, 20)));

            JobRunEntity completed = captor.getValue();
            assertThat(completed.getMinTargetDate()).isEqualTo(LocalDate.of(2026, 4, 12));
            assertThat(completed.getMaxTargetDate()).isEqualTo(LocalDate.of(2026, 4, 20));
        }

        @Test
        @DisplayName("with empty date list does not set target date fields")
        void completeRun_withEmptyDateList_doesNotSetTargetDates() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.SECONDS);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(3L)
                    .runType(RunType.SHORT_TERM)
                    .startedAt(startTime)
                    .build();
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(3L)).thenReturn(List.of());

            jobRunService.completeRun(jobRun, 0, 0, List.of());

            JobRunEntity completed = captor.getValue();
            assertThat(completed.getMinTargetDate()).isNull();
            assertThat(completed.getMaxTargetDate()).isNull();
        }

        @Test
        @DisplayName("with null date list does not set target date fields")
        void completeRun_withNullDateList_doesNotSetTargetDates() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.SECONDS);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(4L)
                    .runType(RunType.WEATHER)
                    .startedAt(startTime)
                    .build();
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(4L)).thenReturn(List.of());

            jobRunService.completeRun(jobRun, 0, 0, null);

            JobRunEntity completed = captor.getValue();
            assertThat(completed.getMinTargetDate()).isNull();
            assertThat(completed.getMaxTargetDate()).isNull();
        }

        @Test
        @DisplayName("completedAt is set to a time close to now")
        void completeRun_setsCompletedAtCloseToNow() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(1, ChronoUnit.SECONDS);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(5L)
                    .runType(RunType.SHORT_TERM)
                    .startedAt(startTime)
                    .build();
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(5L)).thenReturn(List.of());
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

            jobRunService.completeRun(jobRun, 1, 0);

            LocalDateTime completedAt = captor.getValue().getCompletedAt();
            assertThat(completedAt).isAfterOrEqualTo(before);
            assertThat(completedAt).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        }

        @Test
        @DisplayName("durationMs reflects elapsed time since startedAt")
        void completeRun_durationMsReflectsElapsed() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minus(500, ChronoUnit.MILLIS);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(6L)
                    .runType(RunType.SHORT_TERM)
                    .startedAt(startTime)
                    .build();
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(6L)).thenReturn(List.of());

            jobRunService.completeRun(jobRun, 0, 0);

            assertThat(captor.getValue().getDurationMs()).isGreaterThanOrEqualTo(500L);
        }
    }

    @Nested
    @DisplayName("logApiCall()")
    class LogApiCallTests {

        @Test
        @DisplayName("for non-Anthropic service uses flat micro-dollar cost")
        void logApiCall_nonAnthropic_usesFlatCost() {
            ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
            when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(costCalculator.calculateCost(ServiceName.WORLD_TIDES, null)).thenReturn(2);
            when(costCalculator.calculateFlatCostMicroDollars(ServiceName.WORLD_TIDES)).thenReturn(3000L);

            jobRunService.logApiCall(1L, ServiceName.WORLD_TIDES, "GET",
                    "https://www.worldtides.info/api/v3", null,
                    100L, 200, null, true, null);

            ApiCallLogEntity logged = captor.getValue();
            assertThat(logged.getCostPence()).isEqualTo(2);
            assertThat(logged.getCostMicroDollars()).isEqualTo(3000L);
            assertThat(logged.getInputTokens()).isNull();
        }

        @Test
        @DisplayName("records service, URL, method, status code and success flag correctly")
        void logApiCall_recordsAllFields() {
            ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
            when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(costCalculator.calculateCost(ServiceName.OPEN_METEO_FORECAST, null)).thenReturn(0);
            when(costCalculator.calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_FORECAST)).thenReturn(0L);

            jobRunService.logApiCall(7L, ServiceName.OPEN_METEO_FORECAST,
                    "GET", "https://api.open-meteo.com/v1/forecast", null,
                    120L, 200, null, true, null);

            ApiCallLogEntity logged = captor.getValue();
            assertThat(logged.getJobRunId()).isEqualTo(7L);
            assertThat(logged.getService()).isEqualTo(ServiceName.OPEN_METEO_FORECAST);
            assertThat(logged.getRequestMethod()).isEqualTo("GET");
            assertThat(logged.getRequestUrl()).isEqualTo("https://api.open-meteo.com/v1/forecast");
            assertThat(logged.getDurationMs()).isEqualTo(120L);
            assertThat(logged.getStatusCode()).isEqualTo(200);
            assertThat(logged.getSucceeded()).isTrue();
        }

        @Test
        @DisplayName("error message longer than 500 characters is truncated to 500")
        void logApiCall_longErrorMessage_isTruncatedTo500Chars() {
            ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
            when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(costCalculator.calculateCost(ServiceName.WORLD_TIDES, null)).thenReturn(0);
            when(costCalculator.calculateFlatCostMicroDollars(ServiceName.WORLD_TIDES)).thenReturn(0L);
            String longError = "e".repeat(600);

            jobRunService.logApiCall(1L, ServiceName.WORLD_TIDES, "GET",
                    "https://example.com", null, 50L, 500, null, false, longError);

            assertThat(captor.getValue().getErrorMessage()).hasSize(500);
        }

        @Test
        @DisplayName("error message exactly 500 characters is not truncated")
        void logApiCall_errorMessageExactly500Chars_isNotTruncated() {
            ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
            when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(costCalculator.calculateCost(ServiceName.WORLD_TIDES, null)).thenReturn(0);
            when(costCalculator.calculateFlatCostMicroDollars(ServiceName.WORLD_TIDES)).thenReturn(0L);
            String exactError = "x".repeat(500);

            jobRunService.logApiCall(1L, ServiceName.WORLD_TIDES, "GET",
                    "https://example.com", null, 50L, 500, null, false, exactError);

            assertThat(captor.getValue().getErrorMessage()).hasSize(500);
        }

        @Test
        @DisplayName("null error message is stored as null (not truncated to empty string)")
        void logApiCall_nullErrorMessage_isStoredAsNull() {
            ArgumentCaptor<ApiCallLogEntity> captor = ArgumentCaptor.forClass(ApiCallLogEntity.class);
            when(apiCallLogRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            when(costCalculator.calculateCost(ServiceName.OPEN_METEO_FORECAST, null)).thenReturn(0);
            when(costCalculator.calculateFlatCostMicroDollars(ServiceName.OPEN_METEO_FORECAST)).thenReturn(0L);

            jobRunService.logApiCall(1L, ServiceName.OPEN_METEO_FORECAST, "GET",
                    "https://example.com", null, 50L, 200, null, true, null);

            assertThat(captor.getValue().getErrorMessage()).isNull();
        }
    }

    @Nested
    @DisplayName("getRecentRuns()")
    class GetRecentRunsTests {

        @Test
        @DisplayName("returns paginated results for run type")
        void getRecentRuns_returnsPaginatedResults() {
            when(jobRunRepository.findByRunTypeOrderByStartedAtDesc(
                    eq(RunType.WEATHER), eq(PageRequest.of(0, 10))))
                    .thenReturn(List.of(
                            JobRunEntity.builder().id(1L).runType(RunType.WEATHER).build(),
                            JobRunEntity.builder().id(2L).runType(RunType.WEATHER).build()
                    ));

            List<JobRunEntity> result = jobRunService.getRecentRuns(RunType.WEATHER, 10);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo(1L);
            assertThat(result.get(1).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("clamps limit to 1 when caller passes 0 to avoid invalid PageRequest")
        void getRecentRuns_limitZero_usesPageSizeOf1() {
            when(jobRunRepository.findByRunTypeOrderByStartedAtDesc(
                    eq(RunType.WEATHER), eq(PageRequest.of(0, 1))))
                    .thenReturn(List.of());

            jobRunService.getRecentRuns(RunType.WEATHER, 0);

            verify(jobRunRepository).findByRunTypeOrderByStartedAtDesc(
                    eq(RunType.WEATHER), eq(PageRequest.of(0, 1)));
        }
    }

    @Nested
    @DisplayName("getApiCallsForRun()")
    class GetApiCallsForRunTests {

        @Test
        @DisplayName("returns all calls ordered for a job run")
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
            assertThat(result.get(1).getService()).isEqualTo(ServiceName.ANTHROPIC);
        }

        @Test
        @DisplayName("returns empty list when no calls exist for the job run")
        void getApiCallsForRun_noCallsExist_returnsEmptyList() {
            when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(99L))
                    .thenReturn(List.of());

            List<ApiCallLogEntity> result = jobRunService.getApiCallsForRun(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("startBatchRun()")
    class StartBatchRunTests {

        @Test
        @DisplayName("creates job run with SCHEDULED_BATCH run type")
        void startBatchRun_setsScheduledBatchRunType() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(10, "msgbatch_test01");

            assertThat(captor.getValue().getRunType()).isEqualTo(RunType.SCHEDULED_BATCH);
        }

        @Test
        @DisplayName("sets locationsProcessed to the request count")
        void startBatchRun_setsLocationsProcessedToRequestCount() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(42, "msgbatch_test01");

            assertThat(captor.getValue().getLocationsProcessed()).isEqualTo(42);
        }

        @Test
        @DisplayName("embeds the Anthropic batch ID in the notes field")
        void startBatchRun_setsNotesWithBatchId() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(5, "msgbatch_abc123");

            assertThat(captor.getValue().getNotes()).isEqualTo("Anthropic batch: msgbatch_abc123");
        }

        @Test
        @DisplayName("leaves completedAt null so run appears in-progress")
        void startBatchRun_completedAtIsNull() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(3, "msgbatch_test01");

            assertThat(captor.getValue().getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("initialises succeeded and failed counters to zero")
        void startBatchRun_initialCountersAreZero() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(7, "msgbatch_test01");

            assertThat(captor.getValue().getSucceeded()).isEqualTo(0);
            assertThat(captor.getValue().getFailed()).isEqualTo(0);
        }

        @Test
        @DisplayName("marks run as scheduler-triggered (not manual)")
        void startBatchRun_triggeredManuallyIsFalse() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(1, "msgbatch_test01");

            assertThat(captor.getValue().getTriggeredManually()).isFalse();
        }

        @Test
        @DisplayName("stores null exchange rate and still saves when rate fetch fails")
        void startBatchRun_exchangeRateFails_savesNullRate() {
            when(exchangeRateService.getCurrentRate()).thenThrow(new RuntimeException("timeout"));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(5, "msgbatch_test01");

            assertThat(captor.getValue().getExchangeRateGbpPerUsd()).isNull();
            assertThat(captor.getValue().getRunType()).isEqualTo(RunType.SCHEDULED_BATCH);
        }

        @Test
        @DisplayName("stamps app version on the entity")
        void startBatchRun_stampsAppVersion() {
            when(exchangeRateService.getCurrentRate()).thenReturn(0.79);
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.startBatchRun(3, "msgbatch_test01");

            assertThat(captor.getValue().getAppVersion()).isEqualTo("v2.8.19");
        }
    }

    @Nested
    @DisplayName("updateBatchRunProgress()")
    class UpdateBatchRunProgressTests {

        @Test
        @DisplayName("updates succeeded and failed counts when entity found")
        void updateBatchRunProgress_entityFound_updatesCountsAndSaves() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(10L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .succeeded(0)
                    .failed(0)
                    .build();
            when(jobRunRepository.findById(10L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.updateBatchRunProgress(10L, 7, 2);

            verify(jobRunRepository).save(captor.capture());
            JobRunEntity saved = captor.getValue();
            assertThat(saved.getSucceeded()).isEqualTo(7);
            assertThat(saved.getFailed()).isEqualTo(2);
        }

        @Test
        @DisplayName("does not set completedAt during a progress update")
        void updateBatchRunProgress_doesNotSetCompletedAt() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(11L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .build();
            when(jobRunRepository.findById(11L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.updateBatchRunProgress(11L, 3, 0);

            assertThat(captor.getValue().getCompletedAt()).isNull();
        }

        @Test
        @DisplayName("does not save when entity not found")
        void updateBatchRunProgress_entityNotFound_doesNotSave() {
            when(jobRunRepository.findById(999L)).thenReturn(Optional.empty());

            jobRunService.updateBatchRunProgress(999L, 5, 1);

            verify(jobRunRepository, never()).save(any(JobRunEntity.class));
        }
    }

    @Nested
    @DisplayName("completeBatchRun()")
    class CompleteBatchRunTests {

        @Test
        @DisplayName("sets completedAt to a time close to now")
        void completeBatchRun_setsCompletedAtCloseToNow() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(20L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .build();
            when(jobRunRepository.findById(20L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
            LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

            jobRunService.completeBatchRun(20L, 10, 2);

            LocalDateTime completedAt = captor.getValue().getCompletedAt();
            assertThat(completedAt).isAfterOrEqualTo(before);
            assertThat(completedAt).isBeforeOrEqualTo(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        }

        @Test
        @DisplayName("sets the final succeeded and failed counts")
        void completeBatchRun_setsFinalCounts() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(21L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .succeeded(3)
                    .failed(0)
                    .build();
            when(jobRunRepository.findById(21L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.completeBatchRun(21L, 18, 4);

            assertThat(captor.getValue().getSucceeded()).isEqualTo(18);
            assertThat(captor.getValue().getFailed()).isEqualTo(4);
        }

        @Test
        @DisplayName("sets a positive durationMs calculated from startedAt")
        void completeBatchRun_setPositiveDurationMs() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(10);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(22L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .build();
            when(jobRunRepository.findById(22L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.completeBatchRun(22L, 5, 0);

            assertThat(captor.getValue().getDurationMs()).isGreaterThan(0L);
        }

        @Test
        @DisplayName("4-param overload sets totalCostMicroDollars on the entity")
        void completeBatchRun_withCost_setsTotalCostMicroDollars() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(24L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .build();
            when(jobRunRepository.findById(24L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.completeBatchRun(24L, 10, 2, 123456L);

            JobRunEntity saved = captor.getValue();
            assertThat(saved.getTotalCostMicroDollars()).isEqualTo(123456L);
            assertThat(saved.getSucceeded()).isEqualTo(10);
            assertThat(saved.getFailed()).isEqualTo(2);
        }

        @Test
        @DisplayName("3-param overload defaults cost to zero")
        void completeBatchRun_3param_defaultsCostToZero() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(25L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .build();
            when(jobRunRepository.findById(25L)).thenReturn(Optional.of(jobRun));
            ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
            when(jobRunRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

            jobRunService.completeBatchRun(25L, 5, 0);

            assertThat(captor.getValue().getTotalCostMicroDollars()).isEqualTo(0L);
        }

        @Test
        @DisplayName("does not query apiCallLogRepository (batch runs have no individual call logs)")
        void completeBatchRun_doesNotQueryApiCallLog() {
            LocalDateTime startTime = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
            JobRunEntity jobRun = JobRunEntity.builder()
                    .id(23L)
                    .runType(RunType.SCHEDULED_BATCH)
                    .startedAt(startTime)
                    .build();
            when(jobRunRepository.findById(23L)).thenReturn(Optional.of(jobRun));
            when(jobRunRepository.save(jobRun)).thenReturn(jobRun);

            jobRunService.completeBatchRun(23L, 5, 0);

            verifyNoInteractions(apiCallLogRepository);
        }

        @Test
        @DisplayName("does not save when entity not found")
        void completeBatchRun_entityNotFound_doesNotSave() {
            when(jobRunRepository.findById(999L)).thenReturn(Optional.empty());

            jobRunService.completeBatchRun(999L, 5, 0);

            verify(jobRunRepository, never()).save(any(JobRunEntity.class));
        }
    }

    @Nested
    @DisplayName("getRecentRunsAllTypes()")
    class GetRecentRunsAllTypesTests {

        @Test
        @DisplayName("returns up to the limit from results found within the last 7 days")
        void getRecentRunsAllTypes_returnsUpToLimit() {
            List<JobRunEntity> allRuns = List.of(
                    JobRunEntity.builder().id(1L).build(),
                    JobRunEntity.builder().id(2L).build(),
                    JobRunEntity.builder().id(3L).build()
            );
            when(jobRunRepository.findByStartedAtAfterOrderByStartedAtDesc(any()))
                    .thenReturn(allRuns);

            List<JobRunEntity> result = jobRunService.getRecentRunsAllTypes(2);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getId()).isEqualTo(1L);
            assertThat(result.get(1).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("queries with a since timestamp close to 7 days ago")
        void getRecentRunsAllTypes_queriesLast7Days() {
            when(jobRunRepository.findByStartedAtAfterOrderByStartedAtDesc(any()))
                    .thenReturn(List.of());
            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);

            jobRunService.getRecentRunsAllTypes(10);

            verify(jobRunRepository).findByStartedAtAfterOrderByStartedAtDesc(captor.capture());
            LocalDateTime since = captor.getValue();
            LocalDateTime sevenDaysAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
            assertThat(since).isAfterOrEqualTo(sevenDaysAgo.minusSeconds(5));
            assertThat(since).isBeforeOrEqualTo(sevenDaysAgo.plusSeconds(5));
        }
    }

    @Nested
    @DisplayName("getBatchForJobRun()")
    class GetBatchForJobRunTests {

        @Test
        @DisplayName("returns batch entity when found")
        void getBatchForJobRun_found_returnsBatch() {
            ForecastBatchEntity batch = new ForecastBatchEntity(
                    "msgbatch_test", ForecastBatchEntity.BatchType.FORECAST, 10,
                    java.time.Instant.now().plusSeconds(86400));
            batch.setJobRunId(42L);
            when(forecastBatchRepository.findByJobRunId(42L)).thenReturn(Optional.of(batch));

            Optional<ForecastBatchEntity> result = jobRunService.getBatchForJobRun(42L);

            assertThat(result).isPresent();
            assertThat(result.get().getAnthropicBatchId()).isEqualTo("msgbatch_test");
        }

        @Test
        @DisplayName("returns empty when no batch linked to job run")
        void getBatchForJobRun_notFound_returnsEmpty() {
            when(forecastBatchRepository.findByJobRunId(999L)).thenReturn(Optional.empty());

            Optional<ForecastBatchEntity> result = jobRunService.getBatchForJobRun(999L);

            assertThat(result).isEmpty();
        }
    }
}
