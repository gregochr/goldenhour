package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.GridCellStabilityResult;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.service.ForecastStabilityClassifier;
import com.gregochr.goldenhour.service.OpenMeteoService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Grid-cell stability classification for a batch run: classifies every unique grid
 * cell the candidate set touches, optionally publishes the resulting snapshot, and
 * resolves per-candidate stability during the triage loop.
 *
 * <p>Instance-scoped seam extracted from {@code ForecastTaskCollector}. The
 * classification map is a <em>local</em> the collector owns: {@link
 * #classifyGridCellsAndPublishSnapshot} builds and returns it, and {@link
 * #stabilityFor} takes it as a parameter and lazily fills any missing cell via
 * {@code computeIfAbsent}. This service holds no per-run state. Logs under the
 * {@link ForecastTaskCollector} category so stability diagnostics stay grouped
 * with the collector's own output.
 */
public final class GridCellStabilityService {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastTaskCollector.class);

    private final ForecastStabilityClassifier stabilityClassifier;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;

    /**
     * Constructs a {@code GridCellStabilityService}.
     *
     * @param stabilityClassifier       per-grid-cell stability classifier
     * @param stabilitySnapshotProvider provides and persists the stability snapshot
     */
    public GridCellStabilityService(ForecastStabilityClassifier stabilityClassifier,
            StabilitySnapshotProvider stabilitySnapshotProvider) {
        this.stabilityClassifier = stabilityClassifier;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
    }

    /**
     * Classifies every unique grid cell touched by the candidate set, publishes the
     * resulting snapshot via {@link StabilitySnapshotProvider#update}, and returns the
     * classification map for reuse during the per-task triage loop.
     *
     * <p>This is the canonical producer of {@code stability_snapshot} rows for the
     * overnight scheduled flow — previously written by
     * {@code ForecastCommandExecutor.applyStabilityFilter} but stranded when that
     * path's {@code @Scheduled} trigger was commented out during the v2.12
     * consolidation. Without this write the reader at
     * {@code BriefingCandidateCollector.buildStabilityLookup()} sees nothing in memory
     * or in the database and defaults every region to UNSETTLED, collapsing the
     * stability gate.
     *
     * <p>Cells whose locations lack a grid assignment are skipped (the existing
     * triage-loop helper handles those by returning a 1-day window). If no cells
     * survive classification, no snapshot is published — the previously persisted
     * snapshot remains authoritative.
     *
     * <p>The persist itself is best-effort: see the failure semantics in
     * {@link StabilitySnapshotProvider}.
     *
     * @param candidates        surviving briefing candidates
     * @param prefetchedWeather coord-key → prefetched forecast/air-quality result
     * @param ephemeral         when {@code true}, the classification is computed and
     *                          returned but the snapshot is NOT published — used by
     *                          the intraday refresh so its in-memory cost-gate does
     *                          not overwrite the morning's authoritative snapshot
     * @return classification keyed by grid-cell key (empty if no cells classified)
     */
    public Map<String, GridCellStabilityResult> classifyGridCellsAndPublishSnapshot(
            List<ForecastCandidate> candidates,
            Map<String, WeatherExtractionResult> prefetchedWeather,
            boolean ephemeral) {
        Map<String, GridCellStabilityResult> stabilityByCell = new LinkedHashMap<>();
        Map<String, Set<String>> locationsByCell = new LinkedHashMap<>();

        for (ForecastCandidate candidate : candidates) {
            LocationEntity loc = candidate.location();
            if (!loc.hasGridCell()) {
                continue;
            }
            String key = loc.gridCellKey();
            stabilityByCell.computeIfAbsent(key, k -> {
                String coordKey = OpenMeteoService.coordKey(loc.getLat(), loc.getLon());
                WeatherExtractionResult weather = prefetchedWeather.get(coordKey);
                OpenMeteoForecastResponse resp =
                        weather != null ? weather.forecastResponse() : null;
                return stabilityClassifier.classify(
                        key, loc.getGridLat(), loc.getGridLng(),
                        resp != null ? resp.getHourly() : null);
            });
            locationsByCell
                    .computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(loc.getName());
        }

        return publishSnapshot(stabilityByCell, locationsByCell, ephemeral);
    }

    /**
     * Classifies every unique grid cell touched by a synchronous-run batch and publishes
     * the snapshot. {@link ForecastPreEvalResult} counterpart of
     * {@link #classifyGridCellsAndPublishSnapshot(List, Map, boolean)} for the admin
     * synchronous path ({@code ForecastCommandExecutor}), so both forecast engines share
     * one snapshot schema and one publish rule.
     *
     * <p>Tasks without a grid cell are skipped entirely; tasks without a forecast
     * response contribute their location name to the snapshot detail but are not
     * classified (the {@link #stabilityFor} TRANSITIONAL fallback covers them during
     * filtering).
     *
     * @param batch tasks surviving the triage and sentinel phases
     * @return classification keyed by grid-cell key (empty if no cells classified)
     */
    public Map<String, GridCellStabilityResult> classifyGridCellsAndPublishSnapshot(
            List<ForecastPreEvalResult> batch) {
        Map<String, GridCellStabilityResult> stabilityByCell = new LinkedHashMap<>();
        Map<String, Set<String>> locationsByCell = new LinkedHashMap<>();

        for (ForecastPreEvalResult task : batch) {
            LocationEntity loc = task.location();
            if (!loc.hasGridCell()) {
                continue;
            }
            String key = loc.gridCellKey();
            locationsByCell
                    .computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(loc.getName());
            if (task.forecastResponse() == null) {
                continue;
            }
            stabilityByCell.computeIfAbsent(key, k -> stabilityClassifier.classify(
                    key, loc.getGridLat(), loc.getGridLng(),
                    task.forecastResponse().getHourly()));
        }

        return publishSnapshot(stabilityByCell, locationsByCell, false);
    }

    /**
     * Builds the {@link StabilitySummaryResponse} from a classification map and, unless
     * ephemeral or empty, publishes it via {@link StabilitySnapshotProvider#update}.
     * Shared tail of both {@code classifyGridCellsAndPublishSnapshot} overloads — the
     * snapshot schema and publish rule live only here.
     *
     * @param stabilityByCell classification keyed by grid-cell key
     * @param locationsByCell location names per grid cell for the snapshot detail
     * @param ephemeral       when {@code true} the snapshot is built but not published
     * @return {@code stabilityByCell}, for caller chaining
     */
    private Map<String, GridCellStabilityResult> publishSnapshot(
            Map<String, GridCellStabilityResult> stabilityByCell,
            Map<String, Set<String>> locationsByCell,
            boolean ephemeral) {
        if (stabilityByCell.isEmpty()) {
            LOG.warn("[STABILITY] No grid cells classified in this batch run "
                    + "— snapshot not written");
            return stabilityByCell;
        }

        Map<ForecastStability, Long> countsByStability = stabilityByCell.values().stream()
                .collect(Collectors.groupingBy(
                        GridCellStabilityResult::stability, Collectors.counting()));

        List<StabilitySummaryResponse.GridCellDetail> cellDetails =
                stabilityByCell.values().stream()
                        .map(r -> new StabilitySummaryResponse.GridCellDetail(
                                r.gridCellKey(), r.gridLat(), r.gridLng(),
                                r.stability(), r.reason(), r.evaluationWindowDays(),
                                List.copyOf(locationsByCell.getOrDefault(
                                        r.gridCellKey(), Set.of()))))
                        .sorted(Comparator.comparing(
                                StabilitySummaryResponse.GridCellDetail::gridCellKey))
                        .toList();

        StabilitySummaryResponse summary = new StabilitySummaryResponse(
                Instant.now(), stabilityByCell.size(), countsByStability, cellDetails);

        if (ephemeral) {
            LOG.info("[STABILITY] Ephemeral re-classification of {} grid cells "
                    + "(counts: {}) — snapshot NOT published (morning snapshot preserved)",
                    stabilityByCell.size(), countsByStability);
        } else {
            LOG.info("[STABILITY] Built snapshot for {} grid cells (counts: {})",
                    stabilityByCell.size(), countsByStability);
            stabilitySnapshotProvider.update(summary);
        }

        return stabilityByCell;
    }

    /**
     * Resolves the stability classification for a candidate, with a
     * TRANSITIONAL fallback for tasks that lack a grid cell or a forecast
     * response (matches the pre-Gate-4 behaviour of allowing T+0/T+1 for
     * unclassified locations; under the new policy, TRANSITIONAL adds T+2
     * as well, which is acceptable for the rare unclassified case).
     *
     * <p>Lazily fills {@code stabilityByCell} for the candidate's grid cell via
     * {@code computeIfAbsent}, so a cell first seen here (rather than during the
     * bulk classify pass) is classified once and reused.
     *
     * @param location       the candidate location
     * @param preEval        the candidate's pre-evaluation result (supplies hourly weather)
     * @param stabilityByCell the per-run grid-cell classification map, mutated in place
     * @return the resolved stability, or TRANSITIONAL when unclassifiable
     */
    public ForecastStability stabilityFor(LocationEntity location,
            ForecastPreEvalResult preEval,
            Map<String, GridCellStabilityResult> stabilityByCell) {
        if (!location.hasGridCell() || preEval.forecastResponse() == null) {
            return ForecastStability.TRANSITIONAL;
        }
        String key = location.gridCellKey();
        GridCellStabilityResult stability = stabilityByCell.computeIfAbsent(key, k ->
                stabilityClassifier.classify(
                        key, location.getGridLat(), location.getGridLng(),
                        preEval.forecastResponse().getHourly()));
        return stability != null ? stability.stability() : ForecastStability.TRANSITIONAL;
    }
}
