package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.BriefingModelTestRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link BriefingModelTestRunEntity} — briefing model comparison test runs.
 */
public interface BriefingModelTestRunRepository extends JpaRepository<BriefingModelTestRunEntity, Long> {

    /**
     * Returns the 20 most recent briefing model test runs, newest first.
     *
     * @return recent test runs
     */
    List<BriefingModelTestRunEntity> findTop20ByOrderByStartedAtDesc();
}
