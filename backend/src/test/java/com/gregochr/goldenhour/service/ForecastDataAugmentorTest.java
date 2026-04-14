package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideStatisticalSize;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastDataAugmentor}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDataAugmentorTest {

    @Mock
    private OpenMeteoService openMeteoService;

    @Mock
    private SolarService solarService;

    @Mock
    private TideService tideService;

    @Mock
    private LunarPhaseService lunarPhaseService;

    @InjectMocks
    private ForecastDataAugmentor augmentor;

    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 21, 20, 47);

    @BeforeEach
    void setUp() {
        // stubs added per-test to avoid lenient() and unused-stubbing violations
    }

    /** Stubs the solar window for coastal augmentWithTideData tests. */
    private void stubCoastalSolarWindow() {
        // Sunset window: golden hour 20:17-20:47, blue hour 20:47-21:17
        when(solarService.goldenBlueWindow(anyDouble(), anyDouble(), any(), anyBoolean()))
                .thenReturn(new SolarService.SolarWindow(
                        EVENT_TIME, EVENT_TIME.plusMinutes(30),
                        EVENT_TIME.minusMinutes(30), EVENT_TIME));
    }

    @Test
    @DisplayName("augmentWithDirectionalCloud() adds directional data when fetch succeeds")
    void augmentWithDirectionalCloud_success_addsDirectionalData() {
        AtmosphericData base = TestAtmosphericData.defaults();
        DirectionalCloudData directional = new DirectionalCloudData(10, 20, 30, 40, 50, 60, null);
        when(openMeteoService.fetchDirectionalCloudData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn(directional);

        AtmosphericData result = augmentor.augmentWithDirectionalCloud(
                base, 54.77, -1.57, 250, EVENT_TIME, null);

        assertThat(result.directionalCloud()).isEqualTo(directional);
    }

    @Test
    @DisplayName("augmentWithDirectionalCloud() returns original data when fetch returns null")
    void augmentWithDirectionalCloud_fetchReturnsNull_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();
        when(openMeteoService.fetchDirectionalCloudData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any()))
                .thenReturn(null);

        AtmosphericData result = augmentor.augmentWithDirectionalCloud(
                base, 54.77, -1.57, 250, EVENT_TIME, null);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithTideData() adds tide fields for coastal location")
    void augmentWithTideData_coastal_addsTideFields() {
        stubCoastalSolarWindow();
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime highTide = EVENT_TIME.plusHours(2);
        LocalDateTime lowTide = EVENT_TIME.minusHours(4);
        TideData tideData = new TideData(TideState.HIGH, false,
                highTide, new BigDecimal("4.5"), lowTide, new BigDecimal("1.2"),
                highTide, null);
        when(tideService.deriveTideData(1L, EVENT_TIME, 30L)).thenReturn(Optional.of(tideData));
        when(tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH))).thenReturn(true);
        when(lunarPhaseService.classifyTide(EVENT_TIME.toLocalDate()))
                .thenReturn(LunarTideType.SPRING_TIDE);
        when(lunarPhaseService.getMoonPhase(EVENT_TIME.toLocalDate()))
                .thenReturn("Full Moon");
        when(lunarPhaseService.isMoonAtPerigee(EVENT_TIME.toLocalDate()))
                .thenReturn(false);
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result.tide()).isNotNull();
        assertThat(result.tide().tideState()).isEqualTo(TideState.HIGH);
        assertThat(result.tide().tideAligned()).isTrue();
        assertThat(result.tide().lunarTideType()).isEqualTo(LunarTideType.SPRING_TIDE);
        assertThat(result.tide().lunarPhase()).isEqualTo("Full Moon");
        assertThat(result.tide().moonAtPerigee()).isFalse();
        assertThat(result.tide().statisticalSize()).isNull();
    }

    @Test
    @DisplayName("augmentWithTideData() sets KING_TIDE lunar type when perigee + new/full moon")
    void augmentWithTideData_kingTide_setsLunarKingTide() {
        stubCoastalSolarWindow();
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime highTide = EVENT_TIME.plusHours(2);
        LocalDateTime lowTide = EVENT_TIME.minusHours(4);
        TideData tideData = new TideData(TideState.HIGH, false,
                highTide, new BigDecimal("6.2"), lowTide, new BigDecimal("0.8"),
                highTide, null);
        when(tideService.deriveTideData(1L, EVENT_TIME, 30L)).thenReturn(Optional.of(tideData));
        when(tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH))).thenReturn(true);
        when(lunarPhaseService.classifyTide(EVENT_TIME.toLocalDate()))
                .thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(EVENT_TIME.toLocalDate()))
                .thenReturn("New Moon");
        when(lunarPhaseService.isMoonAtPerigee(EVENT_TIME.toLocalDate()))
                .thenReturn(true);
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result.tide().lunarTideType()).isEqualTo(LunarTideType.KING_TIDE);
        assertThat(result.tide().lunarPhase()).isEqualTo("New Moon");
        assertThat(result.tide().moonAtPerigee()).isTrue();
    }

    @Test
    @DisplayName("augmentWithTideData() classifies EXTRA_EXTRA_HIGH when height exceeds P95")
    void augmentWithTideData_heightAboveP95_setsExtraExtraHigh() {
        stubCoastalSolarWindow();
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime highTide = EVENT_TIME.plusHours(2);
        LocalDateTime lowTide = EVENT_TIME.minusHours(4);
        TideData tideData = new TideData(TideState.HIGH, false,
                highTide, new BigDecimal("6.50"), lowTide, new BigDecimal("0.80"),
                highTide, null);
        when(tideService.deriveTideData(1L, EVENT_TIME, 30L)).thenReturn(Optional.of(tideData));
        when(tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH))).thenReturn(true);
        when(lunarPhaseService.classifyTide(EVENT_TIME.toLocalDate()))
                .thenReturn(LunarTideType.KING_TIDE);
        when(lunarPhaseService.getMoonPhase(EVENT_TIME.toLocalDate()))
                .thenReturn("New Moon");
        when(lunarPhaseService.isMoonAtPerigee(EVENT_TIME.toLocalDate()))
                .thenReturn(true);
        TideStats stats = new TideStats(
                new BigDecimal("4.50"), new BigDecimal("6.00"),
                new BigDecimal("1.20"), new BigDecimal("0.50"),
                200, new BigDecimal("3.30"),
                new BigDecimal("5.20"), new BigDecimal("5.80"),
                new BigDecimal("6.00"),
                15, new BigDecimal("0.08"),
                new BigDecimal("5.63"), new BigDecimal("6.00"), 5);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result.tide().statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_EXTRA_HIGH);
    }

    @Test
    @DisplayName("augmentWithTideData() classifies EXTRA_HIGH when height exceeds spring threshold but not P95")
    void augmentWithTideData_heightAboveSpringNotP95_setsExtraHigh() {
        stubCoastalSolarWindow();
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime highTide = EVENT_TIME.plusHours(2);
        LocalDateTime lowTide = EVENT_TIME.minusHours(4);
        TideData tideData = new TideData(TideState.HIGH, false,
                highTide, new BigDecimal("5.80"), lowTide, new BigDecimal("0.80"),
                highTide, null);
        when(tideService.deriveTideData(1L, EVENT_TIME, 30L)).thenReturn(Optional.of(tideData));
        when(tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH))).thenReturn(true);
        when(lunarPhaseService.classifyTide(EVENT_TIME.toLocalDate()))
                .thenReturn(LunarTideType.SPRING_TIDE);
        when(lunarPhaseService.getMoonPhase(EVENT_TIME.toLocalDate()))
                .thenReturn("Full Moon");
        when(lunarPhaseService.isMoonAtPerigee(EVENT_TIME.toLocalDate()))
                .thenReturn(false);
        TideStats stats = new TideStats(
                new BigDecimal("4.50"), new BigDecimal("6.20"),
                new BigDecimal("1.20"), new BigDecimal("0.50"),
                200, new BigDecimal("3.30"),
                new BigDecimal("5.20"), new BigDecimal("5.80"),
                new BigDecimal("6.00"),
                15, new BigDecimal("0.08"),
                new BigDecimal("5.63"), new BigDecimal("6.00"), 5);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result.tide().statisticalSize()).isEqualTo(TideStatisticalSize.EXTRA_HIGH);
    }

    @Test
    @DisplayName("augmentWithTideData() returns null statistical size when height is below thresholds")
    void augmentWithTideData_heightBelowThresholds_nullStatisticalSize() {
        stubCoastalSolarWindow();
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime highTide = EVENT_TIME.plusHours(2);
        LocalDateTime lowTide = EVENT_TIME.minusHours(4);
        TideData tideData = new TideData(TideState.HIGH, false,
                highTide, new BigDecimal("4.00"), lowTide, new BigDecimal("1.20"),
                highTide, null);
        when(tideService.deriveTideData(1L, EVENT_TIME, 30L)).thenReturn(Optional.of(tideData));
        when(tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH))).thenReturn(true);
        when(lunarPhaseService.classifyTide(EVENT_TIME.toLocalDate()))
                .thenReturn(LunarTideType.REGULAR_TIDE);
        when(lunarPhaseService.getMoonPhase(EVENT_TIME.toLocalDate()))
                .thenReturn("Waxing Crescent");
        when(lunarPhaseService.isMoonAtPerigee(EVENT_TIME.toLocalDate()))
                .thenReturn(false);
        TideStats stats = new TideStats(
                new BigDecimal("4.50"), new BigDecimal("6.20"),
                new BigDecimal("1.20"), new BigDecimal("0.50"),
                200, new BigDecimal("3.30"),
                new BigDecimal("5.20"), new BigDecimal("5.80"),
                new BigDecimal("6.00"),
                15, new BigDecimal("0.08"),
                new BigDecimal("5.63"), new BigDecimal("6.00"), 5);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result.tide().statisticalSize()).isNull();
        assertThat(result.tide().lunarTideType()).isEqualTo(LunarTideType.REGULAR_TIDE);
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data for inland location (empty tide types)")
    void augmentWithTideData_inland_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result).isSameAs(base);
        verifyNoInteractions(tideService, solarService);
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data when tide types is null")
    void augmentWithTideData_nullTideTypes_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, null,
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result).isSameAs(base);
        verifyNoInteractions(tideService, solarService);
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data when location ID is null")
    void augmentWithTideData_nullLocationId_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithTideData(
                base, null, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result).isSameAs(base);
        verifyNoInteractions(tideService, solarService);
    }

    @Test
    @DisplayName("augmentWithTideData() returns original data when no tide extremes found")
    void augmentWithTideData_noTideData_returnsOriginal() {
        stubCoastalSolarWindow();
        AtmosphericData base = TestAtmosphericData.defaults();
        when(tideService.deriveTideData(1L, EVENT_TIME, 30L)).thenReturn(Optional.empty());

        AtmosphericData result = augmentor.augmentWithTideData(
                base, 1L, EVENT_TIME, Set.of(TideType.HIGH),
                55.0, -1.5, TargetType.SUNSET);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithCloudApproach() adds cloud approach data when fetch succeeds")
    void augmentWithCloudApproach_success_addsCloudApproachData() {
        AtmosphericData base = TestAtmosphericData.defaults();
        CloudApproachData approach = new CloudApproachData(
                new SolarCloudTrend(List.of(
                        new SolarCloudTrend.SolarCloudSlot(3, 5),
                        new SolarCloudTrend.SolarCloudSlot(0, 30))),
                null);
        when(openMeteoService.fetchCloudApproachData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any(), anyInt(),
                anyDouble(), any()))
                .thenReturn(approach);

        AtmosphericData result = augmentor.augmentWithCloudApproach(
                base, 54.77, -1.57, 250, EVENT_TIME,
                EVENT_TIME.minusHours(4), null);

        assertThat(result.cloudApproach()).isEqualTo(approach);
    }

    @Test
    @DisplayName("augmentWithCloudApproach() returns original data when fetch returns null")
    void augmentWithCloudApproach_fetchReturnsNull_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();
        when(openMeteoService.fetchCloudApproachData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any(), anyInt(),
                anyDouble(), any()))
                .thenReturn(null);

        AtmosphericData result = augmentor.augmentWithCloudApproach(
                base, 54.77, -1.57, 250, EVENT_TIME,
                EVENT_TIME.minusHours(4), null);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithLocationOrientation() sets sunrise-optimised for SUNRISE-only location")
    void augmentWithLocationOrientation_sunriseOnly_setsSunriseOptimised() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithLocationOrientation(
                base, Set.of(SolarEventType.SUNRISE));

        assertThat(result.locationOrientation()).isEqualTo("sunrise-optimised");
    }

    @Test
    @DisplayName("augmentWithLocationOrientation() sets sunset-optimised for SUNSET-only location")
    void augmentWithLocationOrientation_sunsetOnly_setsSunsetOptimised() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithLocationOrientation(
                base, Set.of(SolarEventType.SUNSET));

        assertThat(result.locationOrientation()).isEqualTo("sunset-optimised");
    }

    @Test
    @DisplayName("augmentWithLocationOrientation() returns original for null solarEventTypes")
    void augmentWithLocationOrientation_null_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithLocationOrientation(base, null);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithLocationOrientation() returns original for empty solarEventTypes")
    void augmentWithLocationOrientation_empty_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithLocationOrientation(base, Set.of());

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithLocationOrientation() returns original for ALLDAY")
    void augmentWithLocationOrientation_allday_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithLocationOrientation(
                base, Set.of(SolarEventType.ALLDAY));

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithLocationOrientation() returns original for multi-value set")
    void augmentWithLocationOrientation_multiValue_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithLocationOrientation(
                base, Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET));

        assertThat(result).isSameAs(base);
    }

    // ── Cloud Inversion Tests ───────────────────────────────────────────────

    @Test
    @DisplayName("augmentWithInversionScore() adds score for elevated water-overlook location")
    void augmentWithInversionScore_eligibleLocation_addsScore() {
        AtmosphericData base = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .humidity(95)
                .lowCloud(30)
                .build();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 450, true);

        assertThat(result.inversionScore()).isNotNull();
        assertThat(result.inversionScore()).isGreaterThanOrEqualTo(7.0);
    }

    @Test
    @DisplayName("augmentWithInversionScore() returns original for low elevation")
    void augmentWithInversionScore_lowElevation_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .build();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 199, true);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithInversionScore() returns original when not overlooking water")
    void augmentWithInversionScore_noWater_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .build();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 500, false);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithInversionScore() returns original for null elevation")
    void augmentWithInversionScore_nullElevation_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, null, true);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithInversionScore() returns original when calculator returns null")
    void augmentWithInversionScore_calculatorReturnsNull_returnsOriginal() {
        // No temperature/dew point → calculator returns null
        AtmosphericData base = TestAtmosphericData.defaults();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 400, true);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithInversionScore() works at exact minimum elevation threshold (200m)")
    void augmentWithInversionScore_exactMinElevation_addsScore() {
        AtmosphericData base = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .humidity(90)
                .lowCloud(25)
                .build();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 200, true);

        assertThat(result.inversionScore()).isNotNull();
    }

    @Test
    @DisplayName("augmentWithInversionScore() at threshold elevation but no water returns original")
    void augmentWithInversionScore_thresholdElevationNoWater_returnsOriginal() {
        AtmosphericData base = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .humidity(90)
                .lowCloud(25)
                .build();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 200, false);

        assertThat(result).isSameAs(base);
    }

    @Test
    @DisplayName("augmentWithInversionScore() uses MIN_ELEVATION_METRES constant (200)")
    void augmentWithInversionScore_thresholdConsistentWithConstant() {
        assertThat(InversionScoreCalculator.MIN_ELEVATION_METRES).isEqualTo(200);
    }

    @Test
    @DisplayName("augmentWithInversionScore() at 201m above threshold adds score")
    void augmentWithInversionScore_justAboveThreshold_addsScore() {
        AtmosphericData base = TestAtmosphericData.builder()
                .temperature(6.0)
                .dewPoint(5.5)
                .humidity(90)
                .lowCloud(25)
                .build();

        AtmosphericData result = augmentor.augmentWithInversionScore(base, 201, true);

        assertThat(result.inversionScore()).isNotNull();
    }

    // --- Cache-aware routing tests ---

    @Test
    @DisplayName("augmentWithDirectionalCloud() with non-empty cache calls cache method, not API")
    void augmentWithDirectionalCloud_withCache_callsCacheMethod() {
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);
        var cloudCache = new com.gregochr.goldenhour.model.CloudPointCache(
                java.util.Map.of("test_key",
                        new com.gregochr.goldenhour.model.OpenMeteoForecastResponse()));

        when(openMeteoService.fetchDirectionalCloudDataFromCache(
                org.mockito.ArgumentMatchers.eq(54.77),
                org.mockito.ArgumentMatchers.eq(-1.58),
                org.mockito.ArgumentMatchers.eq(270),
                org.mockito.ArgumentMatchers.eq(eventTime),
                any(),
                org.mockito.ArgumentMatchers.eq(cloudCache)))
                .thenReturn(null);

        augmentor.augmentWithDirectionalCloud(base, 54.77, -1.58, 270, eventTime, null, cloudCache);

        verify(openMeteoService).fetchDirectionalCloudDataFromCache(
                org.mockito.ArgumentMatchers.eq(54.77),
                org.mockito.ArgumentMatchers.eq(-1.58),
                org.mockito.ArgumentMatchers.eq(270),
                org.mockito.ArgumentMatchers.eq(eventTime),
                any(),
                org.mockito.ArgumentMatchers.eq(cloudCache));
        verify(openMeteoService, never()).fetchDirectionalCloudData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("augmentWithDirectionalCloud() with null cache calls API method, not cache")
    void augmentWithDirectionalCloud_nullCache_callsApiMethod() {
        AtmosphericData base = TestAtmosphericData.defaults();
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);

        when(openMeteoService.fetchDirectionalCloudData(
                org.mockito.ArgumentMatchers.eq(54.77),
                org.mockito.ArgumentMatchers.eq(-1.58),
                org.mockito.ArgumentMatchers.eq(270),
                org.mockito.ArgumentMatchers.eq(eventTime),
                any(), any()))
                .thenReturn(null);

        augmentor.augmentWithDirectionalCloud(base, 54.77, -1.58, 270, eventTime, null, null);

        verify(openMeteoService).fetchDirectionalCloudData(
                org.mockito.ArgumentMatchers.eq(54.77),
                org.mockito.ArgumentMatchers.eq(-1.58),
                org.mockito.ArgumentMatchers.eq(270),
                org.mockito.ArgumentMatchers.eq(eventTime),
                any(), any());
        verify(openMeteoService, never()).fetchDirectionalCloudDataFromCache(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("augmentWithCloudApproach() with non-empty cache calls cache method, not API")
    void augmentWithCloudApproach_withCache_callsCacheMethod() {
        AtmosphericData base = TestAtmosphericData.builder()
                .windSpeed(new BigDecimal("5.0")).windDirection(180).build();
        LocalDateTime eventTime = LocalDateTime.of(2026, 6, 21, 20, 47);
        LocalDateTime currentTime = LocalDateTime.of(2026, 6, 21, 17, 0);
        var cloudCache = new com.gregochr.goldenhour.model.CloudPointCache(
                java.util.Map.of("test_key",
                        new com.gregochr.goldenhour.model.OpenMeteoForecastResponse()));

        when(openMeteoService.fetchCloudApproachDataFromCache(
                org.mockito.ArgumentMatchers.eq(54.77),
                org.mockito.ArgumentMatchers.eq(-1.58),
                org.mockito.ArgumentMatchers.eq(270),
                org.mockito.ArgumentMatchers.eq(eventTime),
                org.mockito.ArgumentMatchers.eq(currentTime),
                any(), anyInt(), anyDouble(),
                org.mockito.ArgumentMatchers.eq(cloudCache)))
                .thenReturn(null);

        augmentor.augmentWithCloudApproach(
                base, 54.77, -1.58, 270, eventTime, currentTime, null, cloudCache);

        verify(openMeteoService).fetchCloudApproachDataFromCache(
                org.mockito.ArgumentMatchers.eq(54.77),
                org.mockito.ArgumentMatchers.eq(-1.58),
                org.mockito.ArgumentMatchers.eq(270),
                org.mockito.ArgumentMatchers.eq(eventTime),
                org.mockito.ArgumentMatchers.eq(currentTime),
                any(), anyInt(), anyDouble(),
                org.mockito.ArgumentMatchers.eq(cloudCache));
        verify(openMeteoService, never()).fetchCloudApproachData(
                anyDouble(), anyDouble(), anyInt(), any(), any(), any(),
                anyInt(), anyDouble(), any());
    }
}
