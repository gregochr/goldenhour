package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Returns the astro conditions for a given night of the places that may be recommended: enabled and
     * Bortle-enriched, with each row's location and its region fetched.
     *
     * <p>{@code location} is {@code LAZY} on the entity, and Ask PhotoCast builds its snapshot on the
     * background executor outside any transaction (the Ready precompute), where reading a lazily-loaded
     * location's name, region, coordinates or Bortle class is a {@code LazyInitializationException}. The
     * region is a {@code LEFT JOIN FETCH} because a location's region is optional. The filter exists
     * because {@code AstroConditionsService.evaluateAndPersist} upserts per (location, date) and deletes
     * nothing, so a location disabled or stripped of its Bortle class after a run keeps its old row; the
     * Map's own {@link #findByForecastDate} read stays as it was.
     *
     * @param forecastDate the night to query
     * @return the night's rows for enabled, Bortle-enriched locations, location and region fetched
     */
    @Query("SELECT a FROM AstroConditionsEntity a JOIN FETCH a.location l LEFT JOIN FETCH l.region "
            + "WHERE a.forecastDate = :forecastDate AND l.enabled = true AND l.bortleClass IS NOT NULL")
    List<AstroConditionsEntity> findByForecastDateFetchingLocation(@Param("forecastDate") LocalDate forecastDate);

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
