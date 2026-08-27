package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorldTidesIngestionService} covering the {@code jobRun != null} branches
 * that {@code TideServiceTest} does not exercise — every existing fetch/backfill fixture there
 * calls the single-{@code location} overload (or passes {@code null}), so the job-run metrics
 * branches (including {@link WorldTidesIngestionService#getStatusCode}, reached only from inside
 * those branches) were previously covered only incidentally, if at all, by the much larger
 * pre-split {@code TideService}. Split out from {@code TideServiceTest} rather than added to it,
 * since these fixtures are new coverage, not a repeat of the "pure move" proof.
 */
@ExtendWith(MockitoExtension.class)
class WorldTidesIngestionServiceTest {

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private WorldTidesProperties worldTidesProperties;

    @Mock
    private JobRunService jobRunService;

    private LocationEntity location() {
        return LocationEntity.builder().id(1L).name("Berwick-Upon-Tweed")
                .lat(55.7702).lon(-2.0054).build();
    }

    private WorldTidesResponse errorResponse(int status) {
        WorldTidesResponse response = new WorldTidesResponse();
        response.setStatus(status);
        return response;
    }

    // -------------------------------------------------------------------------
    // fetchAndStoreTideExtremes(location, jobRun) — failure branches with a non-null jobRun
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fetchAndStoreTideExtremes() logs a failed metered call when WorldTides "
            + "returns a non-200 status and a job run is supplied")
    void fetchAndStoreTideExtremes_nonOkStatusWithJobRun_logsFailedMeteredCall() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        RestClient mockClient = mock(RestClient.class);
        RestClientMocks.stubGet(mockClient, WorldTidesResponse.class, errorResponse(400));
        WorldTidesIngestionService service = new WorldTidesIngestionService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        service.fetchAndStoreTideExtremes(location(), JobRunEntity.builder().id(9L).build());

        verify(jobRunService).logMeteredApiCall(eq(9L), eq(ServiceName.WORLD_TIDES),
                eq("GET"), anyString(), anyLong(), eq(400), eq(false), anyString(), isNull());
    }

    @Test
    @DisplayName("fetchAndStoreTideExtremes() logs a failed call with the extracted HTTP status "
            + "code when the vendor call throws a RestClientResponseException and a job run is "
            + "supplied")
    void fetchAndStoreTideExtremes_exceptionWithJobRun_logsFailedCallWithStatusCode() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        RestClient mockClient = mock(RestClient.class);
        RestClientMocks.stubGetThrows(mockClient, WorldTidesResponse.class,
                new RestClientResponseException("Service Unavailable", 503,
                        "Service Unavailable", null, null, null));
        WorldTidesIngestionService service = new WorldTidesIngestionService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        service.fetchAndStoreTideExtremes(location(), JobRunEntity.builder().id(9L).build());

        verify(jobRunService).logApiCall(eq(9L), eq(ServiceName.WORLD_TIDES),
                eq("GET"), anyString(), isNull(), anyLong(), eq(503), isNull(), eq(false),
                anyString());
    }

    // -------------------------------------------------------------------------
    // backfillTideExtremes(location, jobRun) — failure branches with a non-null jobRun
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("backfillTideExtremes() logs a failed call per chunk when WorldTides returns a "
            + "non-200 status and a job run is supplied")
    void backfillTideExtremes_nonOkStatusWithJobRun_logsFailedCall() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(
                anyLong(), any(), any())).thenReturn(false);
        RestClient mockClient = mock(RestClient.class);
        RestClientMocks.stubGet(mockClient, WorldTidesResponse.class, errorResponse(400));
        WorldTidesIngestionService service = new WorldTidesIngestionService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        int fetched = service.backfillTideExtremes(location(), JobRunEntity.builder().id(9L).build());

        assertThat(fetched).isZero();
        verify(jobRunService, atLeastOnce()).logApiCall(eq(9L),
                eq(ServiceName.WORLD_TIDES), eq("GET"), anyString(), isNull(), anyLong(), eq(400),
                isNull(), eq(false), eq("Non-200 status on backfill"));
    }

    @Test
    @DisplayName("backfillTideExtremes() logs a failed call with a null status code — the "
            + "exception is not an HTTP response exception — when a job run is supplied")
    void backfillTideExtremes_exceptionWithJobRun_logsFailedCallWithNullStatusCode() {
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.existsByLocationIdAndEventTimeBetween(
                anyLong(), any(), any())).thenReturn(false);
        RestClient mockClient = mock(RestClient.class);
        RestClientMocks.stubGetThrows(mockClient, WorldTidesResponse.class,
                new RuntimeException("connection reset"));
        WorldTidesIngestionService service = new WorldTidesIngestionService(
                mockClient, tideExtremeRepository, worldTidesProperties, jobRunService);

        service.backfillTideExtremes(location(), JobRunEntity.builder().id(9L).build());

        verify(jobRunService, atLeastOnce()).logApiCall(eq(9L),
                eq(ServiceName.WORLD_TIDES), eq("GET"), anyString(), isNull(), anyLong(), isNull(),
                isNull(), eq(false), eq("connection reset"));
    }
}
