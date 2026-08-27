package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Spring Data repository for {@link TideExtremeEntity}.
 *
 * <p>Supports the weekly fetch-and-merge cycle: delete only the overlapping time window,
 * then bulk-save the fresh batch from WorldTides, preserving historical data.
 */
public interface TideExtremeRepository extends JpaRepository<TideExtremeEntity, Long> {

    /**
     * Returns all tide extremes for a location within a time window, in chronological order.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     * @return chronologically ordered list of tide extremes
     */
    List<TideExtremeEntity> findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
            Long locationId, LocalDateTime from, LocalDateTime to);

    /**
     * Returns all tide extremes for several locations within a time window, in chronological order.
     *
     * <p>The multi-location form exists so the tide-run builder can pick its representative
     * location in one query rather than one per coastal location — that loop ran on every
     * hot-topic build, over the whole coastal roster.
     *
     * @param locationIds the location primary keys
     * @param from        window start (inclusive)
     * @param to          window end (inclusive)
     * @return chronologically ordered list of tide extremes across all the given locations
     */
    List<TideExtremeEntity> findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
            Collection<Long> locationIds, LocalDateTime from, LocalDateTime to);

    /**
     * Returns extremes of one type only, for several locations within a time window.
     *
     * <p>The type filter is not a convenience. The almanac's run detection asks a question only
     * high waters can answer — is this day's water spring-sized or king-sized, by the same
     * thresholds the Plan tab uses — over a window a hundred days wide and a roster sixty locations
     * long. Pulling the lows as well doubles a result set already in the tens of thousands of rows,
     * for data the caller would immediately discard.
     *
     * @param locationIds the location primary keys
     * @param type        the extreme type to return
     * @param from        window start (inclusive)
     * @param to          window end (inclusive)
     * @return chronologically ordered extremes of that type across all the given locations
     */
    List<TideExtremeEntity> findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
            Collection<Long> locationIds, TideExtremeType type,
            LocalDateTime from, LocalDateTime to);

    /**
     * Returns {@code true} if any tide extremes are stored for the given location.
     *
     * <p>Used at startup to decide whether a tide fetch is needed for a coastal location.
     *
     * @param locationId the location primary key
     * @return {@code true} if at least one tide extreme row exists for this location
     */
    boolean existsByLocationId(Long locationId);

    /**
     * Deletes all tide extremes for the given location.
     *
     * @param locationId the location primary key
     */
    @Modifying
    @Query("DELETE FROM TideExtremeEntity t WHERE t.locationId = :locationId")
    void deleteByLocationId(@Param("locationId") Long locationId);

    /**
     * Deletes tide extremes for a location within a time window.
     *
     * <p>Used before saving a fresh batch to clear only the overlapping range,
     * preserving historical data outside the fetch window.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     */
    @Modifying
    @Query("DELETE FROM TideExtremeEntity t WHERE t.locationId = :locationId "
            + "AND t.eventTime >= :from AND t.eventTime <= :to")
    void deleteByLocationIdAndEventTimeBetween(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Returns {@code true} if any tide extremes exist for the location within the given window.
     *
     * <p>Used by the backfill process to skip date ranges that already have data,
     * avoiding duplicate WorldTides API calls.
     *
     * @param locationId the location primary key
     * @param from       window start (inclusive)
     * @param to         window end (inclusive)
     * @return {@code true} if at least one tide extreme exists in the window
     */
    boolean existsByLocationIdAndEventTimeBetween(Long locationId, LocalDateTime from, LocalDateTime to);

    /**
     * Returns the latest stored event time at or after {@code from}, or {@code null} if the
     * location has no stored extremes in that range.
     *
     * <p>This is the location's forward frontier: the point the weekly refresh needs to extend
     * from. Tide predictions are deterministic astronomy and do not change, so re-fetching data
     * already stored buys nothing — before this existed, every Monday re-bought the whole forward
     * window from WorldTides and rewrote roughly 375 identical rows per location.
     *
     * <p>Bounded at {@code from} rather than taken over the whole table so a location carrying
     * twelve months of backfill and no forward data still reports {@code null} and is seeded.
     *
     * @param locationId the location primary key
     * @param from       lower bound, normally the start of today
     * @return the latest stored event time in range, or {@code null} if there is none
     */
    @Query("SELECT MAX(t.eventTime) FROM TideExtremeEntity t "
            + "WHERE t.locationId = :locationId AND t.eventTime >= :from")
    LocalDateTime findLatestEventTimeFrom(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from);

    /**
     * Returns the oldest {@code fetched_at} among stored extremes at or after {@code from}, or
     * {@code null} if there are none.
     *
     * <p>Used to decide when a full re-seed is due. A full seed stamps every forward row with the
     * same {@code fetched_at} and a tail fetch stamps only the newly added days, so this is the
     * fetch day of the oldest batch <em>still inside the forward window</em> — which makes the
     * re-seed schedule derivable from stored data, with no extra column to keep in step.
     *
     * <p>⚠️ Not literally "time since the last full seed", and a maintainer will reason from
     * whichever the doc says. Once the seed batch ages past the horizon this becomes the oldest
     * surviving <em>tail</em> stamp, which saturates near the horizon rather than growing without
     * bound. The cadence still fires, but only because the re-seed interval is bounded by the
     * horizon — see {@code WorldTidesIngestionService.RESEED_INTERVAL_DAYS}.
     *
     * @param locationId the location primary key
     * @param from       lower bound, normally the start of today
     * @return the oldest fetch timestamp in range, or {@code null} if there is none
     */
    @Query("SELECT MIN(t.fetchedAt) FROM TideExtremeEntity t "
            + "WHERE t.locationId = :locationId AND t.eventTime >= :from")
    LocalDateTime findOldestFetchedAtFrom(
            @Param("locationId") Long locationId,
            @Param("from") LocalDateTime from);

    /**
     * Returns aggregate height statistics for a location and tide type, over stored
     * extremes strictly before {@code cutoff}.
     *
     * <p>Result is a single-element array: {@code [avgHeight, maxHeight, minHeight, count]}.
     *
     * <p><strong>Why the cutoff exists.</strong> These aggregates feed the spring-tide
     * threshold ({@code 1.25 × avgHigh}) and the king-tide P95 in
     * {@code TideService.getTideStats}. Without a bound they ran over every row in the
     * table — including the forward extremes the weekly WorldTides fetch writes — which
     * silently coupled the classification threshold to whatever
     * {@code WorldTidesIngestionService.FETCH_LENGTH_SECONDS} happened to be. Lengthening the fetch
     * horizon then moved spring/king classification for every coastal location, and the
     * effect reached a persisted column ({@code forecast_evaluation.surge_risk_level}),
     * the Claude prompt, hot-topic firing and rendered "N m above an average tide" copy.
     * Bounding to observed history decouples the two permanently: the horizon may change
     * again without moving the threshold.
     *
     * @param locationId the location primary key
     * @param type       HIGH or LOW
     * @param cutoff     exclusive upper bound on event time — extremes at or after this
     *                   instant are excluded from the sample
     * @return Object array with avg, max, min BigDecimal heights and Long count
     */
    @Query("SELECT AVG(t.heightMetres), MAX(t.heightMetres), MIN(t.heightMetres), COUNT(t) "
            + "FROM TideExtremeEntity t WHERE t.locationId = :locationId AND t.type = :type "
            + "AND t.eventTime < :cutoff")
    Object[] findHeightStatsByLocationIdAndTypeBefore(
            @Param("locationId") Long locationId,
            @Param("type") TideExtremeType type,
            @Param("cutoff") LocalDateTime cutoff);

    /**
     * Returns all heights for a location and tide type strictly before {@code cutoff},
     * ordered ascending.
     *
     * <p>Used for percentile calculations in Java (H2 lacks PERCENTILE_CONT). Shares the
     * cutoff rationale documented on {@link #findHeightStatsByLocationIdAndTypeBefore} —
     * the two must sample the same population or the P95 and the mean describe different
     * sets of tides.
     *
     * @param locationId the location primary key
     * @param type       HIGH or LOW
     * @param cutoff     exclusive upper bound on event time
     * @return sorted list of heights
     */
    @Query("SELECT t.heightMetres FROM TideExtremeEntity t "
            + "WHERE t.locationId = :locationId AND t.type = :type AND t.eventTime < :cutoff "
            + "ORDER BY t.heightMetres ASC")
    List<BigDecimal> findHeightsByLocationIdAndTypeBeforeOrderByHeightAsc(
            @Param("locationId") Long locationId,
            @Param("type") TideExtremeType type,
            @Param("cutoff") LocalDateTime cutoff);
}
