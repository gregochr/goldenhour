package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link ModelTestRunEntity}.
 */
public interface ModelTestRunRepository extends JpaRepository<ModelTestRunEntity, Long> {

    /**
     * Returns the 20 most recent test runs, ordered by start time descending.
     *
     * @return list of recent test runs
     */
    List<ModelTestRunEntity> findTop20ByOrderByStartedAtDesc();
}
