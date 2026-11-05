package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Branch tests for {@link SupermoonHotTopicStrategy}: no coastal locations (the UK centre is used), a day with no
 * sunset, and a supermoon further out than tomorrow (named by weekday).
 */
class SupermoonHotTopicStrategyBranchTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);
    private static final LocalDate SATURDAY = TODAY.plusDays(3);
    private static final double UK_CENTRE_LAT = 54.5;
    private static final double UK_CENTRE_LON = -2.5;

    private LunarPhaseService lunarPhaseService;
    private LocationRepository locationRepository;
    private LunarCalculator lunarCalculator;
    private MoonriseMoonsetCalculator moonriseMoonsetCalculator;
    private SolarService solarService;
    private SupermoonHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        lunarPhaseService = mock(LunarPhaseService.class);
        locationRepository = mock(LocationRepository.class);
        lunarCalculator = mock(LunarCalculator.class);
        moonriseMoonsetCalculator = mock(MoonriseMoonsetCalculator.class);
        solarService = mock(SolarService.class);
        strategy = new SupermoonHotTopicStrategy(lunarPhaseService, locationRepository, lunarCalculator,
                moonriseMoonsetCalculator, solarService);
    }

    private ZonedDateTime stubSupermoonOnSaturdayWithNoCoast() {
        when(lunarPhaseService.isFullMoon(SATURDAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(SATURDAY)).thenReturn(1.5);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of());
        ZonedDateTime moonrise = SATURDAY.atTime(21, 38).atZone(LONDON);
        when(moonriseMoonsetCalculator.calculate(SATURDAY, UK_CENTRE_LAT, UK_CENTRE_LON, LONDON))
                .thenReturn(new MoonriseMoonset(Optional.of(moonrise), Optional.empty()));
        LunarPosition moon = mock(LunarPosition.class);
        when(moon.illuminationPercent()).thenReturn(100.0);
        when(moon.distanceKm()).thenReturn(356_500.0);
        when(moon.azimuth()).thenReturn(112.0);
        when(lunarCalculator.calculate(moonrise, UK_CENTRE_LAT, UK_CENTRE_LON)).thenReturn(moon);
        return moonrise;
    }

    @Test
    @DisplayName("with no coastal locations the UK centre is used, a later day is named by weekday and a day "
            + "with no sunset reads 'after dark'")
    void detect_noCoast_noSunset_weekdayLabel() {
        stubSupermoonOnSaturdayWithNoCoast();

        List<HotTopic> topics = strategy.detect(TODAY, SATURDAY);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.date()).isEqualTo(SATURDAY);
        assertThat(topic.detail()).isEqualTo("Full moon at perigee — moonrise over the coast Saturday");
        assertThat(topic.regions()).isEmpty();
        HotTopicFact rises = topic.facts().stream().filter(f -> "rises".equals(f.key())).findFirst().orElseThrow();
        assertThat(rises.value()).isEqualTo("21:38, after dark");
        assertThat(rises.dir()).isEqualTo("ESE");
    }

    @Test
    @DisplayName("a supermoon tomorrow is labelled 'tomorrow'")
    void detect_tomorrowLabel() {
        LocalDate tomorrow = TODAY.plusDays(1);
        when(lunarPhaseService.isFullMoon(tomorrow)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(tomorrow)).thenReturn(1.5);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of());
        when(moonriseMoonsetCalculator.calculate(tomorrow, UK_CENTRE_LAT, UK_CENTRE_LON, LONDON))
                .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.empty()));

        List<HotTopic> topics = strategy.detect(TODAY, SATURDAY);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).endsWith("tomorrow");
        // No moonrise and no sunset: no ephemeris sample point, so no fact chips.
        assertThat(topics.get(0).facts()).isNullOrEmpty();
    }
}
