package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.MarineWavePeak;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.repository.TideHighWater;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the big-seas half of {@link MarineWaveRefreshService}: sampling the sea at every
 * daylight high water of a structure location into {@code marine_high_water}.
 *
 * <p>The BST to GMT fixtures matter: Sunday 25 Oct 2026 is a 25-hour UK day, so a morning high
 * water on Saturday 24 Oct (BST) and one on Sunday 25 Oct (GMT) must each be clipped against their
 * own date's dawn, and the day range must be bounded by {@link ForecastHorizon#ukDayStartUtc}.
 * Every mock argument is the exact value the code produces. The refresh swallows runtime exceptions per
 * location, so an unexpected call under strict stubs is caught there and surfaces as a missing or wrong
 * row in the assertions, not as a stubbing error of its own.
 */
@ExtendWith(MockitoExtension.class)
class MarineWaveRefreshServiceBigSeasTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 10);
    private static final LocalDateTime DAY_START = LocalDateTime.of(2026, 12, 10, 0, 0);
    private static final LocalDateTime DAY_END = LocalDateTime.of(2026, 12, 11, 0, 0);
    private static final LocalDateTime DAWN = LocalDateTime.of(2026, 12, 10, 7, 0);
    private static final LocalDateTime DUSK = LocalDateTime.of(2026, 12, 10, 16, 30);
    private static final Long JOB_RUN_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-12-09T20:00:00Z");
    private static final double LAT = 54.92;
    private static final double LON = -1.35;
    private static final long STRUCTURE_ID = 7L;
    private static final MarineClient.Point POINT = new MarineClient.Point(LAT, LON);

    @Mock
    private MarineClient marineClient;

    @Mock
    private MarineWaveRepository marineWaveRepository;

    @Mock
    private SolarService solarService;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private MarineHighWaterWriter highWaterWriter;

    @Mock
    private JobRunService jobRunService;

    private MarineWaveRefreshService service;

    private final OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();

    @BeforeEach
    void setUp() {
        service = new MarineWaveRefreshService(marineClient, marineWaveRepository, solarService,
                tideExtremeRepository, highWaterWriter, jobRunService, Clock.fixed(NOW, ZoneOffset.UTC),
                new BigSeasProperties());
    }

    private static LocationEntity structure(long id, String name) {
        return LocationEntity.builder().id(id).name(name).lat(LAT).lon(LON)
                .tideType(Set.of(TideType.HIGH)).coastalStructure(CoastalStructure.PIER)
                .structureFacesDeg(45).structureLabel(name).build();
    }

    private static TideHighWater hw(long locationId, LocalDateTime time) {
        return new TideHighWater(locationId, time, new BigDecimal("5.250"));
    }

    private static MarineWavePeak peak(LocalDateTime time, double hs) {
        return new MarineWavePeak(time, hs, 30, 11.0, 3.2, 12.0);
    }

    private void stubDaylight(LocalDate date, LocalDateTime dawn, LocalDateTime dusk) {
        when(solarService.civilDawnUtc(LAT, LON, date)).thenReturn(dawn);
        when(solarService.civilDuskUtc(LAT, LON, date)).thenReturn(dusk);
    }

    @SuppressWarnings("unchecked")
    private List<MarineHighWaterEntity> writtenRows(long locationId, LocalDateTime from, LocalDateTime to) {
        ArgumentCaptor<List<MarineHighWaterEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(highWaterWriter).replace(eq(locationId), eq(from), eq(to), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the half-window is configurable: 30 minutes writes HW +-30, unclipped mid-day")
    void halfWindowIsConfigurable() {
        BigSeasProperties thirty = new BigSeasProperties();
        thirty.setHalfWindowMinutes(30);
        service = new MarineWaveRefreshService(marineClient, marineWaveRepository, solarService,
                tideExtremeRepository, highWaterWriter, jobRunService, Clock.fixed(NOW, ZoneOffset.UTC), thirty);
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        stubDaylight(DAY, DAWN, DUSK);
        LocalDateTime noon = LocalDateTime.of(2026, 12, 10, 12, 0);
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of(hw(STRUCTURE_ID, noon)));
        when(marineClient.peakInWindow(response, LocalDateTime.of(2026, 12, 10, 11, 30),
                LocalDateTime.of(2026, 12, 10, 12, 30)))
                .thenReturn(Optional.of(peak(noon, 4.0)));

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        MarineHighWaterEntity row = writtenRows(STRUCTURE_ID, DAY_START, DAY_END).getFirst();
        assertThat(row.getWindowStart()).isEqualTo(noon.minusMinutes(30));
        assertThat(row.getWindowEnd()).isEqualTo(noon.plusMinutes(30));
    }

    @Test
    @DisplayName("each daylight HW is windowed +-90 min and clipped to civil dawn and dusk; the row carries "
            + "the peak, the HW and the stamp")
    void daylightClip_bothEnds() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        stubDaylight(DAY, DAWN, DUSK);
        LocalDateTime early = LocalDateTime.of(2026, 12, 10, 7, 30);
        LocalDateTime noon = LocalDateTime.of(2026, 12, 10, 12, 0);
        LocalDateTime late = LocalDateTime.of(2026, 12, 10, 15, 50);
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of(hw(STRUCTURE_ID, early), hw(STRUCTURE_ID, noon), hw(STRUCTURE_ID, late)));
        // early: 06:00-09:00 clipped to 07:00-09:00; noon: 10:30-13:30 whole; late: 14:20-17:20 clipped to 16:30.
        when(marineClient.peakInWindow(response, DAWN, LocalDateTime.of(2026, 12, 10, 9, 0)))
                .thenReturn(Optional.of(peak(LocalDateTime.of(2026, 12, 10, 8, 0), 2.5)));
        when(marineClient.peakInWindow(response, LocalDateTime.of(2026, 12, 10, 10, 30),
                LocalDateTime.of(2026, 12, 10, 13, 30)))
                .thenReturn(Optional.of(peak(LocalDateTime.of(2026, 12, 10, 12, 0), 4.0)));
        when(marineClient.peakInWindow(response, LocalDateTime.of(2026, 12, 10, 14, 20), DUSK))
                .thenReturn(Optional.of(peak(LocalDateTime.of(2026, 12, 10, 15, 0), 3.1)));

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        List<MarineHighWaterEntity> rows = writtenRows(STRUCTURE_ID, DAY_START, DAY_END);
        assertThat(rows).extracting(MarineHighWaterEntity::getHwTime).containsExactly(early, noon, late);
        assertThat(rows).extracting(MarineHighWaterEntity::getWindowStart)
                .containsExactly(DAWN, LocalDateTime.of(2026, 12, 10, 10, 30),
                        LocalDateTime.of(2026, 12, 10, 14, 20));
        assertThat(rows).extracting(MarineHighWaterEntity::getWindowEnd)
                .containsExactly(LocalDateTime.of(2026, 12, 10, 9, 0),
                        LocalDateTime.of(2026, 12, 10, 13, 30), DUSK);
        MarineHighWaterEntity midday = rows.get(1);
        assertThat(midday.getLocation()).isSameAs(roker);
        assertThat(midday.getHwHeightMetres()).isEqualByComparingTo("5.250");
        assertThat(midday.getPeakTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 12, 0));
        assertThat(midday.getWaveHeightMetres()).isEqualTo(4.0);
        assertThat(midday.getWaveDirectionDegrees()).isEqualTo(30);
        assertThat(midday.getWavePeriodSeconds()).isEqualTo(11.0);
        assertThat(midday.getSwellWaveHeightMetres()).isEqualTo(3.2);
        assertThat(midday.getSwellWavePeriodSeconds()).isEqualTo(12.0);
        assertThat(midday.getEvaluatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a night HW whose window never reaches daylight is excluded, even a stored high water that "
            + "ends exactly at dawn")
    void nightHighWater_excluded() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        stubDaylight(DAY, DAWN, DUSK);
        // 22:00 window is 20:30-23:30, wholly after dusk. 05:30 window is 04:00-07:00, ending exactly at dawn.
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of(hw(STRUCTURE_ID, LocalDateTime.of(2026, 12, 10, 5, 30)),
                        hw(STRUCTURE_ID, LocalDateTime.of(2026, 12, 10, 22, 0))));

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, DAY_START, DAY_END)).isEmpty();
        verify(marineClient, never()).peakInWindow(response, DAWN, DAWN);
    }

    @Test
    @DisplayName("a night HW whose window reaches daylight is kept and clipped to dawn")
    void nightHighWater_reachingDawn_clipped() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        stubDaylight(DAY, DAWN, DUSK);
        LocalDateTime hwTime = LocalDateTime.of(2026, 12, 10, 6, 30);
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of(hw(STRUCTURE_ID, hwTime)));
        // 05:00-08:00 clipped to 07:00-08:00.
        when(marineClient.peakInWindow(response, DAWN, LocalDateTime.of(2026, 12, 10, 8, 0)))
                .thenReturn(Optional.of(peak(LocalDateTime.of(2026, 12, 10, 7, 0), 3.3)));

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        List<MarineHighWaterEntity> rows = writtenRows(STRUCTURE_ID, DAY_START, DAY_END);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getHwTime()).isEqualTo(hwTime);
        assertThat(rows.getFirst().getWindowStart()).isEqualTo(DAWN);
        assertThat(rows.getFirst().getWindowEnd()).isEqualTo(LocalDateTime.of(2026, 12, 10, 8, 0));
    }

    @Test
    @DisplayName("BST to GMT: Sat 24 Oct (BST) and Sun 25 Oct (GMT) mornings each clip against their own "
            + "date's dawn, and the range is bounded by the UK day starts")
    void clockChange_eachMorningClippedAgainstItsOwnDate() {
        LocalDate saturday = LocalDate.of(2026, 10, 24);
        LocalDate sunday = LocalDate.of(2026, 10, 25);
        LocalDateTime from = LocalDateTime.of(2026, 10, 23, 23, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 26, 0, 0);
        assertThat(ForecastHorizon.ukDayStartUtc(saturday)).isEqualTo(from);
        assertThat(ForecastHorizon.ukDayStartUtc(sunday)).isEqualTo(LocalDateTime.of(2026, 10, 24, 23, 0));
        assertThat(ForecastHorizon.ukDayStartUtc(sunday.plusDays(1))).isEqualTo(to);

        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        LocalDateTime saturdayDawn = LocalDateTime.of(2026, 10, 24, 5, 50);
        LocalDateTime sundayDawn = LocalDateTime.of(2026, 10, 25, 6, 42);
        stubDaylight(saturday, saturdayDawn, LocalDateTime.of(2026, 10, 24, 16, 55));
        stubDaylight(sunday, sundayDawn, LocalDateTime.of(2026, 10, 25, 16, 52));
        LocalDateTime saturdayHw = LocalDateTime.of(2026, 10, 24, 6, 30);
        LocalDateTime sundayHw = LocalDateTime.of(2026, 10, 25, 6, 30);
        // 00:30 BST on Sunday is 23:30Z on Saturday: Sunday's civil date, and nowhere near Sunday's dawn.
        LocalDateTime sundayNightHw = LocalDateTime.of(2026, 10, 24, 23, 30);
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), from, to))
                .thenReturn(List.of(hw(STRUCTURE_ID, saturdayHw), hw(STRUCTURE_ID, sundayNightHw),
                        hw(STRUCTURE_ID, sundayHw)));
        when(marineClient.peakInWindow(response, saturdayDawn, LocalDateTime.of(2026, 10, 24, 8, 0)))
                .thenReturn(Optional.of(peak(LocalDateTime.of(2026, 10, 24, 6, 0), 4.5)));
        when(marineClient.peakInWindow(response, sundayDawn, LocalDateTime.of(2026, 10, 25, 8, 0)))
                .thenReturn(Optional.of(peak(LocalDateTime.of(2026, 10, 25, 7, 0), 5.0)));

        service.refresh(List.of(roker), List.of(saturday, sunday), JOB_RUN_ID);

        List<MarineHighWaterEntity> rows = writtenRows(STRUCTURE_ID, from, to);
        assertThat(rows).extracting(MarineHighWaterEntity::getHwTime).containsExactly(saturdayHw, sundayHw);
        assertThat(rows).extracting(MarineHighWaterEntity::getWindowStart)
                .containsExactly(saturdayDawn, sundayDawn);
        // Sunday's night HW (23:30Z Saturday) and Sunday's morning HW both bucket to Sunday's civil date.
        verify(solarService, times(2)).civilDawnUtc(LAT, LON, sunday);
    }

    @Test
    @DisplayName("a single clock-change Sunday spans the 25-hour UK day: 24 Oct 23:00Z to 26 Oct 00:00Z")
    void clockChangeSunday_rangeIsTwentyFiveHours() {
        LocalDate sunday = LocalDate.of(2026, 10, 25);
        LocalDateTime from = LocalDateTime.of(2026, 10, 24, 23, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 26, 0, 0);
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), from, to)).thenReturn(List.of());

        service.refresh(List.of(roker), List.of(sunday), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, from, to)).isEmpty();
    }

    @Test
    @DisplayName("a coastal location with no structure gets no high-water rows and no tide read")
    void nonStructureCoastal_noHighWaterWork() {
        LocationEntity bamburgh = LocationEntity.builder().id(1L).name("Bamburgh").lat(LAT).lon(LON)
                .tideType(Set.of(TideType.HIGH)).build();
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));

        service.refresh(List.of(bamburgh), List.of(DAY), JOB_RUN_ID);

        verifyNoInteractions(tideExtremeRepository, highWaterWriter);
    }

    @Test
    @DisplayName("stale rows go: with no high water left in range the writer still runs with an empty list")
    void noHighWatersLeft_staleRowsCleared() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of());

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("a failed marine fetch CLEARS the structure's rows, never keeps them, and reads no tides")
    void failedFetch_clears() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenThrow(new RuntimeException("boom"));

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, DAY_START, DAY_END)).isEmpty();
        verifyNoInteractions(tideExtremeRepository, marineWaveRepository);
    }

    @Test
    @DisplayName("a land cell (no peak) clears the rows and writes none")
    void landCell_clears() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        stubDaylight(DAY, DAWN, DUSK);
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of(hw(STRUCTURE_ID, LocalDateTime.of(2026, 12, 10, 12, 0))));
        when(marineClient.peakInWindow(response, LocalDateTime.of(2026, 12, 10, 10, 30),
                LocalDateTime.of(2026, 12, 10, 13, 30))).thenReturn(Optional.empty());

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("the sunrise and sunset marine_wave rows are saved exactly as before for a structure location")
    void sunriseSunsetRows_unchanged() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        LocalDateTime sunrise = LocalDateTime.of(2026, 12, 10, 7, 50);
        LocalDateTime sunset = LocalDateTime.of(2026, 12, 10, 15, 40);
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        when(solarService.sunriseUtc(LAT, LON, DAY)).thenReturn(sunrise);
        when(solarService.sunsetUtc(LAT, LON, DAY)).thenReturn(sunset);
        when(marineClient.sampleAt(response, sunrise)).thenReturn(Optional.of(new MarineWaveSample(2.2, 1.0, 40)));
        when(marineClient.sampleAt(response, sunset)).thenReturn(Optional.empty());
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(List.of(STRUCTURE_ID), List.of(DAY)))
                .thenReturn(List.of());
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of());

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineWaveEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marineWaveRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().getEventType()).isEqualTo(TargetType.SUNRISE);
        assertThat(captor.getValue().getFirst().getSignificantWaveHeightMetres()).isEqualTo(2.2);
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq("briefing-marine(batch 1/1, 1 locations)"), isNull(), anyLong(), eq(200), isNull(), eq(true),
                isNull());
    }

    @Test
    @DisplayName("a writer failure for one structure is isolated: the next structure is still refreshed")
    void writerFailure_isolated() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        LocationEntity amble = structure(8L, "Amble");
        when(marineClient.fetchMarineBatch(List.of(POINT, POINT))).thenReturn(List.of(response, response));
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of());
        when(tideExtremeRepository.findHighWatersInWindow(List.of(8L), DAY_START, DAY_END))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("db down")).when(highWaterWriter)
                .replace(eq(STRUCTURE_ID), eq(DAY_START), eq(DAY_END), eq(List.of()));

        service.refresh(List.of(roker, amble), List.of(DAY), JOB_RUN_ID);

        verify(highWaterWriter).replace(8L, DAY_START, DAY_END, List.of());
    }

    @Test
    @DisplayName("a failure while reading or windowing clears the location's rows, best effort")
    void readFailure_clearsRows() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenThrow(new IllegalStateException("db down"));

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    @DisplayName("a high water on a date outside the requested dates is ignored without a solar lookup")
    void highWaterOutsideDates_ignored() {
        LocalDate twelfth = LocalDate.of(2026, 12, 12);
        LocalDateTime to = LocalDateTime.of(2026, 12, 13, 0, 0);
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, to))
                .thenReturn(List.of(hw(STRUCTURE_ID, LocalDateTime.of(2026, 12, 11, 12, 0))));

        service.refresh(List.of(roker), List.of(DAY, twelfth), JOB_RUN_ID);

        assertThat(writtenRows(STRUCTURE_ID, DAY_START, to)).isEmpty();
        verify(solarService, never()).civilDawnUtc(LAT, LON, LocalDate.of(2026, 12, 11));
    }

    @Test
    @DisplayName("an event-grain failure (marine_wave save throws) still replaces the structure's high-water rows")
    void eventGrainFailure_stillRefreshesHighWaters() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        LocalDateTime sunrise = LocalDateTime.of(2026, 12, 10, 7, 50);
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));
        when(solarService.sunriseUtc(LAT, LON, DAY)).thenReturn(sunrise);
        when(marineClient.sampleAt(response, sunrise)).thenReturn(Optional.of(new MarineWaveSample(2.2, 1.0, 40)));
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(List.of(STRUCTURE_ID), List.of(DAY)))
                .thenReturn(List.of());
        when(marineWaveRepository.saveAll(argThat(this::isExpectedSunriseRow)))
                .thenThrow(new IllegalStateException("db down"));
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of());

        service.refresh(List.of(roker), List.of(DAY), JOB_RUN_ID);

        verify(marineWaveRepository).saveAll(argThat(this::isExpectedSunriseRow));
        assertThat(writtenRows(STRUCTURE_ID, DAY_START, DAY_END)).isEmpty();
    }

    /** True only for exactly the one Roker sunrise row the stubbed sample produces. */
    private boolean isExpectedSunriseRow(List<MarineWaveEntity> rows) {
        if (rows == null || rows.size() != 1) {
            return false;
        }
        MarineWaveEntity row = rows.getFirst();
        return row.getLocation() != null && row.getLocation().getId() == STRUCTURE_ID
                && DAY.equals(row.getEvaluationDate()) && row.getEventType() == TargetType.SUNRISE
                && Double.valueOf(2.2).equals(row.getSignificantWaveHeightMetres())
                && Double.valueOf(1.0).equals(row.getSwellWaveHeightMetres())
                && Integer.valueOf(40).equals(row.getWaveDirectionDegrees());
    }

    @Test
    @DisplayName("an event-grain sampling failure for one location keeps the next location's rows and "
            + "still refreshes both high-water sets")
    void eventGrainFailure_nextLocationStillRefreshed() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        LocationEntity amble = structure(8L, "Amble");
        LocalDateTime sunrise = LocalDateTime.of(2026, 12, 10, 7, 50);
        LocalDateTime sunset = LocalDateTime.of(2026, 12, 10, 15, 40);
        when(marineClient.fetchMarineBatch(List.of(POINT, POINT))).thenReturn(List.of(response, response));
        when(solarService.sunriseUtc(LAT, LON, DAY)).thenReturn(sunrise);
        when(solarService.sunsetUtc(LAT, LON, DAY)).thenReturn(sunset);
        // The first location's sampling throws; the second's succeeds.
        when(marineClient.sampleAt(response, sunrise))
                .thenThrow(new IllegalStateException("solar broke"))
                .thenReturn(Optional.of(new MarineWaveSample(2.2, 1.0, 40)));
        when(marineClient.sampleAt(response, sunset)).thenReturn(Optional.empty());
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(
                List.of(STRUCTURE_ID, 8L), List.of(DAY))).thenReturn(List.of());
        when(tideExtremeRepository.findHighWatersInWindow(List.of(STRUCTURE_ID), DAY_START, DAY_END))
                .thenReturn(List.of());
        when(tideExtremeRepository.findHighWatersInWindow(List.of(8L), DAY_START, DAY_END))
                .thenReturn(List.of());

        service.refresh(List.of(roker, amble), List.of(DAY), JOB_RUN_ID);

        verify(highWaterWriter).replace(STRUCTURE_ID, DAY_START, DAY_END, List.of());
        verify(highWaterWriter).replace(8L, DAY_START, DAY_END, List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineWaveEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marineWaveRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().getLocation()).isSameAs(amble);
    }

    @Test
    @DisplayName("no dates, no high-water work")
    void noDates_nothingToDo() {
        LocationEntity roker = structure(STRUCTURE_ID, "Roker");
        when(marineClient.fetchMarineBatch(List.of(POINT))).thenReturn(List.of(response));

        service.refresh(List.of(roker), List.of(), JOB_RUN_ID);

        verifyNoInteractions(highWaterWriter, tideExtremeRepository);
    }
}
