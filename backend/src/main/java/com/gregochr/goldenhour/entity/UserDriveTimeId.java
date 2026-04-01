package com.gregochr.goldenhour.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link UserDriveTimeEntity}.
 */
public class UserDriveTimeId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long locationId;

    /** No-arg constructor required by JPA. */
    public UserDriveTimeId() {
    }

    /**
     * Constructs a composite key.
     *
     * @param userId     the user primary key
     * @param locationId the location primary key
     */
    public UserDriveTimeId(Long userId, Long locationId) {
        this.userId = userId;
        this.locationId = locationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserDriveTimeId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(locationId, that.locationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, locationId);
    }
}
