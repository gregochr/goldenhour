package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CloudPointCache}.
 */
class CloudPointCacheTest {

    @Test
    @DisplayName("getPressureLevels resolves by the same grid key as get, snapping nearby points")
    void getPressureLevels_sameGridKey_snapsNearbyPoints() {
        OpenMeteoForecastResponse cloud = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse levels = new OpenMeteoForecastResponse();
        String key = CloudPointCache.gridKey(54.90, -1.58);
        CloudPointCache cache = new CloudPointCache(Map.of(key, cloud), Map.of(key, levels));

        assertThat(cache.getPressureLevels(54.90, -1.58)).isSameAs(levels);
        assertThat(cache.getPressureLevels(54.9004, -1.5804)).isSameAs(levels);
        assertThat(cache.get(54.90, -1.58)).isSameAs(cloud);
    }

    @Test
    @DisplayName("getPressureLevels is null for a cell with no levels, and size counts the cloud map only")
    void getPressureLevels_absent_nullAndSizeIgnoresLevels() {
        OpenMeteoForecastResponse cloud = new OpenMeteoForecastResponse();
        String cloudKey = CloudPointCache.gridKey(54.90, -1.58);
        String levelKey = CloudPointCache.gridKey(55.50, -2.00);
        CloudPointCache cache = new CloudPointCache(Map.of(cloudKey, cloud),
                Map.of(levelKey, new OpenMeteoForecastResponse()));

        assertThat(cache.getPressureLevels(54.90, -1.58)).isNull();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the one-argument constructor leaves the pressure-level map empty")
    void oneArgConstructor_noPressureLevels() {
        CloudPointCache cache = new CloudPointCache(
                Map.of(CloudPointCache.gridKey(54.90, -1.58), new OpenMeteoForecastResponse()));

        assertThat(cache.getPressureLevels(54.90, -1.58)).isNull();
        assertThat(cache.size()).isEqualTo(1);
    }
}
