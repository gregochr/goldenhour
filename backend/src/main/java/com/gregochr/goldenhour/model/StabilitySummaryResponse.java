package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ForecastStability;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of stability classifications from the most recent scheduled forecast run.
 *
 * <p>Populated by {@code ForecastCommandExecutor} after each triage cycle and served by
 * {@code StabilityController}. Returns {@code null} until at least one automatic run has
 * completed.
 *
 * @param generatedAt        when the stability snapshot was last computed
 * @param totalGridCells     number of grid cells classified
 * @param countsByStability  aggregate counts keyed by stability level
 * @param cells              per-grid-cell detail with location names and reason
 */
public record StabilitySummaryResponse(
        Instant generatedAt,
        int totalGridCells,
        Map<ForecastStability, Long> countsByStability,
        List<GridCellDetail> cells) {

    /**
     * Compact constructor — defensive copies of mutable collections.
     */
    public StabilitySummaryResponse {
        countsByStability = Collections.unmodifiableMap(countsByStability);
        cells = List.copyOf(cells);
    }

    /**
     * Detail for a single Open-Meteo grid cell.
     *
     * @param gridCellKey          canonical key (e.g. {@code "54.7500,-1.6250"})
     * @param gridLat              snapped grid latitude
     * @param gridLng              snapped grid longitude
     * @param stability            the classified stability level
     * @param reason               human-readable explanation of the signals
     * @param evaluationWindowDays days the evaluation window extends from today
     * @param locationNames        names of enabled locations that share this grid cell
     */
    public record GridCellDetail(
            String gridCellKey,
            double gridLat,
            double gridLng,
            ForecastStability stability,
            String reason,
            int evaluationWindowDays,
            List<String> locationNames) {

        /**
         * Compact constructor — defensive copy of locationNames.
         */
        public GridCellDetail {
            locationNames = List.copyOf(locationNames);
        }
    }
}
