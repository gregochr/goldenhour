package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link LocationEntity}.
 */
public interface LocationRepository extends JpaRepository<LocationEntity, Long> {

    /**
     * Returns {@code true} if a location with the given name already exists.
     *
     * @param name location name to check
     * @return {@code true} if a matching row exists
     */
    boolean existsByName(String name);

    /**
     * Finds a location by its exact name.
     *
     * @param name the location name to look up
     * @return an {@link Optional} containing the entity if found
     */
    Optional<LocationEntity> findByName(String name);

    /**
     * Returns all locations ordered alphabetically by name.
     *
     * @return locations sorted by name ascending
     */
    List<LocationEntity> findAllByOrderByNameAsc();

    /**
     * Returns all enabled locations ordered alphabetically by name.
     *
     * @return enabled locations sorted by name ascending
     */
    List<LocationEntity> findAllByEnabledTrueOrderByNameAsc();

    /**
     * Returns every location missing an Open-Meteo grid cell, in name order.
     *
     * <p>Either column being null is enough. They are only ever written as a pair, so a row with
     * one of the two is not a state the code can produce — but a half-populated row would be
     * unusable to {@code LocationEntity.gridCellKey()} either way, and an OR costs nothing.
     *
     * @return locations with no grid cell, sorted by name ascending
     */
    List<LocationEntity> findByGridLatIsNullOrGridLngIsNullOrderByNameAsc();

    /**
     * Returns all locations where {@code bortle_class} has not yet been populated.
     *
     * <p>Used by the Bortle enrichment job to find locations pending enrichment.
     *
     * @return locations with a {@code null} Bortle class
     */
    List<LocationEntity> findByBortleClassIsNull();

    /**
     * Returns all enabled locations with a Bortle class at or below the given threshold.
     *
     * <p>Used by the aurora polling job to find dark-sky-eligible locations.
     * Locations with a {@code null} Bortle class are excluded.
     *
     * @param maxBortleClass the maximum Bortle class to include (inclusive)
     * @return enabled locations with {@code bortle_class <= maxBortleClass}
     */
    List<LocationEntity> findByBortleClassLessThanEqualAndEnabledTrue(int maxBortleClass);

    /**
     * Returns all enabled locations that have been enriched with a Bortle class.
     *
     * <p>Used by the astro conditions scorer to find all dark-sky-eligible locations
     * regardless of Bortle threshold.
     *
     * @return enabled locations with a non-null {@code bortle_class}
     */
    List<LocationEntity> findByBortleClassIsNotNullAndEnabledTrue();

    /**
     * Returns all enabled locations that have the {@code BLUEBELL} location type.
     *
     * <p>Used by the bluebell hot topic detector to find candidate locations
     * during bluebell season.
     *
     * @return enabled bluebell locations
     */
    @Query("SELECT l FROM LocationEntity l JOIN l.locationType lt"
            + " WHERE lt = com.gregochr.goldenhour.entity.LocationType.BLUEBELL"
            + " AND l.enabled = true")
    List<LocationEntity> findBluebellLocations();

    /**
     * Returns all enabled locations that have at least one tide type preference.
     *
     * <p>A non-empty {@code tideType} set indicates a coastal location where
     * tide data is relevant. Used by tide hot topic strategies to identify
     * which regions have coastal locations.
     *
     * @return enabled coastal locations (those with at least one tide type)
     */
    @Query("SELECT DISTINCT l FROM LocationEntity l JOIN l.tideType tt WHERE l.enabled = true")
    List<LocationEntity> findCoastalLocations();

    /**
     * Returns the most recent {@code created_at} across the whole location roster.
     *
     * <p>One query, so a caller deciding "has anything been added" never needs to page through
     * locations or query per candidate. Scoped to every location, not just enabled ones, to match
     * the roster {@code DriveDurationService.measureForUser} actually measures against — that
     * method calls {@link #findAll()} with no {@code enabled} filter.
     *
     * <p>The column is a UTC wall-clock value when the application writes it
     * ({@code LocationEntity.createdAt} is set via {@code LocalDateTime.now(ZoneOffset.UTC)}), but
     * it is stored without a time zone (Postgres {@code timestamp without time zone}), and several
     * Flyway migrations (V84, V138, V143) insert location rows by raw SQL using the column default
     * {@code NOW()} instead of going through the application. Postgres's {@code NOW()} is
     * timezone-aware internally, but writing it into a zone-less column converts it to the
     * database session's {@code TimeZone} setting first — so treating every row in this column as
     * UTC (as {@code DriveTimeRefreshJob.usersNeedingRefresh} does) is only correct while every
     * writer's session runs in UTC, application and migration alike. Nothing in this repository
     * pins that; it is an environmental assumption, not an enforced one.
     *
     * @return the newest creation timestamp, or {@code null} if the roster is empty
     */
    @Query("SELECT MAX(l.createdAt) FROM LocationEntity l")
    LocalDateTime findMaxCreatedAt();

    /**
     * Records one counted failed scheduled cycle against an enabled location: sets the consecutive
     * failure count and the time of the failure and touches no other column.
     *
     * <p>Column-scoped on purpose. A whole-entity {@code save} would write every column, including
     * the tide, type and solar-event sets, from whatever copy the caller held, so it could undo an
     * admin's concurrent edit of the same place. The {@code enabled = true} guard makes the update
     * a no-op for a place an admin disabled mid-cycle.
     *
     * <p>The increment is done by the database, not computed by the caller from an entity read
     * earlier, so two writers can never overwrite each other with a stale absolute value. Read the
     * result back with {@link #findConsecutiveFailuresById}.
     *
     * @param id            the location id
     * @param lastFailureAt UTC time of this failure
     * @return rows updated (0 or 1)
     */
    @Modifying
    @Query("UPDATE LocationEntity l SET "
            + "l.consecutiveFailures = COALESCE(l.consecutiveFailures, 0) + 1, "
            + "l.lastFailureAt = :lastFailureAt WHERE l.id = :id AND l.enabled = true")
    int recordFailure(@Param("id") Long id, @Param("lastFailureAt") LocalDateTime lastFailureAt);

    /**
     * Reads the consecutive failure count straight from the database. A scalar projection, so it
     * never returns a value cached in the persistence context: after {@link #recordFailure} it is
     * the number that update produced.
     *
     * @param id the location id
     * @return the stored count, or {@code null} if the column is null
     */
    @Query("SELECT l.consecutiveFailures FROM LocationEntity l WHERE l.id = :id")
    Integer findConsecutiveFailuresById(@Param("id") Long id);

    /**
     * Auto-disables an enabled location: sets {@code enabled = false}, the reason, the consecutive
     * failure count and the time of the last failure, and touches no other column.
     *
     * @param id            the location id
     * @param failureCount  the consecutive failure count that tripped the disable
     * @param lastFailureAt UTC time of the failure that tripped it
     * @param reason        the fixed-shape reason shown on the admin Locations alerts
     * @return rows updated (0 or 1)
     */
    @Modifying
    @Query("UPDATE LocationEntity l SET l.enabled = false, l.consecutiveFailures = :failureCount, "
            + "l.lastFailureAt = :lastFailureAt, l.disabledReason = :reason "
            + "WHERE l.id = :id AND l.enabled = true")
    int autoDisable(@Param("id") Long id, @Param("failureCount") int failureCount,
            @Param("lastFailureAt") LocalDateTime lastFailureAt, @Param("reason") String reason);

    /**
     * Clears the whole failure state of one location: counter to zero, last-failure time and
     * disabled reason to null. Used when an admin re-enables a place or resets its failures; those
     * columns are {@code updatable = false} on the entity, so this is the only way to clear them.
     * It does not touch {@code enabled}, which the caller sets through the entity.
     *
     * @param id the location id
     * @return rows updated (0 or 1)
     */
    @Modifying
    @Query("UPDATE LocationEntity l SET l.consecutiveFailures = 0, l.lastFailureAt = NULL, "
            + "l.disabledReason = NULL WHERE l.id = :id")
    int clearFailureState(@Param("id") Long id);

    /**
     * Resets the consecutive failure count to zero for every enabled location in the given set
     * whose count is above zero. The time of the last failure is left as the historical fact it is.
     *
     * @param ids the location ids that got through a scheduled cycle
     * @return rows updated
     */
    @Modifying
    @Query("UPDATE LocationEntity l SET l.consecutiveFailures = 0 "
            + "WHERE l.id IN :ids AND l.enabled = true AND l.consecutiveFailures > 0")
    int resetFailureCounts(@Param("ids") Collection<Long> ids);
}
