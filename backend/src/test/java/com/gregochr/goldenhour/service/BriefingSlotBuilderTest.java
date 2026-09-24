package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingSlotBuilder}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingSlotBuilderTest {

    @Mock
    private SolarService solarService;
    @Mock
    private LocationService locationService;
    @Mock
    private TideService tideService;
    @Mock
    private LunarPhaseService lunarPhaseService;
    /**
     * The tide-fit curve's own extremes fetch. Left unstubbed in every test that does not exercise
     * the tide-fit fields: Mockito answers an empty list, which is the "no curve to draw" degrade
     * — {@code tideLevel}/{@code tideDirection}/{@code tideHeight}/{@code tideShortfall}/{@code
     * tideFitPhrase} all read null, exactly as they did before this dependency existed.
     */
    @Mock
    private TideExtremeRepository tideExtremeRepository;

    /**
     * Left unstubbed in every test that is not about the lunar-eclipse seam itself: Mockito
     * answers null, so {@code buildSlot} attaches no {@link BriefingSlot#eclipse()} — the same
     * "unwired dependency defaults to the no-op answer" shape {@code tideExtremeRepository} above
     * already relies on.
     */
    @Mock
    private EclipseSightAssembler eclipseSightAssembler;

    private BriefingSlotBuilder slotBuilder;

    @BeforeEach
    void setUp() {
        slotBuilder = new BriefingSlotBuilder(solarService, locationService,
                new TideFactDeriver(tideService, lunarPhaseService, solarService),
                new BriefingVerdictEvaluator(),
                new WoodlandVerdictEvaluator(), tideExtremeRepository, eclipseSightAssembler);
    }

    /** Wraps one tide curve as a dual-window result; the briefing path ignores the widened one. */
    private static TideService.DualWindowTideData dual(TideData td) {
        return new TideService.DualWindowTideData(td, td);
    }

    /**
     * Stub golden/blue window so tide augmentation can compute windowMinutes.
     * Call this in tests (or nested class {@code @BeforeEach}) that use coastal locations.
     */
    private void stubSolarWindow() {
        when(solarService.goldenBlueWindow(anyDouble(), anyDouble(), any(), anyBoolean()))
                .thenReturn(new SolarService.SolarWindow(
                        LocalDateTime.of(2026, 3, 25, 18, 0),
                        LocalDateTime.of(2026, 3, 25, 18, 30),
                        LocalDateTime.of(2026, 3, 25, 17, 30),
                        LocalDateTime.of(2026, 3, 25, 18, 0)));
    }

    @Nested
    @DisplayName("Woodland routing in buildSlot")
    class WoodlandRoutingTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 10, 12, 7, 30);

        private LocationEntity woodLoc(LocationType... types) {
            return LocationEntity.builder()
                    .id(20L).name("Houghall Woods").lat(54.76).lon(-1.56)
                    .locationType(Set.of(types))
                    .tideType(Set.of())
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        /** Heavy overcast with mist — a woodland GO and a sky STANDDOWN. */
        private OpenMeteoForecastResponse mistyOvercast() {
            OpenMeteoForecastResponse f = buildForecastResponse();
            f.getHourly().getCloudCoverLow().replaceAll(ignored -> 95);
            f.getHourly().getCloudCoverMid().replaceAll(ignored -> 85);
            f.getHourly().getVisibility().replaceAll(ignored -> 3000.0);
            return f;
        }

        private BriefingSlot build(LocationEntity loc, OpenMeteoForecastResponse forecast) {
            // No stubSolarWindow() here: the woodland branch returns before the tide lookup that
            // consumes it, which is itself the point — a wood is never coastal.
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            return slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, forecast),
                    SOLAR_TIME.toLocalDate(), TargetType.SUNRISE);
        }

        @Test
        @DisplayName("BOTH producers stamp the location id — the constructor cannot enforce it")
        void bothProducers_stampLocationId() {
            // The id-less constructor still exists (59 test call sites use it), so nothing at
            // compile time stops a producer quietly dropping the id and reinstating the
            // name-string join Close to home is trying to leave behind. This is that guard.
            BriefingSlot canopy = build(woodLoc(LocationType.WOODLAND, LocationType.BLUEBELL),
                    mistyOvercast());
            assertThat(canopy.canopy()).isTrue();
            assertThat(canopy.locationId())
                    .as("the woodland producer must stamp the id")
                    .isEqualTo(20L);

            BriefingSlot sky = build(woodLoc(LocationType.LANDSCAPE), buildForecastResponse());
            assertThat(sky.canopy()).isFalse();
            assertThat(sky.locationId())
                    .as("the sky producer must stamp the id")
                    .isEqualTo(20L);
        }

        @Test
        @DisplayName("A woodland-only site takes the woodland rules — misty overcast is GO, not STANDDOWN")
        void woodlandOnly_takesWoodlandRules() {
            BriefingSlot slot = build(woodLoc(LocationType.WOODLAND, LocationType.BLUEBELL),
                    mistyOvercast());

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).contains("Mist", "Soft even light");
        }

        @Test
        @DisplayName("The same weather at a LANDSCAPE site still stands down — the fork is per-location")
        void skySiteUnaffected() {
            BriefingSlot slot = build(woodLoc(LocationType.LANDSCAPE), mistyOvercast());

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
        }

        // Allen Banks is the real case: a wood that also has an aspect. It has a horizon to
        // forecast, so the sky verdict is the one that decides whether to drive there.
        @Test
        @DisplayName("A wood that ALSO has an open type keeps the sky chain")
        void woodlandPlusLandscape_keepsSkyRules() {
            BriefingSlot slot = build(woodLoc(LocationType.WOODLAND, LocationType.LANDSCAPE),
                    mistyOvercast());

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
        }

        @Test
        @DisplayName("A woodland slot carries no tide facts — a wood is not coastal")
        void woodlandCarriesNoTideData() {
            BriefingSlot slot = build(woodLoc(LocationType.WOODLAND), mistyOvercast());

            assertThat(slot).isNotNull();
            assertThat(slot.tide()).isEqualTo(BriefingSlot.TideInfo.NONE);
            assertThat(slot.tide().tideState()).isNull();
            assertThat(slot.tide().tideAligned()).isFalse();
        }

        @Test
        @DisplayName("a woodland slot carries no eclipse sight either — the woodland branch returns "
                + "before EclipseSightAssembler's seam, so it is never even asked")
        void woodlandSlot_neverReachesTheEclipseSeam() {
            LocationEntity loc = woodLoc(LocationType.WOODLAND);
            // A real eclipse's own SUNRISE window (2026-08-28), so the only reason this slot
            // carries no eclipse can be the woodland early return, not a genuine "no eclipse
            // today" answer the assembler itself would have given for an open-sky location.
            LocalDateTime eclipseSolarTime = LocalDateTime.of(2026, 8, 28, 5, 0);
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(eclipseSolarTime);

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, mistyOvercast()),
                    eclipseSolarTime.toLocalDate(), TargetType.SUNRISE);

            assertThat(slot).isNotNull();
            assertThat(slot.eclipse()).isNull();
            verifyNoInteractions(eclipseSightAssembler);
        }
    }

    @Nested
    @DisplayName("Coastal tide demotion in buildSlot")
    class TideDemotionTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc() {
            return LocationEntity.builder()
                    .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of(TideType.HIGH))
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private LocationEntity inlandLoc() {
            return LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of())
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private TideData tideData(TideState state) {
            return new TideData(state, false, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("Coastal + weather GO + tide not aligned → STANDDOWN with 'Tide not aligned' flag")
        void coastal_weatherGo_tideNotAligned_demotedToStanddown() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(tideData(TideState.LOW))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).contains("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + weather MARGINAL + tide not aligned → STANDDOWN")
        void coastal_weatherMarginal_tideNotAligned_demotedToStanddown() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(tideData(TideState.LOW))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            OpenMeteoForecastResponse marginalForecast = buildForecastResponse();
            marginalForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 65);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, marginalForecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).contains("Tide not aligned");
        }

        @Test
        @DisplayName("Inland location + weather GO → GO, no tide demotion")
        void inland_weatherGo_notAffected() throws Exception {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + no tide data in DB → weather GO verdict retained")
        void coastal_noTideData_weatherVerdictRetained() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.empty());

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
        }

        @Test
        @DisplayName("Coastal + weather already STANDDOWN + tide not aligned → no 'Tide not aligned' flag")
        void coastal_weatherStanddown_noFlagAdded() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(tideData(TideState.LOW))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            OpenMeteoForecastResponse standdownForecast = buildForecastResponse();
            standdownForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 90);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, standdownForecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
            assertThat(slot.flags()).contains("Sun blocked");
        }

        @Test
        @DisplayName("Coastal + tide aligned → GO retained, 'Tide aligned' flag present")
        void coastal_tideAligned_goRetained() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(tideData(TideState.HIGH))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Tide not aligned");
            assertThat(slot.flags()).contains("Tide aligned");
        }
    }

    /**
     * The map tab's tide-alignment glyph and label-budget tiebreaker: {@code TideInfo}'s
     * type-blind "nearest extreme of either kind" fields, kept deliberately separate from
     * {@code tideAligned} (which tests this location's configured {@code TideType} preference —
     * a different question the map tab must never conflate with "does the water land on the
     * light").
     *
     * <p>{@code stubSolarWindow()} yields a golden/blue span of blueHourStart=18:00,
     * blueHourEnd=18:30, goldenHourStart=17:30, goldenHourEnd=18:00, so for SUNSET the tight
     * alignment half-width ({@code TideFactDeriver.tightAlignmentWindowMinutes}) is
     * {@code Duration.between(17:30, 18:30) / 2 = 30 minutes} — the fixed number every test below
     * is measured against. 2026-03-25 is chosen deliberately: it is before that year's BST start
     * (29 March), so Europe/London reads the same clock time as UTC and the expected phrase
     * strings need no timezone arithmetic of their own.
     */
    @Nested
    @DisplayName("Map-tab tide-on-the-light fields in buildSlot")
    class TideOnTheLightTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc() {
            return LocationEntity.builder()
                    .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of(TideType.HIGH))
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private TideData tideDataWithNearest(TideState state, LocalDateTime nearestHigh,
                LocalDateTime nearestLow) {
            return new TideData(state, false, null, null, null, null, nearestHigh, nearestLow);
        }

        private BriefingSlot buildAgainst(LocationEntity loc, TideData tideData) {
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(tideData)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            return slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(), TargetType.SUNSET);
        }

        @Test
        @DisplayName("A low water nearer than a far high water wins — type-blind selection")
        void nearerLowWater_winsOverFartherHighWater() {
            LocationEntity loc = coastalLoc();
            // High water 3h before sunset (far); low water 20 min after sunset (near).
            TideData td = tideDataWithNearest(TideState.LOW,
                    LocalDateTime.of(2026, 3, 25, 15, 0),
                    LocalDateTime.of(2026, 3, 25, 18, 20));

            BriefingSlot slot = buildAgainst(loc, td);

            assertThat(slot.tide().nearestExtremeKind()).isEqualTo("LW");
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isEqualTo(20);
            assertThat(slot.tide().tideOnTheLight()).as("20m <= the 30-minute stub window").isTrue();
            assertThat(slot.tide().nearestSolarOffsetPhrase())
                    .isEqualTo("LW 18:20 · 20m after sunset");
        }

        @Test
        @DisplayName("A high water nearer than a far low water wins — type-blind the other way")
        void nearerHighWater_winsOverFartherLowWater() {
            LocationEntity loc = coastalLoc();
            TideData td = tideDataWithNearest(TideState.HIGH,
                    LocalDateTime.of(2026, 3, 25, 18, 10),
                    LocalDateTime.of(2026, 3, 25, 21, 20));

            BriefingSlot slot = buildAgainst(loc, td);

            assertThat(slot.tide().nearestExtremeKind()).isEqualTo("HW");
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isEqualTo(10);
        }

        @Test
        @DisplayName("Sign convention: a tide before the light is negative, not just 'before'")
        void tideBeforeLight_isNegativeOffset() {
            LocationEntity loc = coastalLoc();
            // High water 15 min before sunset; no low water found nearby at all.
            TideData td = tideDataWithNearest(TideState.HIGH,
                    LocalDateTime.of(2026, 3, 25, 17, 45), null);

            BriefingSlot slot = buildAgainst(loc, td);

            assertThat(slot.tide().nearestExtremeKind()).isEqualTo("HW");
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isEqualTo(-15);
            assertThat(slot.tide().tideOnTheLight()).isTrue();
            assertThat(slot.tide().nearestSolarOffsetPhrase())
                    .isEqualTo("HW 17:45 · 15m before sunset");
        }

        @Test
        @DisplayName("Outside the dynamic tight window: offset still reported, tideOnTheLight false")
        void outsideTightWindow_tideOnTheLightFalse() {
            LocationEntity loc = coastalLoc();
            // 45 minutes after sunset — outside the 30-minute half-width stubSolarWindow() yields
            // for SUNSET, so this must read false rather than the fixed +/-45min the design bundle
            // used (TideFactDeriver's dynamic half-width is the canonical rule; see CLAUDE.md).
            TideData td = tideDataWithNearest(TideState.LOW, null,
                    LocalDateTime.of(2026, 3, 25, 18, 45));

            BriefingSlot slot = buildAgainst(loc, td);

            assertThat(slot.tide().nearestExtremeKind()).isEqualTo("LW");
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isEqualTo(45);
            assertThat(slot.tide().tideOnTheLight()).isFalse();
            assertThat(slot.tide().nearestSolarOffsetPhrase())
                    .isEqualTo("LW 18:45 · 45m after sunset");
        }

        @Test
        @DisplayName("A tie breaks toward the earlier water, not toward a type")
        void tiedOffsets_earlierWaterWins() {
            LocationEntity loc = coastalLoc();
            // Both 30 minutes from the light in magnitude; the low water is the earlier one.
            TideData td = tideDataWithNearest(TideState.MID,
                    LocalDateTime.of(2026, 3, 25, 18, 30),
                    LocalDateTime.of(2026, 3, 25, 17, 30));

            BriefingSlot slot = buildAgainst(loc, td);

            assertThat(slot.tide().nearestExtremeKind()).isEqualTo("LW");
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isEqualTo(-30);
        }

        @Test
        @DisplayName("Neither extreme found nearby: all four tide-alignment fields are null")
        void noExtremeEitherKind_fieldsNull() {
            LocationEntity loc = coastalLoc();
            TideData td = tideDataWithNearest(TideState.MID, null, null);

            BriefingSlot slot = buildAgainst(loc, td);

            assertThat(slot.tide().nearestExtremeKind()).isNull();
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isNull();
            assertThat(slot.tide().tideOnTheLight()).isNull();
            assertThat(slot.tide().nearestSolarOffsetPhrase()).isNull();
        }

        @Test
        @DisplayName("No stored extremes at all: tide-alignment fields fail-soft to null")
        void noExtremesInDb_fieldsNull() {
            LocationEntity loc = coastalLoc();
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.empty());

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot.tide()).isEqualTo(BriefingSlot.TideInfo.NONE);
            assertThat(slot.tide().nearestExtremeKind()).isNull();
            assertThat(slot.tide().nearestSolarOffsetMinutes()).isNull();
            assertThat(slot.tide().tideOnTheLight()).isNull();
            assertThat(slot.tide().nearestSolarOffsetPhrase()).isNull();
        }
    }

    @Test
    @DisplayName("Sunrise event type uses sunriseUtc")
    void sunrise_usesSunriseService() {
        LocationEntity loc = LocationEntity.builder()
                .id(11L).name("Durham").lat(54.8).lon(-1.6)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of()).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
        LocalDateTime sunriseTime = LocalDateTime.of(2026, 3, 25, 6, 15);
        when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                .thenReturn(sunriseTime);
        when(locationService.isCoastal(loc)).thenReturn(false);

        BriefingSlotBuilder.LocationWeather lw =
                new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
        BriefingSlot slot = slotBuilder.buildSlot(lw, sunriseTime.toLocalDate(),
                TargetType.SUNRISE);

        assertThat(slot).isNotNull();
        assertThat(slot.verdict()).isEqualTo(Verdict.GO);
    }

    @Test
    @DisplayName("Solar service exception returns null slot")
    void solarException_returnsNull() {
        LocationEntity loc = LocationEntity.builder()
                .id(11L).name("Durham").lat(54.8).lon(-1.6)
                .locationType(Set.of(LocationType.LANDSCAPE))
                .tideType(Set.of()).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                .thenThrow(new RuntimeException("No sunset at this latitude"));

        BriefingSlotBuilder.LocationWeather lw =
                new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
        BriefingSlot slot = slotBuilder.buildSlot(lw, LocalDate.of(2026, 6, 21),
                TargetType.SUNSET);

        assertThat(slot).isNull();
    }

    @Test
    @DisplayName("King tide detected when height exceeds P95")
    void kingTide_detected() {
        stubSolarWindow();
        LocalDateTime solarTime = LocalDateTime.of(2026, 3, 25, 18, 0);
        LocationEntity loc = LocationEntity.builder()
                .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                .locationType(Set.of(LocationType.SEASCAPE))
                .tideType(Set.of(TideType.HIGH)).solarEventType(Set.of())
                .enabled(true).createdAt(LocalDateTime.now()).build();
        when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                .thenReturn(solarTime);
        when(locationService.isCoastal(loc)).thenReturn(true);
        when(lunarPhaseService.classifyTide(solarTime.toLocalDate()))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.getMoonPhase(solarTime.toLocalDate()))
                .thenReturn("Waxing Crescent");
        when(lunarPhaseService.isMoonAtPerigee(solarTime.toLocalDate()))
                .thenReturn(false);

        // HIGH tide within 90 min of solar event, with height data
        TideData td = new TideData(TideState.HIGH, false, null,
                new BigDecimal("5.80"), null, null,
                solarTime.plusMinutes(30), null);
        when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(solarTime), anyLong(), anyLong()))
                .thenReturn(Optional.of(dual(td)));
        when(tideService.calculateTideAligned(any(), any())).thenReturn(true);

        // Stats with p95 = 5.50 and spring threshold = 5.00
        TideStats stats = new TideStats(
                new BigDecimal("4.00"), new BigDecimal("6.00"),
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                200, new BigDecimal("3.00"),
                new BigDecimal("4.50"), new BigDecimal("5.00"), new BigDecimal("5.50"),
                10, new BigDecimal("0.05"), new BigDecimal("5.00"),
                new BigDecimal("5.50"), 5);
        when(tideService.getTideStats(loc.getId())).thenReturn(Optional.of(stats));

        BriefingSlotBuilder.LocationWeather lw =
                new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
        BriefingSlot slot = slotBuilder.buildSlot(lw, solarTime.toLocalDate(),
                TargetType.SUNSET);

        assertThat(slot).isNotNull();
        assertThat(slot.tide().heightAboveP95()).isTrue();
        assertThat(slot.tide().heightAboveSpringThreshold()).isTrue();
        assertThat(slot.flags()).contains("Tide aligned");
    }

    @Nested
    @DisplayName("Lunar tide fields in buildSlot")
    class LunarTideTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc() {
            return LocationEntity.builder()
                    .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of(TideType.HIGH)).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
        }

        @Test
        @DisplayName("Lunar spring tide flag when new/full moon")
        void lunarSpringTide_flagGenerated() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(
                            new TideData(TideState.HIGH, false, null, null, null, null,
                                    SOLAR_TIME.plusMinutes(30), null))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);
            when(lunarPhaseService.classifyTide(SOLAR_TIME.toLocalDate()))
                    .thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.getMoonPhase(SOLAR_TIME.toLocalDate()))
                    .thenReturn("Full Moon");
            when(lunarPhaseService.isMoonAtPerigee(SOLAR_TIME.toLocalDate()))
                    .thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.tide().lunarTideType()).isEqualTo(LunarTideType.SPRING_TIDE);
            assertThat(slot.tide().lunarPhase()).isEqualTo("Full Moon");
            assertThat(slot.tide().moonAtPerigee()).isFalse();
            assertThat(slot.flags()).contains("Spring Tide");
        }

        @Test
        @DisplayName("Lunar king tide flag when spring + perigee")
        void lunarKingTide_flagGenerated() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(
                            new TideData(TideState.HIGH, false, null, null, null, null,
                                    SOLAR_TIME.plusMinutes(30), null))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);
            when(lunarPhaseService.classifyTide(SOLAR_TIME.toLocalDate()))
                    .thenReturn(LunarTideType.KING_TIDE);
            when(lunarPhaseService.getMoonPhase(SOLAR_TIME.toLocalDate()))
                    .thenReturn("New Moon");
            when(lunarPhaseService.isMoonAtPerigee(SOLAR_TIME.toLocalDate()))
                    .thenReturn(true);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.tide().lunarTideType()).isEqualTo(LunarTideType.KING_TIDE);
            assertThat(slot.tide().lunarPhase()).isEqualTo("New Moon");
            assertThat(slot.tide().moonAtPerigee()).isTrue();
            assertThat(slot.flags()).contains("King Tide");
        }

        @Test
        @DisplayName("Inland location has null lunar fields")
        void inland_nullLunarFields() throws Exception {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.tide().lunarTideType()).isNull();
            assertThat(slot.tide().lunarPhase()).isNull();
            assertThat(slot.tide().moonAtPerigee()).isNull();
        }

        @Test
        @DisplayName("Combined label: Spring Tide + Extra High")
        void combinedLabel_springPlusStatHigh() throws Exception {
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(lunarPhaseService.classifyTide(SOLAR_TIME.toLocalDate()))
                    .thenReturn(LunarTideType.SPRING_TIDE);
            when(lunarPhaseService.getMoonPhase(SOLAR_TIME.toLocalDate()))
                    .thenReturn("Full Moon");
            when(lunarPhaseService.isMoonAtPerigee(SOLAR_TIME.toLocalDate()))
                    .thenReturn(false);

            TideData td = new TideData(TideState.HIGH, false, null,
                    new BigDecimal("5.20"), null, null,
                    SOLAR_TIME.plusMinutes(30), null);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);
            TideStats stats = new TideStats(
                    new BigDecimal("4.00"), new BigDecimal("6.00"),
                    new BigDecimal("1.00"), new BigDecimal("0.50"),
                    200, new BigDecimal("3.00"),
                    new BigDecimal("4.50"), new BigDecimal("5.00"), new BigDecimal("5.50"),
                    10, new BigDecimal("0.05"), new BigDecimal("5.00"),
                    new BigDecimal("5.50"), 5);
            when(tideService.getTideStats(loc.getId())).thenReturn(Optional.of(stats));

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.flags()).contains("Spring Tide, Extra High");
        }
    }

    // ── Clear-sky demotion through buildSlot ──

    @Nested
    @DisplayName("Clear-sky demotion through buildSlot")
    class ClearSkyIntegrationTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        @Test
        @DisplayName("Cloudless sky (all layers < 15%) demotes GO to MARGINAL with flag")
        void cloudlessSky_goToMarginal() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(54.8), eq(-1.6), eq(SOLAR_TIME.toLocalDate())))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse clearForecast = buildForecastResponse();
            clearForecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 5);
            clearForecast.getHourly().getCloudCoverMid().replaceAll(ignored -> 8);
            clearForecast.getHourly().getCloudCoverHigh().replaceAll(ignored -> 10);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, clearForecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.MARGINAL);
            assertThat(slot.flags()).contains("Clear all layers");
            assertThat(slot.standdownReason()).isNull();
        }

        @Test
        @DisplayName("Cirrus present (high >= 15%) prevents clear-sky demotion — stays GO")
        void cirrusPresent_staysGo() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(54.8), eq(-1.6), eq(SOLAR_TIME.toLocalDate())))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse forecast = buildForecastResponse();
            forecast.getHourly().getCloudCoverLow().replaceAll(ignored -> 5);
            forecast.getHourly().getCloudCoverMid().replaceAll(ignored -> 8);
            forecast.getHourly().getCloudCoverHigh().replaceAll(ignored -> 25);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, forecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
            assertThat(slot.flags()).doesNotContain("Clear all layers");
        }
    }

    // ── Horizon cloud demotion through buildSlot ──

    @Nested
    @DisplayName("Horizon cloud demotion through buildSlot")
    class HorizonCloudIntegrationTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        @Test
        @DisplayName("Horizon low cloud >= 70% demotes to STANDDOWN with reason")
        void horizonBlocked_standdown() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(54.8), eq(-1.6), eq(SOLAR_TIME.toLocalDate())))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse horizonForecast = buildCloudOnlyResponse(80);
            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET, horizonForecast);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.standdownReason()).isNotNull();
        }

        @Test
        @DisplayName("Horizon low cloud 40-69% demotes GO to MARGINAL")
        void horizonPartial_marginal() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(54.8), eq(-1.6), eq(SOLAR_TIME.toLocalDate())))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse horizonForecast = buildCloudOnlyResponse(50);
            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET, horizonForecast);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.MARGINAL);
        }

        @Test
        @DisplayName("Horizon low cloud < 40% — no demotion, stays GO")
        void horizonClear_staysGo() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(54.8), eq(-1.6), eq(SOLAR_TIME.toLocalDate())))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse horizonForecast = buildCloudOnlyResponse(15);
            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET, horizonForecast);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
        }

        @Test
        @DisplayName("Null horizon forecast — no demotion applied")
        void nullHorizon_noDemotion() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(54.8), eq(-1.6), eq(SOLAR_TIME.toLocalDate())))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET, null);

            assertThat(slot).isNotNull();
            assertThat(slot.verdict()).isEqualTo(Verdict.GO);
        }
    }

    // ── extractHorizonLowCloud static method ──

    @Nested
    @DisplayName("extractHorizonLowCloud")
    class ExtractHorizonTests {

        @Test
        @DisplayName("Extracts low cloud at specified index")
        void validExtraction() {
            OpenMeteoForecastResponse response = buildCloudOnlyResponse(75);
            assertThat(BriefingSlotBuilder.extractHorizonLowCloud(response, 0)).isEqualTo(75);
            assertThat(BriefingSlotBuilder.extractHorizonLowCloud(response, 10)).isEqualTo(75);
        }

        @Test
        @DisplayName("Returns null for null response")
        void nullResponse() {
            assertThat(BriefingSlotBuilder.extractHorizonLowCloud(null, 0)).isNull();
        }

        @Test
        @DisplayName("Returns null when hourly data is null")
        void nullHourly() {
            OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
            assertThat(BriefingSlotBuilder.extractHorizonLowCloud(response, 0)).isNull();
        }

        @Test
        @DisplayName("Returns null when cloudCoverLow is null")
        void nullCloudList() {
            OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
            response.setHourly(new OpenMeteoForecastResponse.Hourly());
            assertThat(BriefingSlotBuilder.extractHorizonLowCloud(response, 0)).isNull();
        }

        @Test
        @DisplayName("Returns null when index exceeds available hours")
        void indexOutOfBounds() {
            OpenMeteoForecastResponse response = buildCloudOnlyResponse(50);
            assertThat(BriefingSlotBuilder.extractHorizonLowCloud(response, 999)).isNull();
        }
    }

    // ── Mid/high cloud null-guard extraction ──

    @Nested
    @DisplayName("Mid and high cloud extraction in buildSlot")
    class WeatherMetricsExtractionTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity inlandLoc() {
            return LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
        }

        @Test
        @DisplayName("midCloudPercent reflects forecast value 30 — not null-guard fallback of 0")
        void midCloud_readFromForecast() {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.weather().midCloudPercent()).isEqualTo(30);
        }

        @Test
        @DisplayName("highCloudPercent reflects forecast value 40 — not null-guard fallback of 0")
        void highCloud_readFromForecast() {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.weather().highCloudPercent()).isEqualTo(40);
        }

        @Test
        @DisplayName("midCloudPercent defaults to 0 when cloudCoverMid list is null")
        void midCloud_nullList_defaultsToZero() {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse forecast = buildForecastResponse();
            forecast.getHourly().setCloudCoverMid(null);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, forecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.weather().midCloudPercent()).isEqualTo(0);
        }

        @Test
        @DisplayName("highCloudPercent defaults to 0 when cloudCoverHigh list is null")
        void highCloud_nullList_defaultsToZero() {
            LocationEntity loc = inlandLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            OpenMeteoForecastResponse forecast = buildForecastResponse();
            forecast.getHourly().setCloudCoverHigh(null);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, forecast);
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.weather().highCloudPercent()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Eclipse sight attachment at the TideInfo seam")
    class EclipseSightAttachmentTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 8, 28, 5, 0);

        private LocationEntity inlandLoc() {
            return LocationEntity.builder()
                    .id(31L).name("Dunstanburgh").lat(55.49).lon(-1.59)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
        }

        private BriefingSlot.EclipseSight sampleSight() {
            LocalDateTime max = LocalDateTime.of(2026, 8, 28, 5, 12);
            return new BriefingSlot.EclipseSight("LUNAR_ECLIPSE", 8, 241, "WSW", max,
                    max.minusHours(2), max.plusHours(2), max.plusHours(1), max.minusHours(4),
                    true, false, "DAWN",
                    List.of(new BriefingSlot.LightStop("NAUTICAL_DAWN", max.minusHours(2))));
        }

        @Test
        @DisplayName("buildSlot attaches whatever EclipseSightAssembler returns for this window")
        void attachesTheAssemblersSight() {
            LocationEntity loc = inlandLoc();
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);
            BriefingSlot.EclipseSight sight = sampleSight();
            when(eclipseSightAssembler.forSlot(loc, SOLAR_TIME.toLocalDate(), TargetType.SUNRISE))
                    .thenReturn(sight);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(), TargetType.SUNRISE);

            assertThat(slot).isNotNull();
            assertThat(slot.eclipse()).isEqualTo(sight);
        }

        @Test
        @DisplayName("buildSlot carries no eclipse when the assembler answers null — every other night")
        void carriesNoEclipseWhenAssemblerAnswersNull() {
            LocationEntity loc = inlandLoc();
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);
            // eclipseSightAssembler left unstubbed: Mockito answers null.

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(), TargetType.SUNRISE);

            assertThat(slot).isNotNull();
            assertThat(slot.eclipse()).isNull();
        }

        @Test
        @DisplayName("the assembler is asked with this slot's own location, date and event type")
        void assemblerIsAskedWithTheSlotsOwnCoordinates() {
            LocationEntity loc = inlandLoc();
            LocalDateTime sunsetTime = LocalDateTime.of(2026, 8, 28, 19, 45);
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(sunsetTime);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            slotBuilder.buildSlot(lw, sunsetTime.toLocalDate(), TargetType.SUNSET);

            verify(eclipseSightAssembler).forSlot(loc, sunsetTime.toLocalDate(), TargetType.SUNSET);
        }
    }

    // ── Tide window minutes and king/spring threshold boundaries ──

    @Nested
    @DisplayName("Tide window minutes and king/spring threshold boundaries")
    class TideCalculationTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc() {
            return LocationEntity.builder()
                    .id(10L).name("Bamburgh").lat(55.6).lon(-1.7)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(Set.of(TideType.HIGH)).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
        }

        private TideStats buildStats(BigDecimal springThreshold, BigDecimal p95) {
            return new TideStats(
                    new BigDecimal("4.00"), new BigDecimal("6.00"),
                    new BigDecimal("1.00"), new BigDecimal("0.50"),
                    200, new BigDecimal("3.00"),
                    new BigDecimal("4.50"), new BigDecimal("5.00"), p95,
                    10, new BigDecimal("0.05"), springThreshold,
                    p95, 5);
        }

        private void stubLunar() {
            when(lunarPhaseService.classifyTide(SOLAR_TIME.toLocalDate()))
                    .thenReturn(LunarTideType.REGULAR_TIDE);
            when(lunarPhaseService.getMoonPhase(SOLAR_TIME.toLocalDate()))
                    .thenReturn("Waxing Crescent");
            when(lunarPhaseService.isMoonAtPerigee(SOLAR_TIME.toLocalDate()))
                    .thenReturn(false);
        }

        @Test
        @DisplayName("deriveDualWindowTideData is called with tight window = 30 (duration / 2), "
                + "widened = 90")
        void windowMinutes_isHalfOfDuration() {
            // stubSolarWindow: goldenHourStart=17:30, blueHourEnd=18:30 → 60 min / 2 = 30; widened +60
            stubSolarWindow();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            // eq(30L), eq(90L) are the assertion: strict stubs fail if the mutant passes 120L instead
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), eq(30L), eq(90L)))
                    .thenReturn(Optional.empty());

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(), TargetType.SUNSET);
        }

        @Test
        @DisplayName("Height exactly at P95 threshold is NOT a king tide (strictly greater-than, not >=)")
        void heightAtP95_notKingTide() {
            stubSolarWindow();
            stubLunar();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            TideData td = new TideData(TideState.HIGH, false, null,
                    new BigDecimal("5.50"), null, null, SOLAR_TIME.plusMinutes(30), null);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);
            when(tideService.getTideStats(loc.getId())).thenReturn(
                    Optional.of(buildStats(new BigDecimal("5.00"), new BigDecimal("5.50"))));

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.tide().heightAboveP95()).isFalse();
        }

        @Test
        @DisplayName("Height one cent above P95 threshold IS a king tide")
        void heightAboveP95_heightAboveP95() {
            stubSolarWindow();
            stubLunar();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            TideData td = new TideData(TideState.HIGH, false, null,
                    new BigDecimal("5.51"), null, null, SOLAR_TIME.plusMinutes(30), null);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);
            when(tideService.getTideStats(loc.getId())).thenReturn(
                    Optional.of(buildStats(new BigDecimal("5.00"), new BigDecimal("5.50"))));

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.tide().heightAboveP95()).isTrue();
        }

        @Test
        @DisplayName("Height exactly at spring threshold is NOT a spring tide (strictly greater-than)")
        void heightAtSpringThreshold_notSpringTide() {
            stubSolarWindow();
            stubLunar();
            LocationEntity loc = coastalLoc();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            TideData td = new TideData(TideState.HIGH, false, null,
                    new BigDecimal("5.00"), null, null, SOLAR_TIME.plusMinutes(30), null);
            when(tideService.deriveDualWindowTideData(eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);
            when(tideService.getTideStats(loc.getId())).thenReturn(
                    Optional.of(buildStats(new BigDecimal("5.00"), new BigDecimal("5.50"))));

            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            BriefingSlot slot = slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(),
                    TargetType.SUNSET);

            assertThat(slot).isNotNull();
            assertThat(slot.tide().heightAboveSpringThreshold()).isFalse();
        }
    }

    // ── extractLowCloudTrend static method ──

    @Nested
    @DisplayName("extractLowCloudTrend")
    class LowCloudTrendExtractionTests {

        @Test
        @DisplayName("Returns [idx-2, idx-1, idx] — 3 values, not an empty list")
        void returnsThreeValues_forNormalIdx() {
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setCloudCoverLow(new ArrayList<>(List.of(10, 20, 30, 40, 50, 60)));

            List<Integer> trend = BriefingSlotBuilder.extractLowCloudTrend(hourly, 5);

            assertThat(trend).containsExactly(40, 50, 60);
        }

        @Test
        @DisplayName("idx=0: returns exactly [cloud[0]], clamping start to 0")
        void idx0_returnsSingleElement() {
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setCloudCoverLow(new ArrayList<>(List.of(55, 60, 65)));

            List<Integer> trend = BriefingSlotBuilder.extractLowCloudTrend(hourly, 0);

            assertThat(trend).containsExactly(55);
        }

        @Test
        @DisplayName("idx=1: clamps start to 0 and returns [cloud[0], cloud[1]]")
        void idx1_twoElementsWithClampedStart() {
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setCloudCoverLow(new ArrayList<>(List.of(10, 20, 30)));

            List<Integer> trend = BriefingSlotBuilder.extractLowCloudTrend(hourly, 1);

            assertThat(trend).containsExactly(10, 20);
        }

        @Test
        @DisplayName("Returns empty list when cloudCoverLow is null")
        void nullCloud_returnsEmpty() {
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setCloudCoverLow(null);

            List<Integer> trend = BriefingSlotBuilder.extractLowCloudTrend(hourly, 3);

            assertThat(trend).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list when cloudCoverLow is empty")
        void emptyCloud_returnsEmpty() {
            OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
            hourly.setCloudCoverLow(new ArrayList<>());

            List<Integer> trend = BriefingSlotBuilder.extractLowCloudTrend(hourly, 0);

            assertThat(trend).isEmpty();
        }
    }

    /**
     * Builds a cloud-only forecast response with uniform low cloud values.
     * Matches what {@code fetchCloudOnlyBatch} returns (only cloud layers populated).
     */
    private static OpenMeteoForecastResponse buildCloudOnlyResponse(int lowCloudPct) {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

        List<String> times = new ArrayList<>();
        List<Integer> cloudLow = new ArrayList<>();

        LocalDateTime start = LocalDate.now().atStartOfDay();
        for (int i = 0; i < 48; i++) {
            times.add(start.plusHours(i).toString());
            cloudLow.add(lowCloudPct);
        }

        hourly.setTime(times);
        hourly.setCloudCoverLow(cloudLow);
        response.setHourly(hourly);
        return response;
    }

    private static OpenMeteoForecastResponse buildForecastResponse() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();

        List<String> times = new ArrayList<>();
        List<Integer> cloudLow = new ArrayList<>();
        List<Integer> cloudMid = new ArrayList<>();
        List<Integer> cloudHigh = new ArrayList<>();
        List<Double> visibility = new ArrayList<>();
        List<Double> windSpeed = new ArrayList<>();
        List<Integer> windDir = new ArrayList<>();
        List<Double> precip = new ArrayList<>();
        List<Integer> weatherCode = new ArrayList<>();
        List<Integer> humidity = new ArrayList<>();
        List<Double> pressure = new ArrayList<>();
        List<Double> radiation = new ArrayList<>();
        List<Double> blh = new ArrayList<>();
        List<Double> temp = new ArrayList<>();
        List<Double> feelsLike = new ArrayList<>();
        List<Integer> precipProb = new ArrayList<>();
        List<Double> dewPoint = new ArrayList<>();

        LocalDateTime start = LocalDate.now().atStartOfDay();
        for (int i = 0; i < 48; i++) {
            times.add(start.plusHours(i).toString());
            cloudLow.add(20);
            cloudMid.add(30);
            cloudHigh.add(40);
            visibility.add(15000.0);
            windSpeed.add(5.0);
            windDir.add(180);
            precip.add(0.0);
            weatherCode.add(0);
            humidity.add(70);
            pressure.add(1013.0);
            radiation.add(100.0);
            blh.add(500.0);
            temp.add(10.0);
            feelsLike.add(8.0);
            precipProb.add(5);
            dewPoint.add(5.0);
        }

        hourly.setTime(times);
        hourly.setCloudCoverLow(cloudLow);
        hourly.setCloudCoverMid(cloudMid);
        hourly.setCloudCoverHigh(cloudHigh);
        hourly.setVisibility(visibility);
        hourly.setWindSpeed10m(windSpeed);
        hourly.setWindDirection10m(windDir);
        hourly.setPrecipitation(precip);
        hourly.setWeatherCode(weatherCode);
        hourly.setRelativeHumidity2m(humidity);
        hourly.setSurfacePressure(pressure);
        hourly.setShortwaveRadiation(radiation);
        hourly.setBoundaryLayerHeight(blh);
        hourly.setTemperature2m(temp);
        hourly.setApparentTemperature(feelsLike);
        hourly.setPrecipitationProbability(precipProb);
        hourly.setDewPoint2m(dewPoint);

        response.setHourly(hourly);
        return response;
    }

    /**
     * The served reason a slot was withheld from Claude — {@code BriefingSlot.evaluationGate}.
     *
     * <p>⚠️ <b>Rewritten for the tide gate lift (2026-09-18, {@code
     * docs/engineering/tide-window-plan.md} §6 Q1).</b> Before the lift a tide mismatch was the
     * one hard constraint that produced this field ({@code TideWording.tideGatePhrase}, deleted
     * with the lift). {@code BriefingGatingPolicy.HARD_CONSTRAINT_REASONS} is now empty, so this
     * class's job is the mirror image of what it used to test: proving a tide mismatch produces
     * **no** gate any more — the verdict still reads STANDDOWN (a triage label, kept for the
     * region roll-up — see {@code BriefingSlotBuilder}'s own comment where the override lives),
     * but the slot is eligible, not a hard-constraint skip, and carries no {@code evaluationGate}.
     * {@code alignedTide_noGate} and {@code inland_noGate} were already testing the "no gate"
     * case and are unchanged.
     *
     * <p>Same fixture geometry as {@code TideOnTheLightTests}: 2026-03-25 is before BST, so the
     * clock in any offset phrase needs no timezone arithmetic.
     */
    @Nested
    @DisplayName("Evaluation gate in buildSlot")
    class EvaluationGateTests {

        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 3, 25, 18, 0);

        private LocationEntity coastalLoc(Set<TideType> wants) {
            return LocationEntity.builder()
                    .id(10L).name("Seaham Chemical Beach").lat(54.83).lon(-1.32)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(wants)
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        private BriefingSlot buildCoastal(LocationEntity loc, TideData td, boolean aligned) {
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(aligned);
            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            return slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(), TargetType.SUNSET);
        }

        @Test
        @DisplayName("A tide mismatch still reads STANDDOWN (the triage label) but is eligible and "
                + "carries no gate — it reaches Claude and scores through TideVisitor instead")
        void tideMismatch_eligibleNoGate() {
            // Wants low water; it is mid tide; the nearest extreme is LW 20 min after sunset.
            TideData td = new TideData(TideState.MID, false, null, null, null, null,
                    LocalDateTime.of(2026, 3, 25, 15, 0), LocalDateTime.of(2026, 3, 25, 18, 20));

            BriefingSlot slot = buildCoastal(coastalLoc(Set.of(TideType.LOW)), td, false);

            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(BriefingGatingPolicy.isHardConstraintSkip(slot))
                    .as("the same call the candidate collector makes — the tide gate lift left "
                            + "HARD_CONSTRAINT_REASONS empty").isFalse();
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
            assertThat(slot.evaluationGate()).isNull();
        }

        @Test
        @DisplayName("A coastal WEATHER stand-down with the tide aligned serves no gate — it reaches Claude")
        void weatherStanddown_tideAligned_noGate() {
            // The case that separates "ask the policy" from "test the verdict": STANDDOWN, coastal,
            // but the reason is cloud, which Gate 2 sends to Claude to be rated down.
            TideData td = new TideData(TideState.HIGH, false, null, null, null, null,
                    LocalDateTime.of(2026, 3, 25, 18, 10), null);
            LocationEntity loc = coastalLoc(Set.of(TideType.HIGH));
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);
            OpenMeteoForecastResponse overcast = buildForecastResponse();
            overcast.getHourly().getCloudCoverLow().replaceAll(ignored -> 95);

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, overcast),
                    SOLAR_TIME.toLocalDate(), TargetType.SUNSET);

            assertThat(slot.verdict()).isEqualTo(Verdict.STANDDOWN);
            assertThat(slot.standdownReason()).isNotEqualTo("Tide mismatch");
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
            assertThat(slot.evaluationGate()).isNull();
        }

        @Test
        @DisplayName("A sunrise tide mismatch is eligible and gate-free too — not only the sunset case")
        void sunriseTideMismatch_eligibleNoGate() {
            LocalDateTime dawn = LocalDateTime.of(2026, 3, 25, 6, 0);
            LocationEntity loc = coastalLoc(Set.of(TideType.LOW));
            stubSolarWindow();
            when(solarService.sunriseUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(dawn);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(dawn), anyLong(), anyLong()))
                    .thenReturn(Optional.of(dual(new TideData(TideState.MID, false, null, null,
                            null, null, LocalDateTime.of(2026, 3, 25, 9, 0), null))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse()),
                    dawn.toLocalDate(), TargetType.SUNRISE);

            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
            assertThat(slot.evaluationGate()).isNull();
        }

        @Test
        @DisplayName("No nearby extreme at all is still eligible and gate-free")
        void noNearestExtreme_eligibleNoGate() {
            TideData td = new TideData(TideState.MID, false, null, null, null, null, null, null);

            BriefingSlot slot = buildCoastal(coastalLoc(Set.of(TideType.HIGH)), td, false);

            assertThat(slot.tide().nearestSolarOffsetPhrase()).isNull();
            assertThat(BriefingGatingPolicy.isEligibleForEvaluation(slot)).isTrue();
            assertThat(slot.evaluationGate()).isNull();
        }

        @Test
        @DisplayName("An aligned tide serves no gate — the slot reaches Claude")
        void alignedTide_noGate() {
            TideData td = new TideData(TideState.HIGH, false, null, null, null, null,
                    LocalDateTime.of(2026, 3, 25, 18, 10), null);

            BriefingSlot slot = buildCoastal(coastalLoc(Set.of(TideType.HIGH)), td, true);

            assertThat(slot.verdict()).isNotEqualTo(Verdict.STANDDOWN);
            assertThat(slot.evaluationGate()).isNull();
        }

        @Test
        @DisplayName("An inland location serves no gate — there is no tide to gate on")
        void inland_noGate() {
            LocationEntity loc = LocationEntity.builder()
                    .id(11L).name("Durham").lat(54.8).lon(-1.6)
                    .locationType(Set.of(LocationType.LANDSCAPE))
                    .tideType(Set.of()).solarEventType(Set.of())
                    .enabled(true).createdAt(LocalDateTime.now()).build();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(false);

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse()),
                    SOLAR_TIME.toLocalDate(), TargetType.SUNSET);

            assertThat(slot.evaluationGate()).isNull();
        }
    }

    @Nested
    @DisplayName("Map-tab tide-fit fields (level, direction, height, shortfall, fit phrase) "
            + "in buildSlot")
    class TideFitTests {

        /** A UTC sunset outside BST, so the fit phrase's clock clause reads bare. */
        private static final LocalDateTime SOLAR_TIME = LocalDateTime.of(2026, 1, 27, 9, 0);

        /**
         * A clean symmetric day: HIGH 03:00 / LOW 09:00 / HIGH 15:00 / LOW 21:00, all on the
         * 30-minute sample grid so the sampled curve's min and max equal the stored heights
         * exactly, with no interpolation error to tolerate.
         */
        private static List<TideExtremeEntity> symmetricDay() {
            LocalDate day = SOLAR_TIME.toLocalDate();
            return List.of(
                    extreme(TideExtremeType.HIGH, day.atTime(3, 0), 4.0),
                    extreme(TideExtremeType.LOW, day.atTime(9, 0), 1.0),
                    extreme(TideExtremeType.HIGH, day.atTime(15, 0), 4.0),
                    extreme(TideExtremeType.LOW, day.atTime(21, 0), 1.0));
        }

        private static TideExtremeEntity extreme(TideExtremeType type, LocalDateTime utc, double m) {
            TideExtremeEntity e = new TideExtremeEntity();
            e.setType(type);
            e.setEventTime(utc);
            e.setHeightMetres(BigDecimal.valueOf(m));
            return e;
        }

        private LocationEntity coastalLoc(Set<TideType> wants) {
            return LocationEntity.builder()
                    .id(10L).name("Seaham Chemical Beach").lat(54.83).lon(-1.32)
                    .locationType(Set.of(LocationType.SEASCAPE))
                    .tideType(wants)
                    .solarEventType(Set.of())
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        /**
         * Builds a coastal slot at {@link #SOLAR_TIME}, with {@link #symmetricDay()}'s extremes
         * behind the tide-fit curve and the given served state/alignment behind {@code
         * TideFactDeriver} (mocked at the {@code TideService} boundary, exactly as the rest of
         * this file does — the curve fetch is a genuinely separate, second repository call).
         *
         * <p>Every stubbed argument is pinned exact ({@code eq()}, never {@code any()}) where the
         * value is knowable: {@link #stubSolarWindow()}'s fixed golden/blue window makes a SUNSET
         * tight/widened alignment window of exactly 30/90 minutes (the same figures the "Tide
         * window minutes" nested class above pins directly), and the curve fetch window is exactly
         * {@code SOLAR_TIME ± CURVE_QUERY_WINDOW_DAYS}. A mutant that shrank either window would
         * leave these stubs unmatched and the test would fail loudly rather than pass quietly.
         */
        private BriefingSlot build(LocationEntity loc, TideState state, boolean aligned) {
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            // A nearest extreme exactly on the light (the 09:00 LOW), so the gate sentence and
            // the map-tab nearest-extreme phrase are both well-defined non-null strings — the
            // case gatedMiss_offsetClauseAppearsOnceOnTheCard needs a real clause to check for.
            TideData td = new TideData(state, false, null, null, null, null, null, SOLAR_TIME);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), eq(30L), eq(90L)))
                    .thenReturn(Optional.of(dual(td)));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(aligned);
            when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                    eq(loc.getId()), eq(SOLAR_TIME.minusDays(2)), eq(SOLAR_TIME.plusDays(2))))
                    .thenReturn(symmetricDay());
            BriefingSlotBuilder.LocationWeather lw =
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse());
            return slotBuilder.buildSlot(lw, SOLAR_TIME.toLocalDate(), TargetType.SUNSET);
        }

        @Test
        @DisplayName("no extremes stored: all five tide-fit fields fail-soft to null, not an "
                + "exception")
        void noExtremes_fieldsFailSoftToNull() {
            LocationEntity loc = coastalLoc(Set.of(TideType.HIGH));
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), eq(30L), eq(90L)))
                    .thenReturn(Optional.of(dual(
                            new TideData(TideState.LOW, false, null, null, null, null, null, null))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(true);
            // tideExtremeRepository left unstubbed: Mockito answers an empty list.

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse()),
                    SOLAR_TIME.toLocalDate(), TargetType.SUNSET);

            assertThat(slot.tide().tideLevel()).isNull();
            assertThat(slot.tide().tideDirection()).isNull();
            assertThat(slot.tide().tideHeight()).isNull();
            assertThat(slot.tide().tideShortfall()).isNull();
            assertThat(slot.tide().tideFitPhrase()).isNull();
        }

        @Test
        @DisplayName("aligned at a low water landing exactly on the light: level 0.0, direction "
                + "RISING, height and fit phrase from the same curve the Plan tab draws")
        void alignedOnTheLight_levelDirectionHeightAndPhrase() {
            // The solar event lands exactly on the 09:00 LOW: level is the series' own minimum
            // (0.0), the height is that extreme's own stored height (1.0 m) with no
            // interpolation error, and the next extreme after it is the 15:00 HIGH, so direction
            // is RISING — the water has just turned.
            BriefingSlot slot = build(coastalLoc(Set.of(TideType.LOW)), TideState.LOW, true);

            assertThat(slot.tide().tideLevel()).isEqualTo(0.0);
            assertThat(slot.tide().tideDirection()).isEqualTo("RISING");
            assertThat(slot.tide().tideHeight()).isEqualTo("1.0 m");
            assertThat(slot.tide().tideShortfall())
                    .as("aligned: no shortfall to report").isNull();
            assertThat(slot.tide().tideFitPhrase())
                    .as("the match form: state+direction, the nearest-extreme phrase (LW exactly "
                            + "on the light — the offset rounds to \"at sunset\"), then the height")
                    .isEqualTo("low water, rising · LW 09:00 · at sunset · 1.0 m");
        }

        @Test
        @DisplayName("a miss below every wanted band: shortfall HIGHER, and the fit phrase "
                + "names the wanted water, the light's own clock, and the day's high")
        void missBelowEveryWantedBand_shortfallHigherAndPhrase() {
            // Wants HIGH; the light lands on a LOW. Every wanted state (just HIGH) outranks the
            // served state, so the location wants MORE water than it is getting.
            BriefingSlot slot = build(coastalLoc(Set.of(TideType.HIGH)), TideState.LOW, false);

            assertThat(slot.tide().tideShortfall()).isEqualTo("HIGHER");
            assertThat(slot.tide().tideLevel()).isEqualTo(0.0);
            assertThat(slot.tide().tideHeight()).isEqualTo("1.0 m");
            assertThat(slot.tide().tideFitPhrase())
                    .isEqualTo("wants high water · low water, rising at 09:00 · 1.0 m of 4.0 m");
        }

        @Test
        @DisplayName("C0: tideAlignmentQuality reaches the served slot — 1.0 on an aligned light "
                + "landing exactly on its extreme, null on a miss")
        void tideAlignmentQuality_reachesTheServedSlot() {
            // Same fixture as alignedOnTheLight_levelDirectionHeightAndPhrase: the light lands
            // exactly on the 09:00 LOW, so the offset from the light to that extreme is zero and
            // the quality is the tight window's own maximum, 1.0 — real TideFactDeriver wiring,
            // not a re-derivation of the C0 unit tests' arithmetic.
            BriefingSlot aligned = build(coastalLoc(Set.of(TideType.LOW)), TideState.LOW, true);
            assertThat(aligned.tide().tideAlignmentQuality()).isEqualTo(1.0);

            BriefingSlot missed = build(coastalLoc(Set.of(TideType.HIGH)), TideState.LOW, false);
            assertThat(missed.tide().tideAlignmentQuality())
                    .as("no alignment, no quality to report").isNull();
        }

        @Test
        @DisplayName("a miss above every wanted band: shortfall LOWER")
        void missAboveEveryWantedBand_shortfallLower() {
            // Wants LOW; served HIGH is above the top of a low-only preference. Re-derive off a
            // different solar time so the light lands on the 03:00 HIGH instead of the LOW.
            LocationEntity loc = coastalLoc(Set.of(TideType.LOW));
            LocalDateTime onTheHigh = LocalDateTime.of(2026, 1, 27, 3, 0);
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(onTheHigh);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(onTheHigh), eq(30L), eq(90L)))
                    .thenReturn(Optional.of(dual(
                            new TideData(TideState.HIGH, false, null, null, null, null, null, null))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);
            when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                    eq(loc.getId()), eq(onTheHigh.minusDays(2)), eq(onTheHigh.plusDays(2))))
                    .thenReturn(symmetricDay());

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse()),
                    onTheHigh.toLocalDate(), TargetType.SUNSET);

            assertThat(slot.tide().tideShortfall()).isEqualTo("LOWER");
        }

        @Test
        @DisplayName("a wanted set straddling the state reports no shortfall — one arrow cannot "
                + "say both 'higher' and 'lower'")
        void straddlingWantedSet_noShortfall() {
            // Wants HIGH or LOW; served MID sits between both — HIGH outranks MID but LOW does
            // not, so neither "every wanted state is higher" nor "every wanted state is lower"
            // holds, and the chip must draw the plain miss wave rather than guess a direction.
            BriefingSlot slot =
                    build(coastalLoc(Set.of(TideType.HIGH, TideType.LOW)), TideState.MID, false);

            assertThat(slot.tide().tideShortfall()).isNull();
            assertThat(slot.tide().tideFitPhrase())
                    .as("the phrase still names both wanted states, in gate-phrase order, even "
                            + "with no single arrow to draw")
                    .isEqualTo("wants high water or low water · mid tide, rising at 09:00 "
                            + "· 1.0 m of 4.0 m");
        }

        @Test
        @DisplayName("extremes exist in the wider fetch window but none fall close enough to the "
                + "light to draw a shape from: fields fail-soft to null rather than throwing")
        void extremesOutsideTheDrawableWindow_fieldsFailSoftToNull() {
            // TideCurveCalculator.seriesAround narrows to [localDate-1, localDate+1]; a row two
            // days before SOLAR_TIME's date is inside the +/-2-day repository fetch but outside
            // that narrower window, so the fetch returns a non-empty list yet the drawable series
            // is empty. Skipping the second guard throws IndexOutOfBoundsException out of the
            // whole briefing build instead of degrading this one slot.
            LocationEntity loc = coastalLoc(Set.of(TideType.HIGH));
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), eq(30L), eq(90L)))
                    .thenReturn(Optional.of(dual(
                            new TideData(TideState.LOW, false, null, null, null, null, null, null))));
            when(tideService.calculateTideAligned(any(), any())).thenReturn(false);
            List<TideExtremeEntity> tooFarOut = List.of(
                    extreme(TideExtremeType.HIGH, SOLAR_TIME.minusDays(2).plusHours(1), 4.0));
            when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                    eq(loc.getId()), eq(SOLAR_TIME.minusDays(2)), eq(SOLAR_TIME.plusDays(2))))
                    .thenReturn(tooFarOut);

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse()),
                    SOLAR_TIME.toLocalDate(), TargetType.SUNSET);

            assertThat(slot).as("must not throw").isNotNull();
            assertThat(slot.tide().tideLevel()).isNull();
            assertThat(slot.tide().tideDirection()).isNull();
            assertThat(slot.tide().tideHeight()).isNull();
            assertThat(slot.tide().tideFitPhrase()).isNull();
        }

        @Test
        @DisplayName("the tide-fit fields track the TIGHT alignment, never the widened one — a "
                + "future accidental swap here would make the chip's match tier looser than the "
                + "gate (task 5's confirmation)")
        void tideFitFieldsTrackTightAlignmentNotWidened() {
            LocationEntity loc = coastalLoc(Set.of(TideType.HIGH));
            stubSolarWindow();
            when(solarService.sunsetUtc(eq(loc.getLat()), eq(loc.getLon()), any()))
                    .thenReturn(SOLAR_TIME);
            when(locationService.isCoastal(loc)).thenReturn(true);
            // Distinct TideData objects (nearMidPoint differs) so eq() can tell tight from
            // widened apart — a real record equal to itself would let Mockito match either stub
            // for either argument, hiding exactly the bug this test exists to catch.
            TideData tight = new TideData(TideState.LOW, false, null, null, null, null, null, null);
            TideData widened = new TideData(TideState.LOW, true, null, null, null, null, null, null);
            when(tideService.deriveDualWindowTideData(
                    eq(loc.getId()), eq(SOLAR_TIME), eq(30L), eq(90L)))
                    .thenReturn(Optional.of(new TideService.DualWindowTideData(tight, widened)));
            // Tight (the gate's own input) says not aligned; widened (the scoring-only 3* band,
            // never served on the briefing path) says aligned. The wanted set is pinned to the
            // location's own — any() here would let this pass even if calculateTideData started
            // passing the wrong (or an empty) preference set, which is exactly the wiring this
            // test exists to protect.
            when(tideService.calculateTideAligned(eq(tight), eq(loc.getTideType()))).thenReturn(false);
            when(tideService.calculateTideAligned(eq(widened), eq(loc.getTideType()))).thenReturn(true);
            when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                    eq(loc.getId()), eq(SOLAR_TIME.minusDays(2)), eq(SOLAR_TIME.plusDays(2))))
                    .thenReturn(symmetricDay());

            BriefingSlot slot = slotBuilder.buildSlot(
                    new BriefingSlotBuilder.LocationWeather(loc, buildForecastResponse()),
                    SOLAR_TIME.toLocalDate(), TargetType.SUNSET);

            assertThat(slot.tide().tideAligned())
                    .as("the served alignment is the tight one").isFalse();
            assertThat(slot.tide().tideShortfall())
                    .as("a miss — reading widened here would report no shortfall at all")
                    .isEqualTo("HIGHER");
            assertThat(slot.tide().tideFitPhrase())
                    .as("the miss form — reading widened here would print the match form instead")
                    .startsWith("wants high water ·");
        }

        @Test
        @DisplayName("the miss phrase never repeats the nearest-extreme offset — the fit block's "
                + "own no-fact-twice rule, independent of the (now-retired) tide gate")
        void miss_offsetClauseNotRepeatedInFitPhrase() {
            // ⚠️ Before the tide gate lift (2026-09-18, docs/engineering/tide-window-plan.md §6
            // Q1) this same fixture also carried an `evaluationGate` sentence whose own third
            // clause was this offset — the miss form's job was not to repeat what the gate row
            // already said. The gate is gone (`slot.evaluationGate()` is null here now), but the
            // rule the miss form itself follows stands on its own: it states the light's own
            // clock time instead of the extreme's offset either way.
            BriefingSlot slot = build(coastalLoc(Set.of(TideType.HIGH)), TideState.LOW, false);

            assertThat(slot.evaluationGate())
                    .as("no hard constraint remains — BriefingGatingPolicy.HARD_CONSTRAINT_REASONS"
                            + " is empty since the tide gate lift").isNull();
            String offsetClause = slot.tide().nearestSolarOffsetPhrase();
            assertThat(offsetClause).as("a nearest extreme exists to be duplicated").isNotBlank();
            assertThat(slot.tide().tideFitPhrase())
                    .as("the fit phrase's miss form states its own clock, not the offset clause")
                    .doesNotContain(offsetClause);
        }
    }
}
