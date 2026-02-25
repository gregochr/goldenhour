package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.JobName;
import com.gregochr.goldenhour.entity.JobRunEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data repository for {@link JobRunEntity}.
 */
public interface JobRunRepository extends JpaRepository<JobRunEntity, Long> {

    /**
     * Finds recent job runs by name, ordered by start time descending.
     *
     * @param jobName the job name to filter by
     * @param pageable pagination configuration
     * @return list of job runs
     */
    List<JobRunEntity> findByJobNameOrderByStartedAtDesc(JobName jobName, Pageable pageable);

    /**
     * Finds all job runs that started after a given time, ordered descending.
     *
     * @param since the start time threshold
     * @return list of job runs
     */
    List<JobRunEntity> findByStartedAtAfterOrderByStartedAtDesc(LocalDateTime since);
}
