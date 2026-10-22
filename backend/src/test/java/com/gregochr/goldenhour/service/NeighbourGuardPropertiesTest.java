package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
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
    @DisplayName("the defaults are the plan's: on, 5 km, 2 neighbours, margin 2, floor 3, 150 m")
    void defaults() {
        NeighbourGuardProperties p = new NeighbourGuardProperties();

        assertThat(p.isEnabled()).as("on by default since G4").isTrue();
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

    private static List<ILoggingEvent> logOf(NeighbourGuardProperties p) {
        Logger logger = (Logger) LoggerFactory.getLogger(NeighbourGuardProperties.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            p.afterPropertiesSet();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    @DisplayName("startup logs the effective configuration once at INFO")
    void startupLogsTheEffectiveConfiguration() {
        NeighbourGuardProperties p = new NeighbourGuardProperties();
        p.setRadiusKm(7.5);

        List<ILoggingEvent> events = logOf(p);

        assertThat(events).filteredOn(e -> e.getLevel() == Level.INFO).extracting(
                ILoggingEvent::getFormattedMessage).containsExactly(
                "[NEIGHBOUR GUARD] enabled=true radiusKm=7.5 minNeighbours=2 margin=2 minRating=3"
                        + " maxElevationGapM=150");
        assertThat(events).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
    }

    @Test
    @DisplayName("startup warns when min-rating minus margin is below 1, and not at exactly 1")
    void startupWarnsWhenTheGuardCanNeverFlagTheFloor() {
        NeighbourGuardProperties unreachable = new NeighbourGuardProperties();
        unreachable.setMargin(3);
        unreachable.setMinRating(3);
        NeighbourGuardProperties reachable = new NeighbourGuardProperties();
        reachable.setMargin(3);
        reachable.setMinRating(4);

        assertThat(logOf(unreachable)).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
        assertThat(logOf(reachable)).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
    }

    @Test
    @DisplayName("an empty configuration binds to the enabled defaults, and enabled=false is the rollback")
    void bindsEnabledByDefaultAndRollsBack() {
        NeighbourGuardProperties unset = new Binder(new MapConfigurationPropertySource(Map.of(
                "photocast.neighbour-guard.radius-km", "6")))
                .bind("photocast.neighbour-guard", NeighbourGuardProperties.class).get();
        NeighbourGuardProperties off = new Binder(new MapConfigurationPropertySource(Map.of(
                "photocast.neighbour-guard.enabled", "false")))
                .bind("photocast.neighbour-guard", NeighbourGuardProperties.class).get();

        assertThat(unset.isEnabled()).isTrue();
        assertThat(off.isEnabled()).isFalse();
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
