package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.PrefetchCall;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Failure-reporting edges of {@link OpenMeteoService}: how an upstream failure is recorded against a job run (or
 * silently dropped without one), the HTTP status it keeps, the 429 read from a message, and the far-point
 * pressure-level prefetch.
 */
class OpenMeteoServiceFailureTest {

    private static final double LAT = 55.0;
    private static final double LON = -1.5;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final LocalDateTime SUNSET = LocalDateTime.of(2026, 6, 17, 20, 30);
    private static final ForecastRequest REQUEST = new ForecastRequest(LAT, LON, "Bamburgh", DATE,
            TargetType.SUNSET);
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT
            + "&longitude=" + LON + "&hourly=" + OpenMeteoClient.FORECAST_PARAMS
            + "&wind_speed_unit=ms&timezone=UTC";
    private static final String AIR_QUALITY_URL = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude="
            + LAT + "&longitude=" + LON + "&hourly=" + OpenMeteoClient.AIR_QUALITY_PARAMS + "&timezone=UTC";

    private OpenMeteoClient client;
    private JobRunService jobRunService;
    private OpenMeteoService service;
    private final JobRunEntity jobRun = JobRunEntity.builder().id(9L).build();

    @BeforeEach
    void setUp() {
        client = mock(OpenMeteoClient.class);
        jobRunService = mock(JobRunService.class);
        service = new OpenMeteoService(client, jobRunService);
    }

    private void verifyBothCallsLogged(Integer status, String error) {
        ArgumentCaptor<Long> forecastDuration = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> airDuration = ArgumentCaptor.forClass(Long.class);
        verify(jobRunService).logApiCall(eq(9L), eq(ServiceName.OPEN_METEO_FORECAST), eq("GET"), eq(FORECAST_URL),
                isNull(), forecastDuration.capture(), status == null ? isNull() : eq(status), isNull(), eq(false),
                eq(error));
        verify(jobRunService).logApiCall(eq(9L), eq(ServiceName.OPEN_METEO_AIR_QUALITY), eq("GET"),
                eq(AIR_QUALITY_URL), isNull(), airDuration.capture(), status == null ? isNull() : eq(status),
                isNull(), eq(false), eq(error));
        assertThat(forecastDuration.getValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("a message-less failure is logged under the exception's class name with no HTTP status")
    void getAtmosphericData_messageLessFailure_loggedByClassName() {
        when(client.fetchForecast(LAT, LON)).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> service.getAtmosphericData(REQUEST, SUNSET, jobRun))
                .isInstanceOf(IllegalStateException.class);

        verifyBothCallsLogged(null, "IllegalStateException");
    }

    @Test
    @DisplayName("an HTTP error response is logged with its status code and message")
    void getAtmosphericData_httpFailure_loggedWithStatus() {
        HttpClientErrorException tooMany = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        when(client.fetchForecast(LAT, LON)).thenThrow(tooMany);

        assertThatThrownBy(() -> service.getAtmosphericData(REQUEST, SUNSET, jobRun))
                .isSameAs(tooMany);

        verifyBothCallsLogged(429, tooMany.getMessage());
    }

    @Test
    @DisplayName("without a job run a failure is rethrown and nothing is recorded")
    void getAtmosphericData_failureWithoutJobRun_notRecorded() {
        when(client.fetchForecast(LAT, LON)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.getAtmosphericData(REQUEST, SUNSET))
                .hasMessage("boom");

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("the hourly path records a message-less failure under its class name and rethrows")
    void getHourlyAtmosphericData_failure_loggedAndRethrown() {
        when(client.fetchForecast(LAT, LON)).thenThrow(new IllegalStateException());

        assertThatThrownBy(() -> service.getHourlyAtmosphericData(REQUEST, SUNSET, SUNSET.plusHours(2), jobRun))
                .isInstanceOf(IllegalStateException.class);

        verifyBothCallsLogged(null, "IllegalStateException");
    }

    @Test
    @DisplayName("the hourly path without a job run records nothing")
    void getHourlyAtmosphericData_failureWithoutJobRun_notRecorded() {
        when(client.fetchForecast(LAT, LON)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.getHourlyAtmosphericData(REQUEST, SUNSET, SUNSET.plusHours(2)))
                .hasMessage("boom");

        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("a failed cloud prefetch reports the class name when the message is empty")
    void prefetchCloudBatch_messageLessFailure_reportedByClassName() {
        List<double[]> coords = List.<double[]>of(new double[]{LAT, LON});
        when(client.fetchCloudOnlyBatch(coords)).thenThrow(new IllegalStateException());
        List<PrefetchCall> calls = new ArrayList<>();

        CloudPointCache cache = service.prefetchCloudBatch(coords, null, calls::add);

        assertThat(cache).isNotNull();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).succeeded()).isFalse();
        assertThat(calls.get(0).error()).isEqualTo("IllegalStateException");
        assertThat(calls.get(0).statusCode()).isNull();
        assertThat(calls.get(0).coordsRequested()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rate limit that only shows in the message is still recorded as a 429")
    void prefetchCloudBatch_rateLimitInMessage_recordedAs429() {
        List<double[]> coords = List.<double[]>of(new double[]{LAT, LON});
        when(client.fetchCloudOnlyBatch(coords)).thenThrow(new IllegalStateException("429 Too Many Requests"));
        List<PrefetchCall> calls = new ArrayList<>();

        service.prefetchCloudBatch(coords, null, calls::add);

        assertThat(calls.get(0).statusCode()).isEqualTo(429);
        assertThat(calls.get(0).error()).isEqualTo("429 Too Many Requests");
    }

    @Test
    @DisplayName("a failed pressure-level prefetch is recorded by class name; the cloud cells are still returned")
    void prefetchCloudBatch_pressureLevelFailure_recorded() {
        double[] near = {LAT, LON};
        double[] far = {LAT + 1.0, LON};
        OpenMeteoForecastResponse cell = new OpenMeteoForecastResponse();
        when(client.fetchCloudOnlyBatch(List.<double[]>of(near))).thenReturn(List.of(cell));
        when(client.fetchPressureLevelCloudBatch(List.<double[]>of(far))).thenThrow(new IllegalStateException());
        List<PrefetchCall> calls = new ArrayList<>();

        CloudPointCache cache = service.prefetchCloudBatch(List.<double[]>of(near), List.<double[]>of(far),
                calls::add);

        assertThat(cache).isNotNull();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).succeeded()).isTrue();
        assertThat(calls.get(1).succeeded()).isFalse();
        assertThat(calls.get(1).error()).isEqualTo("IllegalStateException");
        assertThat(calls.get(1).label()).isEqualTo("batch-prefetch pressure levels (1)");
    }

    @Test
    @DisplayName("an empty far-point list skips the pressure-level fetch entirely")
    void prefetchCloudBatch_emptyFarPoints_noPressureFetch() {
        double[] near = {LAT, LON};
        when(client.fetchCloudOnlyBatch(List.<double[]>of(near))).thenReturn(List.of(new OpenMeteoForecastResponse()));
        List<PrefetchCall> calls = new ArrayList<>();

        service.prefetchCloudBatch(List.<double[]>of(near), List.of(), calls::add);

        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("the inversion-profile calls are delegated to the profile fetcher with the same arguments")
    void inversionProfileCalls_delegate() {
        LocationEntity location = LocationEntity.builder().id(1L).name("Plain").lat(LAT).lon(LON).build();
        Collection<LocationEntity> locations = List.of(location);

        Map<Long, InversionProfile> prefetched = service.prefetchInversionProfiles(locations);
        InversionProfile onDemand = service.fetchInversionProfileOnDemand(location);

        // An ineligible location yields nothing and costs no request.
        assertThat(prefetched).isEmpty();
        assertThat(onDemand).isNull();
        verifyNoInteractions(client);
    }
}
