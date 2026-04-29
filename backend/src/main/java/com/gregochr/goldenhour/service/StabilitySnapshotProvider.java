package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.StabilitySnapshotEntity;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.repository.StabilitySnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Mediates access to the most recent forecast-stability snapshot, holding both
 * the in-memory cache and the durable backing store on behalf of every consumer
 * that needs to read or refresh it.
 *
 * <p>Extracted from {@code ForecastCommandExecutor} in v2.11.13 (Pass 3.2.2)
 * so the executor's responsibility stays "execute commands" and snapshot
 * read/write concerns live behind this single collaborator.
 *
 * <h2>Concurrency contract</h2>
 *
 * <p>Both the in-memory cache and the database are last-write-wins:
 * <ul>
 *   <li>The in-memory {@link AtomicReference} guarantees readers always see a
 *       fully-formed {@link StabilitySummaryResponse} — never a torn or
 *       partially-populated one.</li>
 *   <li>The database upserts each grid cell with a {@code findByGridCellKey}
 *       + {@code save} pair. Two concurrent {@link #update} calls for the same
 *       grid cell race on that read-then-write window; the later commit wins.
 *       This matches the pre-extraction behaviour and is acceptable for a
 *       snapshot whose semantics are "most recent classification wins".</li>
 * </ul>
 *
 * <p>{@link #update} is therefore safe to call from any thread. Callers that
 * need stronger ordering guarantees must serialise externally (e.g. the
 * scheduled batch flow's {@code AtomicBoolean} guard).
 *
 * <h2>Failure semantics</h2>
 *
 * <p>{@link #update} sets the in-memory cache before attempting the database
 * persist. If the database write fails, the in-memory cache remains updated
 * and the failure is logged at WARN — it never bubbles up. The in-memory value
 * is therefore authoritative within a process for as long as the process
 * lives. Container restarts re-load from the most recent successfully-persisted
 * snapshot via {@link #getLatestStabilitySummary}'s DB fallback. Operators
 * monitor stability-persist failures via the WARN log line; the batch pipeline
 * never crashes on a stale DB.
 *
 * <p>Read failures are similarly non-fatal: a DB exception during the
 * {@link #getLatestStabilitySummary} fallback is logged and the method returns
 * {@code null}, leaving callers to apply their own UNSETTLED defaults.
 */
@Service
public class StabilitySnapshotProvider {

    private static final Logger LOG = LoggerFactory.getLogger(StabilitySnapshotProvider.class);

    /** Hours after which a persisted snapshot is considered stale. */
    private static final long SNAPSHOT_STALENESS_HOURS = 24;

    private final StabilitySnapshotRepository stabilitySnapshotRepository;

    /** Most recent stability snapshot, populated after each scheduled triage run. */
    private final AtomicReference<StabilitySummaryResponse> latestStabilitySummary =
            new AtomicReference<>();

    /**
     * Constructs the provider.
     *
     * @param stabilitySnapshotRepository repository for persisting and retrieving snapshots
     */
    public StabilitySnapshotProvider(StabilitySnapshotRepository stabilitySnapshotRepository) {
        this.stabilitySnapshotRepository = stabilitySnapshotRepository;
    }

    /**
     * Returns the most recent stability summary. Tries in-memory first, then falls
     * back to the database (with a 24-hour staleness guard). Returns {@code null} if
     * no fresh snapshot is available from either source.
     *
     * @return latest snapshot, or {@code null}
     */
    public StabilitySummaryResponse getLatestStabilitySummary() {
        StabilitySummaryResponse inMemory = latestStabilitySummary.get();
        if (inMemory != null) {
            return inMemory;
        }
        return loadSnapshotFromDb();
    }

    /**
     * Sets the in-memory cache and persists the snapshot to the database.
     *
     * <p>The in-memory write is atomic and unconditional. The database persist
     * is best-effort: a DB failure is logged at WARN and never propagated, so
     * the in-memory cache and DB may diverge briefly until the next successful
     * {@link #update}. See the class JavaDoc for full concurrency and failure
     * semantics.
     *
     * <p>A {@code null} summary is ignored — the in-memory cache is untouched
     * and no DB write occurs.
     *
     * @param summary the summary to publish (may be {@code null}, in which case the call is a no-op)
     */
    public void update(StabilitySummaryResponse summary) {
        if (summary == null) {
            return;
        }
        latestStabilitySummary.set(summary);
        persistSnapshot(summary);
    }

    /**
     * Write-through: persists the stability snapshot to the database so it survives
     * container restarts. Non-fatal — a DB failure never breaks the batch pipeline.
     */
    private void persistSnapshot(StabilitySummaryResponse summary) {
        try {
            Instant now = Instant.now();
            for (StabilitySummaryResponse.GridCellDetail cell : summary.cells()) {
                StabilitySnapshotEntity entity = stabilitySnapshotRepository
                        .findByGridCellKey(cell.gridCellKey())
                        .orElseGet(() -> {
                            StabilitySnapshotEntity e = new StabilitySnapshotEntity();
                            e.setGridCellKey(cell.gridCellKey());
                            return e;
                        });
                entity.setGridLat(cell.gridLat());
                entity.setGridLng(cell.gridLng());
                entity.setStabilityLevel(cell.stability());
                entity.setReason(cell.reason());
                entity.setEvaluationWindowDays(cell.evaluationWindowDays());
                entity.setLocationNames(String.join(",", cell.locationNames()));
                entity.setClassifiedAt(now);
                entity.setUpdatedAt(now);
                stabilitySnapshotRepository.save(entity);
            }
            LOG.info("[STABILITY] Persisted snapshot for {} grid cells", summary.cells().size());
        } catch (Exception e) {
            LOG.warn("[STABILITY] Failed to persist snapshot — in-memory still valid: {}",
                    e.getMessage());
        }
    }

    /**
     * Read-through: loads the stability snapshot from the database when the in-memory
     * cache is empty (e.g. after container restart). Applies a 24-hour staleness guard —
     * snapshots older than that are treated as missing.
     *
     * @return reconstructed snapshot, or {@code null} if no fresh rows exist
     */
    private StabilitySummaryResponse loadSnapshotFromDb() {
        try {
            Instant staleThreshold = Instant.now().minus(SNAPSHOT_STALENESS_HOURS, ChronoUnit.HOURS);
            List<StabilitySnapshotEntity> fresh = stabilitySnapshotRepository
                    .findByClassifiedAtAfter(staleThreshold);

            if (fresh.isEmpty()) {
                LOG.info("[STABILITY] No fresh snapshot in DB (threshold {}h)",
                        SNAPSHOT_STALENESS_HOURS);
                return null;
            }

            Map<ForecastStability, Long> countsByStability = fresh.stream()
                    .collect(Collectors.groupingBy(
                            StabilitySnapshotEntity::getStabilityLevel, Collectors.counting()));

            List<StabilitySummaryResponse.GridCellDetail> cellDetails = fresh.stream()
                    .map(e -> new StabilitySummaryResponse.GridCellDetail(
                            e.getGridCellKey(), e.getGridLat(), e.getGridLng(),
                            e.getStabilityLevel(), e.getReason(),
                            e.getEvaluationWindowDays(),
                            List.of(e.getLocationNames().split(","))))
                    .sorted(Comparator.comparing(
                            StabilitySummaryResponse.GridCellDetail::gridCellKey))
                    .toList();

            Instant mostRecent = fresh.stream()
                    .map(StabilitySnapshotEntity::getClassifiedAt)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.now());

            StabilitySummaryResponse summary = new StabilitySummaryResponse(
                    mostRecent, fresh.size(), countsByStability, cellDetails);
            latestStabilitySummary.set(summary);

            long ageHours = ChronoUnit.HOURS.between(mostRecent, Instant.now());
            LOG.info("[STABILITY] Loaded snapshot from DB: {} grid cells, age={}h",
                    fresh.size(), ageHours);
            return summary;
        } catch (Exception e) {
            LOG.warn("[STABILITY] Failed to load snapshot from DB: {}", e.getMessage());
            return null;
        }
    }
}
