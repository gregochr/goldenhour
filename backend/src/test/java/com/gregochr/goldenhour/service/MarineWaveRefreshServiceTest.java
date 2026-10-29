package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.MarineWaveSample;
import com.gregochr.goldenhour.model.OpenMeteoMarineResponse;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MarineWaveRefreshService} — the briefing-time fetch + persist of coastal
 * sea-state into {@code marine_wave}. Covers coastal filtering, the sunrise/sunset upsert, fetch
 * failure isolation, and the land-cell (no sample) skip.
 *
 * <p>The only matcher left loose is {@code anyLong()} for the logged call duration, which is a
 * wall-clock elapsed time and therefore genuinely non-deterministic; every other argument is the
 * exact value the code produces.
 */
@ExtendWith(MockitoExtension.class)
class MarineWaveRefreshServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 6, 17, 4, 40);
    private static final LocalDateTime SUNSET = LocalDateTime.of(2026, 6, 17, 21, 40);
    private static final Long JOB_RUN_ID = 42L;
    private static final String MARINE_LABEL = "briefing-marine(batch 1/1, 1 locations)";
    private static final Instant NOW = Instant.parse("2026-06-17T03:00:00Z");
    private static final double LAT = 55.6;
    private static final double LON = -1.7;

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

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private MarineWaveRefreshService service;

    @BeforeEach
    void setUp() {
        service = new MarineWaveRefreshService(
                marineClient, marineWaveRepository, solarService, tideExtremeRepository, highWaterWriter,
                jobRunService, clock, new BigSeasProperties());
    }

    private static LocationEntity coastal() {
        return LocationEntity.builder().id(1L).name("Bamburgh").lat(LAT).lon(LON)
                .tideType(Set.of(TideType.HIGH)).build();
    }

    private static LocationEntity inland() {
        return LocationEntity.builder().id(2L).name("Durham").lat(54.7).lon(-1.5).build();
    }

    @Test
    @DisplayName("persists the sampled sea-state at the sunrise event hour, and logs the marine call")
    void refresh_coastal_persistsSample() {
        OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();
        when(marineClient.fetchMarineBatch(List.of(new MarineClient.Point(LAT, LON))))
                .thenReturn(List.of(response));
        when(solarService.sunriseUtc(LAT, LON, DATE)).thenReturn(SUNRISE);
        when(solarService.sunsetUtc(LAT, LON, DATE)).thenReturn(SUNSET);
        when(marineClient.sampleAt(response, SUNRISE))
                .thenReturn(Optional.of(new MarineWaveSample(4.2, 1.1, 250)));
        // No sunset sample — the sunset upsert must be skipped, so only one row is saved.
        when(marineClient.sampleAt(response, SUNSET)).thenReturn(Optional.empty());
        when(marineWaveRepository.findByLocation_IdInAndEvaluationDateIn(List.of(1L), List.of(DATE)))
                .thenReturn(List.of());

        service.refresh(List.of(coastal()), List.of(DATE), JOB_RUN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarineWaveEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(marineWaveRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        MarineWaveEntity saved = captor.getValue().getFirst();
        assertThat(saved.getEvaluationDate()).isEqualTo(DATE);
        assertThat(saved.getEventType()).isEqualTo(TargetType.SUNRISE);
        assertThat(saved.getSignificantWaveHeightMetres()).isEqualTo(4.2);
        assertThat(saved.getSwellWaveHeightMetres()).isEqualTo(1.1);
        assertThat(saved.getWaveDirectionDegrees()).isEqualTo(250);
        assertThat(saved.getEvaluatedAt()).isEqualTo(NOW);
        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq(MARINE_LABEL), isNull(), anyLong(), eq(200), isNull(), eq(true), isNull());
    }

    @Test
    @DisplayName("skips inland locations entirely — no marine fetch, no API-call log")
    void refresh_inland_skips() {
        service.refresh(List.of(inland()), List.of(DATE), JOB_RUN_ID);

        verifyNoInteractions(marineClient, solarService, marineWaveRepository, tideExtremeRepository,
                highWaterWriter, jobRunService);
    }

    @Test
    @DisplayName("a marine fetch failure is logged and does not persist or abort the refresh")
    void refresh_fetchFailure_isolated() {
        when(marineClient.fetchMarineBatch(List.of(new MarineClient.Point(LAT, LON)))).thenThrow(
                new RuntimeException("boom"));

        service.refresh(List.of(coastal()), List.of(DATE), JOB_RUN_ID);

        verify(jobRunService).logApiCall(eq(JOB_RUN_ID), eq(ServiceName.OPEN_METEO_MARINE), eq("GET"),
                eq(MARINE_LABEL), isNull(), anyLong(), isNull(), isNull(), eq(false), eq("boom"));
        verifyNoInteractions(marineWaveRepository, tideExtremeRepository, highWaterWriter);
    }

    @Test
    @DisplayName("saves nothing when the grid cell returns no wave sample for the event hour")
    void refresh_noSample_noSave() {
        OpenMeteoMarineResponse response = new OpenMeteoMarineResponse();
        when(marineClient.fetchMarineBatch(List.of(new MarineClient.Point(LAT, LON))))
                .thenReturn(List.of(response));
        when(solarService.sunriseUtc(LAT, LON, DATE)).thenReturn(SUNRISE);
        when(solarService.sunsetUtc(LAT, LON, DATE)).thenReturn(SUNSET);
        when(marineClient.sampleAt(response, SUNRISE)).thenReturn(Optional.empty());
        when(marineClient.sampleAt(response, SUNSET)).thenReturn(Optional.empty());

        service.refresh(List.of(coastal()), List.of(DATE), JOB_RUN_ID);

        verify(marineWaveRepository).findByLocation_IdInAndEvaluationDateIn(List.of(1L), List.of(DATE));
        verifyNoMoreInteractions(marineWaveRepository);
        verifyNoInteractions(tideExtremeRepository, highWaterWriter);
    }
}
