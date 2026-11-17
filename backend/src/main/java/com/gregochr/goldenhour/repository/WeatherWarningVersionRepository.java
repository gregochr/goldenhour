package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Insert-and-read access to {@code weather_warning_version}. Deliberately extends the bare {@link Repository}
 * marker, not {@code JpaRepository}: the table is insert-only, so no delete or update method is declared, and a
 * test pins that.
 */
public interface WeatherWarningVersionRepository extends Repository<WeatherWarningVersionEntity, Long> {

    /**
     * Inserts one version. A repeat of a stored (warning_id, version) violates the unique key; the caller treats
     * that as already stored.
     *
     * @param entity the new version
     * @return the stored row
     */
    WeatherWarningVersionEntity save(WeatherWarningVersionEntity entity);

    /**
     * Whether a version is already stored.
     *
     * @param warningId      the warning id
     * @param warningVersion the version, compared numerically ({@code 3.0} equals {@code 3.00})
     * @return true when stored
     */
    boolean existsByWarningIdAndWarningVersion(String warningId, BigDecimal warningVersion);

    /**
     * Whether any real (non-simulated) warning version is stored; false on a database that has never ingested the
     * feed, which is when the poller loads the issued snapshot.
     *
     * @return true when at least one real version is stored
     */
    boolean existsBySimulatedFalse();

    /**
     * Every stored version of every warning that has at least one version still valid after {@code instant}, so the
     * timeline sees the whole history of any warning that could be active at or after that instant.
     *
     * @param instant the earliest instant of interest
     * @return all versions of the matching warnings
     */
    @Query("select w from WeatherWarningVersionEntity w where w.warningId in "
            + "(select x.warningId from WeatherWarningVersionEntity x where x.validTo > :instant)")
    List<WeatherWarningVersionEntity> findAllVersionsOfWarningsValidAfter(@Param("instant") Instant instant);

    /**
     * The most recently received row, for the status screen.
     *
     * @return the newest row by receipt, if any
     */
    Optional<WeatherWarningVersionEntity> findFirstByOrderByReceivedAtDescIdDesc();

    /**
     * Number of stored versions.
     *
     * @return the row count
     */
    long count();

    /**
     * Number of stored versions flagged simulated.
     *
     * @param simulated the simulated flag to count
     * @return the matching row count
     */
    long countBySimulated(boolean simulated);
}
