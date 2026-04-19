package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BatchSubmitRequest;
import com.gregochr.goldenhour.model.RegionSummaryDto;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceResultResponse;
import com.gregochr.goldenhour.service.batch.ForceSubmitBatchService.ForceSubmitResult;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService.BatchSubmitResult;
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
import java.util.stream.Collectors;

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
    private final LocationService locationService;

    /**
     * Constructs the batch admin controller.
     *
     * @param batchRepository         repository for reading batch records
     * @param batchEvaluationService  service exposing the batch guard reset operation
     * @param forceSubmitBatchService service for force-submitting test batches
     * @param locationService         service for retrieving enabled locations
     */
    public BatchAdminController(ForecastBatchRepository batchRepository,
            ScheduledBatchEvaluationService batchEvaluationService,
            ForceSubmitBatchService forceSubmitBatchService,
            LocationService locationService) {
        this.batchRepository = batchRepository;
        this.batchEvaluationService = batchEvaluationService;
        this.forceSubmitBatchService = forceSubmitBatchService;
        this.locationService = locationService;
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
     * Returns all regions with the count of enabled locations in each.
     *
     * @return list of region summaries, sorted alphabetically
     */
    @GetMapping("/regions")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RegionSummaryDto> getRegionsWithCounts() {
        List<LocationEntity> enabled = locationService.findAllEnabled();
        Map<Long, List<LocationEntity>> byRegion = enabled.stream()
                .filter(loc -> loc.getRegion() != null)
                .collect(Collectors.groupingBy(loc -> loc.getRegion().getId()));

        return byRegion.entrySet().stream()
                .map(entry -> {
                    LocationEntity sample = entry.getValue().get(0);
                    return new RegionSummaryDto(
                            entry.getKey(),
                            sample.getRegion().getName(),
                            entry.getValue().size());
                })
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    /**
     * Submits a scheduled batch filtered to the given region IDs. Uses the same triage
     * and stability gates as the overnight scheduled job.
     *
     * <p>Returns 409 if a batch is already in SUBMITTED status.
     *
     * @param request optional region IDs — null or empty means all regions
     * @return submission result with batch ID and request count
     */
    @PostMapping("/submit-scheduled")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchSubmitResult> submitScheduledBatch(
            @RequestBody(required = false) BatchSubmitRequest request) {
        if (hasActiveBatch()) {
            return ResponseEntity.status(409).build();
        }

        List<Long> regionIds = request != null ? request.regionIds() : null;
        BatchSubmitResult result =
                batchEvaluationService.submitScheduledBatchForRegions(regionIds);
        if (result == null) {
            return ResponseEntity.unprocessableEntity().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Submits a JFDI batch — bypasses all triage gates, evaluates all dates T+0 to T+3
     * with both SUNRISE and SUNSET for every location in the selected regions.
     *
     * <p>Returns 409 if a batch is already in SUBMITTED status.
     *
     * @param request optional region IDs — null or empty means all regions
     * @return submission result with batch ID and request count
     */
    @PostMapping("/submit-jfdi")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BatchSubmitResult> submitJfdiBatch(
            @RequestBody(required = false) BatchSubmitRequest request) {
        if (hasActiveBatch()) {
            return ResponseEntity.status(409).build();
        }

        List<Long> regionIds = request != null ? request.regionIds() : null;
        BatchSubmitResult result = forceSubmitBatchService.submitJfdiBatch(regionIds);
        if (result == null) {
            return ResponseEntity.unprocessableEntity().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Checks whether any batch is currently in SUBMITTED status (still processing).
     */
    private boolean hasActiveBatch() {
        return !batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED)
                .isEmpty();
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
