package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link AuroraForecastResultEntity}.
 *
 * <p>Provides queries for the aurora forecast run service and the map view
 * to read stored per-location results keyed by date.
 */
@Repository
public interface AuroraForecastResultRepository extends JpaRepository<AuroraForecastResultEntity, Long> {

    /**
     * Returns all aurora forecast results for the given night.
     *
     * @param forecastDate the night to query
     * @return all results for that date, including triaged entries
     */
    List<AuroraForecastResultEntity> findByForecastDate(LocalDate forecastDate);

    /**
     * Deletes all aurora forecast results for the given nights.
     * Called before inserting new results so that a re-run for the same night replaces old data.
     *
     * @param dates the nights whose results should be removed
     */
    void deleteByForecastDateIn(List<LocalDate> dates);

    /**
     * Returns all distinct dates for which at least one result exists.
     * Used by the frontend to determine whether the Aurora toggle should be shown.
     *
     * @return sorted list of dates with stored aurora results
     */
    @Query("SELECT DISTINCT r.forecastDate FROM AuroraForecastResultEntity r ORDER BY r.forecastDate")
    List<LocalDate> findDistinctForecastDates();
}
