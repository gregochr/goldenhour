package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.OptimisationStrategyEntity;
import com.gregochr.goldenhour.entity.OptimisationStrategyType;
import com.gregochr.goldenhour.entity.RunType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
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

    /**
     * Deletes every row whose {@code strategy_type} is one of {@code retiredTypes}.
     *
     * <p>Native, and keyed on the column's raw string, because the rows it exists to remove are
     * exactly the ones JPA cannot load: {@code strategy_type} maps through
     * {@code @Enumerated(STRING)}, so a row naming a retired type makes Hibernate throw on read.
     *
     * <p>⚠️ An explicit list, never "everything I don't recognise". A {@code NOT IN (known)} delete
     * would let an older binary started against a newer database — production sets
     * {@code validate-on-migrate: false}, so it boots — silently and permanently delete a newer
     * type's rows, admin settings and all. Naming the retired types leaves an unknown one to fail
     * loudly on read instead, which loses nothing.
     *
     * @param retiredTypes the enum names that have been removed from {@link OptimisationStrategyType}
     * @return the number of rows deleted
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM optimisation_strategy WHERE strategy_type IN (:retiredTypes)",
            nativeQuery = true)
    int deleteByStrategyTypeIn(@Param("retiredTypes") Collection<String> retiredTypes);
}
