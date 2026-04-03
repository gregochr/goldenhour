package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LocationEntity}.
 */
class LocationEntityTest {

    @Test
    @DisplayName("null solarEventType supports all target types")
    void nullSolarEventType_supportsAll() {
        LocationEntity loc = LocationEntity.builder().name("Test").lat(55).lon(-1)
                .solarEventType(null).build();

        assertThat(loc.supportsTargetType(TargetType.SUNRISE)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.SUNSET)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.HOURLY)).isTrue();
    }

    @Test
    @DisplayName("empty solarEventType supports all target types")
    void emptySolarEventType_supportsAll() {
        LocationEntity loc = LocationEntity.builder().name("Test").lat(55).lon(-1)
                .solarEventType(Set.of()).build();

        assertThat(loc.supportsTargetType(TargetType.SUNRISE)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.SUNSET)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.HOURLY)).isTrue();
    }

    @Test
    @DisplayName("ALLDAY supports all target types")
    void allday_supportsAll() {
        LocationEntity loc = LocationEntity.builder().name("Test").lat(55).lon(-1)
                .solarEventType(Set.of(SolarEventType.ALLDAY)).build();

        assertThat(loc.supportsTargetType(TargetType.SUNRISE)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.SUNSET)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.HOURLY)).isTrue();
    }

    @Test
    @DisplayName("SUNRISE-only supports SUNRISE and HOURLY but not SUNSET")
    void sunriseOnly() {
        LocationEntity loc = LocationEntity.builder().name("Test").lat(55).lon(-1)
                .solarEventType(Set.of(SolarEventType.SUNRISE)).build();

        assertThat(loc.supportsTargetType(TargetType.SUNRISE)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.SUNSET)).isFalse();
        assertThat(loc.supportsTargetType(TargetType.HOURLY)).isTrue();
    }

    @Test
    @DisplayName("SUNSET-only supports SUNSET and HOURLY but not SUNRISE")
    void sunsetOnly() {
        LocationEntity loc = LocationEntity.builder().name("Test").lat(55).lon(-1)
                .solarEventType(Set.of(SolarEventType.SUNSET)).build();

        assertThat(loc.supportsTargetType(TargetType.SUNRISE)).isFalse();
        assertThat(loc.supportsTargetType(TargetType.SUNSET)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.HOURLY)).isTrue();
    }

    @Test
    @DisplayName("SUNRISE + SUNSET supports both")
    void sunriseAndSunset_supportsBoth() {
        LocationEntity loc = LocationEntity.builder().name("Test").lat(55).lon(-1)
                .solarEventType(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)).build();

        assertThat(loc.supportsTargetType(TargetType.SUNRISE)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.SUNSET)).isTrue();
        assertThat(loc.supportsTargetType(TargetType.HOURLY)).isTrue();
    }

    @Nested
    @DisplayName("hasGridCell()")
    class HasGridCellTests {

        @Test
        @DisplayName("returns true when both gridLat and gridLng are set")
        void bothPresent_returnsTrue() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1)
                    .gridLat(55.0).gridLng(-1.0).build();
            assertThat(loc.hasGridCell()).isTrue();
        }

        @Test
        @DisplayName("returns false when both are null")
        void bothNull_returnsFalse() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1).build();
            assertThat(loc.hasGridCell()).isFalse();
        }

        @Test
        @DisplayName("returns false when only gridLat is set")
        void onlyLatSet_returnsFalse() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1)
                    .gridLat(55.0).build();
            assertThat(loc.hasGridCell()).isFalse();
        }

        @Test
        @DisplayName("returns false when only gridLng is set")
        void onlyLngSet_returnsFalse() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1)
                    .gridLng(-1.0).build();
            assertThat(loc.hasGridCell()).isFalse();
        }

        @Test
        @DisplayName("returns true for zero coordinates (equator/prime meridian)")
        void zeroCoordinates_returnsTrue() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(0).lon(0)
                    .gridLat(0.0).gridLng(0.0).build();
            assertThat(loc.hasGridCell()).isTrue();
        }

        @Test
        @DisplayName("returns true for negative coordinates (southern/western hemisphere)")
        void negativeCoordinates_returnsTrue() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(-34).lon(-58)
                    .gridLat(-34.0).gridLng(-58.0).build();
            assertThat(loc.hasGridCell()).isTrue();
        }
    }

    @Nested
    @DisplayName("gridCellKey()")
    class GridCellKeyTests {

        @Test
        @DisplayName("formats to 4 decimal places with comma separator")
        void format_4dp() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1)
                    .gridLat(55.6).gridLng(-1.7).build();
            assertThat(loc.gridCellKey()).isEqualTo("55.6000,-1.7000");
        }

        @Test
        @DisplayName("rounds long decimals consistently")
        void roundsLongDecimals() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1)
                    .gridLat(55.60005).gridLng(-1.69995).build();
            assertThat(loc.gridCellKey()).isEqualTo("55.6001,-1.7000");
        }

        @Test
        @DisplayName("identical grid coords produce identical keys")
        void identicalCoords_identicalKeys() {
            LocationEntity loc1 = LocationEntity.builder().name("A").lat(55).lon(-1)
                    .gridLat(55.1234).gridLng(-1.5678).build();
            LocationEntity loc2 = LocationEntity.builder().name("B").lat(55).lon(-1)
                    .gridLat(55.1234).gridLng(-1.5678).build();
            assertThat(loc1.gridCellKey()).isEqualTo(loc2.gridCellKey());
        }

        @Test
        @DisplayName("produces 'null' token when gridLat is null — callers must check hasGridCell() first")
        void nullGridLat_producesNullToken() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(55).lon(-1)
                    .gridLng(-1.0).build();
            // String.format handles null Double by printing "null" — not a usable key,
            // which is why callers must always check hasGridCell() before calling gridCellKey().
            assertThat(loc.gridCellKey()).contains("null");
        }

        @Test
        @DisplayName("handles negative coordinates correctly")
        void negativeCoordinates() {
            LocationEntity loc = LocationEntity.builder().name("T").lat(-34).lon(-58)
                    .gridLat(-34.625).gridLng(-58.375).build();
            assertThat(loc.gridCellKey()).isEqualTo("-34.6250,-58.3750");
        }
    }
}
