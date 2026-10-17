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
     * <p>⚠️ The column is compared through {@code CAST(... AS VARCHAR)}, and that cast is load-bearing
     * on the one database this prune exists for. Production's column is a plain {@code VARCHAR(30)}
     * (V41), where the cast changes nothing. Local dev runs no migrations, and Hibernate 7 generates an
     * {@code @Enumerated(STRING)} column on H2 as a native {@code ENUM(...)} of the enum's CURRENT
     * constants — which H2 refuses to compare with any other value, so a bare {@code strategy_type IN
     * (?)} bound to a retired name threw ({@code Value not permitted for column ... "SKIP_LOW_RATED"})
     * on every start after the first of a fresh local database. Casting the column to its label makes
     * the comparison a string one, which is all the prune ever needed. The alternative — mapping the
     * column as VARCHAR so H2's schema matches V41 — was tried and rejected: Hibernate then adds a
     * {@code CHECK (strategy_type IN (...))} of the current constants that neither a
     * {@code columnDefinition} nor an {@code AttributeConverter} removes, and H2 2.4.240 stops
     * evaluating such a check once the connection that created the table closes ({@code Check
     * constraint invalid ... The database has been closed}) — so every write to the table would have
     * failed as soon as Hikari retired Hibernate's schema-update connection.
     *
     * @param retiredTypes the enum names that have been removed from {@link OptimisationStrategyType}
     * @return the number of rows deleted
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM optimisation_strategy WHERE CAST(strategy_type AS VARCHAR) IN (:retiredTypes)",
            nativeQuery = true)
    int deleteByStrategyTypeIn(@Param("retiredTypes") Collection<String> retiredTypes);
}
