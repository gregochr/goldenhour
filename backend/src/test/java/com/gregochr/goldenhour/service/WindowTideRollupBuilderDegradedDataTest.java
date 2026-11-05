package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rollup under degraded data: a day with no date beside a good one, an extreme with no stored height, no
 * historical baseline for the range comparison, and a sea-state row with no wave height. Each degrades alone; the
 * rest of the row still renders.
 */
class WindowTideRollupBuilderDegradedDataTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 27);
    private static final long ID = 1L;
    private static final double LAT = 54.49;
    private static final double LON = -0.61;
    private static final long WINDOW_MINUTES = 41;

    private LocationRepository locationRepository;
    private TideExtremeRepository tideExtremeRepository;
    private MarineWaveRepository marineWaveRepository;
    private TideService tideService;
    private SolarService solarService;
    private TideFactDeriver tideFactDeriver;
    private WindowTideRollupBuilder builder;

    @BeforeEach
    void setUp() {
        locationRepository = mock(LocationRepository.class);
        tideExtremeRepository = mock(TideExtremeRepository.class);
        marineWaveRepository = mock(MarineWaveRepository.class);
        tideService = mock(TideService.class);
        solarService = mock(SolarService.class);
        tideFactDeriver = mock(TideFactDeriver.class);
        builder = new WindowTideRollupBuilder(locationRepository, tideExtremeRepository, marineWaveRepository,
                tideService, solarService, tideFactDeriver, "");
    }

    private static TideExtremeEntity extreme(LocalDateTime time, String metres, TideExtremeType type) {
        return TideExtremeEntity.builder().locationId(ID).eventTime(time)
                .heightMetres(metres == null ? null : new BigDecimal(metres)).type(type).build();
    }

    @Test
    @DisplayName("an undated day is skipped; an extreme with no height is ignored; no baseline and a height-less "
            + "wave leave the anomaly and the sea state out")
    void build_degradedData_rowStillRenders() {
        LocationEntity whitby = LocationEntity.builder().id(ID).name("Whitby").lat(LAT).lon(LON).build();
        List<TideExtremeEntity> extremes = List.of(
                extreme(DAY.atTime(2, 20), "0.6", TideExtremeType.LOW),
                extreme(DAY.atTime(5, 0), null, TideExtremeType.HIGH),
                extreme(DAY.atTime(8, 35), "5.4", TideExtremeType.HIGH),
                extreme(DAY.atTime(14, 45), "0.9", TideExtremeType.LOW),
                extreme(DAY.atTime(20, 55), "5.2", TideExtremeType.HIGH));
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(whitby));
        when(tideExtremeRepository.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(List.of(ID),
                DAY.minusDays(1).atStartOfDay(), DAY.plusDays(2).atStartOfDay())).thenReturn(extremes);
        when(tideService.getTideStats(ID)).thenReturn(Optional.empty());
        MarineWaveEntity wave = new MarineWaveEntity();
        wave.setEvaluationDate(DAY);
        wave.setEventType(TargetType.SUNSET);
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateBetween(ID, DAY, DAY))
                .thenReturn(List.of(wave));
        when(solarService.sunriseUtc(LAT, LON, DAY)).thenReturn(DAY.atTime(8, 10));
        when(solarService.sunsetUtc(LAT, LON, DAY)).thenReturn(DAY.atTime(16, 30));
        when(tideFactDeriver.tightAlignmentWindowMinutes(LAT, LON, DAY.atTime(16, 30), TargetType.SUNSET))
                .thenReturn(WINDOW_MINUTES);
        when(tideService.classifyTideState(extremes, DAY.atTime(16, 30), WINDOW_MINUTES))
                .thenReturn(TideState.MID);
        BriefingDay undated = new BriefingDay(null,
                List.of(new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of())));
        BriefingDay dated = new BriefingDay(DAY,
                List.of(new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of())));

        Map<PlanWindowProjector.WindowKey, BriefingWindowTide> rollups = builder.build(List.of(undated, dated));

        assertThat(rollups).containsOnlyKeys(new PlanWindowProjector.WindowKey(DAY, TargetType.SUNSET));
        BriefingWindowTide tide = rollups.get(new PlanWindowProjector.WindowKey(DAY, TargetType.SUNSET));
        assertThat(tide.locationName()).isEqualTo("Whitby");
        assertThat(tide.range()).isEqualTo("4.8 m");
        assertThat(tide.rangeAnomaly()).isNull();
        assertThat(tide.seas()).isNull();
        assertThat(tide.state()).isEqualTo(TideState.MID);
    }
}
