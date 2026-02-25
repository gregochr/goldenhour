package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.JobName;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Service for tracking scheduled job runs and API call timings for observability and debugging.
 */
@Service
public class JobRunService {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunService.class);

    private final JobRunRepository jobRunRepository;
    private final ApiCallLogRepository apiCallLogRepository;

    /**
     * Constructs a {@code JobRunService}.
     *
     * @param jobRunRepository     repository for job run entities
     * @param apiCallLogRepository repository for API call log entities
     */
    public JobRunService(JobRunRepository jobRunRepository,
            ApiCallLogRepository apiCallLogRepository) {
        this.jobRunRepository = jobRunRepository;
        this.apiCallLogRepository = apiCallLogRepository;
    }

    /**
     * Starts a new job run with the given name.
     *
     * @param jobName the name of the job
     * @return the newly created job run entity
     */
    public JobRunEntity startRun(JobName jobName) {
        JobRunEntity jobRun = JobRunEntity.builder()
                .jobName(jobName)
                .startedAt(LocalDateTime.now(ZoneOffset.UTC))
                .locationsProcessed(0)
                .succeeded(0)
                .failed(0)
                .build();
        return jobRunRepository.save(jobRun);
    }

    /**
     * Records a single API call made during a job run.
     *
     * @param jobRunId       the job run ID
     * @param service        the service name
     * @param requestMethod  HTTP method (GET, POST, etc.) or null
     * @param requestUrl     full request URL
     * @param requestBody    request body (JSON) or null
     * @param durationMs     duration in milliseconds
     * @param statusCode     HTTP status code or null
     * @param responseBody   response body on error, or null on success
     * @param succeeded      true if the call succeeded
     * @param errorMessage   brief error message if failed, or null
     * @return the newly created API call log entity
     */
    public ApiCallLogEntity logApiCall(Long jobRunId, ServiceName service,
            String requestMethod, String requestUrl, String requestBody,
            long durationMs, Integer statusCode, String responseBody,
            boolean succeeded, String errorMessage) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ApiCallLogEntity log = ApiCallLogEntity.builder()
                .jobRunId(jobRunId)
                .service(service)
                .calledAt(now)
                .completedAt(now)
                .durationMs(durationMs)
                .requestMethod(requestMethod)
                .requestUrl(requestUrl)
                .requestBody(requestBody)
                .statusCode(statusCode)
                .responseBody(responseBody)
                .succeeded(succeeded)
                .errorMessage(errorMessage)
                .createdAt(now)
                .build();
        return apiCallLogRepository.save(log);
    }

    /**
     * Completes a job run with success and failure counts.
     *
     * @param jobRun   the job run to complete
     * @param succeeded number of successful evaluations
     * @param failed   number of failed evaluations
     */
    public void completeRun(JobRunEntity jobRun, int succeeded, int failed) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long durationMs = ChronoUnit.MILLIS.between(jobRun.getStartedAt(), now);
        jobRun.setCompletedAt(now);
        jobRun.setDurationMs(durationMs);
        jobRun.setSucceeded(succeeded);
        jobRun.setFailed(failed);
        jobRunRepository.save(jobRun);
    }

    /**
     * Retrieves recent job runs for a given job name, ordered by start time descending.
     *
     * @param jobName the job name to filter by
     * @param limit   maximum number of runs to return
     * @return list of recent job runs
     */
    public List<JobRunEntity> getRecentRuns(JobName jobName, int limit) {
        return jobRunRepository.findByJobNameOrderByStartedAtDesc(jobName,
                PageRequest.of(0, Math.max(limit, 1)));
    }

    /**
     * Retrieves all API calls for a given job run.
     *
     * @param jobRunId the job run ID
     * @return list of API call logs ordered by call time
     */
    public List<ApiCallLogEntity> getApiCallsForRun(Long jobRunId) {
        return apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(jobRunId);
    }

    /**
     * Retrieves recent job runs by all job names, ordered by start time descending.
     *
     * @param limit maximum number of runs to return (across all job types)
     * @return list of recent job runs
     */
    public List<JobRunEntity> getRecentRunsAllTypes(int limit) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
        List<JobRunEntity> runs = jobRunRepository.findByStartedAtAfterOrderByStartedAtDesc(since);
        return runs.stream().limit(limit).toList();
    }
}
