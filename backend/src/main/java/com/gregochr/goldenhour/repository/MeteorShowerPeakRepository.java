package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.MeteorShowerPeakEntity;
import com.gregochr.goldenhour.entity.MeteorShowerPeakId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the per-year meteor shower peaks.
 */
public interface MeteorShowerPeakRepository extends JpaRepository<MeteorShowerPeakEntity, MeteorShowerPeakId> {
}
