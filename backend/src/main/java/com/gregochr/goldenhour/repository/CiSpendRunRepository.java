package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CiSpendRunEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
