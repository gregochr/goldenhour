package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CloudVerificationEntity;
import com.gregochr.goldenhour.model.CloudVerificationPair;
import com.gregochr.goldenhour.model.VerificationCandidate;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * makes the backfill resumable and idempotent: a row is a candidate exactly once, and a
     * verification that found no archive data is still a row, so it is not retried forever.
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
            + " e.cloudApproach.upwindDistanceKm, e.windDirection, e.azimuthDeg)"
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
