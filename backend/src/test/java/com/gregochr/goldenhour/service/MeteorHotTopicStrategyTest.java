package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.MeteorClarity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeteorHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class MeteorHotTopicStrategyTest {

    private static final LocalDate PERSEIDS_PEAK = LocalDate.of(2026, 8, 12);

    @Mock
    private MeteorMoonGate moonGate;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private MeteorClarityService meteorClarityService;

    private MeteorHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MeteorHotTopicStrategy(
                moonGate, locationRepository, meteorClarityService, MeteorPeakCalendars.empty());
    }

    private static MeteorHotTopicStrategy.Shower shower(String name) {
        return MeteorHotTopicStrategy.SHOWERS.stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /** Stubs the gate: a moon of the given illumination that is up but not washing the shower out. */
    private void stubMoon(String name, LocalDate night, int pct) {
        when(moonGate.assess(shower(name), night)).thenReturn(
                new MeteorMoonGate.MoonOverShower(pct, false, false, Optional.empty(), Optional.empty()));
    }

    /** Stubs the gate with an arbitrary assessment. */
    private void stubMoon(String name, LocalDate night, MeteorMoonGate.MoonOverShower moon) {
        when(moonGate.assess(shower(name), night)).thenReturn(moon);
    }

    /** Stubs the clarity cache as empty — the topic fires, but carries no clear-sky fact. */
    private void stubNoClarity() {
        when(meteorClarityService.getCached()).thenReturn(MeteorClarity.EMPTY);
    }

    /** Stubs the clarity cache with a clear/total count for one peak night. */
    private void stubClarity(LocalDate peak, int clear, int total) {
        when(meteorClarityService.getCached()).thenReturn(
                new MeteorClarity(Map.of(peak, new MeteorClarity.NightClarity(clear, total))));
    }

    @Test
    @DisplayName("shower peak in window with dark moon fires with priority 7 and shower name")
    void detect_peakInWindowDarkMoon_fires() {
        stubMoon("Perseids", PERSEIDS_PEAK, 20);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("METEOR");
        assertThat(topic.priority()).isEqualTo(7);
        assertThat(topic.date()).isEqualTo(PERSEIDS_PEAK);
        assertThat(topic.detail()).contains("Perseids");

        // Perseids catalogue constants: ZHR ~100, radiant NE, best 01:00–04:00; moon 20% is dark.
        assertThat(factWithKey(topic, "ZHR").value()).isEqualTo("~100 at peak");
        HotTopicFact radiant = factWithKey(topic, "radiant");
        assertThat(radiant.value()).isEqualTo("best 01:00–04:00");
        assertThat(radiant.dir()).isEqualTo("NE");
        HotTopicFact moon = factWithKey(topic, "moon");
        assertThat(moon.value()).isEqualTo("20% · dark enough");
        assertThat(moon.optional()).isTrue();
    }

    // ── Leonids, minor showers and the table itself ──────────────────────────────

    @Test
    @DisplayName("the Leonids fire in a 14-17 Nov window under a dark moon")
    void detect_leonidsInWindow_fires() {
        LocalDate leonids = LocalDate.of(2026, 11, 17);
        stubMoon("Leonids", leonids, 30);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 11, 14), leonids);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).contains("Leonids");
        assertThat(topics.get(0).date()).isEqualTo(leonids);
        assertThat(factWithKey(topics.get(0), "radiant").dir()).isEqualTo("SE");
    }

    @Test
    @DisplayName("a minor shower never fires, even under a dark moon")
    void detect_minorShower_neverFires() {
        // N. Taurids 12 Nov and Eta Aquariids 6 May: no illumination stub is needed (and strict
        // stubs would flag one) because the minor showers are skipped before the moon is read.
        assertThat(strategy.detect(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 13))).isEmpty();
        assertThat(strategy.detect(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 8))).isEmpty();
    }

    @Test
    @DisplayName("the table holds twelve showers in calendar order, each with a distinct peak day")
    void showerTable_distinctPeaksInCalendarOrder() {
        List<MeteorHotTopicStrategy.Shower> showers = MeteorHotTopicStrategy.SHOWERS;

        assertThat(showers).hasSize(12);
        assertThat(showers).extracting(MeteorHotTopicStrategy.Shower::peak).doesNotHaveDuplicates();
        assertThat(showers).extracting(MeteorHotTopicStrategy.Shower::peak).isSorted();
        assertThat(showers).filteredOn(MeteorHotTopicStrategy.Shower::major)
                .extracting(MeteorHotTopicStrategy.Shower::name)
                .containsExactly("Quadrantids", "Lyrids", "Perseids", "Orionids", "Leonids", "Geminids");
        assertThat(showers).filteredOn(shower -> shower.ukRate() != null)
                .extracting(MeteorHotTopicStrategy.Shower::name)
                .containsExactly("Eta Aquariids", "S. Delta Aquariids");
    }

    // ── Clear-sky fact (overhead dark-sky clarity for the peak night) ────────────

    @Test
    @DisplayName("clear-sky fact shows clear-of-total dark-sky locations for the peak night")
    void detect_clearSkyFact_present() {
        stubMoon("Perseids", PERSEIDS_PEAK, 20);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubClarity(PERSEIDS_PEAK, 12, 30);

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.facts())
                .anyMatch(f -> "clear at 12 of 30 dark-sky locations".equals(f.value()));
    }

    @Test
    @DisplayName("no clear-sky fact when the clarity scan did not cover the peak night")
    void detect_noClarityForPeak_omitsClearFact() {
        stubMoon("Perseids", PERSEIDS_PEAK, 20);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.facts())
                .noneMatch(f -> f.value() != null && f.value().startsWith("clear at"));
    }

    @Test
    @DisplayName("clear-sky fact omitted when the scanned total is zero (no fabricated fraction)")
    void detect_zeroTotal_omitsClearFact() {
        stubMoon("Perseids", PERSEIDS_PEAK, 20);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubClarity(PERSEIDS_PEAK, 0, 0);

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.facts())
                .noneMatch(f -> f.value() != null && f.value().startsWith("clear at"));
    }

    // ── Moon band + fire gate ───────────────────────────────────────────────────

    @Test
    @DisplayName("a half-lit peak still under the gate reads 'some moonlight' in both headline and chip")
    void detect_someMoonlightPeak_headlineAndChipAgree() {
        stubMoon("Perseids", PERSEIDS_PEAK, 40);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        // The headline must not claim "dark moon" while the chip reports the sky is 40% lit.
        assertThat(topic.detail()).isEqualTo("Perseids peak — some moonlight, still worth a look");
        assertThat(factWithKey(topic, "moon").value()).isEqualTo("40% · some moonlight");
    }

    @Test
    @DisplayName("moon exactly at the 25% dark band boundary reads 'some moonlight'")
    void detect_moonAtDarkBandBoundary_someMoonlight() {
        stubMoon("Perseids", PERSEIDS_PEAK, 25);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.detail()).isEqualTo("Perseids peak — some moonlight, still worth a look");
        assertThat(factWithKey(topic, "moon").value()).isEqualTo("25% · some moonlight");
    }

    @Test
    @DisplayName("moon just below the 25% boundary reads 'dark moon' / 'dark enough'")
    void detect_moonJustBelowDarkBandBoundary_darkMoon() {
        stubMoon("Perseids", PERSEIDS_PEAK, 24);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(topic.detail()).isEqualTo("Perseids peak — dark moon, good viewing");
        assertThat(factWithKey(topic, "moon").value()).isEqualTo("24% · dark enough");
    }

    @Test
    @DisplayName("a washed-out major shower is dropped")
    void detect_washedOut_doesNotFire() {
        when(moonGate.assess(shower("Perseids"), PERSEIDS_PEAK)).thenReturn(
                new MeteorMoonGate.MoonOverShower(92, true, false, Optional.empty(), Optional.empty()));

        assertThat(strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13))).isEmpty();
    }

    @Test
    @DisplayName("the Leonids fire in a 14-17 Nov 2026 window with the 'down in the best hours' chip")
    void detect_leonidsMoonDownInBestHours_fires() {
        LocalDate leonids = LocalDate.of(2026, 11, 17);
        stubMoon("Leonids", leonids,
                new MeteorMoonGate.MoonOverShower(57, false, true, Optional.empty(), Optional.empty()));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 11, 14), leonids);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).detail()).isEqualTo("Leonids peak — moon down, good viewing");
        assertThat(factWithKey(topics.get(0), "moon").value()).isEqualTo("57% · down in the best hours");
    }

    @Test
    @DisplayName("a moon that sets in the best hours reads 'sets HH:mm' in the chip")
    void detect_moonSetsInBestHours_chipSaysWhen() {
        LocalDate orionids = LocalDate.of(2026, 10, 20);
        stubMoon("Orionids", orionids, new MeteorMoonGate.MoonOverShower(60, false, false,
                Optional.of(LocalTime.of(2, 56)), Optional.empty()));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 10, 19), orionids).get(0);

        assertThat(factWithKey(topic, "moon").value()).isEqualTo("60% · sets 02:56");
        assertThat(topic.detail()).isEqualTo("Orionids peak — some moonlight, still worth a look");
    }

    @Test
    @DisplayName("a moon that rises in the best hours reads 'rises HH:mm' in the chip")
    void detect_moonRisesInBestHours_chipSaysWhen() {
        LocalDate geminids = LocalDate.of(2026, 12, 14);
        stubMoon("Geminids", geminids, new MeteorMoonGate.MoonOverShower(35, false, false,
                Optional.empty(), Optional.of(LocalTime.of(0, 30))));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 12, 12), geminids).get(0);

        assertThat(factWithKey(topic, "moon").value()).isEqualTo("35% · rises 00:30");
    }

    @Test
    @DisplayName("a dark moon that is also down reads as dark, not as 'down in the best hours'")
    void detect_darkAndDown_readsDark() {
        stubMoon("Perseids", PERSEIDS_PEAK,
                new MeteorMoonGate.MoonOverShower(10, false, true, Optional.empty(), Optional.empty()));
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        HotTopic topic = strategy.detect(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13)).get(0);

        assertThat(factWithKey(topic, "moon").value()).isEqualTo("10% · dark enough");
    }

    @Test
    @DisplayName("no shower peak in window does not fire")
    void detect_noPeakInWindow_doesNotFire() {
        assertThat(strategy.detect(LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 20))).isEmpty();
    }

    @Test
    @DisplayName("window straddling the year boundary finds the next-year Quadrantids peak")
    void detect_windowStraddlesYearBoundary_findsPeak() {
        LocalDate quadrantids = LocalDate.of(2027, 1, 3);
        stubMoon("Quadrantids", quadrantids, 10);
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        stubNoClarity();

        List<HotTopic> topics = strategy.detect(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 3));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(quadrantids);
        assertThat(topics.get(0).detail()).contains("Quadrantids");
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    private List<LocationEntity> darkSkyLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return List.of(LocationEntity.builder()
                .name("Kielder").bortleClass(2).region(region).build());
    }
}
