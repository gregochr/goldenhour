package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SentinelSelector}.
 */
class SentinelSelectorTest {

    private SentinelSelector selector;

    @BeforeEach
    void setUp() {
        selector = new SentinelSelector();
    }

    @Test
    @DisplayName("5 or fewer locations returns all")
    void fiveOrFewer_returnsAll() {
        List<LocationEntity> locations = List.of(
                loc("A", 54.0, -1.0),
                loc("B", 55.0, -2.0),
                loc("C", 53.0, 0.0));
        List<LocationEntity> sentinels = selector.selectSentinels(locations);
        assertThat(sentinels).hasSize(3);
        assertThat(sentinels).containsExactlyInAnyOrderElementsOf(locations);
    }

    @Test
    @DisplayName("Exactly 5 locations returns all")
    void exactlyFive_returnsAll() {
        List<LocationEntity> locations = List.of(
                loc("A", 54.0, -1.0), loc("B", 55.0, -2.0),
                loc("C", 53.0, 0.0), loc("D", 54.5, -1.5),
                loc("E", 53.5, -0.5));
        List<LocationEntity> sentinels = selector.selectSentinels(locations);
        assertThat(sentinels).hasSize(5);
    }

    @Test
    @DisplayName(">5 locations returns geographic extremes plus centroid")
    void moreThanFive_returnsExtremes() {
        LocationEntity north = loc("North", 56.0, -1.0);
        LocationEntity south = loc("South", 50.0, -1.0);
        LocationEntity east = loc("East", 53.0, 2.0);
        LocationEntity west = loc("West", 53.0, -5.0);
        LocationEntity centre = loc("Centre", 53.0, -1.0);
        LocationEntity extra1 = loc("Extra1", 54.0, -2.0);
        LocationEntity extra2 = loc("Extra2", 52.0, 0.0);

        List<LocationEntity> sentinels = selector.selectSentinels(
                List.of(north, south, east, west, centre, extra1, extra2));

        assertThat(sentinels).contains(north, south, east, west);
        assertThat(sentinels.size()).isBetween(3, 5);
    }

    @Test
    @DisplayName("Deduplicates when extremes overlap")
    void deduplicates_whenExtremesOverlap() {
        // Same location is both most north and most east
        LocationEntity ne = loc("NE", 56.0, 2.0);
        LocationEntity sw = loc("SW", 50.0, -5.0);
        LocationEntity mid1 = loc("Mid1", 53.0, -1.0);
        LocationEntity mid2 = loc("Mid2", 52.5, -0.5);
        LocationEntity mid3 = loc("Mid3", 53.5, -1.5);
        LocationEntity mid4 = loc("Mid4", 54.0, -2.0);

        List<LocationEntity> sentinels = selector.selectSentinels(
                List.of(ne, sw, mid1, mid2, mid3, mid4));

        // NE is both max lat and max lon — should not be duplicated
        long neCount = sentinels.stream().filter(s -> s.getName().equals("NE")).count();
        assertThat(neCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Single location returns that location")
    void singleLocation_returnsSingle() {
        LocationEntity only = loc("Only", 54.0, -1.0);
        List<LocationEntity> sentinels = selector.selectSentinels(List.of(only));
        assertThat(sentinels).containsExactly(only);
    }

    @Test
    @DisplayName("Two locations returns both")
    void twoLocations_returnsBoth() {
        LocationEntity a = loc("A", 54.0, -1.0);
        LocationEntity b = loc("B", 55.0, -2.0);
        List<LocationEntity> sentinels = selector.selectSentinels(List.of(a, b));
        assertThat(sentinels).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("Centroid location is included in sentinels")
    void centroidIncluded() {
        LocationEntity north = loc("North", 60.0, 0.0);
        LocationEntity south = loc("South", 40.0, 0.0);
        LocationEntity east = loc("East", 50.0, 10.0);
        LocationEntity west = loc("West", 50.0, -10.0);
        // Centroid is (50, 0). Centre is closest.
        LocationEntity centre = loc("Centre", 50.0, 0.0);
        LocationEntity farOff = loc("FarOff", 45.0, 5.0);

        List<LocationEntity> sentinels = selector.selectSentinels(
                List.of(north, south, east, west, centre, farOff));

        assertThat(sentinels).contains(centre);
    }

    @Test
    @DisplayName("Result never exceeds 5 sentinels")
    void neverExceedsFive() {
        List<LocationEntity> locations = List.of(
                loc("A", 60.0, 10.0), loc("B", 40.0, -10.0),
                loc("C", 50.0, 15.0), loc("D", 50.0, -15.0),
                loc("E", 50.0, 0.0), loc("F", 55.0, 5.0),
                loc("G", 45.0, -5.0), loc("H", 52.0, 3.0));
        List<LocationEntity> sentinels = selector.selectSentinels(locations);
        assertThat(sentinels.size()).isLessThanOrEqualTo(5);
    }

    private static LocationEntity loc(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id((long) name.hashCode())
                .name(name)
                .lat(lat)
                .lon(lon)
                .build();
    }
}
