package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SolarEventFreshness}.
 */
@ExtendWith(MockitoExtension.class)
class SolarEventFreshnessTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    /** Fixed "now": 2026-06-17 05:00 UTC. */
    private static final Clock CLOCK =
            Clock.fixed(DATE.atTime(5, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock
    private SolarService solarService;

    private SolarEventFreshness freshness;

    @BeforeEach
    void setUp() {
        freshness = new SolarEventFreshness(solarService, CLOCK);
    }

    @Test
    @DisplayName("null event time is treated as ahead")
    void isAhead_nullTime_true() {
        assertThat(freshness.isAhead((LocalDateTime) null)).isTrue();
    }

    @Test
    @DisplayName("future time ahead, past time not ahead")
    void isAhead_time() {
        assertThat(freshness.isAhead(DATE.atTime(6, 0))).isTrue();
        assertThat(freshness.isAhead(DATE.atTime(4, 0))).isFalse();
    }

    @Test
    @DisplayName("sunrise before now is not ahead; sunset after now is ahead")
    void isAhead_byEventType() {
        LocationEntity loc = LocationEntity.builder().name("Kielder").lat(55).lon(-2).build();
        when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(DATE.atTime(4, 42));
        when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenReturn(DATE.atTime(21, 30));

        assertThat(freshness.isAhead(loc, DATE, TargetType.SUNRISE)).isFalse();
        assertThat(freshness.isAhead(loc, DATE, TargetType.SUNSET)).isTrue();
    }

    @Test
    @DisplayName("null location cannot be placed and is treated as ahead")
    void isAhead_nullLocation_true() {
        assertThat(freshness.isAhead(null, DATE, TargetType.SUNRISE)).isTrue();
        assertThat(freshness.eventTime(null, DATE, TargetType.SUNRISE)).isNull();
    }
}
