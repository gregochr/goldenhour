package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for monitoring Anthropic Batch API submissions.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/admin/batches")
public class BatchAdminController {

    private final ForecastBatchRepository batchRepository;
    private final ScheduledBatchEvaluationService batchEvaluationService;

    /**
     * Constructs the batch admin controller.
     *
     * @param batchRepository       repository for reading batch records
     * @param batchEvaluationService service exposing the batch guard reset operation
     */
    public BatchAdminController(ForecastBatchRepository batchRepository,
            ScheduledBatchEvaluationService batchEvaluationService) {
        this.batchRepository = batchRepository;
        this.batchEvaluationService = batchEvaluationService;
    }

    /**
     * Returns the 20 most recent batch submissions regardless of status.
     *
     * @return list of batch records, newest first
     */
    @GetMapping("/recent")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ForecastBatchEntity> getRecentBatches() {
        return batchRepository.findTop20ByOrderBySubmittedAtDesc();
    }

    /**
     * Returns a single batch record by its database ID.
     *
     * @param id the batch database ID
     * @return the batch record, or 404 if not found
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ForecastBatchEntity> getBatch(@PathVariable Long id) {
        return batchRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Forcibly resets the {@code forecastBatchRunning} and {@code auroraBatchRunning} guards
     * in {@link ScheduledBatchEvaluationService} to {@code false}.
     *
     * <p>This is an emergency escape hatch. Under normal operation the guards are always
     * cleared by the {@code finally} block in each submit method, so this endpoint should
     * never be needed. Call it when a guard appears stuck and new batch submissions are being
     * silently rejected.
     *
     * @return 200 with {@code {"forecastReset": true, "auroraReset": true}}
     */
    @PostMapping("/reset-guards")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Boolean> resetBatchGuards() {
        batchEvaluationService.resetBatchGuards();
        return Map.of("forecastReset", true, "auroraReset", true);
    }
}
