package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.TideDerivation;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CoastalTideFactsBuilder} — the anomaly-first tide fact line for the KING_TIDE
 * and SPRING_TIDE pills (high water beside its spring baseline; range beside its average), with the
 * shared sea-state chip appended.
 */
@ExtendWith(MockitoExtension.class)
class CoastalTideFactsBuilderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime SUNRISE_TIME = LocalDateTime.of(2026, 6, 17, 5, 14);

    @Mock
    private TideFactDeriver tideFactDeriver;

    @Mock
    private MarineWaveRepository marineWaveRepository;

    private CoastalTideFactsBuilder builder() {
        return new CoastalTideFactsBuilder(tideFactDeriver, marineWaveRepository);
    }

    private static LocationEntity bamburgh() {
        LocationEntity loc = new LocationEntity();
        loc.setId(1L);
        loc.setName("Bamburgh");
        loc.setLat(55.6);
        loc.setLon(-1.7);
        loc.setTideType(Set.of(TideType.HIGH));
        return loc;
    }

    private static BriefingDay dayWith(BriefingSlot.TideInfo tide) {
        BriefingSlot slot = new BriefingSlot("Bamburgh", SUNRISE_TIME, Verdict.GO, null, tide,
                List.of(), null);
        BriefingEventSummary event = new BriefingEventSummary(TargetType.SUNRISE, List.of(),
                List.of(slot));
        return new BriefingDay(DATE, List.of(event));
    }

    private void stubDerive(TideDerivation derivation) {
        when(tideFactDeriver.derive(eq(1L), eq(SUNRISE_TIME), eq(Set.of(TideType.HIGH)),
                eq(55.6), eq(-1.7), eq(TargetType.SUNRISE))).thenReturn(Optional.of(derivation));
    }

    private void stubWaves(double hs) {
        MarineWaveEntity wave = new MarineWaveEntity();
        wave.setSignificantWaveHeightMetres(hs);
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(1L), eq(DATE), eq(TargetType.SUNRISE))).thenReturn(Optional.of(wave));
    }

    private static HotTopicFact factWithKey(List<HotTopicFact> facts, String key) {
        return facts.stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("king tide: high water shown beside its '+X over spring' anomaly, plus sea-state")
    void buildsKingFacts() {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, true, true,
                LunarTideType.KING_TIDE, "Full Moon", true);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 6, 17, 6, 42), new BigDecimal("5.8"),
                LocalDateTime.of(2026, 6, 17, 0, 30), new BigDecimal("1.0"),
                true, true, null, null, LunarTideType.KING_TIDE, "Full Moon", true, true, true,
                new BigDecimal("5.1"), new BigDecimal("3.8")));
        stubWaves(4.2);

        CoastalTideFactsBuilder.CoastalScience science = builder().buildKing(dayWith(tide), List.of(bamburgh()));

        assertThat(science).isNotNull();
        HotTopicFact hw = factWithKey(science.facts(), "high water");
        assertThat(hw.value()).isEqualTo("5.8 m");
        assertThat(hw.emphasis()).isTrue();
        assertThat(science.facts()).anyMatch(f -> "+0.7 m over spring".equals(f.value()));
        assertThat(factWithKey(science.facts(), "HW").value()).isEqualTo("06:42");
        assertThat(factWithKey(science.facts(), "seas").value()).isEqualTo("4.2 m · very rough");
        assertThat(science.note()).isEqualTo("causeways & foreshore submerged — shoot reflections");
    }

    @Test
    @DisplayName("king tide: the '+over spring' chip is omitted when the delta is negligible")
    void omitsNegligibleSpringDelta() {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, true, true,
                LunarTideType.KING_TIDE, "Full Moon", true);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 6, 17, 6, 42), new BigDecimal("5.12"),
                LocalDateTime.of(2026, 6, 17, 0, 30), new BigDecimal("1.0"),
                true, true, null, null, LunarTideType.KING_TIDE, "Full Moon", true, true, true,
                new BigDecimal("5.10"), new BigDecimal("3.8")));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildKing(dayWith(tide), List.of(bamburgh()));

        assertThat(science.facts()).noneMatch(f -> f.value() != null && f.value().contains("over spring"));
    }

    @Test
    @DisplayName("spring tide: tidal range shown beside its '+X over average' anomaly")
    void buildsSpringFacts() {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, false, true,
                LunarTideType.SPRING_TIDE, "New Moon", false);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 6, 17, 18, 20), new BigDecimal("5.5"),
                LocalDateTime.of(2026, 6, 17, 12, 10), new BigDecimal("0.6"),
                true, true, null, null, LunarTideType.SPRING_TIDE, "New Moon", false, false, true,
                new BigDecimal("5.1"), new BigDecimal("3.8")));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildSpring(dayWith(tide), List.of(bamburgh()));

        assertThat(science).isNotNull();
        HotTopicFact range = factWithKey(science.facts(), "range");
        assertThat(range.value()).isEqualTo("4.9 m");
        assertThat(range.emphasis()).isTrue();
        assertThat(science.facts()).anyMatch(f -> "+1.1 m over average".equals(f.value()));
        assertThat(factWithKey(science.facts(), "LW").value()).isEqualTo("12:10");
        assertThat(factWithKey(science.facts(), "HW").value()).isEqualTo("18:20");
        assertThat(science.note()).isEqualTo("low water bares the foreground");
    }

    @Test
    @DisplayName("returns null when no coastal slot qualifies for the day")
    void nullWhenNoQualifyingSlot() {
        assertThat(builder().buildKing(dayWith(BriefingSlot.TideInfo.NONE), List.of(bamburgh()))).isNull();
    }

    @Test
    @DisplayName("spring tide: a slot missing its low-tide height is skipped (no fabricated range)")
    void springSkipsSlotWithNullLowTide() {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, false, true,
                LunarTideType.SPRING_TIDE, "New Moon", false);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 6, 17, 18, 20), new BigDecimal("5.8"),
                null, null,
                true, true, null, null, LunarTideType.SPRING_TIDE, "New Moon", false, false, true,
                new BigDecimal("5.1"), new BigDecimal("3.8")));

        assertThat(builder().buildSpring(dayWith(tide), List.of(bamburgh()))).isNull();
    }
}
