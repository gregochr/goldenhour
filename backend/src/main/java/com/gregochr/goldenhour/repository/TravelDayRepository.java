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
     * Returns the current and upcoming travel ranges — those whose end date is on or after
     * {@code today} — ordered by start date descending (furthest-future range first). Past ranges
     * (entirely before today) are excluded, since they neither gate any upcoming forecast nor need
     * to clutter the admin list.
     *
     * @param today the cutoff date (a range is kept when {@code endDate >= today})
     * @return current and future ranges, furthest-future first
     */
    List<TravelDayEntity> findByEndDateGreaterThanEqualOrderByStartDateDesc(LocalDate today);

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
