package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RegionDriveTimeId}.
 *
 * <p><b>What breaks if these fail.</b> This is an {@code @IdClass}, so Hibernate uses its
 * {@code equals}/{@code hashCode} to decide whether two rows are the same row. A key that ignored
 * {@code regionId} would make Keswick's drive to Bamburgh and Bakewell's drive to Bamburgh the same
 * persistence-context entry, and the second region's sweep would silently overwrite the first's —
 * on the Plan tab that is an away origin showing another region's drive times, which reaches the
 * leave-by line.
 */
class RegionDriveTimeIdTest {

    @Test
    @DisplayName("two keys with the same region and location are equal and hash alike")
    void equals_sameComponents_isEqual() {
        RegionDriveTimeId a = new RegionDriveTimeId(7L, 11L);
        RegionDriveTimeId b = new RegionDriveTimeId(7L, 11L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("⚠️ a different REGION is a different key — the same journey from two bases")
    void equals_differentRegion_isNotEqual() {
        assertThat(new RegionDriveTimeId(7L, 11L)).isNotEqualTo(new RegionDriveTimeId(9L, 11L));
    }

    @Test
    @DisplayName("a different LOCATION is a different key")
    void equals_differentLocation_isNotEqual() {
        assertThat(new RegionDriveTimeId(7L, 11L)).isNotEqualTo(new RegionDriveTimeId(7L, 12L));
    }

    @Test
    @DisplayName("both components distinguish keys inside a Set, which is how JPA uses them")
    void hashCode_distinguishesBothComponents() {
        Set<RegionDriveTimeId> keys = new HashSet<>();
        keys.add(new RegionDriveTimeId(7L, 11L));
        keys.add(new RegionDriveTimeId(7L, 11L));
        keys.add(new RegionDriveTimeId(9L, 11L));
        keys.add(new RegionDriveTimeId(7L, 12L));

        assertThat(keys).hasSize(3);
    }

    @Test
    @DisplayName("is reflexive, and unequal to null and to another type")
    void equals_edges() {
        RegionDriveTimeId key = new RegionDriveTimeId(7L, 11L);

        assertThat(key.equals(key)).isTrue();
        assertThat(key).isNotEqualTo(null);
        assertThat(key).isNotEqualTo("7:11");
    }

    @Test
    @DisplayName("the no-arg constructor JPA requires leaves both components null, and is self-equal")
    void noArgConstructor_isUsableAsAKey() {
        assertThat(new RegionDriveTimeId()).isEqualTo(new RegionDriveTimeId());
        assertThat(new RegionDriveTimeId()).isNotEqualTo(new RegionDriveTimeId(7L, 11L));
    }
}
