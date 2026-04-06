package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoints for monitoring Anthropic Batch API submissions.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/admin/batches")
public class BatchAdminController {

    private final ForecastBatchRepository batchRepository;

    /**
     * Constructs the batch admin controller.
     *
     * @param batchRepository repository for reading batch records
     */
    public BatchAdminController(ForecastBatchRepository batchRepository) {
        this.batchRepository = batchRepository;
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
}
