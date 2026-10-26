package com.gregochr.goldenhour.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * A named area such as "Hadrian's Wall": a name, other names for it, and the locations that belong to it.
 *
 * <p>Membership is explicit (location ids), not geometry. There is deliberately no association to
 * {@link LocationEntity}, so saving a location can never rewrite membership. Both collections are lazy:
 * read them inside a read-only transaction and never return the entity from a controller.
 *
 * <p>A {@link AreaKind#SUB_REGION} also has a {@link #region}, and its members are NOT in {@link #locationIds}
 * (that is {@code area_location}, which cannot carry the one-per-location rule): they are the
 * {@code location_sub_region} rows, read through {@code LocationSubRegionRepository}.
 */
@Entity
@Table(name = "area")
@Getter
@Setter
@NoArgsConstructor
public class AreaEntity {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Display name, unique case-insensitively. */
    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /**
     * Which kind of area this is. Null (a row read before V191, or a local schema that predates the column)
     * reads as {@link AreaKind#NAMED}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    @ColumnDefault("'NAMED'")
    private AreaKind kind = AreaKind.NAMED;

    /** The region a sub-region partitions; null for a {@link AreaKind#NAMED} area. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private RegionEntity region;

    /** When the area was created (wall clock, matching the {@code TIMESTAMP} column). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Other names readers use for the area. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "area_alias", joinColumns = @JoinColumn(name = "area_id"))
    @Column(name = "alias", nullable = false, length = 80)
    private Set<String> aliases = new HashSet<>();

    /** Ids of the member locations. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "area_location", joinColumns = @JoinColumn(name = "area_id"))
    @Column(name = "location_id", nullable = false)
    private Set<Long> locationIds = new HashSet<>();

    /**
     * Returns the kind, reading null as {@link AreaKind#NAMED}.
     *
     * @return the kind; never null
     */
    public AreaKind getKind() {
        return kind == null ? AreaKind.NAMED : kind;
    }

    /**
     * Whether this area is a sub-region.
     *
     * @return true for {@link AreaKind#SUB_REGION}
     */
    public boolean isSubRegion() {
        return getKind() == AreaKind.SUB_REGION;
    }

    /** Stamps the creation time when the row is first saved. */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
