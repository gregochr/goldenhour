package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.JobRunService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for job run metrics and API call logging.
 *
 * <p>Accessible only to ADMIN users. Provides real-time visibility into scheduled job
 * performance and external API reliability.
 */
@RestController
@RequestMapping("/api/metrics")
public class JobMetricsController {

    private final JobRunService jobRunService;

    /**
     * Constructs a {@code JobMetricsController}.
     *
     * @param jobRunService the job run metrics service
     */
    public JobMetricsController(JobRunService jobRunService) {
        this.jobRunService = jobRunService;
    }

    /**
     * Retrieves recent job runs, optionally filtered by run type.
     *
     * @param runType the run type filter (optional; e.g. VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE)
     * @param page    the page index (default 0)
     * @param size    the page size (default 20)
     * @return a page of job runs
     */
    @GetMapping("/job-runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getJobRuns(
            @RequestParam(required = false) String runType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<JobRunEntity> runs;

            if (runType != null && !runType.isEmpty()) {
                RunType rt = RunType.valueOf(runType.toUpperCase());
                runs = jobRunService.getRecentRuns(rt, size * (page + 1)).stream()
                        .skip((long) page * size)
                        .limit(size)
                        .toList();
            } else {
                runs = jobRunService.getRecentRunsAllTypes(size);
            }

            return ResponseEntity.ok(new PageImpl<>(runs, PageRequest.of(page, size), runs.size()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid run type: " + runType);
        }
    }

    /**
     * Retrieves all API calls for a specific job run.
     *
     * @param jobRunId the job run ID
     * @return list of API call logs ordered by call time
     */
    @GetMapping("/api-calls")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getApiCalls(@RequestParam Long jobRunId) {
        List<ApiCallLogEntity> calls = jobRunService.getApiCallsForRun(jobRunId);
        return ResponseEntity.ok(calls);
    }
}
