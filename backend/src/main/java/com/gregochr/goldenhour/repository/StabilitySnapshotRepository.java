package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.StabilitySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link StabilitySnapshotEntity} — persisted stability
 * classifications per Open-Meteo grid cell.
 */
public interface StabilitySnapshotRepository extends JpaRepository<StabilitySnapshotEntity, Long> {

    /**
     * Finds the snapshot row for a specific grid cell.
     *
     * @param gridCellKey the canonical grid cell key (e.g. {@code "54.7500,-1.6250"})
     * @return the entity if present
     */
    Optional<StabilitySnapshotEntity> findByGridCellKey(String gridCellKey);

    /**
     * Finds all snapshot rows classified after the given threshold, used for
     * the staleness guard (e.g. only rows classified within the last 24 hours).
     *
     * @param threshold the cutoff instant
     * @return fresh snapshot rows
     */
    List<StabilitySnapshotEntity> findByClassifiedAtAfter(Instant threshold);
}
