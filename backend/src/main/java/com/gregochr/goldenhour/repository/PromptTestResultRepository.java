package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PromptTestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for prompt test results.
 */
public interface PromptTestResultRepository extends JpaRepository<PromptTestResultEntity, Long> {

    /**
     * Returns results for a specific test run, ordered by location name.
     *
     * @param testRunId the test run ID
     * @return results ordered by location name ascending
     */
    List<PromptTestResultEntity> findByTestRunIdOrderByLocationNameAsc(Long testRunId);

    /**
     * Returns results for a specific test run, ordered by location, date, and target type.
     *
     * @param testRunId the test run ID
     * @return results ordered by location name, target date, then target type ascending
     */
    List<PromptTestResultEntity> findByTestRunIdOrderByLocationNameAscTargetDateAscTargetTypeAsc(
            Long testRunId);
}
