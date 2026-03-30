package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LocationEntity#supportsTargetType(TargetType)}.
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
}
