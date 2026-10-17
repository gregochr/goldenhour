package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
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
     * @return rows inserted (1)
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO outcome_rating (user_id, location_id, event_date, event_type, rating, "
            + "event_time, pipeline_run_id, forecast_run_at, forecast_evaluation_id, forecast_rating, "
            + "app_build, created_at, updated_at) VALUES (:userId, :locationId, :eventDate, :eventType, "
            + "CAST(:rating AS INTEGER), :eventTime, CAST(:pipelineRunId AS BIGINT), "
            + "CAST(:forecastRunAt AS TIMESTAMP WITH TIME ZONE), CAST(:forecastEvaluationId AS BIGINT), "
            + "CAST(:forecastRating AS INTEGER), CAST(:appBuild AS VARCHAR(40)), :now, :now)",
            nativeQuery = true)
    int insertRow(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") String eventType,
            @Param("rating") Integer rating, @Param("eventTime") Instant eventTime,
            @Param("pipelineRunId") Long pipelineRunId, @Param("forecastRunAt") Instant forecastRunAt,
            @Param("forecastEvaluationId") Long forecastEvaluationId,
            @Param("forecastRating") Integer forecastRating, @Param("appBuild") String appBuild,
            @Param("now") Instant now);

    /**
     * Replaces a rating and its provenance in place: one statement, last write wins.
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
     * @return 1 when the row was updated; 0 when there is no such row
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutcomeRatingEntity r SET r.rating = :rating, r.eventTime = :eventTime, "
            + "r.pipelineRunId = :pipelineRunId, r.forecastRunAt = :forecastRunAt, "
            + "r.forecastEvaluationId = :forecastEvaluationId, r.forecastRating = :forecastRating, "
            + "r.appBuild = :appBuild, r.updatedAt = :now "
            + "WHERE r.userId = :userId AND r.locationId = :locationId "
            + "AND r.eventDate = :eventDate AND r.eventType = :eventType")
    int updateRow(@Param("userId") Long userId, @Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") TargetType eventType,
            @Param("rating") Integer rating, @Param("eventTime") Instant eventTime,
            @Param("pipelineRunId") Long pipelineRunId, @Param("forecastRunAt") Instant forecastRunAt,
            @Param("forecastEvaluationId") Long forecastEvaluationId,
            @Param("forecastRating") Integer forecastRating, @Param("appBuild") String appBuild,
            @Param("now") Instant now);

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
     * ratings" list. A theta join, the house shape ({@code findCalibrationPairs}): the entity holds
     * the user as a plain id.
     *
     * @param locationId the location
     * @param eventDate  the event date
     * @param eventType  SUNRISE or SUNSET
     * @return the slot's rows, most recently recorded first
     */
    @Query("SELECT new com.gregochr.goldenhour.model.SlotRatingRow("
            + " r.userId, u.username, r.rating, r.updatedAt, r.eventTime, r.forecastRunAt,"
            + " r.pipelineRunId)"
            + " FROM OutcomeRatingEntity r, AppUserEntity u"
            + " WHERE u.id = r.userId"
            + " AND r.locationId = :locationId AND r.eventDate = :eventDate"
            + " AND r.eventType = :eventType"
            + " ORDER BY r.updatedAt DESC, r.id DESC")
    List<SlotRatingRow> findAllForSlot(@Param("locationId") Long locationId,
            @Param("eventDate") LocalDate eventDate, @Param("eventType") TargetType eventType);
}
