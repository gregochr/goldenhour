package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.SubRegionPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the single {@link SubRegionPolicyEntity} row (id 1).
 */
@Repository
public interface SubRegionPolicyRepository extends JpaRepository<SubRegionPolicyEntity, Short> {
}
