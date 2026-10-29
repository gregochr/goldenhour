package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.OpenMeteoMarineApi;
import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineHighWaterEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests {@link MarineWaveRefreshService}'s batched fetch: 20 exact coordinates per request, the
 * same rows the former per-location path wrote, failure isolation per chunk, one bulk read of the
 * existing rows, and the snapshot or live source for high waters.
 *
 * <p>A real {@link MarineClient} sits over a mocked {@link OpenMeteoMarineApi} so the request
 * strings (the chunk contents and order) are asserted exactly. {@code anyLong()} is used only for
 * the logged wall-clock duration.
 */
@ExtendWith(MockitoExtension.class)
class MarineWaveRefreshServiceBatchTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 6, 17, 4, 0);
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 6, 17, 12, 0);
    private static final LocalDateTime SUNSET = LocalDateTime.of(2026, 6, 17, 21, 0);
    private static final LocalDateTime DAWN = LocalDateTime.of(2026, 6, 17, 3, 0);
    private static final LocalDateTime DUSK = LocalDateTime.of(2026, 6, 17, 22, 0);
    private static final LocalDateTime FROM = ForecastHorizon.ukDayStartUtc(DATE);
    private static final LocalDateTime TO = ForecastHorizon.ukDayStartUtc(DATE.plusDays(1));
    private static final Instant NOW = Instant.parse("2026-06-17T02:00:00Z");
    private static final Long JOB_RUN_ID = 42L;
    private static final String HOURLY =
            "wave_height,swell_wave_height,wave_direction,wave_period,swell_wave_period";
    private static final int COASTAL_COUNT = 25;

    @Mock
    private OpenMeteoMarineApi marineApi;

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

    @BeforeEach
    void setUp() {
        service = new MarineWaveRefreshService(new MarineClient(marineApi), marineWaveRepository, solarService,
                tideExtremeRepository, highWaterWriter, jobRunService, Clock.fixed(NOW, ZoneOffset.UTC),
                new BigSeasProperties());
    }

    private static double lat(long id) {
        return 54.0 + id * 0.01;
    }

    private static double lon(long id) {
        return -1.0 - id * 0.01;
    }

    private static LocationEntity coastal(long id, boolean structure) {
        return LocationEntity.builder().id(id).name("Coast" + id).lat(lat(id)).lon(lon(id))
                .tideType(Set.of(TideType.HIGH))
                .coastalStructure(structure ? CoastalStructure.PIER : null).build();
    }

    private static LocationEntity inland(long id) {
        return LocationEntity.builder().id(id).name("Inland" + id).lat(lat(id)).lon(lon(id)).build();
    }

    /** A response whose waves are a deterministic function of the location id. */
    private static OpenMeteoMarineResponse responseFor(long id) {
        OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();
        OpenMeteoMarineResponse.Hourly hourly = new OpenMeteoMarineResponse.Hourly();
        hourly.setTime(List.of("2026-06-17T04:00", "2026-06-17T12:00", "2026-06-17T21:00"));
        hourly.setWaveHeight(List.of(id * 0.1, id * 0.3, id * 0.2));
        hourly.setSwellWaveHeight(List.of(id * 0.01, id * 0.03, id * 0.02));
        hourly.setWaveDirection(List.of((int) id, (int) id + 100, (int) id + 200));
        hourly.setWavePeriod(List.of(5.0, 6.0, 7.0));
        hourly.setSwellWavePeriod(List.of(8.0, 9.0, 10.0));
        response.setHourly(hourly);
        return response;
    }

    private static String lats(long fromId, long toIdInclusive) {
        return IntStream.rangeClosed((int) fromId, (int) toIdInclusive)
                .mapToObj(i -> String.valueOf(lat(i))).collect(Collectors.joining(","));
    }

    private static String lons(long fromId, long toIdInclusive) {
        return IntStream.rangeClosed((int) fromId, (int) toIdInclusive)
                .mapToObj(i -> String.valueOf(lon(i))).collect(Collectors.joining(","));
    }

    private static List<OpenMeteoMarineResponse> responses(long fromId, long toIdInclusive) {
        return IntStream.rangeClosed((int) fromId, (int) toIdInclusive)
                .mapToObj(i -> responseFor(i)).toList();
    }

    private void stubSolar(long id) {
        when(solarService.sunriseUtc(lat(id), lon(id), DATE)).thenReturn(SUNRISE);
        when(solarService.sunsetUtc(lat(id), lon(id), DATE)).thenReturn(SUNSET);
    }

    private void stubBothChunks() {
        when(marineApi.getMarineBatch(lats(1, 20), lons(1, 20), HOURLY, "UTC", 7)).thenReturn(responses(1, 20));
        when(marineApi.getMarineBatch(lats(21, 25), lons(21, 25), HOURLY, "UTC", 7)).thenReturn(responses(21, 25));
    }

    private static List<LocationEntity> twentyFiveCoastalPlusInland() {
        List<LocationEntity> all = new ArrayList<>();
        for (long id = 1; id <= COASTAL_COUNT; id++) {
            all.add(coastal(id, false));
            if (id % 10 == 0) {
                all.add(inland(100 + id));
            }
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<MarineWaveEntity> savedRows() {
        ArgumentCaptor<List<MarineWaveEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marineWaveRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("25 coastal locations (plus inland ones) are fetched as chunks of 20 and 5 and persist "
            + "exactly the rows the per-location path wrote, upserting an existing row in place")
    void equivalentToPerLocationPath() {
        stubBothChunks();
        for (long id = 1; id <= COASTAL_COUNT; id++) {
            stubSolar(id);
        }
        MarineWaveEntity existing = new MarineWaveEntity();
        existing.setId(99L);
        existing.setLocation(coastal(3, false));
        existing.setEvaluationDate(DATE);
        existing.setEventType(TargetType.SUNRISE);
        existing.setSignificantWaveHeightMetres(9.9);
        List<Long> ids = LongStreamBoxed.range(1, COASTAL_COUNT);
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(ids, List.of(DATE)))
                .thenReturn(List.of(existing));

        service.refresh(twentyFiveCoastalPlusInland(), List.of(DATE), JOB_RUN_ID);

        List<MarineWaveEntity> rows = savedRows();
        assertThat(rows).hasSize(2 * COASTAL_COUNT);
        for (long id = 1; id <= COASTAL_COUNT; id++) {
            final long locId = id;
            MarineWaveEntity sunrise = rows.stream()
                    .filter(r -> r.getLocation().getId() == locId && r.getEventType() == TargetType.SUNRISE)
                    .findFirst().orElseThrow();
            MarineWaveEntity sunset = rows.stream()
                    .filter(r -> r.getLocation().getId() == locId && r.getEventType() == TargetType.SUNSET)
                    .findFirst().orElseThrow();
            assertThat(sunrise.getEvaluationDate()).isEqualTo(DATE);
            assertThat(sunrise.getSignificantWaveHeightMetres()).isEqualTo(id * 0.1);
            assertThat(sunrise.getSwellWaveHeightMetres()).isEqualTo(id * 0.01);
            assertThat(sunrise.getWaveDirectionDegrees()).isEqualTo((int) id);
            assertThat(sunrise.getEvaluatedAt()).isEqualTo(NOW);
            assertThat(sunset.getSignificantWaveHeightMetres()).isEqualTo(id * 0.2);
            assertThat(sunset.getWaveDirectionDegrees()).isEqualTo((int) id + 200);
            assertThat(sunset.getEvaluatedAt()).isEqualTo(NOW);
        }
        MarineWaveEntity updated = rows.stream()
                .filter(r -> r.getLocation().getId() == 3L && r.getEventType() == TargetType.SUNRISE)
                .findFirst().orElseThrow();
        assertThat(updated).isSameAs(existing);
        assertThat(updated.getId()).isEqualTo(99L);
        assertThat(updated.getSignificantWaveHeightMetres()).isEqualTo(3 * 0.1);
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq("briefing-marine(batch 1/2, 20 locations)"), isNull(), anyLong(), eq(200), isNull(),
                eq(true), isNull());
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq("briefing-marine(batch 2/2, 5 locations)"), isNull(), anyLong(), eq(200), isNull(),
                eq(true), isNull());
    }

    @Test
    @DisplayName("the existing marine_wave rows are read in a single call with the exact coastal ids and dates")
    void bulkReadIsOneCallWithExactArguments() {
        stubBothChunks();
        for (long id = 1; id <= COASTAL_COUNT; id++) {
            stubSolar(id);
        }
        LocalDate other = DATE.plusDays(1);
        for (long id = 1; id <= COASTAL_COUNT; id++) {
            when(solarService.sunriseUtc(lat(id), lon(id), other)).thenReturn(SUNRISE.plusDays(1));
            when(solarService.sunsetUtc(lat(id), lon(id), other)).thenReturn(SUNSET.plusDays(1));
        }
        List<Long> ids = LongStreamBoxed.range(1, COASTAL_COUNT);
        List<LocalDate> dates = List.of(DATE, other);
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(ids, dates)).thenReturn(List.of());

        service.refresh(twentyFiveCoastalPlusInland(), dates, JOB_RUN_ID);

        verify(marineWaveRepository).findByLocation_IdInAndEvaluationDateIn(ids, dates);
        // The other day's series has no matching hours, so it yields no rows; only DATE's 50 are saved.
        assertThat(savedRows()).hasSize(2 * COASTAL_COUNT);
        verifyNoMoreInteractions(marineWaveRepository);
    }

    @Test
    @DisplayName("a failed chunk persists nothing for its locations, but their high-water rows are still "
            + "cleared and the other chunk is unaffected")
    void failedChunk_isolated_andHighWaterStillRuns() {
        when(marineApi.getMarineBatch(lats(1, 20), lons(1, 20), HOURLY, "UTC", 7))
                .thenThrow(new IllegalStateException("429 too many"));
        when(marineApi.getMarineBatch(lats(21, 25), lons(21, 25), HOURLY, "UTC", 7)).thenReturn(responses(21, 25));
        for (long id = 21; id <= 25; id++) {
            stubSolar(id);
        }
        List<LocationEntity> locations = new ArrayList<>();
        for (long id = 1; id <= COASTAL_COUNT; id++) {
            // Location 1 (failed chunk) and 21 (good chunk) are structures with high-water rows.
            locations.add(coastal(id, id == 1 || id == 21));
        }
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(
                LongStreamBoxed.range(21, 25), List.of(DATE))).thenReturn(List.of());
        when(tideExtremeRepository.findHighWatersInWindow(List.of(21L), FROM, TO))
                .thenReturn(List.of(new TideHighWater(21L, NOON, new BigDecimal("5.250"))));
        when(solarService.civilDawnUtc(lat(21), lon(21), DATE)).thenReturn(DAWN);
        when(solarService.civilDuskUtc(lat(21), lon(21), DATE)).thenReturn(DUSK);

        service.refresh(locations, List.of(DATE), JOB_RUN_ID);

        List<MarineWaveEntity> rows = savedRows();
        assertThat(rows).hasSize(2 * 5);
        assertThat(rows).extracting(r -> r.getLocation().getId()).containsOnly(21L, 22L, 23L, 24L, 25L);
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq("briefing-marine(batch 1/2, 20 locations)"), isNull(), anyLong(), isNull(), isNull(),
                eq(false), eq("429 too many"));
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq("briefing-marine(batch 2/2, 5 locations)"), isNull(), anyLong(), eq(200), isNull(),
                eq(true), isNull());
        // The failed chunk's structure location: no tide read, rows cleared.
        verify(highWaterWriter).replace(1L, FROM, TO, List.of());
        verify(tideExtremeRepository, never()).findHighWatersInWindow(List.of(1L), FROM, TO);
        // The good chunk's structure location: one fresh row at the noon high water.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineHighWaterEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(highWaterWriter).replace(eq(21L), eq(FROM), eq(TO), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().getHwTime()).isEqualTo(NOON);
        assertThat(captor.getValue().getFirst().getWaveHeightMetres()).isEqualTo(21 * 0.3);
        assertThat(captor.getValue().getFirst().getEvaluatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("with a snapshot, high waters come from it (HIGH only) and the live window query is not used")
    void snapshot_isTheHighWaterSource() {
        LocationEntity pier = coastal(21, true);
        LocationEntity other = coastal(22, false);
        when(marineApi.getMarineBatch(lats(21, 22), lons(21, 22), HOURLY, "UTC", 7)).thenReturn(responses(21, 22));
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(List.of(21L, 22L), List.of(DATE)))
                .thenReturn(List.of());
        when(solarService.civilDawnUtc(lat(21), lon(21), DATE)).thenReturn(DAWN);
        when(solarService.civilDuskUtc(lat(21), lon(21), DATE)).thenReturn(DUSK);
        when(tideExtremeRepository.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                Set.of(21L), FROM, TO)).thenReturn(List.of(
                        tide(21L, LocalDateTime.of(2026, 6, 17, 6, 0), TideExtremeType.LOW, "0.500"),
                        tide(21L, NOON, TideExtremeType.HIGH, "5.250")));
        TideExtremeSnapshot snapshot = TideExtremeSnapshot.load(tideExtremeRepository, List.of(21L),
                new TideExtremeSnapshot.Window(FROM, TO));

        service.refresh(List.of(pier, other), List.of(DATE), JOB_RUN_ID, snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineHighWaterEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(highWaterWriter).replace(eq(21L), eq(FROM), eq(TO), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().getFirst().getHwTime()).isEqualTo(NOON);
        assertThat(captor.getValue().getFirst().getHwHeightMetres()).isEqualByComparingTo("5.250");
        verify(tideExtremeRepository, never()).findHighWatersInWindow(List.of(21L), FROM, TO);
    }

    @Test
    @DisplayName("a location the snapshot did not load falls back to a live read, never to an empty result")
    void snapshot_missingLocation_fallsBackToLive() {
        LocationEntity pier = coastal(21, true);
        LocationEntity other = coastal(22, false);
        when(marineApi.getMarineBatch(lats(21, 22), lons(21, 22), HOURLY, "UTC", 7)).thenReturn(responses(21, 22));
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(List.of(21L, 22L), List.of(DATE)))
                .thenReturn(List.of());
        when(solarService.civilDawnUtc(lat(21), lon(21), DATE)).thenReturn(DAWN);
        when(solarService.civilDuskUtc(lat(21), lon(21), DATE)).thenReturn(DUSK);
        when(tideExtremeRepository.findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(
                Set.of(22L), FROM, TO)).thenReturn(List.of());
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(21L, FROM, TO))
                .thenReturn(List.of(tide(21L, NOON, TideExtremeType.HIGH, "5.250")));
        TideExtremeSnapshot snapshot = TideExtremeSnapshot.load(tideExtremeRepository, List.of(22L),
                new TideExtremeSnapshot.Window(FROM, TO));

        service.refresh(List.of(pier, other), List.of(DATE), JOB_RUN_ID, snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineHighWaterEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(highWaterWriter).replace(eq(21L), eq(FROM), eq(TO), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        verify(tideExtremeRepository, never()).findHighWatersInWindow(List.of(21L), FROM, TO);
    }

    @Test
    @DisplayName("without a snapshot the live window query is the high-water source")
    void noSnapshot_usesLiveQuery() {
        LocationEntity pier = coastal(21, true);
        LocationEntity other = coastal(22, false);
        when(marineApi.getMarineBatch(lats(21, 22), lons(21, 22), HOURLY, "UTC", 7)).thenReturn(responses(21, 22));
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(List.of(21L, 22L), List.of(DATE)))
                .thenReturn(List.of());
        when(solarService.civilDawnUtc(lat(21), lon(21), DATE)).thenReturn(DAWN);
        when(solarService.civilDuskUtc(lat(21), lon(21), DATE)).thenReturn(DUSK);
        when(tideExtremeRepository.findHighWatersInWindow(List.of(21L), FROM, TO))
                .thenReturn(List.of(new TideHighWater(21L, NOON, new BigDecimal("5.250"))));

        service.refresh(List.of(pier, other), List.of(DATE), JOB_RUN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineHighWaterEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(highWaterWriter).replace(eq(21L), eq(FROM), eq(TO), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        verify(tideExtremeRepository, never())
                .findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(Set.of(21L), FROM, TO);
    }

    private static TideExtremeEntity tide(long locationId, LocalDateTime time, TideExtremeType type, String height) {
        return TideExtremeEntity.builder().locationId(locationId).eventTime(time).type(type)
                .heightMetres(new BigDecimal(height)).fetchedAt(LocalDateTime.of(2026, 6, 10, 0, 0)).build();
    }

    /** Boxed id ranges for exact-argument stubs. */
    private static final class LongStreamBoxed {

        private LongStreamBoxed() {
        }

        static List<Long> range(long fromInclusive, long toInclusive) {
            return java.util.stream.LongStream.rangeClosed(fromInclusive, toInclusive).boxed().toList();
        }
    }
}
