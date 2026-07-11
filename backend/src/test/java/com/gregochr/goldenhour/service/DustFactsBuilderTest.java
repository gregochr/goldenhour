package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DustFactsBuilder} — the AOD chip (with its honest thickness band), the
 * sunset→civil-dusk afterglow window (converted to Europe/London), and the graceful drops when a
 * figure is unavailable.
 */
@ExtendWith(MockitoExtension.class)
class DustFactsBuilderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final double LAT = 54.5;
    private static final double LON = -1.0;

    @Mock
    private SolarService solarService;

    private DustFactsBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DustFactsBuilder(solarService);
    }

    private static SurvivorSignals dustRow(String aod) {
        LocationEntity location = LocationEntity.builder().name("Coast").lat(LAT).lon(LON).build();
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                aod == null ? null : new BigDecimal(aod),
                null, null, null, null, null, null, null, null, null, null);
        return new SurvivorSignals(location, DATE, TargetType.SUNSET,
                SurvivorSignals.Scores.EMPTY, readings);
    }

    private static HotTopic baseTopic() {
        return new HotTopic("DUST", "Saharan dust", "detail", DATE, 3, null, List.of(), "desc", null);
    }

    /** 20:44 UTC → 21:44 London (BST); 21:12 UTC → 22:12 London. */
    private void stubAfterglow() {
        when(solarService.sunsetUtc(LAT, LON, DATE)).thenReturn(DATE.atTime(20, 44));
        when(solarService.civilDuskUtc(LAT, LON, DATE)).thenReturn(DATE.atTime(21, 12));
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("AOD chip (with thickness band) + afterglow window + note")
    void buildsAodAndAfterglow() {
        stubAfterglow();

        HotTopic result = builder.attach(baseTopic(), List.of(dustRow("0.42")));

        assertThat(factWithKey(result, "AOD").value()).isEqualTo("0.42 · thick plume");
        assertThat(factWithKey(result, "afterglow").value()).isEqualTo("21:44–22:12");
        assertThat(result.note()).isEqualTo("look W, high — colour peaks after the sun's gone");
    }

    @Test
    @DisplayName("AOD ≥ 0.6 reads as a dense plume")
    void densePlumeBand() {
        stubAfterglow();
        assertThat(factWithKey(builder.attach(baseTopic(), List.of(dustRow("0.61"))), "AOD").value())
                .isEqualTo("0.61 · dense plume");
    }

    @Test
    @DisplayName("AOD in 0.3–0.4 reads as a light veil")
    void lightVeilBand() {
        stubAfterglow();
        assertThat(factWithKey(builder.attach(baseTopic(), List.of(dustRow("0.35"))), "AOD").value())
                .isEqualTo("0.35 · light veil");
    }

    @Test
    @DisplayName("AOD exactly 0.60 is dense (band boundary, inclusive)")
    void densePlumeBoundary() {
        stubAfterglow();
        assertThat(factWithKey(builder.attach(baseTopic(), List.of(dustRow("0.60"))), "AOD").value())
                .isEqualTo("0.60 · dense plume");
    }

    @Test
    @DisplayName("AOD 0.59 (just below dense) stays thick (band boundary)")
    void thickPlumeUpperBoundary() {
        stubAfterglow();
        assertThat(factWithKey(builder.attach(baseTopic(), List.of(dustRow("0.59"))), "AOD").value())
                .isEqualTo("0.59 · thick plume");
    }

    @Test
    @DisplayName("AOD exactly 0.40 is thick (band boundary, inclusive)")
    void thickPlumeLowerBoundary() {
        stubAfterglow();
        assertThat(factWithKey(builder.attach(baseTopic(), List.of(dustRow("0.40"))), "AOD").value())
                .isEqualTo("0.40 · thick plume");
    }

    @Test
    @DisplayName("AOD 0.39 (just below thick) reads as a light veil (band boundary)")
    void lightVeilUpperBoundary() {
        stubAfterglow();
        assertThat(factWithKey(builder.attach(baseTopic(), List.of(dustRow("0.39"))), "AOD").value())
                .isEqualTo("0.39 · light veil");
    }

    @Test
    @DisplayName("null AOD drops the AOD chip but keeps the afterglow window")
    void nullAodDropsChipKeepsAfterglow() {
        stubAfterglow();

        HotTopic result = builder.attach(baseTopic(), List.of(dustRow(null)));

        assertThat(result.facts()).noneMatch(f -> "AOD".equals(f.key()));
        assertThat(factWithKey(result, "afterglow").value()).isEqualTo("21:44–22:12");
    }

    @Test
    @DisplayName("no solar times drops the afterglow window but keeps the AOD chip")
    void nullSolarDropsAfterglow() {
        when(solarService.sunsetUtc(LAT, LON, DATE)).thenReturn(null);

        HotTopic result = builder.attach(baseTopic(), List.of(dustRow("0.42")));

        assertThat(result.facts()).noneMatch(f -> "afterglow".equals(f.key()));
        assertThat(factWithKey(result, "AOD").value()).isEqualTo("0.42 · thick plume");
    }

    @Test
    @DisplayName("the thickest plume in the day is chosen as the representative")
    void picksThickestPlume() {
        stubAfterglow();

        HotTopic result = builder.attach(baseTopic(), List.of(dustRow("0.35"), dustRow("0.55")));

        assertThat(factWithKey(result, "AOD").value()).isEqualTo("0.55 · thick plume");
    }
}
