package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.EvaluationDeltaLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for evaluation delta log entries.
 */
public interface EvaluationDeltaLogRepository extends JpaRepository<EvaluationDeltaLogEntity, Long> {
}
