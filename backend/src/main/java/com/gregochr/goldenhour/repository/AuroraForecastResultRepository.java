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
 * again.
 *
 * <p>The two delete methods are deliberately asymmetric, and
 * {@link com.gregochr.goldenhour.service.aurora.AuroraForecastResultWriter} picks between them by
 * the run it is writing, never by what a night currently holds: a
 * <b>real</b> run is authoritative for that night and calls {@link #deleteByForecastDateIn}, which
 * clears real and simulated rows alike (an earlier admin test run must not survive a real one for
 * the same night). A <b>simulated</b> run calls {@link #deleteByForecastDateAndSimulatedTrue}
 * instead, touching only rows an earlier simulated run left behind — never the real, user-facing
 * results of a night that already had a genuine forecast. Getting this backwards once made a
 * simulated test run silently delete that night's real results and replace them with rows every
 * read method above then hides, so the night reads as never forecast at all.
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
     * Deletes all aurora forecast results for the given nights, real and simulated alike. Called
     * before inserting a <b>real</b> run's results, so that run replaces old data of either kind —
     * deliberately not filtered by {@code simulated}, so a real re-run also clears out any earlier
     * simulated rows for that same night rather than leaving them alongside the new real ones.
     *
     * @param dates the nights whose results should be removed
     */
    void deleteByForecastDateIn(List<LocalDate> dates);

    /**
     * Deletes only the simulated aurora forecast results for the given night. Called before
     * inserting a <b>simulated</b> run's results, so a repeated admin test run replaces its own
     * earlier simulated rows without touching that night's real, user-facing results if it has
     * any — a simulated run must never be able to delete real data.
     *
     * @param forecastDate the night whose simulated results should be removed
     */
    void deleteByForecastDateAndSimulatedTrue(LocalDate forecastDate);

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
