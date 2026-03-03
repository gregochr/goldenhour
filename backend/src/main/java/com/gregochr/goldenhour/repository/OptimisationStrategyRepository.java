package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link OptimisationStrategyEntity}.
 */
public interface OptimisationStrategyRepository extends JpaRepository<OptimisationStrategyEntity, Long> {

    /**
     * Finds all strategies for a given run type.
     *
     * @param runType the run type to filter by
     * @return all strategy rows for that run type
     */
    List<OptimisationStrategyEntity> findByRunType(RunType runType);

    /**
     * Finds only enabled strategies for a given run type.
     *
     * @param runType the run type to filter by
     * @return enabled strategy rows for that run type
     */
    List<OptimisationStrategyEntity> findByRunTypeAndEnabledTrue(RunType runType);

    /**
     * Finds a specific strategy for a run type.
     *
     * @param runType      the run type
     * @param strategyType the strategy type
     * @return the strategy entity if found
     */
    Optional<OptimisationStrategyEntity> findByRunTypeAndStrategyType(RunType runType,
            OptimisationStrategyType strategyType);
}
