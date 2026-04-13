package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.client.LightPollutionClient;
import com.gregochr.goldenhour.client.LightPollutionClient.SkyBrightnessResult;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.RunProgressTracker;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BortleEnrichmentService}.
 */
@ExtendWith(MockitoExtension.class)
class BortleEnrichmentServiceTest {

    @Mock private LocationRepository locationRepository;
    @Mock private LightPollutionClient lightPollutionClient;
    @Mock private JobRunService jobRunService;
    @Mock private RunProgressTracker progressTracker;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BortleEnrichmentService service;
    private JobRunEntity stubJobRun;

    @BeforeEach
    void setUp() {
        service = new BortleEnrichmentService(locationRepository, lightPollutionClient,
                jobRunService, progressTracker, eventPublisher);
        stubJobRun = new JobRunEntity();
        stubJobRun.setId(1L);
    }

    @Test
    @DisplayName("enrichAll returns zero counts when no locations are pending")
    void enrichAll_noPendingLocations_returnsZeroCounts() {
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of());

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("test-key", stubJobRun);

        assertThat(result.enriched()).isZero();
        assertThat(result.failed()).isEmpty();
        verify(lightPollutionClient, never())
                .querySkyBrightness(anyDouble(), anyDouble(), eq("test-key"));
    }

    @Test
    @DisplayName("enrichAll saves Bortle class and SQM when API returns a value")
    void enrichAll_apiReturnsValue_savesAndCounts() {
        var loc = locationEntity("Bamburgh", 55.6, -1.7);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc));
        when(lightPollutionClient.querySkyBrightness(55.6, -1.7, "key"))
                .thenReturn(new SkyBrightnessResult(21.75, 3));

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("key", stubJobRun);

        assertThat(result.enriched()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
        assertThat(loc.getBortleClass()).isEqualTo(3);
        assertThat(loc.getSkyBrightnessSqm()).isEqualTo(21.75);
        verify(locationRepository, times(1)).save(loc);
    }

    @Test
    @DisplayName("enrichAll records failure when API returns null")
    void enrichAll_apiReturnsNull_recordsFailure() {
        var loc = locationEntity("Urban Site", 53.5, -2.2);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc));
        when(lightPollutionClient.querySkyBrightness(53.5, -2.2, "key"))
                .thenReturn(null);

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("key", stubJobRun);

        assertThat(result.enriched()).isZero();
        assertThat(result.failed()).containsExactly("Urban Site");
        verify(locationRepository, never()).save(loc);
    }

    @Test
    @DisplayName("enrichAll handles mixed success and failure across multiple locations")
    void enrichAll_mixedResults_countsCorrectly() {
        var loc1 = locationEntity("Dark Peak", 53.4, -1.8);
        var loc2 = locationEntity("City Centre", 53.5, -2.2);
        var loc3 = locationEntity("Coast", 55.7, -1.5);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc1, loc2, loc3));
        when(lightPollutionClient.querySkyBrightness(53.4, -1.8, "key"))
                .thenReturn(new SkyBrightnessResult(20.8, 4));
        when(lightPollutionClient.querySkyBrightness(53.5, -2.2, "key"))
                .thenReturn(null);
        when(lightPollutionClient.querySkyBrightness(55.7, -1.5, "key"))
                .thenReturn(new SkyBrightnessResult(21.95, 2));

        BortleEnrichmentService.EnrichmentResult result = service.enrichAll("key", stubJobRun);

        assertThat(result.enriched()).isEqualTo(2);
        assertThat(result.failed()).containsExactly("City Centre");
    }

    @Test
    @DisplayName("enrichAll calls the API for each pending location")
    void enrichAll_callsApiForEachPendingLocation() {
        var loc1 = locationEntity("A", 54.0, -1.0);
        var loc2 = locationEntity("B", 55.0, -2.0);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc1, loc2));
        when(lightPollutionClient.querySkyBrightness(anyDouble(), anyDouble(), eq("key")))
                .thenReturn(new SkyBrightnessResult(19.8, 5));

        service.enrichAll("key", stubJobRun);

        verify(lightPollutionClient, times(1)).querySkyBrightness(54.0, -1.0, "key");
        verify(lightPollutionClient, times(1)).querySkyBrightness(55.0, -2.0, "key");
    }

    // -------------------------------------------------------------------------
    // logApiCall verification
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("logApiCall on success: LIGHT_POLLUTION service, GET, status 200, succeeded=true, no error")
    void enrichAll_apiSuccess_logsCallWithCorrectArguments() {
        var loc = locationEntity("Bamburgh", 55.6, -1.7);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc));
        when(lightPollutionClient.querySkyBrightness(55.6, -1.7, "key"))
                .thenReturn(new SkyBrightnessResult(21.75, 3));

        service.enrichAll("key", stubJobRun);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jobRunService).logApiCall(
                eq(1L),
                eq(ServiceName.LIGHT_POLLUTION),
                eq("GET"),
                urlCaptor.capture(),
                isNull(),
                durationCaptor.capture(),
                eq(200),
                isNull(),
                eq(true),
                isNull());
        // Longitude (-1.7) must appear before latitude (55.6) — API requires lon,lat order
        assertThat(urlCaptor.getValue()).contains("qd=-1.700000,55.600000");
        assertThat(urlCaptor.getValue()).doesNotContain("qd=55.600000,-1.700000");
        assertThat(durationCaptor.getValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("logApiCall on failure: LIGHT_POLLUTION service, GET, null status, succeeded=false, error message set")
    void enrichAll_apiFailure_logsCallWithNullStatusAndFailedFlag() {
        var loc = locationEntity("Urban Site", 53.5, -2.2);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc));
        when(lightPollutionClient.querySkyBrightness(53.5, -2.2, "key"))
                .thenReturn(null);

        service.enrichAll("key", stubJobRun);

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jobRunService).logApiCall(
                eq(1L),
                eq(ServiceName.LIGHT_POLLUTION),
                eq("GET"),
                urlCaptor.capture(),
                isNull(),
                durationCaptor.capture(),
                isNull(),
                isNull(),
                eq(false),
                eq("API returned no data"));
        assertThat(urlCaptor.getValue()).contains("qd=-2.200000,53.500000");
        assertThat(durationCaptor.getValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("logApiCall is called once per location, not batched or skipped")
    void enrichAll_twoLocationsAllSucceed_logsApiCallTwice() {
        var loc1 = locationEntity("Kielder", 55.2, -2.6);
        var loc2 = locationEntity("Bamburgh", 55.6, -1.7);
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of(loc1, loc2));
        when(lightPollutionClient.querySkyBrightness(55.2, -2.6, "key"))
                .thenReturn(new SkyBrightnessResult(21.9, 2));
        when(lightPollutionClient.querySkyBrightness(55.6, -1.7, "key"))
                .thenReturn(new SkyBrightnessResult(21.75, 3));

        service.enrichAll("key", stubJobRun);

        verify(jobRunService, times(2)).logApiCall(
                eq(1L), eq(ServiceName.LIGHT_POLLUTION), eq("GET"),
                anyString(), isNull(), anyLong(), eq(200), isNull(), eq(true), isNull());
    }

    @Test
    @DisplayName("logApiCall is never invoked when there are no pending locations")
    void enrichAll_noPendingLocations_logApiCallNeverInvoked() {
        when(locationRepository.findByBortleClassIsNull()).thenReturn(List.of());

        service.enrichAll("key", stubJobRun);

        verify(jobRunService, never()).logApiCall(
                anyLong(), eq(ServiceName.LIGHT_POLLUTION), anyString(),
                anyString(), isNull(), anyLong(), any(), isNull(), anyBoolean(), anyString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LocationEntity locationEntity(String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(1L)
                .name(name)
                .lat(lat)
                .lon(lon)
                .enabled(true)
                .build();
    }
}
