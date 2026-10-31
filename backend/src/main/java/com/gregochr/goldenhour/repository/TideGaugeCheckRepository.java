package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TideGaugeCheckEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link TideGaugeCheckEntity}.
 */
public interface TideGaugeCheckRepository extends JpaRepository<TideGaugeCheckEntity, Long> {

    /**
     * Returns a gauge's most recent checks at a location, newest first.
     *
     * @param locationId     the mapped location
     * @param gaugeMeasureId the gauge measure id
     * @param page           page request that limits how many runs are returned
     * @return the checks, newest first
     */
    List<TideGaugeCheckEntity> findByLocationIdAndGaugeMeasureIdOrderByRunAtDesc(
            Long locationId, String gaugeMeasureId, Pageable page);
}
