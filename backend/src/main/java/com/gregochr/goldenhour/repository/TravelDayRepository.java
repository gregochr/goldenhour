package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TravelDayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * Spring Data repository for {@link TravelDayEntity}.
 */
public interface TravelDayRepository extends JpaRepository<TravelDayEntity, Long> {

    /**
     * Returns all travel ranges ordered by start date ascending (display order).
     *
     * @return all ranges, soonest first
     */
    List<TravelDayEntity> findAllByOrderByStartDateAsc();

    /**
     * Tests whether the given date falls inside any travel range (inclusive of
     * both bounds). This is the containment check the batch gate runs per target
     * date.
     *
     * @param date the date to test
     * @return {@code true} if the date lies within at least one range
     */
    @Query("SELECT COUNT(t) > 0 FROM TravelDayEntity t "
            + "WHERE :date BETWEEN t.startDate AND t.endDate")
    boolean existsForDate(@Param("date") LocalDate date);
}
