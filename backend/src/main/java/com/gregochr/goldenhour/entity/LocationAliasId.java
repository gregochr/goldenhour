package com.gregochr.goldenhour.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link LocationAliasEntity}.
 */
public class LocationAliasId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long locationId;
    private String alias;

    /** No-arg constructor required by JPA. */
    public LocationAliasId() {
    }

    /**
     * Constructs a composite key.
     *
     * @param locationId the location primary key
     * @param alias      the alias text
     */
    public LocationAliasId(Long locationId, String alias) {
        this.locationId = locationId;
        this.alias = alias;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LocationAliasId that)) {
            return false;
        }
        return Objects.equals(locationId, that.locationId) && Objects.equals(alias, that.alias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(locationId, alias);
    }
}
