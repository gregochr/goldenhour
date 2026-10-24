package com.gregochr.goldenhour.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Another name for one location, for example "The Heugh" for Hartlepool Headland.
 *
 * <p>Keyed by location id and alias. There is deliberately no association to {@link LocationEntity}.
 */
@Entity
@Table(name = "location_alias")
@IdClass(LocationAliasId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LocationAliasEntity {

    /** The aliased location's primary key. */
    @Id
    @Column(name = "location_id")
    private Long locationId;

    /** The other name. */
    @Id
    @Column(name = "alias", length = 80)
    private String alias;
}
