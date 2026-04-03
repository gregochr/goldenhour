package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link SchedulerJobConfigEntity}.
 */
public interface SchedulerJobConfigRepository extends JpaRepository<SchedulerJobConfigEntity, Long> {

    /**
     * Finds a job config by its unique key.
     *
     * @param jobKey the job key (e.g. "tide_refresh")
     * @return the config entity, if present
     */
    Optional<SchedulerJobConfigEntity> findByJobKey(String jobKey);

    /**
     * Returns all job configs ordered by ID ascending.
     *
     * @return all configs in insertion order
     */
    List<SchedulerJobConfigEntity> findAllByOrderByIdAsc();
}
