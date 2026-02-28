package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ModelSelectionEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for the per-run-type evaluation model selection.
 */
@Repository
public interface ModelSelectionRepository extends JpaRepository<ModelSelectionEntity, Long> {

    /**
     * Get the currently active model selection (legacy — returns most recent row).
     *
     * @return the most recently updated model selection, or empty if none exist
     */
    Optional<ModelSelectionEntity> findFirstByOrderByUpdatedAtDesc();

    /**
     * Get the model selection for a specific run type.
     *
     * @param runType the run type (VERY_SHORT_TERM, SHORT_TERM, or LONG_TERM)
     * @return the model selection for that run type, or empty if not seeded
     */
    Optional<ModelSelectionEntity> findByRunType(RunType runType);
}
