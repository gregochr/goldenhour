package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.ApiCallLogDto;
import com.gregochr.goldenhour.model.DispositionBreakdownResponse;
import com.gregochr.goldenhour.model.JobRunDto;
import com.gregochr.goldenhour.service.BatchSummaryDeriver;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.batch.ForecastDispositionService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for job run metrics and API call logging.
 *
 * <p>Accessible only to ADMIN users. Provides real-time visibility into scheduled job
 * performance and external API reliability.
 */
@RestController
@RequestMapping("/api/metrics")
public class JobMetricsController {

    /** Largest page size honoured; anything bigger is clamped down to this. */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Ceiling on rows fetched to satisfy a deep page. Run-type paging is skip-based, so the
     * fetch grows with the page index; without a ceiling a caller could ask the DB for billions
     * of rows (or overflow the int the service takes) with a single query string.
     */
    private static final int MAX_SCANNED_ROWS = 5_000;

    private final JobRunService jobRunService;
    private final BatchSummaryDeriver batchSummaryDeriver;
    private final ForecastDispositionService dispositionService;

    /**
     * Constructs a {@code JobMetricsController}.
     *
     * @param jobRunService        the job run metrics service
     * @param batchSummaryDeriver  derives the read-time summary line for batch run rows
     * @param dispositionService   serves the per-candidate disposition breakdown for a job run
     */
    public JobMetricsController(JobRunService jobRunService,
            BatchSummaryDeriver batchSummaryDeriver,
            ForecastDispositionService dispositionService) {
        this.jobRunService = jobRunService;
        this.batchSummaryDeriver = batchSummaryDeriver;
        this.dispositionService = dispositionService;
    }

    /**
     * Retrieves recent job runs, optionally filtered by run type.
     *
     * <p>A negative {@code page} or a non-positive {@code size} is rejected with 400 — there is no
     * sensible reading of either. An oversized {@code size} is clamped to {@value #MAX_PAGE_SIZE}
     * rather than rejected, so an over-eager UI still gets its data.
     *
     * @param runType the run type filter (optional; e.g. VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE)
     * @param page    the page index (default 0, must be &gt;= 0)
     * @param size    the page size (default 20, clamped to {@value #MAX_PAGE_SIZE})
     * @return a page of job runs, or 400 for an unusable page index / size
     */
    @GetMapping("/job-runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getJobRuns(
            @RequestParam(required = false) String runType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1) {
            return ResponseEntity.badRequest().body("page must be >= 0 and size must be >= 1");
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);

        try {
            List<JobRunEntity> runs;

            if (runType != null && !runType.isEmpty()) {
                RunType rt = RunType.valueOf(runType.toUpperCase());
                runs = jobRunService.getRecentRuns(rt, scanLimit(page, pageSize)).stream()
                        .skip((long) page * pageSize)
                        .limit(pageSize)
                        .toList();
            } else {
                runs = jobRunService.getRecentRunsAllTypes(pageSize);
            }

            runs.forEach(run -> batchSummaryDeriver.derive(run).ifPresent(run::setBatchSummary));

            List<JobRunDto> content = runs.stream().map(JobRunDto::from).toList();
            return ResponseEntity.ok(new PageImpl<>(content, PageRequest.of(page, pageSize), runs.size()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid run type: " + runType);
        }
    }

    /**
     * Rows to fetch so that the requested page can be skipped to, bounded and overflow-free.
     *
     * <p>Computed in {@code long} arithmetic because {@code size * (page + 1)} overflows int for
     * large page indices, which would hand the service a negative or absurd limit.
     *
     * @param page     the validated (non-negative) page index
     * @param pageSize the clamped page size
     * @return the fetch limit, never above {@value #MAX_SCANNED_ROWS}
     */
    private static int scanLimit(int page, int pageSize) {
        return (int) Math.min((long) pageSize * (page + 1L), MAX_SCANNED_ROWS);
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
        return ResponseEntity.ok(calls.stream().map(ApiCallLogDto::from).toList());
    }

    /**
     * Returns a token usage and cost summary for a batch job run.
     *
     * @param jobRunId the job run ID linked to a forecast batch
     * @return batch summary map, or 404 if no batch is linked
     */
    @GetMapping("/batch-summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getBatchSummary(@RequestParam Long jobRunId) {
        return jobRunService.getBatchForJobRun(jobRunId)
                .map(this::toBatchSummaryMap)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns the per-candidate disposition breakdown for a batch job run —
     * the data behind the Job Run detail UI's "Disposition Breakdown" section.
     *
     * <p>For a given {@code jobRunId}, returns the total count, per-disposition
     * counts, and every recorded disposition row (EVALUATED + every SKIPPED_*).
     * Empty but well-formed when the run has no disposition rows (the cycle's
     * non-primary bucket job runs and all non-batch job runs).
     *
     * @param jobRunId the job run id to query
     * @return breakdown response (always 200, possibly with zero counts)
     */
    @GetMapping("/disposition-breakdown")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DispositionBreakdownResponse> getDispositionBreakdown(
            @RequestParam Long jobRunId) {
        return ResponseEntity.ok(dispositionService.getBreakdownForJobRun(jobRunId));
    }

    private Map<String, Object> toBatchSummaryMap(ForecastBatchEntity batch) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalInputTokens", batch.getTotalInputTokens());
        map.put("totalOutputTokens", batch.getTotalOutputTokens());
        map.put("totalCacheReadTokens", batch.getTotalCacheReadTokens());
        map.put("totalCacheCreationTokens", batch.getTotalCacheCreationTokens());

        BigDecimal costUsd = batch.getEstimatedCostUsd();
        long costMicro = costUsd != null
                ? costUsd.multiply(BigDecimal.valueOf(1_000_000)).longValue() : 0L;
        map.put("estimatedCostMicroDollars", costMicro);

        map.put("requestCount", batch.getRequestCount());
        map.put("succeededCount", batch.getSucceededCount());
        map.put("erroredCount", batch.getErroredCount());
        map.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
        return map;
    }
}
