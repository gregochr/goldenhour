package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DisplayVerdict;
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
                List.of());
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

    // ── Selection edge cases ────────────────────────────────────────────────

    private static final BriefingSlot.TideInfo KING_TIDE_INFO = new BriefingSlot.TideInfo("HIGH", true, null,
            null, true, true, LunarTideType.KING_TIDE, "Full Moon", true);

    private static LocationEntity coastal(Long id, String name, double lat, double lon) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName(name);
        loc.setLat(lat);
        loc.setLon(lon);
        loc.setTideType(Set.of(TideType.HIGH));
        return loc;
    }

    private static TideDerivation derivation(LocalDateTime hwTime, String hwHeight, LocalDateTime lwTime,
            String lwHeight, String threshold, String avgRange) {
        return new TideDerivation(TideState.HIGH, hwTime, hwHeight == null ? null : new BigDecimal(hwHeight),
                lwTime, lwHeight == null ? null : new BigDecimal(lwHeight),
                true, true, null, null, LunarTideType.KING_TIDE, "Full Moon", true, true, true,
                threshold == null ? null : new BigDecimal(threshold),
                avgRange == null ? null : new BigDecimal(avgRange));
    }

    private static BriefingSlot slotAt(String name, LocalDateTime solarEvent, BriefingSlot.TideInfo tide) {
        return new BriefingSlot(name, solarEvent, Verdict.GO, null, tide, List.of());
    }

    private static BriefingRegion regionOf(BriefingSlot... slots) {
        return new BriefingRegion("Northumberland", Verdict.GO, "summary", List.of(), List.of(slots),
                null, null, null, null, null, null, DisplayVerdict.WORTH_IT, 0);
    }

    private void stubDeriveFor(LocationEntity loc, LocalDateTime solarEvent, TideDerivation derivation) {
        when(tideFactDeriver.derive(eq(loc.getId()), eq(solarEvent), eq(Set.of(TideType.HIGH)),
                eq(loc.getLat()), eq(loc.getLon()), eq(TargetType.SUNRISE)))
                .thenReturn(Optional.of(derivation));
    }

    @Test
    @DisplayName("a tidal slot with no solar event time is skipped without asking the deriver")
    void skipsSlotWithoutSolarEventTime() {
        BriefingDay day = new BriefingDay(DATE, List.of(new BriefingEventSummary(TargetType.SUNRISE, List.of(),
                List.of(slotAt("Bamburgh", null, KING_TIDE_INFO)))));

        assertThat(builder().buildKing(day, List.of(bamburgh()))).isNull();
        verifyNoInteractions(tideFactDeriver);
    }

    @Test
    @DisplayName("a tidal slot at a location outside the coastal roster is skipped without asking the deriver")
    void skipsSlotAtUnknownLocation() {
        BriefingDay day = new BriefingDay(DATE, List.of(new BriefingEventSummary(TargetType.SUNRISE, List.of(),
                List.of(slotAt("Kielder", SUNRISE_TIME, KING_TIDE_INFO)))));

        assertThat(builder().buildKing(day, List.of(bamburgh()))).isNull();
        verifyNoInteractions(tideFactDeriver);
    }

    @Test
    @DisplayName("a slot the deriver cannot derive, or whose next high tide has no height, gives no facts")
    void skipsUnderivableSlots() {
        when(tideFactDeriver.derive(eq(1L), eq(SUNRISE_TIME), eq(Set.of(TideType.HIGH)),
                eq(55.6), eq(-1.7), eq(TargetType.SUNRISE))).thenReturn(Optional.empty());
        assertThat(builder().buildKing(dayWith(KING_TIDE_INFO), List.of(bamburgh()))).isNull();

        stubDerive(derivation(LocalDateTime.of(2026, 6, 17, 6, 42), null, null, null, null, null));
        assertThat(builder().buildKing(dayWith(KING_TIDE_INFO), List.of(bamburgh()))).isNull();
    }

    @Test
    @DisplayName("the biggest high water wins across region and unregioned slots; the winner's sea state is used")
    void picksTheBiggestSlotAcrossRegionsAndUnregioned() {
        LocationEntity bamburgh = coastal(1L, "Bamburgh", 55.6, -1.7);
        LocationEntity seahouses = coastal(2L, "Seahouses", 55.58, -1.65);
        LocationEntity craster = coastal(3L, "Craster", 55.47, -1.59);
        LocalDateTime seahousesTime = SUNRISE_TIME.plusMinutes(2);
        LocalDateTime crasterTime = SUNRISE_TIME.plusMinutes(4);
        stubDeriveFor(bamburgh, SUNRISE_TIME, derivation(LocalDateTime.of(2026, 6, 17, 6, 42), "5.4", null,
                null, "5.1", null));
        stubDeriveFor(seahouses, seahousesTime, derivation(LocalDateTime.of(2026, 6, 17, 6, 44), "5.9", null,
                null, "5.1", null));
        stubDeriveFor(craster, crasterTime, derivation(LocalDateTime.of(2026, 6, 17, 6, 46), "5.2", null,
                null, "5.1", null));
        MarineWaveEntity wave = new MarineWaveEntity();
        wave.setSignificantWaveHeightMetres(0.4);
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(2L), eq(DATE), eq(TargetType.SUNRISE))).thenReturn(Optional.of(wave));
        BriefingEventSummary event = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(regionOf(slotAt("Bamburgh", SUNRISE_TIME, KING_TIDE_INFO))),
                List.of(slotAt("Seahouses", seahousesTime, KING_TIDE_INFO),
                        slotAt("Craster", crasterTime, KING_TIDE_INFO)));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildKing(
                new BriefingDay(DATE, List.of(event)), List.of(bamburgh, seahouses, craster));

        assertThat(factWithKey(science.facts(), "high water").value()).isEqualTo("5.9 m");
        assertThat(factWithKey(science.facts(), "HW").value()).isEqualTo("06:44");
        assertThat(factWithKey(science.facts(), "seas").value()).isEqualTo("0.4 m · smooth");
    }

    @Test
    @DisplayName("when two coastal locations share a name the first in the roster is the one used")
    void duplicateLocationNames_firstWins() {
        LocationEntity first = coastal(1L, "Bamburgh", 55.6, -1.7);
        LocationEntity second = coastal(9L, "Bamburgh", 10.0, 10.0);
        stubDeriveFor(first, SUNRISE_TIME, derivation(LocalDateTime.of(2026, 6, 17, 6, 42), "5.8", null,
                null, "5.1", null));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildKing(
                dayWith(KING_TIDE_INFO), List.of(first, second));

        assertThat(factWithKey(science.facts(), "high water").value()).isEqualTo("5.8 m");
    }

    @Test
    @DisplayName("king tide with no spring threshold and no high-water time shows the height alone")
    void kingWithoutThresholdOrTime_showsHeightOnly() {
        stubDerive(derivation(null, "5.8", null, null, null, null));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildKing(
                dayWith(KING_TIDE_INFO), List.of(bamburgh()));

        assertThat(science.facts()).extracting(HotTopicFact::key).containsExactly("high water");
    }

    @Test
    @DisplayName("a sea-state row with no wave height adds no sea-state chip")
    void seaStateWithoutWaveHeight_addsNoChip() {
        stubDerive(derivation(LocalDateTime.of(2026, 6, 17, 6, 42), "5.8", null, null, "5.1", null));
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(1L), eq(DATE), eq(TargetType.SUNRISE))).thenReturn(Optional.of(new MarineWaveEntity()));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildKing(
                dayWith(KING_TIDE_INFO), List.of(bamburgh()));

        assertThat(science.facts()).extracting(HotTopicFact::key).doesNotContain("seas");
    }

    @Test
    @DisplayName("spring tide with no average range reads 'big swing'")
    void springWithoutAverageRange_readsBigSwing() {
        stubDerive(derivation(LocalDateTime.of(2026, 6, 17, 18, 20), "5.5", LocalDateTime.of(2026, 6, 17, 12, 10),
                "0.6", "5.1", null));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildSpring(
                dayWith(KING_TIDE_INFO), List.of(bamburgh()));

        assertThat(science.facts()).anyMatch(f -> "big swing".equals(f.value()));
    }

    @Test
    @DisplayName("spring tide with a range only 0.04 m above its average is below the anomaly floor: 'big swing'")
    void springBarelyAboveAverage_readsBigSwing() {
        // range 4.9 against an average of 4.86: the 0.04 m anomaly is under the 0.05 m floor
        stubDerive(derivation(LocalDateTime.of(2026, 6, 17, 18, 20), "5.5", LocalDateTime.of(2026, 6, 17, 12, 10),
                "0.6", "5.1", "4.86"));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildSpring(
                dayWith(KING_TIDE_INFO), List.of(bamburgh()));

        assertThat(science.facts()).anyMatch(f -> "big swing".equals(f.value()));
    }

    @Test
    @DisplayName("spring tide with no low or high water times omits the LW and HW chips")
    void springWithoutTideTimes_omitsLwAndHw() {
        stubDerive(derivation(null, "5.5", null, "0.6", "5.1", "3.8"));

        CoastalTideFactsBuilder.CoastalScience science = builder().buildSpring(
                dayWith(KING_TIDE_INFO), List.of(bamburgh()));

        assertThat(science.facts()).extracting(HotTopicFact::key).doesNotContain("LW", "HW");
        assertThat(factWithKey(science.facts(), "range").value()).isEqualTo("4.9 m");
    }

    @Test
    @DisplayName("spring tide includes the sea-state chip when the slot has a wave height")
    void springIncludesSeaState() {
        stubDerive(derivation(LocalDateTime.of(2026, 6, 17, 18, 20), "5.5", LocalDateTime.of(2026, 6, 17, 12, 10),
                "0.6", "5.1", "3.8"));
        stubWaves(1.6);

        CoastalTideFactsBuilder.CoastalScience science = builder().buildSpring(
                dayWith(KING_TIDE_INFO), List.of(bamburgh()));

        assertThat(factWithKey(science.facts(), "seas").value()).isEqualTo("1.6 m · moderate");
    }
}
