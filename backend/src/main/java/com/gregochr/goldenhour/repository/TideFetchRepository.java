package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.TideFetchEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

/**
 * Spring Data repository for {@link TideFetchEntity}, the per-call WorldTides provenance log.
 */
public interface TideFetchRepository extends JpaRepository<TideFetchEntity, Long> {

    /**
     * Returns the most recent successful fetch of a location whose reason is in the given set.
     *
     * <p>Callers pass the forward reasons only, so a history backfill never counts as the point
     * the stored forward data came from.
     *
     * @param locationId the location primary key
     * @param reasons    the reason codes to consider
     * @return the latest matching successful fetch, if any
     */
    Optional<TideFetchEntity> findFirstByLocationIdAndSuccessTrueAndReasonInOrderByFetchedAtDescIdDesc(
            Long locationId, Collection<String> reasons);
}
