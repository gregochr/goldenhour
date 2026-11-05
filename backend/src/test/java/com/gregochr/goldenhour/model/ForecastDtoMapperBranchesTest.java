package com.gregochr.goldenhour.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.LunarPhaseService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.SolarService.SolarWindow;
import com.gregochr.solarutils.LunarCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The gates and fallbacks of {@link ForecastDtoMapper}: which slots may carry a coastal sea-state, how the bulk
 * pre-loads cope with undated rows and incomplete wave samples, what happens when the solar maths fails, and which
 * bluebell conditions suppress the bluebell fields.
 */
@ExtendWith(MockitoExtension.class)
class ForecastDtoMapperBranchesTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @Mock
    private SolarService solarService;
    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private MarineWaveRepository marineWaveRepository;
    @Mock
    private EvaluationViewService evaluationViewService;

    private ForecastDtoMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ForecastDtoMapper(new LunarPhaseService(new LunarCalculator()), solarService,
                new SeasonalWindow(MonthDay.of(4, 18), MonthDay.of(5, 18), "BLUEBELL"),
                forecastScoreRepository, marineWaveRepository, evaluationViewService);
    }

    private static LocationEntity coastal(Long id) {
        return LocationEntity.builder().id(id).name("Bamburgh").lat(55.6).lon(-1.7)
                .tideType(Set.of(TideType.HIGH)).build();
    }

    private static ForecastEvaluationEntity entity(LocationEntity location, LocalDate date, TargetType type) {
        ForecastEvaluationEntity e = new ForecastEvaluationEntity();
        e.setLocation(location);
        e.setTargetDate(date);
        e.setTargetType(type);
        return e;
    }

    private static MarineWaveEntity wave(LocationEntity location, LocalDate date, Double hs) {
        MarineWaveEntity w = new MarineWaveEntity();
        w.setLocation(location);
        w.setEvaluationDate(date);
        w.setEventType(TargetType.SUNSET);
        w.setSignificantWaveHeightMetres(hs);
        return w;
    }

    // ── which slots may carry a sea-state ────────────────────────────────────

    @Test
    @DisplayName("a row that cannot be a coastal solar slot never queries the wave table and has no sea-state")
    void toDto_nonCoastalSlots_haveNoSeaStateAndNoWaveQuery() {
        LocationEntity noTideSet = LocationEntity.builder().id(7L).name("Hill").lat(54.0).lon(-2.0)
                .tideType(Set.of()).build();
        List<ForecastEvaluationEntity> slots = List.of(
                entity(null, DATE, TargetType.SUNSET),
                entity(coastal(null), DATE, TargetType.SUNSET),
                entity(coastal(7L), null, TargetType.SUNSET),
                entity(coastal(7L), DATE, null),
                entity(coastal(7L), DATE, TargetType.HOURLY),
                entity(noTideSet, DATE, TargetType.SUNSET));
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());

        for (ForecastEvaluationEntity slot : slots) {
            ForecastEvaluationDto dto = mapper.toDto(slot, false);
            assertThat(dto.seaState()).isNull();
            assertThat(dto.significantWaveHeightMetres()).isNull();
        }

        verifyNoInteractions(marineWaveRepository);
    }

    @Test
    @DisplayName("a wave sample with no height resolves to no sea-state rather than a zero-height band")
    void toDto_waveSampleWithoutHeight_hasNoSeaState() {
        LocationEntity bamburgh = coastal(7L);
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(7L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.of(wave(bamburgh, DATE, null)));

        ForecastEvaluationDto dto = mapper.toDto(entity(bamburgh, DATE, TargetType.SUNSET), false);

        assertThat(dto.seaState()).isNull();
        assertThat(dto.significantWaveHeightMetres()).isNull();
    }

    // ── bulk pre-loads ───────────────────────────────────────────────────────

    @Test
    @DisplayName("toDtoList() with only undated rows queries neither the wave table nor the stability skips")
    void toDtoList_allUndated_skipsPreloads() {
        ForecastEvaluationEntity undated = entity(coastal(7L), null, TargetType.SUNSET);

        List<ForecastEvaluationDto> dtos = mapper.toDtoList(List.of(undated), false);

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).seaState()).isNull();
        verifyNoInteractions(marineWaveRepository);
        verify(evaluationViewService, never()).loadStabilitySkips(eq(DATE), eq(DATE));
    }

    @Test
    @DisplayName("toDtoList() loads one wave and skip window spanning the earliest to the latest dated row")
    void toDtoList_mixedDates_loadsOneSpanningWindow() {
        LocalDate early = DATE.minusDays(2);
        LocalDate late = DATE.plusDays(3);
        LocationEntity bamburgh = coastal(7L);
        List<ForecastEvaluationEntity> rows = List.of(
                entity(bamburgh, DATE, TargetType.SUNSET),
                entity(bamburgh, null, TargetType.SUNSET),
                entity(bamburgh, late, TargetType.SUNSET),
                entity(bamburgh, early, TargetType.SUNSET),
                entity(bamburgh, DATE.plusDays(1), TargetType.SUNSET));
        when(marineWaveRepository.findByEvaluationDateBetween(early, late)).thenReturn(List.of());
        when(evaluationViewService.loadStabilitySkips(early, late)).thenReturn(Map.of());

        List<ForecastEvaluationDto> dtos = mapper.toDtoList(rows, false);

        assertThat(dtos).hasSize(5);
        verify(marineWaveRepository).findByEvaluationDateBetween(early, late);
        verify(evaluationViewService).loadStabilitySkips(early, late);
    }

    @Test
    @DisplayName("bulk wave pre-load ignores samples without a height, a location or a location id")
    void toDtoList_incompleteWaveSamples_areIgnored() {
        LocationEntity bamburgh = coastal(7L);
        LocationEntity craster = coastal(8L);
        List<MarineWaveEntity> samples = new ArrayList<>();
        samples.add(wave(bamburgh, DATE, null));
        samples.add(wave(null, DATE, 3.0));
        samples.add(wave(coastal(null), DATE, 3.0));
        samples.add(wave(craster, DATE, 1.1));
        when(marineWaveRepository.findByEvaluationDateBetween(DATE, DATE)).thenReturn(samples);
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());

        List<ForecastEvaluationDto> dtos = mapper.toDtoList(List.of(
                entity(bamburgh, DATE, TargetType.SUNSET), entity(craster, DATE, TargetType.SUNSET)), false);

        assertThat(dtos.get(0).significantWaveHeightMetres()).isNull();
        assertThat(dtos.get(1).significantWaveHeightMetres()).isEqualTo(1.1);
        assertThat(dtos.get(1).seaState()).isEqualTo("slight");
    }

    // ── solar maths ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the golden and blue hour window is skipped without a date, a solar type or coordinates")
    void toDto_goldenWindow_skippedWhenSlotIncomplete() {
        ForecastEvaluationEntity noDate = entity(coastal(7L), null, TargetType.SUNRISE);
        noDate.setLocationLat(BigDecimal.valueOf(55.6));
        noDate.setLocationLon(BigDecimal.valueOf(-1.7));
        ForecastEvaluationEntity noType = entity(coastal(7L), DATE, null);
        noType.setLocationLat(BigDecimal.valueOf(55.6));
        noType.setLocationLon(BigDecimal.valueOf(-1.7));
        ForecastEvaluationEntity hourly = entity(coastal(7L), DATE, TargetType.HOURLY);
        hourly.setLocationLat(BigDecimal.valueOf(55.6));
        hourly.setLocationLon(BigDecimal.valueOf(-1.7));
        ForecastEvaluationEntity noLat = entity(coastal(7L), DATE, TargetType.SUNRISE);
        noLat.setLocationLon(BigDecimal.valueOf(-1.7));
        ForecastEvaluationEntity noLon = entity(coastal(7L), DATE, TargetType.SUNRISE);
        noLon.setLocationLat(BigDecimal.valueOf(55.6));
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(7L), eq(DATE), eq(TargetType.SUNRISE))).thenReturn(Optional.empty());

        for (ForecastEvaluationEntity slot : List.of(noDate, noType, hourly, noLat, noLon)) {
            ForecastEvaluationDto dto = mapper.toDto(slot, false);
            assertThat(dto.goldenHourStart()).isNull();
            assertThat(dto.blueHourEnd()).isNull();
        }

        verifyNoInteractions(solarService);
    }

    @Test
    @DisplayName("a failure in the solar window maths leaves the window null instead of failing the row")
    void toDto_goldenWindowFailure_isSwallowed() {
        ForecastEvaluationEntity slot = entity(LocationEntity.builder().id(3L).name("Arctic").lat(78.0).lon(15.0)
                .build(), DATE, TargetType.SUNRISE);
        slot.setLocationLat(BigDecimal.valueOf(78.0));
        slot.setLocationLon(BigDecimal.valueOf(15.0));
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());
        when(solarService.goldenBlueWindow(78.0, 15.0, DATE, true))
                .thenThrow(new IllegalStateException("polar day"));

        ForecastEvaluationDto dto = mapper.toDto(slot, false);

        assertThat(dto.goldenHourStart()).isNull();
        assertThat(dto.goldenHourEnd()).isNull();
        assertThat(dto.blueHourStart()).isNull();
        assertThat(dto.blueHourEnd()).isNull();
    }

    @Test
    @DisplayName("a sunset slot takes the sunset flag and returns the window the solar service computed")
    void toDto_sunsetSlot_usesSunsetWindow() {
        ForecastEvaluationEntity slot = entity(coastal(7L), DATE, TargetType.SUNSET);
        slot.setLocationLat(BigDecimal.valueOf(55.6));
        slot.setLocationLon(BigDecimal.valueOf(-1.7));
        LocalDateTime goldenStart = LocalDateTime.of(2026, 6, 17, 20, 30);
        LocalDateTime goldenEnd = LocalDateTime.of(2026, 6, 17, 21, 30);
        LocalDateTime blueStart = LocalDateTime.of(2026, 6, 17, 21, 30);
        LocalDateTime blueEnd = LocalDateTime.of(2026, 6, 17, 22, 10);
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(7L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.empty());
        when(solarService.goldenBlueWindow(55.6, -1.7, DATE, false))
                .thenReturn(new SolarWindow(blueStart, blueEnd, goldenStart, goldenEnd));

        ForecastEvaluationDto dto = mapper.toDto(slot, false);

        assertThat(dto.goldenHourStart()).isEqualTo(goldenStart);
        assertThat(dto.goldenHourEnd()).isEqualTo(goldenEnd);
        assertThat(dto.blueHourStart()).isEqualTo(blueStart);
        assertThat(dto.blueHourEnd()).isEqualTo(blueEnd);
    }

    // ── sparse list DTO ──────────────────────────────────────────────────────

    private static LocationEvaluationView view(LocalDate date, TargetType type, Instant evaluatedAt) {
        return new LocationEvaluationView(7L, "Bamburgh", 3L, "North East", date, type,
                LocationEvaluationView.Source.CACHED_EVALUATION, 4, "Clear.", 81, 66, null, "msg", "SONNET",
                evaluatedAt, DisplayVerdict.WORTH_IT, null, null, null, null);
    }

    @Test
    @DisplayName("toSparseListDto() derives sunrise time and azimuth for a sunrise view, and stamps the run time")
    void toSparseListDto_sunrise_usesSunriseMaths() {
        LocationEntity bamburgh = coastal(7L);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 17, 3, 55);
        when(solarService.sunriseUtc(55.6, -1.7, DATE)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(55.6, -1.7, DATE)).thenReturn(48);

        ForecastListDto dto = mapper.toSparseListDto(
                view(DATE, TargetType.SUNRISE, Instant.parse("2026-06-16T21:00:00Z")), bamburgh, false);

        assertThat(dto.solarEventTime()).isEqualTo(sunrise);
        assertThat(dto.azimuthDeg()).isEqualTo(48);
        assertThat(dto.forecastRunAt()).isEqualTo(LocalDateTime.of(2026, 6, 16, 21, 0));
    }

    @Test
    @DisplayName("toSparseListDto() derives sunset time and azimuth for a sunset view, with no run time when unstamped")
    void toSparseListDto_sunset_usesSunsetMaths() {
        LocationEntity bamburgh = coastal(7L);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 17, 20, 50);
        when(solarService.sunsetUtc(55.6, -1.7, DATE)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(55.6, -1.7, DATE)).thenReturn(311);

        ForecastListDto dto = mapper.toSparseListDto(view(DATE, TargetType.SUNSET, null), bamburgh, false);

        assertThat(dto.solarEventTime()).isEqualTo(sunset);
        assertThat(dto.azimuthDeg()).isEqualTo(311);
        assertThat(dto.forecastRunAt()).isNull();
    }

    @Test
    @DisplayName("toSparseListDto() computes no solar times for an hourly, untyped or undated view")
    void toSparseListDto_nonSolarViews_haveNoSolarTimes() {
        LocationEntity bamburgh = coastal(7L);

        ForecastListDto hourly = mapper.toSparseListDto(view(DATE, TargetType.HOURLY, null), bamburgh, false);
        ForecastListDto untyped = mapper.toSparseListDto(view(DATE, null, null), bamburgh, false);
        ForecastListDto undated = mapper.toSparseListDto(view(null, TargetType.SUNSET, null), bamburgh, false);

        for (ForecastListDto dto : List.of(hourly, untyped, undated)) {
            assertThat(dto.solarEventTime()).isNull();
            assertThat(dto.azimuthDeg()).isNull();
        }
        verifyNoInteractions(solarService);
    }

    @Test
    @DisplayName("toSparseListDto() survives a solar-maths failure and returns a row without solar times")
    void toSparseListDto_solarFailure_isSwallowed() {
        LocationEntity bamburgh = coastal(7L);
        when(solarService.sunsetUtc(55.6, -1.7, DATE)).thenThrow(new IllegalStateException("no sunset"));

        ForecastListDto dto = mapper.toSparseListDto(view(DATE, TargetType.SUNSET, null), bamburgh, false);

        assertThat(dto.solarEventTime()).isNull();
        assertThat(dto.azimuthDeg()).isNull();
        assertThat(dto.locationName()).isEqualTo("Bamburgh");
    }

    // ── bluebell gating ──────────────────────────────────────────────────────

    private static LocationEntity bluebell(Long id, BluebellExposure exposure) {
        return LocationEntity.builder().id(id).name("Bluebell Wood").lat(54.5).lon(-3.0)
                .locationType(Set.of(LocationType.BLUEBELL)).bluebellExposure(exposure).build();
    }

    @Test
    @DisplayName("no bluebell lookup happens for a missing location, a non-bluebell site, a missing date or an "
            + "out-of-season date")
    void toDto_bluebellGate_closedForEachDisqualifier() {
        LocalDate inSeason = LocalDate.of(2026, 5, 1);
        LocationEntity noTypes = LocationEntity.builder().id(9L).name("Plain").lat(54.5).lon(-3.0).build();
        LocationEntity notBluebell = LocationEntity.builder().id(9L).name("Other").lat(54.5).lon(-3.0)
                .locationType(Set.of(LocationType.LANDSCAPE)).build();
        List<ForecastEvaluationEntity> rows = List.of(
                entity(null, inSeason, TargetType.SUNRISE),
                entity(noTypes, inSeason, TargetType.SUNRISE),
                entity(notBluebell, inSeason, TargetType.SUNRISE),
                entity(bluebell(9L, BluebellExposure.WOODLAND), null, TargetType.SUNRISE),
                entity(bluebell(9L, BluebellExposure.WOODLAND), DATE, TargetType.SUNRISE));
        when(evaluationViewService.loadStabilitySkips(inSeason, inSeason)).thenReturn(Map.of());
        when(evaluationViewService.loadStabilitySkips(DATE, DATE)).thenReturn(Map.of());

        for (ForecastEvaluationEntity row : rows) {
            ForecastEvaluationDto dto = mapper.toDto(row, false);
            assertThat(dto.bluebellScore()).isNull();
            assertThat(dto.bluebellExposure()).isNull();
        }

        verifyNoInteractions(forecastScoreRepository);
    }

    @Test
    @DisplayName("an in-season bluebell site with no id or no event type skips the score lookup but keeps its exposure")
    void toDto_bluebellWithoutIdOrType_keepsExposureOnly() {
        LocalDate inSeason = LocalDate.of(2026, 5, 1);
        when(evaluationViewService.loadStabilitySkips(inSeason, inSeason)).thenReturn(Map.of());

        ForecastEvaluationDto noId = mapper.toDto(
                entity(bluebell(null, BluebellExposure.WOODLAND), inSeason, TargetType.SUNRISE), false);
        ForecastEvaluationDto noType = mapper.toDto(
                entity(bluebell(9L, BluebellExposure.WOODLAND), inSeason, null), false);

        assertThat(noId.bluebellScore()).isNull();
        assertThat(noId.bluebellExposure()).isEqualTo("WOODLAND");
        assertThat(noType.bluebellScore()).isNull();
        assertThat(noType.bluebellExposure()).isEqualTo("WOODLAND");
        verifyNoInteractions(forecastScoreRepository);
    }

    @Test
    @DisplayName("an in-season bluebell site without a recorded exposure still carries its score")
    void toDto_bluebellWithoutExposure_keepsScore() {
        LocalDate inSeason = LocalDate.of(2026, 5, 1);
        LocationEntity wood = bluebell(9L, null);
        ForecastScoreEntity row = new ForecastScoreEntity();
        row.setForecastType(ForecastType.BLUEBELL);
        row.setScore(4);
        row.setSummary("Fresh bloom.");
        row.setEvaluatedAt(Instant.parse("2026-04-30T18:00:00Z"));
        when(evaluationViewService.loadStabilitySkips(inSeason, inSeason)).thenReturn(Map.of());
        when(forecastScoreRepository.findComponent(
                eq(ForecastType.BLUEBELL), eq(9L), eq(inSeason), eq(TargetType.SUNRISE)))
                .thenReturn(Optional.of(row));

        ForecastEvaluationDto dto = mapper.toDto(entity(wood, inSeason, TargetType.SUNRISE), false);

        assertThat(dto.bluebellScore()).isEqualTo(4);
        assertThat(dto.bluebellSummary()).isEqualTo("Fresh bloom.");
        assertThat(dto.bluebellExposure()).isNull();
    }

    @Test
    @DisplayName("a stability skip for a different slot does not retract this slot's bluebell score")
    void toDto_stabilitySkipForOtherSlot_doesNotRetract() {
        LocalDate inSeason = LocalDate.of(2026, 5, 1);
        LocationEntity wood = bluebell(9L, BluebellExposure.WOODLAND);
        ForecastScoreEntity row = new ForecastScoreEntity();
        row.setForecastType(ForecastType.BLUEBELL);
        row.setScore(3);
        row.setSummary("Quiet.");
        row.setEvaluatedAt(Instant.parse("2026-04-30T18:00:00Z"));
        when(forecastScoreRepository.findComponent(
                eq(ForecastType.BLUEBELL), eq(9L), eq(inSeason), eq(TargetType.SUNRISE)))
                .thenReturn(Optional.of(row));
        String otherSlot = EvaluationViewService.stabilitySkipKey("Elsewhere", inSeason, TargetType.SUNRISE);
        when(evaluationViewService.loadStabilitySkips(inSeason, inSeason))
                .thenReturn(Map.of(otherSlot, Instant.parse("2026-04-30T19:00:00Z")));

        ForecastEvaluationDto dto = mapper.toDto(entity(wood, inSeason, TargetType.SUNRISE), false);

        assertThat(dto.bluebellScore()).isEqualTo(3);
    }
}
