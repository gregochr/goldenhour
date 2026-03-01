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

    /** UTC timestamp when this region was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
