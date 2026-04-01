package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link AstroConditionsEntity}.
 */
public interface AstroConditionsRepository extends JpaRepository<AstroConditionsEntity, Long> {

    /**
     * Returns all astro conditions for a given night.
     *
     * @param forecastDate the night to query
     * @return all scores for that date
     */
    List<AstroConditionsEntity> findByForecastDate(LocalDate forecastDate);

    /**
     * Returns all astro conditions for a set of nights.
     *
     * @param dates the nights to query
     * @return all scores for those dates
     */
    List<AstroConditionsEntity> findByForecastDateIn(List<LocalDate> dates);

    /**
     * Deletes all astro conditions for the given nights.
     * Called before inserting new results so a re-run replaces old data.
     *
     * @param dates the nights whose results should be removed
     */
    void deleteByForecastDateIn(List<LocalDate> dates);

    /**
     * Returns all dates that have stored astro conditions, in ascending order.
     *
     * @return distinct forecast dates
     */
    @Query("SELECT DISTINCT a.forecastDate FROM AstroConditionsEntity a ORDER BY a.forecastDate")
    List<LocalDate> findDistinctForecastDates();
}
