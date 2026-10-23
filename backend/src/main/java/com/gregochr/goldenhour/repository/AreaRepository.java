package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AreaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link AreaEntity}.
 */
@Repository
public interface AreaRepository extends JpaRepository<AreaEntity, Long> {
}
