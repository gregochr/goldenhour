package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link ModelTestResultEntity}.
 */
public interface ModelTestResultRepository extends JpaRepository<ModelTestResultEntity, Long> {

    /**
     * Returns all results for a test run, ordered by region name then model.
     *
     * @param testRunId the parent test run ID
     * @return results grouped by region and ordered by model
     */
    List<ModelTestResultEntity> findByTestRunIdOrderByRegionNameAscEvaluationModelAsc(Long testRunId);
}
