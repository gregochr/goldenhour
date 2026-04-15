package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceResultResponse;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceSubmitResult;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for monitoring and testing Anthropic Batch API submissions.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/admin/batches")
public class BatchAdminController {

    private final ForecastBatchRepository batchRepository;
    private final ScheduledBatchEvaluationService batchEvaluationService;
    private final ForceSubmitBatchService forceSubmitBatchService;

    /**
     * Constructs the batch admin controller.
     *
     * @param batchRepository         repository for reading batch records
     * @param batchEvaluationService  service exposing the batch guard reset operation
     * @param forceSubmitBatchService service for force-submitting test batches
     */
    public BatchAdminController(ForecastBatchRepository batchRepository,
            ScheduledBatchEvaluationService batchEvaluationService,
            ForceSubmitBatchService forceSubmitBatchService) {
        this.batchRepository = batchRepository;
        this.batchEvaluationService = batchEvaluationService;
        this.forceSubmitBatchService = forceSubmitBatchService;
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

    /**
     * Submits a force-test batch for all locations in a region, bypassing all gates
     * (triage, stability, cache). Proves the SDK batch wiring works independently.
     *
     * @param request the force-submit request containing regionId, date, and event
     * @return submission summary with batch ID and location breakdown
     */
    @PostMapping("/force-submit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ForceSubmitResult> forceSubmit(
            @RequestBody ForceSubmitRequest request) {
        if (request.regionId() == null || request.date() == null
                || request.event() == null) {
            return ResponseEntity.badRequest().build();
        }

        TargetType targetType;
        try {
            targetType = TargetType.valueOf(request.event().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        try {
            ForceSubmitResult result = forceSubmitBatchService.forceSubmit(
                    request.regionId(), request.date(), targetType);
            if (result.batchId() == null) {
                return ResponseEntity.unprocessableEntity().body(result);
            }
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(
                    new ForceSubmitResult(null, 0, null, 0, 0, 0, List.of(e.getMessage())));
        }
    }

    /**
     * Retrieves the status and results of a force-submitted batch.
     *
     * @param batchId the Anthropic batch ID
     * @return current status and any available results
     */
    @GetMapping("/force-result/{batchId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ForceResultResponse forceResult(@PathVariable String batchId) {
        return forceSubmitBatchService.getResult(batchId);
    }

    /**
     * Request body for the force-submit endpoint.
     *
     * @param regionId the database ID of the target region
     * @param date     the forecast date (ISO format)
     * @param event    "SUNRISE" or "SUNSET"
     */
    public record ForceSubmitRequest(Long regionId, LocalDate date, String event) {}
}
