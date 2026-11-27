package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Access to {@code ci_spend_run}, written only by {@code CiSpendPollService} and read by the Runs cost views.
 */
public interface CiSpendRunRepository extends JpaRepository<CiSpendRunEntity, Long> {

    /**
     * Finds the row for one suite of one attempt, without a lock.
     *
     * @param githubRunId the workflow run id
     * @param runAttempt  the attempt number
     * @param suite       {@code sky} or {@code ask}
     * @return the row, if stored
     */
    Optional<CiSpendRunEntity> findByGithubRunIdAndRunAttemptAndSuite(Long githubRunId, Integer runAttempt,
            String suite);

    /**
     * Finds the row for one suite of one attempt with a write lock, so an UNKNOWN row is replaced by one writer only.
     *
     * @param githubRunId the workflow run id
     * @param runAttempt  the attempt number
     * @param suite       {@code sky} or {@code ask}
     * @return the locked row, if stored
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CiSpendRunEntity r where r.githubRunId = :runId and r.runAttempt = :attempt "
            + "and r.suite = :suite")
    Optional<CiSpendRunEntity> lockByKey(@Param("runId") Long githubRunId, @Param("attempt") Integer runAttempt,
            @Param("suite") String suite);

    /**
     * Rows in one usage state, oldest first (used to reprice the UNPRICED ones).
     *
     * @param usageState the state
     * @return the matching rows
     */
    List<CiSpendRunEntity> findByUsageStateOrderByStartedAtAsc(String usageState);

    /**
     * Rows that started in a half-open window, oldest first (the Cost trends read; model rows stay lazy).
     *
     * @param from the window start, inclusive
     * @param to   the window end, exclusive
     * @return the rows
     */
    List<CiSpendRunEntity> findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscSuiteAscRunAttemptAsc(
            Instant from, Instant to);

    /**
     * Rows that started in a half-open window with their model rows, in start, suite, attempt order (the Day view
     * read, which sums the tokens).
     *
     * @param from the window start, inclusive
     * @param to   the window end, exclusive
     * @return the rows, each model collection loaded
     */
    @Query("select distinct r from CiSpendRunEntity r left join fetch r.models where r.startedAt >= :from "
            + "and r.startedAt < :to order by r.startedAt, r.suite, r.runAttempt")
    List<CiSpendRunEntity> findWithModelsStartedBetween(@Param("from") Instant from, @Param("to") Instant to);
}
