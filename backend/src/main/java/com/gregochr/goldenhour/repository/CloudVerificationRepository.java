package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CloudVerificationEntity;
import com.gregochr.goldenhour.model.CloudVerificationPair;
import com.gregochr.goldenhour.model.VerificationCandidate;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for {@link CloudVerificationEntity} persistence operations.
 */
@Repository
public interface CloudVerificationRepository extends JpaRepository<CloudVerificationEntity, Long> {

    /**
     * Finds evaluations that are old enough to verify and have not been verified yet.
     *
     * <p>Requires the fields the sampling needs — a solar azimuth and an event time to locate the
     * horizon point and the hour, and a directional reading to compare against. The anti-join
     * makes the backfill resumable and idempotent: a row is a candidate exactly once.
     *
     * <p>Because it is an anti-join, a row with <em>no observations</em> masks its evaluation just
     * as effectively as a real one — so {@link #deleteIncompleteVerifications()} clears those
     * before each run rather than letting an upstream outage silently retire part of the backlog.
     *
     * @param cutoff the newest target date the archive is expected to cover
     * @param limit  maximum candidates to return, bounding one backfill pass
     * @return unverified evaluations, oldest first
     */
    @Query("SELECT new com.gregochr.goldenhour.model.VerificationCandidate("
            + " e.id, e.location.lat, e.location.lon, e.azimuthDeg, e.solarEventTime, e.targetType)"
            + " FROM ForecastEvaluationEntity e"
            + " WHERE e.targetDate <= :cutoff"
            + " AND e.azimuthDeg IS NOT NULL"
            + " AND e.solarEventTime IS NOT NULL"
            + " AND e.directionalCloud.solarLow IS NOT NULL"
            + " AND NOT EXISTS ("
            + "   SELECT 1 FROM CloudVerificationEntity v"
            + "   WHERE v.forecastEvaluationId = e.id)"
            + " ORDER BY e.targetDate ASC, e.id ASC")
    List<VerificationCandidate> findUnverified(@Param("cutoff") LocalDate cutoff, Limit limit);

    /**
     * Deletes verification rows missing any observation the current sampling records.
     *
     * <p>A blank row records only that an attempt was made — typically during an upstream outage
     * or a rate limit. Since {@link #findUnverified} is an anti-join, leaving it in place would
     * mask that evaluation permanently. Removing it returns the evaluation to the candidate pool.
     *
     * <p>Deliberately checks every observed column the measurement pass added, not just
     * {@code horizonLowCloud}: rows verified before the cone extremes and the far-solar reading
     * existed are incomplete for the questions the report now asks, so the first run after that
     * deploy re-verifies the entire history. The cone extremes are set together with the mean, so
     * for post-upgrade rows the extra predicates only ever fire on a genuinely failed far fetch.
     *
     * @return the number of rows removed
     */
    @Modifying
    @Query("DELETE FROM CloudVerificationEntity v WHERE v.horizonLowCloud IS NULL"
            + " OR v.horizonLowMin IS NULL OR v.horizonLowMax IS NULL OR v.farLowCloud IS NULL")
    int deleteIncompleteVerifications();

    /**
     * Counts evaluations still awaiting verification, for backfill progress reporting.
     *
     * <p>Mirrors {@link #findUnverified} exactly — the two must agree, or reported progress will
     * not converge on zero.
     *
     * @param cutoff the newest target date the archive is expected to cover
     * @return number of unverified evaluations at or before the cutoff
     */
    @Query("SELECT COUNT(e) FROM ForecastEvaluationEntity e"
            + " WHERE e.targetDate <= :cutoff"
            + " AND e.azimuthDeg IS NOT NULL"
            + " AND e.solarEventTime IS NOT NULL"
            + " AND e.directionalCloud.solarLow IS NOT NULL"
            + " AND NOT EXISTS ("
            + "   SELECT 1 FROM CloudVerificationEntity v"
            + "   WHERE v.forecastEvaluationId = e.id)")
    long countUnverified(@Param("cutoff") LocalDate cutoff);

    /**
     * Pairs each verified evaluation's cloud claims with the cloud that was actually analysed.
     *
     * <p>Projects both claims the forecast makes — the gap (solar-horizon low cloud) and the
     * canvas (observer mid/high) — plus both veto triggers, the upwind distance (to split capped
     * from uncapped samples) and the wind and solar bearings.
     *
     * @param from start of the target-date window (inclusive)
     * @param to   end of the target-date window (inclusive)
     * @return verified forecast/observation pairs
     */
    @Query("SELECT new com.gregochr.goldenhour.model.CloudVerificationPair("
            + " e.location.name, e.targetDate, e.targetType, e.daysAhead, e.rating,"
            + " e.directionalCloud.solarLow, v.horizonLowCloud,"
            + " e.midCloud, e.highCloud, v.observerMidCloud, v.observerHighCloud,"
            + " e.cloudApproach.solarTrendBuilding, e.cloudApproach.upwindCurrentLowCloud,"
            + " e.cloudApproach.upwindDistanceKm, e.windDirection, e.azimuthDeg,"
            + " e.directionalCloud.farSolarLow, v.horizonLowMin, v.horizonLowMax, v.farLowCloud)"
            + " FROM ForecastEvaluationEntity e, CloudVerificationEntity v"
            + " WHERE v.forecastEvaluationId = e.id"
            + " AND e.targetDate BETWEEN :from AND :to")
    List<CloudVerificationPair> findVerifiedPairs(
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Counts verifications recorded for a target-date window.
     *
     * @param from start of the window (inclusive)
     * @param to   end of the window (inclusive)
     * @return number of evaluations verified in the window
     */
    @Query("SELECT COUNT(v) FROM ForecastEvaluationEntity e, CloudVerificationEntity v"
            + " WHERE v.forecastEvaluationId = e.id AND e.targetDate BETWEEN :from AND :to")
    long countVerifiedInWindow(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
