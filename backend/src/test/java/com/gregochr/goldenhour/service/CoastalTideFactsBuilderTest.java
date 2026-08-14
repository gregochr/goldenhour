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
import static org.mockito.Mockito.verifyNoInteractions;
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
    @DisplayName("spring tide: spring-sized water with a regular moon still gets its fact chips")
    void buildsSpringFactsFromHeightAloneWhenTheMoonHasMovedOn() {
        // This selector must match SpringTideHotTopicStrategy#isSpringNotKing exactly. It does not
        // decide whether the card appears — it decides whether the card that IS appearing has any
        // figures under it — so a narrower rule here strands the pill rather than suppressing it.
        // It was narrowed to lunar-only alongside the strategy with no test edit at all, because
        // every fixture in this class sets lunarTideType and the height booleans together.
        //
        // heightAboveSpringThreshold=true with lunarTideType=REGULAR_TIDE is the August 2026 peak:
        // the water is at its biggest two days after the moon's alignment.
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, false, true,
                LunarTideType.REGULAR_TIDE, "Waning Crescent", false);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 8, 14, 4, 58), new BigDecimal("5.5"),
                LocalDateTime.of(2026, 8, 14, 11, 12), new BigDecimal("0.6"),
                true, true, null, null, LunarTideType.REGULAR_TIDE, "Waning Crescent", false,
                false, true, new BigDecimal("5.1"), new BigDecimal("3.8")));

        CoastalTideFactsBuilder.CoastalScience science =
                builder().buildSpring(dayWith(tide), List.of(bamburgh()));

        assertThat(science).isNotNull();
        assertThat(factWithKey(science.facts(), "range").value()).isEqualTo("4.9 m");
    }

    @Test
    @DisplayName("both builders select the same slots — the LABEL is the caller's, the slots are "
            + "the day's")
    void bothBuildersSelectFromTheSameTidalSet() {
        // ⚠️ REPLACES `springSelectorSkipsKingTideSlots`, which asserted that buildSpring refused a
        // king slot. That guard is deliberately gone, and this is a design change rather than a
        // test bent to fit the code — so it is recorded here rather than quietly edited.
        //
        // Whether a day is king or spring is a property of its RUN's syzygy, which a slot cannot
        // see: a king run is five or six days long while `lunarTideType == KING_TIDE` is true for
        // two or three of them. A builder re-deriving the label from slot data could therefore only
        // disagree with the card it sits under, and did — a king run's lagging days rendered with
        // no figures beneath them. The strategies decide the label; the builders find the biggest
        // tidal slot and format it.
        //
        // So the invariant worth pinning is that the two builders no longer partition anything: on
        // one tidal day, both find the same slot.
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null,
                false, true, LunarTideType.REGULAR_TIDE, "Waning Gibbous", false);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 6, 17, 18, 20), new BigDecimal("5.5"),
                LocalDateTime.of(2026, 6, 17, 12, 10), new BigDecimal("0.6"),
                true, true, null, null, LunarTideType.REGULAR_TIDE, "Waning Gibbous", false,
                false, true, new BigDecimal("5.1"), new BigDecimal("3.8")));

        assertThat(builder().buildSpring(dayWith(tide), List.of(bamburgh()))).isNotNull();
        assertThat(builder().buildKing(dayWith(tide), List.of(bamburgh()))).isNotNull();
    }

    @Test
    @DisplayName("neither builder selects a slot with no tide at all")
    void neitherBuilderSelectsAnUntidalSlot() {
        // ⚠️ The null alone does NOT pin this, which is why the interaction check is here.
        // `selectRepresentative` tests the matcher before it consults the deriver, so an untidal
        // slot is dropped without one — but a null also arrives when the deriver simply returns
        // empty, which is what an unstubbed mock does. Asserting only the null would therefore pass
        // with the tidal test DELETED: the deriver would be called, return empty, and the method
        // would return null by a different route.
        //
        // "The deriver was never asked" is the matcher's actual observable behaviour, and it is
        // false the moment the matcher stops rejecting anything.
        BriefingSlot.TideInfo noTide = new BriefingSlot.TideInfo("MID", false, null, null,
                false, false, LunarTideType.REGULAR_TIDE, "Waning Gibbous", false);

        assertThat(builder().buildSpring(dayWith(noTide), List.of(bamburgh()))).isNull();
        assertThat(builder().buildKing(dayWith(noTide), List.of(bamburgh()))).isNull();
        verifyNoInteractions(tideFactDeriver);
    }

    @Test
    @DisplayName("spring tide: syzygy with the water still under its threshold — the lunar arm "
            + "alone carries the fact line")
    void buildsSpringFactsFromTheLunarArmWhenTheWaterHasNotBuilt() {
        // The mirror of buildsSpringFactsFromHeightAloneWhenTheMoonHasMovedOn, and the row that was
        // missing: EVERY buildSpring fixture in this class set heightAboveSpringThreshold=true, so
        // the lunar arm of `(SPRING || height)` was as unobservable here as the height arm was in
        // SpringTideHotTopicStrategyTest. Deleting it left this class green.
        //
        // Physically this is a run's leading edge — the day of syzygy, before the age of the tide
        // has carried the water up to this port's own threshold. It is also the state
        // BriefingSlotBuilder's ±90-minute gate produces whenever the big water misses the light,
        // which makes it common rather than contrived.
        BriefingSlot.TideInfo syzygyBeforeTheWater = new BriefingSlot.TideInfo("HIGH", true, null,
                null, false, false, LunarTideType.SPRING_TIDE, "New Moon", false);
        stubDerive(new TideDerivation(TideState.HIGH,
                LocalDateTime.of(2026, 6, 17, 18, 20), new BigDecimal("4.4"),
                LocalDateTime.of(2026, 6, 17, 12, 10), new BigDecimal("0.9"),
                true, true, null, null, LunarTideType.SPRING_TIDE, "New Moon", false, false, false,
                new BigDecimal("5.1"), new BigDecimal("3.8")));

        CoastalTideFactsBuilder.CoastalScience science =
                builder().buildSpring(dayWith(syzygyBeforeTheWater), List.of(bamburgh()));

        assertThat(science).isNotNull();
        assertThat(factWithKey(science.facts(), "range").value()).isEqualTo("3.5 m");
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
