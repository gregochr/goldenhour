package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the single-row daily briefing cache.
 */
public interface DailyBriefingCacheRepository extends JpaRepository<DailyBriefingCacheEntity, Integer> {
}
