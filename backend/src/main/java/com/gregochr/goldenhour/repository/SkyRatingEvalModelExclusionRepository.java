package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SkyRatingEvalModelExclusionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link SkyRatingEvalModelExclusionEntity} — the models switched off for the weekly
 * Sky-Rating Eval.
 */
public interface SkyRatingEvalModelExclusionRepository
        extends JpaRepository<SkyRatingEvalModelExclusionEntity, String> {
}
