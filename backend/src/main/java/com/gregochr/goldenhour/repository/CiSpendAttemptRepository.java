package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CiSpendAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Access to {@code ci_spend_attempt}: the marker that a CI run attempt is settled. The key is
 * {@link CiSpendAttemptEntity.Key}.
 */
public interface CiSpendAttemptRepository extends JpaRepository<CiSpendAttemptEntity, CiSpendAttemptEntity.Key> {
}
