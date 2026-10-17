package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link NeighbourGuardProperties}: the defaults and every bound, at and beyond. */
class NeighbourGuardPropertiesTest {

    private record Bound(String key, int min, int max, BiConsumer<NeighbourGuardProperties, Integer> setter) {
        @Override
        public String toString() {
            return key + " " + min + ".." + max;
        }
    }

    static Stream<Bound> bounds() {
        return Stream.of(
                new Bound("min-neighbours", 1, 5, NeighbourGuardProperties::setMinNeighbours),
                new Bound("margin", 2, 4, NeighbourGuardProperties::setMargin),
                new Bound("min-rating", 3, 5, NeighbourGuardProperties::setMinRating),
                new Bound("max-elevation-gap-m", 0, 1000, NeighbourGuardProperties::setMaxElevationGapM));
    }

    @Test
    @DisplayName("the defaults are the plan's: off, 5 km, 2 neighbours, margin 2, floor 3, 150 m")
    void defaults() {
        NeighbourGuardProperties p = new NeighbourGuardProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getRadiusKm()).isEqualTo(5.0);
        assertThat(p.getMinNeighbours()).isEqualTo(2);
        assertThat(p.getMargin()).isEqualTo(2);
        assertThat(p.getMinRating()).isEqualTo(3);
        assertThat(p.getMaxElevationGapM()).isEqualTo(150);
    }

    @ParameterizedTest
    @MethodSource("bounds")
    @DisplayName("each integer setting accepts its limits and refuses one beyond either")
    void boundsAreEnforced(Bound bound) {
        NeighbourGuardProperties p = new NeighbourGuardProperties();

        assertThatCode(() -> bound.setter().accept(p, bound.min())).doesNotThrowAnyException();
        assertThatCode(() -> bound.setter().accept(p, bound.max())).doesNotThrowAnyException();
        assertThatThrownBy(() -> bound.setter().accept(p, bound.min() - 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photocast.neighbour-guard." + bound.key());
        assertThatThrownBy(() -> bound.setter().accept(p, bound.max() + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("photocast.neighbour-guard." + bound.key());
    }

    @Test
    @DisplayName("radius-km accepts 1 and 20 and refuses just beyond either, and NaN")
    void radiusBounds() {
        NeighbourGuardProperties p = new NeighbourGuardProperties();

        assertThatCode(() -> p.setRadiusKm(1.0)).doesNotThrowAnyException();
        assertThatCode(() -> p.setRadiusKm(20.0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> p.setRadiusKm(0.99)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius-km");
        assertThatThrownBy(() -> p.setRadiusKm(20.01)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("radius-km");
        assertThatThrownBy(() -> p.setRadiusKm(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getRadiusKm()).as("a refused value leaves the last good one").isEqualTo(20.0);
    }

    @Test
    @DisplayName("a refused integer leaves the earlier value in place")
    void refusedValueIsNotStored() {
        NeighbourGuardProperties p = new NeighbourGuardProperties();

        assertThatThrownBy(() -> p.setMargin(5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getMargin()).isEqualTo(2);
    }

    @Test
    @DisplayName("bound from properties, an in-range set takes effect and a bad value fails the bind")
    void bindsFromProperties() {
        Binder good = new Binder(new MapConfigurationPropertySource(Map.of(
                "photocast.neighbour-guard.enabled", "true",
                "photocast.neighbour-guard.radius-km", "8.5",
                "photocast.neighbour-guard.min-rating", "4")));
        NeighbourGuardProperties bound = good.bind("photocast.neighbour-guard", NeighbourGuardProperties.class)
                .get();

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getRadiusKm()).isEqualTo(8.5);
        assertThat(bound.getMinRating()).isEqualTo(4);

        Binder bad = new Binder(new MapConfigurationPropertySource(Map.of(
                "photocast.neighbour-guard.margin", "1")));
        assertThatThrownBy(() -> bad.bind("photocast.neighbour-guard", NeighbourGuardProperties.class))
                .isInstanceOf(BindException.class);
    }
}
