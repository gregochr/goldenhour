package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ImoCalendarFetchEntity;
import com.gregochr.goldenhour.entity.ImoCalendarFetchOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the IMO calendar refresh log.
 */
public interface ImoCalendarFetchRepository extends JpaRepository<ImoCalendarFetchEntity, Long> {

    /**
     * The newest attempts for a year, newest first.
     *
     * @param calendarYear the IMO calendar year
     * @return at most the four most recent rows (enough to see whether a failure run is exactly three long)
     */
    List<ImoCalendarFetchEntity> findTop4ByCalendarYearOrderByAttemptedAtDescIdDesc(int calendarYear);

    /**
     * The newest attempt for a year whose outcome is one of {@code outcomes}.
     *
     * @param calendarYear the IMO calendar year
     * @param outcomes     the outcomes to consider
     * @return the newest matching row, if any
     */
    Optional<ImoCalendarFetchEntity> findFirstByCalendarYearAndOutcomeInOrderByAttemptedAtDescIdDesc(
            int calendarYear, Collection<ImoCalendarFetchOutcome> outcomes);

    /**
     * Whether a year has an attempt with this outcome at or after {@code since}.
     *
     * @param calendarYear the IMO calendar year
     * @param outcome      the outcome
     * @param since        the earliest attempt time counted
     * @return true when such a row exists
     */
    boolean existsByCalendarYearAndOutcomeAndAttemptedAtGreaterThanEqual(
            int calendarYear, ImoCalendarFetchOutcome outcome, Instant since);
}
