package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link AuroraForecastResultEntity}.
 *
 * <p>Provides queries for the aurora forecast run service and the map view
 * to read stored per-location results keyed by date.
 *
 * <p><b>Every read method here excludes {@code simulated = true} rows.</b> A row written while an
 * admin's aurora simulation was active carries real weather triage and a real Claude call, but
 * fake Kp/storm data underneath it — the admin who ran it already sees the outcome in the
 * synchronous {@code POST /api/aurora/forecast/run} response, so nothing here needs to serve it
 * again. {@link #deleteByForecastDateIn} is the one exception: a re-run must clear a night's rows
 * regardless of how they were produced, real or simulated, or a stale simulated row would survive
 * a real re-run of the same night.
 */
@Repository
public interface AuroraForecastResultRepository extends JpaRepository<AuroraForecastResultEntity, Long> {

    /**
     * Returns all real (non-simulated) aurora forecast results for the given night.
     *
     * @param forecastDate the night to query
     * @return all real results for that date, including triaged entries
     */
    List<AuroraForecastResultEntity> findByForecastDateAndSimulatedFalse(LocalDate forecastDate);

    /**
     * Returns all real (non-simulated) aurora forecast results for the given night, eagerly
     * fetching each row's location and its region so a caller can group by region without a
     * lazy-load outside a transaction — {@code location} is {@code LAZY} on this entity, unlike
     * most others in this codebase.
     *
     * @param forecastDate the night to query
     * @return all real results for that date (location + region fetched), including triaged entries
     */
    @Query("SELECT r FROM AuroraForecastResultEntity r "
            + "JOIN FETCH r.location l LEFT JOIN FETCH l.region "
            + "WHERE r.forecastDate = :forecastDate AND r.simulated = false")
    List<AuroraForecastResultEntity> findByForecastDateAndSimulatedFalseFetchingLocation(
            @Param("forecastDate") LocalDate forecastDate);

    /**
     * Deletes all aurora forecast results for the given nights, real and simulated alike.
     * Called before inserting new results so that a re-run for the same night replaces old data —
     * deliberately not filtered by {@code simulated}, so a real re-run also clears out any earlier
     * simulated rows for that same night rather than leaving them alongside the new real ones.
     *
     * @param dates the nights whose results should be removed
     */
    void deleteByForecastDateIn(List<LocalDate> dates);

    /**
     * Returns all distinct dates for which at least one real (non-simulated) result exists.
     * Used by the frontend to determine whether the Aurora toggle should be shown.
     *
     * @return sorted list of dates with stored real aurora results
     */
    @Query("SELECT DISTINCT r.forecastDate FROM AuroraForecastResultEntity r "
            + "WHERE r.simulated = false ORDER BY r.forecastDate")
    List<LocalDate> findDistinctForecastDatesExcludingSimulated();
}
