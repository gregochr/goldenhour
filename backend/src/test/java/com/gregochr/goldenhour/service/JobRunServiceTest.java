package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobName;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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

    @InjectMocks
    private JobRunService jobRunService;

    @Test
    @DisplayName("startRun() creates a job run with correct job name")
    void startRun_createsJobRunWithJobName() {
        JobRunEntity mockEntity = JobRunEntity.builder()
                .id(1L)
                .jobName(JobName.SONNET)
                .build();
        when(jobRunRepository.save(any())).thenReturn(mockEntity);

        JobRunEntity result = jobRunService.startRun(JobName.SONNET, false);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getJobName()).isEqualTo(JobName.SONNET);
        verify(jobRunRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("logApiCall() records API call with all details and cost")
    void logApiCall_recordsApiCallWithDetails() {
        ArgumentCaptor<com.gregochr.goldenhour.entity.ApiCallLogEntity> captor =
                ArgumentCaptor.forClass(com.gregochr.goldenhour.entity.ApiCallLogEntity.class);
        when(apiCallLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(costCalculator.calculateCost(ServiceName.ANTHROPIC, EvaluationModel.SONNET)).thenReturn(13);  // 1.3p

        jobRunService.logApiCall(
                1L, ServiceName.ANTHROPIC, "POST",
                "https://api.anthropic.com/messages",
                "{\"model\":\"claude-opus\"}",
                250L, 200, null, true, null, EvaluationModel.SONNET);

        verify(apiCallLogRepository, times(1)).save(captor.capture());
        com.gregochr.goldenhour.entity.ApiCallLogEntity logged = captor.getValue();
        assertThat(logged.getJobRunId()).isEqualTo(1L);
        assertThat(logged.getService()).isEqualTo(ServiceName.ANTHROPIC);
        assertThat(logged.getDurationMs()).isEqualTo(250L);
        assertThat(logged.getSucceeded()).isTrue();
        assertThat(logged.getCostPence()).isEqualTo(13);
    }

    @Test
    @DisplayName("completeRun() sets completion time and duration")
    void completeRun_setsCompletionTimeAndDuration() {
        LocalDateTime startTime = LocalDateTime.now().minus(1, ChronoUnit.SECONDS);
        JobRunEntity jobRun = JobRunEntity.builder()
                .id(1L)
                .jobName(JobName.HAIKU)
                .startedAt(startTime)
                .build();
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        jobRunService.completeRun(jobRun, 5, 2);

        ArgumentCaptor<JobRunEntity> captor = ArgumentCaptor.forClass(JobRunEntity.class);
        verify(jobRunRepository, times(1)).save(captor.capture());
        JobRunEntity completed = captor.getValue();
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getDurationMs()).isGreaterThan(0L);
        assertThat(completed.getSucceeded()).isEqualTo(5);
        assertThat(completed.getFailed()).isEqualTo(2);
    }

    @Test
    @DisplayName("getRecentRuns() returns paginated results for job type")
    void getRecentRuns_returnsPaginatedResults() {
        when(jobRunRepository.findByJobNameOrderByStartedAtDesc(eq(JobName.WEATHER), any()))
                .thenReturn(java.util.List.of(
                        JobRunEntity.builder().id(1L).jobName(JobName.WEATHER).build(),
                        JobRunEntity.builder().id(2L).jobName(JobName.WEATHER).build()
                ));

        java.util.List<JobRunEntity> result = jobRunService.getRecentRuns(JobName.WEATHER, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getJobName()).isEqualTo(JobName.WEATHER);
    }

    @Test
    @DisplayName("getApiCallsForRun() returns all calls for a job run")
    void getApiCallsForRun_returnsAllCallsForJobRun() {
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L))
                .thenReturn(java.util.List.of(
                        com.gregochr.goldenhour.entity.ApiCallLogEntity.builder()
                                .jobRunId(1L)
                                .service(ServiceName.OPEN_METEO_FORECAST)
                                .build(),
                        com.gregochr.goldenhour.entity.ApiCallLogEntity.builder()
                                .jobRunId(1L)
                                .service(ServiceName.ANTHROPIC)
                                .build()
                ));

        java.util.List<com.gregochr.goldenhour.entity.ApiCallLogEntity> result =
                jobRunService.getApiCallsForRun(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getService()).isEqualTo(ServiceName.OPEN_METEO_FORECAST);
    }
}
