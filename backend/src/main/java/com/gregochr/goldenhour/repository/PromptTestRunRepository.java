package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PromptTestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for prompt test runs.
 */
public interface PromptTestRunRepository extends JpaRepository<PromptTestRunEntity, Long> {

    /**
     * Returns the 20 most recent prompt test runs.
     *
     * @return recent runs ordered by start time descending
     */
    List<PromptTestRunEntity> findTop20ByOrderByStartedAtDesc();
}
