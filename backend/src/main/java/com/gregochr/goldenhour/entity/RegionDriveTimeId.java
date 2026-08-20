package com.gregochr.goldenhour.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link RegionDriveTimeEntity}.
 */
public class RegionDriveTimeId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long regionId;
    private Long locationId;

    /** No-arg constructor required by JPA. */
    public RegionDriveTimeId() {
    }

    /**
     * Constructs a composite key.
     *
     * @param regionId   the origin region's primary key
     * @param locationId the destination location's primary key
     */
    public RegionDriveTimeId(Long regionId, Long locationId) {
        this.regionId = regionId;
        this.locationId = locationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RegionDriveTimeId that)) {
            return false;
        }
        return Objects.equals(regionId, that.regionId)
                && Objects.equals(locationId, that.locationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(regionId, locationId);
    }
}
