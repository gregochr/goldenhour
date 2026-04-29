package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for inspecting weather stability classifications.
 *
 * <p>The stability summary is populated after each scheduled triage run and held by
 * {@link StabilitySnapshotProvider}. Returns 204 until the first scheduled run has
 * completed (manual runs bypass the stability filter and do not update the snapshot).
 */
@RestController
@RequestMapping("/api/admin/stability")
public class StabilityController {

    private final StabilitySnapshotProvider stabilitySnapshotProvider;

    /**
     * Constructs a {@code StabilityController}.
     *
     * @param stabilitySnapshotProvider provider of the latest stability snapshot
     */
    public StabilityController(StabilitySnapshotProvider stabilitySnapshotProvider) {
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
    }

    /**
     * Returns the most recent forecast stability summary.
     *
     * <p>Contains per-grid-cell stability classifications (SETTLED / TRANSITIONAL /
     * UNSETTLED), the signals that drove each classification, the evaluation window
     * in days, and the location names that share each grid cell.
     *
     * @return 200 with summary body, or 204 if no scheduled run has completed yet
     */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StabilitySummaryResponse> getStabilitySummary() {
        StabilitySummaryResponse summary = stabilitySnapshotProvider.getLatestStabilitySummary();
        if (summary == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(summary);
    }
}
