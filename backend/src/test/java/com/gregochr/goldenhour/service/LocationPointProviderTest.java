package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.LocationPoint;
import com.gregochr.goldenhour.repository.LocationRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocationPointProvider}: the id map and its 60-second memo, timed on a monotonic
 * source the test moves (never the rewind-aware clock bean), failures included.
 */
class LocationPointProviderTest {

    private static final long SECOND = 1_000_000_000L;

    private LocationRepository repository;
    private long nanos;
    private LocationPointProvider provider;
    private ListAppender<ILoggingEvent> warnings;
    private Logger providerLogger;

    @BeforeEach
    void setUp() {
        repository = mock(LocationRepository.class);
        nanos = 5 * SECOND;
        provider = new LocationPointProvider(repository, () -> nanos);
        providerLogger = (Logger) LoggerFactory.getLogger(LocationPointProvider.class);
        warnings = new ListAppender<>();
        warnings.start();
        providerLogger.addAppender(warnings);
    }

    @AfterEach
    void tearDown() {
        providerLogger.detachAppender(warnings);
    }

    private void advanceSeconds(long seconds) {
        nanos += seconds * SECOND;
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
        advanceSeconds(59);
        provider.points();
        verify(repository, times(1)).findEnabledPoints();

        advanceSeconds(1);
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
        advanceSeconds(120);
        assertThat(provider.points()).containsOnlyKeys(1L, 2L);
    }

    @Test
    @DisplayName("a failed read answers an empty map, is memoised for the minute and WARNs once")
    void failedReadIsMemoisedAndWarnedOnce() {
        when(repository.findEnabledPoints()).thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null)));

        assertThat(provider.points()).isEmpty();
        advanceSeconds(30);
        assertThat(provider.points()).isEmpty();
        assertThat(provider.points()).isEmpty();

        verify(repository, times(1)).findEnabledPoints();
        assertThat(warnings.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
        assertThat(warnings.list.get(0).getFormattedMessage()).contains("db down");
    }

    @Test
    @DisplayName("after the minute a failed read is retried, and a recovery is served")
    void failedReadIsRetriedAfterTheMemo() {
        when(repository.findEnabledPoints()).thenThrow(new IllegalStateException("db down"))
                .thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null)));

        assertThat(provider.points()).isEmpty();
        advanceSeconds(60);

        assertThat(provider.points()).containsOnlyKeys(1L);
    }

    @Test
    @DisplayName("a failure that persists WARNs once per minute, not once per serve")
    void persistentFailureWarnsOncePerMinute() {
        when(repository.findEnabledPoints()).thenThrow(new IllegalStateException("db down"));

        provider.points();
        provider.points();
        advanceSeconds(61);
        provider.points();
        provider.points();

        assertThat(warnings.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(2);
    }

    @Test
    @DisplayName("the production constructor runs on the system monotonic clock and serves a read")
    void productionConstructorWorks() {
        when(repository.findEnabledPoints()).thenReturn(List.of(new LocationPoint(1L, 55.0, -2.0, null)));

        assertThat(new LocationPointProvider(repository).points()).containsOnlyKeys(1L);
    }
}
