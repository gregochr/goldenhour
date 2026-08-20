package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity representing a geographic region used to group forecast locations.
 *
 * <p>Regions are managed exclusively via the REST API and persist in the database.
 * Disabled regions are hidden from location add/edit dropdowns but do not affect
 * existing location associations.
 */
@Entity
@Table(name = "regions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegionEntity {

    /** Database primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable region name (e.g. "Northumberland"). */
    @Column(nullable = false, unique = true)
    private String name;

    /** Whether this region is enabled and visible in location dropdowns. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * The town a visitor would actually base themselves in — the origin the Plan tab plans from
     * when the reader moves the origin here (plan P7).
     *
     * <p><b>Nullable, and admin-entered rather than derived.</b> A region's centroid is frequently
     * offshore or on a fell, so a drive time computed from it measures a journey nobody can make.
     * A region with no base is still searchable and still appears everywhere it did before; it
     * simply cannot be an origin, and the UI says why rather than inventing a point.
     *
     * <p>The three base columns move together — {@code RegionService} rejects a partial base — but
     * that is enforced in Java, not by a check constraint, because every pre-P7 row has none.
     */
    @Column(name = "base_name", length = 120)
    private String baseName;

    /** The base town's latitude, or null when the region has no base. */
    @Column(name = "base_lat")
    private Double baseLat;

    /** The base town's longitude, or null when the region has no base. */
    @Column(name = "base_lon")
    private Double baseLon;

    /** UTC timestamp when this region was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
