package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.LocationPoint;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link LocationPointProvider}: the id map and its 60-second memo on the injected clock. */
class LocationPointProviderTest {

    private static final Instant T0 = Instant.parse("2026-10-18T06:00:00Z");

    private LocationRepository repository;
    private MutableClock clock;
    private LocationPointProvider provider;

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    @BeforeEach
    void setUp() {
        repository = mock(LocationRepository.class);
        clock = new MutableClock();
        provider = new LocationPointProvider(repository, clock);
    }

    @Test
    @DisplayName("points are keyed by location id, with coordinates and elevation")
    void keyedById() {
        when(repository.findEnabledPoints()).thenReturn(List.of(
                new LocationPoint(1L, 55.0, -2.0, 240), new LocationPoint(2L, 54.5, -3.1, null)));

        Map<Long, LocationPoint> points = provider.points();

        assertThat(points).containsOnlyKeys(1L, 2L);
        assertThat(points.get(1L).elevationMetres()).isEqualTo(240);
        assertThat(points.get(2L).elevationMetres()).isNull();
    }

    @Test
    @DisplayName("a read is reused for 60 seconds and repeated at 60")
    void memoisedForSixtySeconds() {
        when(repository.findEnabledPoints()).thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null)));

        provider.points();
        clock.advance(Duration.ofSeconds(59));
        provider.points();
        verify(repository, times(1)).findEnabledPoints();

        clock.advance(Duration.ofSeconds(1));
        provider.points();
        verify(repository, times(2)).findEnabledPoints();
    }

    @Test
    @DisplayName("a location added between reads appears at the next read")
    void refreshedAfterTheMemoExpires() {
        when(repository.findEnabledPoints())
                .thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null)))
                .thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null),
                        new LocationPoint(2L, 55.1, -2.1, null)));

        assertThat(provider.points()).containsOnlyKeys(1L);
        clock.advance(Duration.ofMinutes(2));
        assertThat(provider.points()).containsOnlyKeys(1L, 2L);
    }

    @Test
    @DisplayName("a failed read propagates and is not memoised")
    void failedReadIsNotMemoised() {
        when(repository.findEnabledPoints()).thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null)));

        assertThatThrownBy(provider::points).isInstanceOf(IllegalStateException.class);
        assertThat(provider.points()).containsOnlyKeys(1L);
    }
}
