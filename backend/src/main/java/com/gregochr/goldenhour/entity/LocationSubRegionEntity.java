package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One row of {@code location_sub_region} (V191): the sub-region a location belongs to.
 *
 * <p>The primary key is the location, which is the "one sub-region per location" rule, enforced by the
 * database. The entity is read-only on purpose: it has no setters and is {@link Immutable}, and the table is
 * written only by {@code SubRegionMembershipService}, through native statements on
 * {@code LocationSubRegionRepository}. It exists so the local and unit-test schemas (built by Hibernate, not
 * Flyway) have the table, and so reads need no hand-written mapping. There is deliberately no association to
 * {@link LocationEntity} or {@link AreaEntity}: saving either can never rewrite membership.
 */
@Entity
@Immutable
@Table(name = "location_sub_region")
@Getter
@NoArgsConstructor
public class LocationSubRegionEntity {

    /** The member location (primary key). */
    @Id
    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** The {@code SUB_REGION} area the location belongs to. */
    @Column(name = "area_id", nullable = false)
    private Long areaId;
}
