package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.comingup.ComingUpDayRange;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.service.LunarPhaseService;
import com.gregochr.goldenhour.service.SolarService;
import com.gregochr.goldenhour.service.TideAlmanacSource;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import com.gregochr.goldenhour.service.TideSizeIndex;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Agreement across the real producers of a tide run's figures: {@link TideRunBuilder} (real), the
 * real {@link TideAlmanacSource} and the real {@link ComingUpAssembler} — only the repositories,
 * services and the size index are stubbed. Two ports make the representative choice matter: the
 * port with the bigger range peaks on a different day from the other, so a {@code dayRanges} read at
 * the wrong port would put the {@code peak} flag on the wrong date. Asserts that the {@code peak}
 * day is the source's {@code peakDate}, and that the tallest bar, the headline metric and
 * {@code tide.range} all say the same figure.
 *
 * <p>January dates, where Europe/London is UTC, so stored UTC clocks read unambiguously.
 */
@ExtendWith(MockitoExtension.class)
class ComingUpDayRangesAgreementTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 28);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 29);

    private static final LocationEntity WHITBY =
            LocationEntity.builder().id(1L).name("Whitby").lat(54.49).lon(-0.61).build();
    private static final LocationEntity SEAHAM =
            LocationEntity.builder().id(2L).name("Seaham").lat(54.84).lon(-1.33).build();

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private MarineWaveRepository marineWaveRepository;

    @Mock
    private TideService tideService;

    @Mock
    private SolarService solarService;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private TideSizeIndex tideSizeIndex;

    @Mock
    private TideRunPeakHistory tideRunPeakHistory;

    @Test
    @DisplayName("the peak flag, the tallest bar, the headline metric and tide.range all agree, "
            + "measured at the representative port rather than the other one")
    void peakBarMetricAndTideRangeAgree() {
        List<LocationEntity> roster = List.of(WHITBY, SEAHAM);
        when(locationRepository.findCoastalLocations()).thenReturn(roster);
        when(tideSizeIndex.measure(any(), any(), any()))
                .thenReturn(new TideSizeIndex.Sizes(Set.of(D1, D2, D3), Set.of(), true, java.util.Map.of()));
        lenient().when(solarService.sunriseUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(8, 10));
        lenient().when(solarService.sunsetUtc(anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> ((LocalDate) inv.getArgument(2)).atTime(16, 30));
        when(tideService.getTideStats(any())).thenReturn(Optional.of(new TideStats(null, null, null,
                null, 100L, new BigDecimal("3.5"), null, null, null, 0L, null, null, null, 0L)));

        List<TideExtremeEntity> extremes = new ArrayList<>();
        // Whitby is biggest on the 28th (3.0 m) — the wrong port would put the peak there.
        addDay(extremes, 1L, D1, 0.5, 2.0);
        addDay(extremes, 1L, D2, 0.5, 3.5);
        addDay(extremes, 1L, D3, 0.5, 2.5);
        // Seaham, the representative, is biggest on the 29th: 8.966 - 1.516 = 7.449999...
        addDay(extremes, 2L, D1, 0.4, 6.4);
        addDay(extremes, 2L, D2, 0.4, 7.4);
        addDay(extremes, 2L, D3, 1.516, 8.966);
        when(tideExtremeRepository.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                any(), any(), any())).thenReturn(extremes);

        TideRunBuilder builder = new TideRunBuilder(tideExtremeRepository, marineWaveRepository,
                tideService, solarService, "");
        TideAlmanacSource source =
                new TideAlmanacSource(lunarPhaseService, builder, locationRepository, tideSizeIndex,
                        new ComingUpScoringProperties());
        ComingUpAssembler assembler = new ComingUpAssembler(locationRepository, builder,
                tideRunPeakHistory, new DailyRangeHistory(builder, tideSizeIndex), tideService,
                new ComingUpScoringProperties(),
                new MoonriseMoonsetCalculator());

        List<AlmanacEvent> events = source.events(D1, D3);
        ComingUpEntry entry = assembler.assemble(D1, events).entries().getFirst();

        assertThat(events).singleElement().satisfies(e ->
                assertThat(e.meta()).containsEntry("peakDate", D3.toString())
                        .containsEntry("location", "Seaham"));
        assertThat(entry.dayRanges()).extracting(ComingUpDayRange::date).containsExactly(D1, D2, D3);
        assertThat(entry.dayRanges()).filteredOn(ComingUpDayRange::peak)
                .extracting(ComingUpDayRange::date).containsExactly(D3);
        double tallest = entry.dayRanges().stream().mapToDouble(ComingUpDayRange::rangeMetres).max()
                .getAsDouble();
        assertThat(tallest).isEqualTo(Double.parseDouble(entry.metric().replace(" m", "")));
        assertThat(entry.tide()).isNotNull();
        // One rounding rule for the metric, the bars and the sparkline's range (7.449999... -> 7.4),
        // and delta is the displayed range minus the 3.5 m average.
        assertThat(entry.tide().range()).isEqualTo(tallest);
        assertThat(entry.tide().delta()).isEqualTo(3.9);
    }

    private static void addDay(List<TideExtremeEntity> out, long locationId, LocalDate date, double low,
            double high) {
        out.add(extreme(locationId, date.atTime(5, 0), low, TideExtremeType.LOW));
        out.add(extreme(locationId, date.atTime(11, 10), high, TideExtremeType.HIGH));
        out.add(extreme(locationId, date.atTime(17, 20), low, TideExtremeType.LOW));
    }

    private static TideExtremeEntity extreme(long locationId, LocalDateTime utc, double height,
            TideExtremeType type) {
        return TideExtremeEntity.builder().locationId(locationId).eventTime(utc)
                .heightMetres(BigDecimal.valueOf(height)).type(type).build();
    }
}
