package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.BriefingModelTestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link BriefingModelTestResultEntity} — individual model results
 * within a briefing comparison test run.
 */
public interface BriefingModelTestResultRepository extends JpaRepository<BriefingModelTestResultEntity, Long> {

    /**
     * Returns all results for a given test run, ordered by model name.
     *
     * @param testRunId the parent test run ID
     * @return results ordered by evaluation model ascending
     */
    List<BriefingModelTestResultEntity> findByTestRunIdOrderByEvaluationModelAsc(Long testRunId);
}
