package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link LocationEntity}.
 */
public interface LocationRepository extends JpaRepository<LocationEntity, Long> {

    /**
     * Returns {@code true} if a location with the given name already exists.
     *
     * @param name location name to check
     * @return {@code true} if a matching row exists
     */
    boolean existsByName(String name);

    /**
     * Returns all locations ordered alphabetically by name.
     *
     * @return locations sorted by name ascending
     */
    List<LocationEntity> findAllByOrderByNameAsc();
}
