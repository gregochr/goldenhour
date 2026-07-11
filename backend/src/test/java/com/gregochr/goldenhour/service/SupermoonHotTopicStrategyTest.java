package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.LunarPosition;
import com.gregochr.solarutils.MoonriseMoonset;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SupermoonHotTopicStrategy} — the fire gate (full moon within the perigee
 * window) and the enriched fact line (mean-distance-based "larger than an average full moon" figure
 * plus moonrise time, bearing and relation to sunset).
 */
@ExtendWith(MockitoExtension.class)
class SupermoonHotTopicStrategyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO_DATE = TODAY.plusDays(3);
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final double LAT = 54.5;
    private static final double LON = -0.6;

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LunarCalculator lunarCalculator;

    @Mock
    private MoonriseMoonsetCalculator moonriseMoonsetCalculator;

    @Mock
    private SolarService solarService;

    private SupermoonHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SupermoonHotTopicStrategy(lunarPhaseService, locationRepository,
                lunarCalculator, moonriseMoonsetCalculator, solarService);
    }

    /** Stubs a perigee full moon rising at 21:38 London (38 min after a 20:00 UTC sunset), ESE. */
    private void stubLunar(LocalDate date) {
        var moonrise = date.atTime(21, 38).atZone(LONDON);
        when(moonriseMoonsetCalculator.calculate(date, LAT, LON, LONDON))
                .thenReturn(new MoonriseMoonset(Optional.of(moonrise), Optional.empty()));
        when(solarService.sunsetUtc(LAT, LON, date)).thenReturn(date.atTime(20, 0));
        LunarPosition moon = mock(LunarPosition.class);
        when(moon.illuminationPercent()).thenReturn(100.0);
        when(moon.distanceKm()).thenReturn(356500.0);
        when(moon.azimuth()).thenReturn(112.0);
        when(lunarCalculator.calculate(moonrise, LAT, LON)).thenReturn(moon);
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    /** Stubs the fire gate: a full moon two days from perigee over a coastal region. */
    private void stubFireGate() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(2.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());
    }

    /** A perigee full moon whose bearing (azimuth) is read for the "rises" chip. */
    private LunarPosition perigeeMoonWithAzimuth() {
        LunarPosition moon = mock(LunarPosition.class);
        when(moon.illuminationPercent()).thenReturn(100.0);
        when(moon.distanceKm()).thenReturn(356500.0);
        when(moon.azimuth()).thenReturn(112.0);
        return moon;
    }

    /** A perigee full moon whose bearing is never read (no moonrise, so no "rises" chip). */
    private LunarPosition perigeeMoonNoAzimuth() {
        LunarPosition moon = mock(LunarPosition.class);
        when(moon.illuminationPercent()).thenReturn(100.0);
        when(moon.distanceKm()).thenReturn(356500.0);
        return moon;
    }

    /** Stubs a moonrise at the given London wall-clock time (sunset fixed at 20:00 UTC). */
    private void stubMoonriseAt(int hour, int minute) {
        var moonrise = TODAY.atTime(hour, minute).atZone(LONDON);
        when(moonriseMoonsetCalculator.calculate(TODAY, LAT, LON, LONDON))
                .thenReturn(new MoonriseMoonset(Optional.of(moonrise), Optional.empty()));
        when(solarService.sunsetUtc(LAT, LON, TODAY)).thenReturn(TODAY.atTime(20, 0));
        // Build the moon mock fully before stubbing lunarCalculator — nesting when() inside
        // .thenReturn() would trip Mockito's UnfinishedStubbing guard.
        LunarPosition moon = perigeeMoonWithAzimuth();
        when(lunarCalculator.calculate(moonrise, LAT, LON)).thenReturn(moon);
    }

    @Test
    @DisplayName("moonrise before sunset reads 'before sunset'")
    void detect_moonriseBeforeSunset_readsBeforeSunset() {
        stubFireGate();
        stubMoonriseAt(20, 30); // 19:30 UTC — 30 min before the 20:00 UTC sunset

        HotTopic topic = strategy.detect(TODAY, TODAY).get(0);

        assertThat(factWithKey(topic, "rises").value()).isEqualTo("20:30, before sunset");
    }

    @Test
    @DisplayName("moonrise more than 90 min after sunset reads 'after dark'")
    void detect_moonriseWellAfterSunset_readsAfterDark() {
        stubFireGate();
        stubMoonriseAt(23, 0); // 22:00 UTC — 120 min after the 20:00 UTC sunset

        HotTopic topic = strategy.detect(TODAY, TODAY).get(0);

        assertThat(factWithKey(topic, "rises").value()).isEqualTo("23:00, after dark");
    }

    @Test
    @DisplayName("moonrise exactly 90 min after sunset still reads 'just after sunset' (boundary)")
    void detect_moonrise90MinAfterSunset_justAfterSunset() {
        stubFireGate();
        stubMoonriseAt(22, 30); // 21:30 UTC — exactly 90 min after the 20:00 UTC sunset

        HotTopic topic = strategy.detect(TODAY, TODAY).get(0);

        assertThat(factWithKey(topic, "rises").value()).isEqualTo("22:30, just after sunset");
    }

    @Test
    @DisplayName("a moon that does not rise still emits the perigee chip but no 'rises' chip")
    void detect_noMoonrise_perigeeChipOnly() {
        stubFireGate();
        when(moonriseMoonsetCalculator.calculate(TODAY, LAT, LON, LONDON))
                .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.empty()));
        when(solarService.sunsetUtc(LAT, LON, TODAY)).thenReturn(TODAY.atTime(20, 0));
        LunarPosition moon = perigeeMoonNoAzimuth();
        when(lunarCalculator.calculate(TODAY.atTime(20, 0).atZone(ZoneOffset.UTC), LAT, LON))
                .thenReturn(moon);

        HotTopic topic = strategy.detect(TODAY, TODAY).get(0);

        assertThat(factWithKey(topic, "perigee").value()).isEqualTo("+8% larger · 100% lit");
        assertThat(topic.facts()).noneMatch(f -> "rises".equals(f.key()));
    }

    @Test
    @DisplayName("no moonrise and no sunset degrades to a topic carrying no facts")
    void detect_noMoonriseNoSunset_noFacts() {
        stubFireGate();
        when(moonriseMoonsetCalculator.calculate(TODAY, LAT, LON, LONDON))
                .thenReturn(new MoonriseMoonset(Optional.empty(), Optional.empty()));
        when(solarService.sunsetUtc(LAT, LON, TODAY)).thenReturn(null);

        HotTopic topic = strategy.detect(TODAY, TODAY).get(0);

        assertThat(topic.type()).isEqualTo("SUPERMOON");
        assertThat(topic.facts()).isNullOrEmpty();
        assertThat(topic.note()).isNull();
    }

    @Test
    @DisplayName("full moon within 3 days of perigee fires with priority 5, coastal regions + facts")
    void detect_fullMoonNearPerigee_fires() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(2.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());
        stubLunar(TODAY);

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("SUPERMOON");
        assertThat(topic.priority()).isEqualTo(5);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.detail()).contains("today");
        assertThat(topic.regions()).containsExactly("The North Yorkshire Coast");

        // 384400 / 356500 - 1 ≈ +8%; 100% illuminated.
        assertThat(factWithKey(topic, "perigee").value()).isEqualTo("+8% larger · 100% lit");
        HotTopicFact rises = factWithKey(topic, "rises");
        assertThat(rises.value()).isEqualTo("21:38, just after sunset");
        assertThat(rises.dir()).isEqualTo("ESE");
        assertThat(topic.note()).isEqualTo("catch it low behind a landmark");
    }

    @Test
    @DisplayName("exactly 3 days from perigee still fires (boundary inclusive)")
    void detect_exactlyThreeDaysFromPerigee_fires() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(3.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());
        stubLunar(TODAY);

        assertThat(strategy.detect(TODAY, TODAY)).hasSize(1);
    }

    @Test
    @DisplayName("just beyond 3 days from perigee does not fire (boundary)")
    void detect_justBeyondPerigeeWindow_doesNotFire() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(3.01);

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("full moon not at perigee does not fire")
    void detect_fullMoonFarFromPerigee_doesNotFire() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(TODAY)).thenReturn(6.0);

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("not a full moon does not fire even at perigee")
    void detect_notFullMoon_doesNotFire() {
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(false);

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("emits on the earliest qualifying day within the 4-day window")
    void detect_qualifyingDayMidWindow_datesToThatDay() {
        LocalDate tomorrow = TODAY.plusDays(1);
        when(lunarPhaseService.isFullMoon(TODAY)).thenReturn(false);
        when(lunarPhaseService.isFullMoon(tomorrow)).thenReturn(true);
        when(lunarPhaseService.daysFromNearestPerigee(tomorrow)).thenReturn(1.0);
        when(locationRepository.findCoastalLocations()).thenReturn(coastalLocations());
        stubLunar(tomorrow);

        List<HotTopic> topics = strategy.detect(TODAY, TO_DATE);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(tomorrow);
        assertThat(topics.get(0).detail()).contains("tomorrow");
    }

    private List<LocationEntity> coastalLocations() {
        RegionEntity region = new RegionEntity();
        region.setName("The North Yorkshire Coast");
        return List.of(LocationEntity.builder()
                .name("Saltwick Bay").lat(LAT).lon(LON).region(region).build());
    }
}
