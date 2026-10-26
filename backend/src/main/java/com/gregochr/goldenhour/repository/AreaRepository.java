package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository for {@link AreaEntity}.
 */
@Repository
public interface AreaRepository extends JpaRepository<AreaEntity, Long> {

    /**
     * Every area of one kind.
     *
     * @param kind the kind
     * @return the areas, in no particular order
     */
    List<AreaEntity> findByKind(AreaKind kind);

    /**
     * How many areas are of one kind.
     *
     * @param kind the kind
     * @return the count
     */
    long countByKind(AreaKind kind);
}
