package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SlotRatingRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link OutcomeRatingEntity}.
 *
 * <p>Every write is a single statement the database applies atomically: a native {@code INSERT}
 * for a new row and one conditional {@code UPDATE} for an existing one; the entity is never loaded,
 * modified and saved. The {@code @Transactional} sits on each write method, never on the store that
 * calls them — see {@code OutcomeRatingStore} for why that is load-bearing on Postgres.
 */
public interface OutcomeRatingRepository extends JpaRepository<OutcomeRatingEntity, Long> {

    /**
     * Whether a user already has a rating for a slot.
     *
     * @param userId    the user
     * @param locationId the location
     * @param eventDate the event date
     * @param eventType SUNRISE or SUNSET
     * @return true when the row exists
     */
    boolean existsByUserIdAndLocationIdAndEventDateAndEventType(Long userId, Long locationId,
            LocalDate eventDate, TargetType eventType);

    /**
     * A user's existing rating of a slot, if any.
     *
     * @param userId     the user
     * @param locationId the location
     * @param eventDate  the event date
     * @param eventType  SUNRISE or SUNSET
     * @return the row, or empty
     */
    Optional<OutcomeRatingEntity> findByUserIdAndLocationIdAndEventDateAndEventType(Long userId,
            Long locationId, LocalDate eventDate, TargetType eventType);

    /**
     * Inserts a rating. A plain {@code INSERT} (no {@code ON CONFLICT}, which the local H2 database
     * lacks): a second insert of the same slot violates {@code uq_outcome_rating_slot}, which the
     * caller reads as "the row exists now". A native statement rather than {@code save}, so a lost
     * race leaves no half-persisted entity in the request's session.
     *
     * <p>Every nullable value is {@code CAST} to its column type: an untyped {@code null} bind has
     * no type for Postgres to infer from a parameter on its own.
     *
     * @param userId               the rating user
     * @param locationId           the location
     * @param eventDate            the event date
     * @param eventType            {@code SUNRISE} or {@code SUNSET}, as a name
     * @param rating               1–5, or null for "didn't see it"
     * @param eventTime            the solar event instant
     * @param pipelineRunId        the cycle the served forecast came from, or null
     * @param forecastRunAt        the served forecast's submission instant, or null
     * @param forecastEvaluationId the resolved {@code forecast_evaluation} row, or null
     * @param forecastRating       the star the reader saw, or null
     * @param appBuild             the release writing the row (V182), or null
     * @param now                  stamped as both {@code created_at} and {@code updated_at}
     * @param seen                 the {@code SeenTag} name the reader chose (V187), or null for none
     * @return rows inserted (1)
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO outcome_rating (user_id, location_id, event_date, event_type, rating, "
            + "event_time, pipeline_run_id, forecast_run_at, forecast_evaluation_id, forecast_rating, "
            + "app_build, created_at, updated_at, seen) VALUES (:userId, :locationId, :eventDate, :eventType, "
            + "CAST(:rating AS INTEGER), :eventTime, CAST(:pipelineRunId AS BIGINT), "
            + "CAST(:forecastRunAt AS TIMESTAMP WITH TIME ZONE), CAST(:forecastEvaluationId AS BIGINT), "
            + "CAST(:forecastRating AS INTEGER), CAST(:appBuild AS VARCHAR(40)), :now, :now, "
            + "CAST(:seen AS VARCHAR(10)))",
            nativeQuery = true)
    int insertRow(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") String eventType,
            @Param("rating") Integer rating, @Param("eventTime") Instant eventTime,
            @Param("pipelineRunId") Long pipelineRunId, @Param("forecastRunAt") Instant forecastRunAt,
            @Param("forecastEvaluationId") Long forecastEvaluationId,
            @Param("forecastRating") Integer forecastRating, @Param("appBuild") String appBuild,
            @Param("now") Instant now, @Param("seen") String seen);

    /**
     * Replaces the rating, the tag and the provenance in place in ONE statement. Used only for the
     * "didn't see it" answer that names its tag in the same request (the rating and the tag are one
     * change, and writing them together keeps the pairing the CHECK states); every other update is
     * {@link #updateRating} or {@link #updateSeen}, each scoped to its own column.
     *
     * @param userId               the rating user
     * @param locationId           the location
     * @param eventDate            the event date
     * @param eventType            SUNRISE or SUNSET
     * @param rating               1–5, or null for "didn't see it"
     * @param eventTime            the solar event instant
     * @param pipelineRunId        the cycle the served forecast came from, or null
     * @param forecastRunAt        the served forecast's submission instant, or null
     * @param forecastEvaluationId the resolved {@code forecast_evaluation} row, or null
     * @param forecastRating       the star the reader saw, or null
     * @param appBuild             the release writing the row (V182), replacing the earlier one
     * @param now                  the new {@code updated_at}
     * @param seen                 the tag to store (V187), or null to clear it - the caller has already
     *                             decided whether the earlier tag is kept
     * @return 1 when the row was updated; 0 when there is no such row
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutcomeRatingEntity r SET r.rating = :rating, r.eventTime = :eventTime, "
            + "r.pipelineRunId = :pipelineRunId, r.forecastRunAt = :forecastRunAt, "
            + "r.forecastEvaluationId = :forecastEvaluationId, r.forecastRating = :forecastRating, "
            + "r.appBuild = :appBuild, r.updatedAt = :now, r.seen = :seen "
            + "WHERE r.userId = :userId AND r.locationId = :locationId "
            + "AND r.eventDate = :eventDate AND r.eventType = :eventType")
    int updateRow(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") TargetType eventType,
            @Param("rating") Integer rating, @Param("eventTime") Instant eventTime,
            @Param("pipelineRunId") Long pipelineRunId, @Param("forecastRunAt") Instant forecastRunAt,
            @Param("forecastEvaluationId") Long forecastEvaluationId,
            @Param("forecastRating") Integer forecastRating, @Param("appBuild") String appBuild,
            @Param("now") Instant now, @Param("seen") SeenTag seen);

    /**
     * Records a new STAR (or "didn't see it") on an existing row and nothing about the tag the reader
     * chose: one column-scoped statement that never reads the row first, so an overlapping tag write
     * cannot be undone from a stale copy. The tag is touched only by the rule that keeps the pairing
     * the V187 CHECK states, done in the SQL itself: a sighting tag does not survive "didn't see it"
     * and {@code NOT_SEEN} does not survive a star; any other tag, and a null one, stays as it is.
     *
     * @param userId               the rating user
     * @param locationId           the location
     * @param eventDate            the event date
     * @param eventType            SUNRISE or SUNSET
     * @param rating               1–5, or null for "didn't see it"
     * @param hasRating            whether {@code rating} is non-null (a plain boolean, so the CASE below
     *                             never asks Postgres to infer the type of an untyped null parameter)
     * @param eventTime            the solar event instant
     * @param pipelineRunId        the cycle the served forecast came from, or null
     * @param forecastRunAt        the served forecast's submission instant, or null
     * @param forecastEvaluationId the resolved {@code forecast_evaluation} row, or null
     * @param forecastRating       the star the reader saw, or null
     * @param appBuild             the release writing the rating
     * @param now                  the new {@code updated_at}
     * @return 1 when the row was updated; 0 when there is no such row
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutcomeRatingEntity r SET r.rating = :rating, r.eventTime = :eventTime, "
            + "r.pipelineRunId = :pipelineRunId, r.forecastRunAt = :forecastRunAt, "
            + "r.forecastEvaluationId = :forecastEvaluationId, r.forecastRating = :forecastRating, "
            + "r.appBuild = :appBuild, r.updatedAt = :now, "
            + "r.seen = CASE WHEN (:hasRating = false AND r.seen <> "
            + "com.gregochr.goldenhour.entity.SeenTag.NOT_SEEN) "
            + "OR (:hasRating = true AND r.seen = com.gregochr.goldenhour.entity.SeenTag.NOT_SEEN) "
            + "THEN NULL ELSE r.seen END "
            + "WHERE r.userId = :userId AND r.locationId = :locationId "
            + "AND r.eventDate = :eventDate AND r.eventType = :eventType")
    int updateRating(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") TargetType eventType,
            @Param("rating") Integer rating, @Param("hasRating") boolean hasRating,
            @Param("eventTime") Instant eventTime, @Param("pipelineRunId") Long pipelineRunId,
            @Param("forecastRunAt") Instant forecastRunAt,
            @Param("forecastEvaluationId") Long forecastEvaluationId,
            @Param("forecastRating") Integer forecastRating, @Param("appBuild") String appBuild,
            @Param("now") Instant now);

    /**
     * Sets or clears the tag on an existing row and touches no other column but {@code updated_at}: a
     * tag tap never carries a star back, so a star changed underneath it (another device) is not
     * restored from a stale copy. If the star DID change so the tag no longer fits it, V187's CHECK
     * refuses the statement and the caller answers 409.
     *
     * @param userId     the rating user
     * @param locationId the location
     * @param eventDate  the event date
     * @param eventType  SUNRISE or SUNSET
     * @param seen       the tag to store, or null to clear it
     * @param now        the new {@code updated_at}
     * @return 1 when the row was updated; 0 when there is no such row
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutcomeRatingEntity r SET r.seen = :seen, r.updatedAt = :now "
            + "WHERE r.userId = :userId AND r.locationId = :locationId "
            + "AND r.eventDate = :eventDate AND r.eventType = :eventType")
    int updateSeen(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") TargetType eventType,
            @Param("seen") SeenTag seen, @Param("now") Instant now);

    /**
     * Counts every rating in an event-date window, "didn't see it" rows included — the
     * calibration report's outcome coverage, which counts the old table's unrated rows too.
     *
     * @param from start of the window (inclusive)
     * @param to   end of the window (inclusive)
     * @return the number of rows in the window
     */
    long countByEventDateBetween(LocalDate from, LocalDate to);

    /**
     * One user's own ratings over a date range, oldest event first.
     *
     * @param userId the user
     * @param from   first event date (inclusive)
     * @param to     last event date (inclusive)
     * @return the rows, ordered by event date then id
     */
    List<OutcomeRatingEntity> findByUserIdAndEventDateBetweenOrderByEventDateAscIdAsc(Long userId,
            LocalDate from, LocalDate to);

    /**
     * Every user's rating of one slot with the username joined, newest first — the admin's "all
     * ratings" list. A theta join, the house shape ({@code findCalibrationPairsFromRatings}): the entity holds
     * the user as a plain id.
     *
     * @param locationId the location
     * @param eventDate  the event date
     * @param eventType  SUNRISE or SUNSET
     * @return the slot's rows, most recently recorded first
     */
    @Query("SELECT new com.gregochr.goldenhour.model.SlotRatingRow("
            + " r.userId, u.username, r.rating, r.updatedAt, r.eventTime, r.forecastRunAt,"
            + " r.pipelineRunId, r.seen)"
            + " FROM OutcomeRatingEntity r, AppUserEntity u"
            + " WHERE u.id = r.userId"
            + " AND r.locationId = :locationId AND r.eventDate = :eventDate"
            + " AND r.eventType = :eventType"
            + " ORDER BY r.updatedAt DESC, r.id DESC")
    List<SlotRatingRow> findAllForSlot(@Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") TargetType eventType);
}
