package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

/**
 * Edge cases of {@link InversionProfileFetcher}: an eligible site without an id, fewer responses than
 * requested cells, and an empty on-demand response.
 */
class InversionProfileFetcherEdgeCaseTest {

    private OpenMeteoClient client;
    private InversionProfileFetcher fetcher;

    @BeforeEach
    void setUp() {
        client = mock(OpenMeteoClient.class);
        fetcher = new InversionProfileFetcher(client);
        fetcher.nowMs = () -> 1_000_000L;
    }

    private static LocationEntity site(Long id, double lat) {
        return LocationEntity.builder().id(id).name("Site " + id).lat(54.5).lon(-3.1)
                .elevationMetres(451).overlooksWater(true)
                .valleyFloorMetres(80).valleyFloorLat(lat).valleyFloorLng(-3.152).build();
    }

    private static ArgumentMatcher<List<double[]>> sending(double[]... expected) {
        return actual -> actual != null && actual.size() == expected.length
                && IntStream.range(0, expected.length).allMatch(i -> Arrays.equals(actual.get(i), expected[i]));
    }

    private static OpenMeteoForecastResponse response(double elevation) {
        OpenMeteoForecastResponse r = new OpenMeteoForecastResponse();
        r.setLatitude(54.575);
        r.setLongitude(-3.152);
        r.setElevation(elevation);
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(List.of("2025-10-15T06:00", "2025-10-15T07:00"));
        h.setTemperature2m(List.of(5.0, 6.0));
        r.setHourly(h);
        return r;
    }

    @Test
    @DisplayName("an eligible site with no id is not fetched, so no request is made")
    void prefetch_siteWithoutId_skipped() {
        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(site(null, 54.576)));

        assertThat(result).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("fewer responses than cells: the unanswered site simply gets no profile")
    void prefetch_shortResponseList() {
        when(client.fetchInversionProfileBatch(argThat(sending(
                new double[]{54.576, -3.152, 80}, new double[]{54.577, -3.152, 80}))))
                .thenReturn(new ArrayList<>(List.of(response(80))));

        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(site(1L, 54.576), site(2L, 54.577)));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L).cellElevationMetres()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("an empty on-demand response yields no profile")
    void fetchOnDemand_emptyResponse_null() {
        when(client.fetchInversionProfileBatch(argThat(sending(new double[]{54.576, -3.152, 80}))))
                .thenReturn(new ArrayList<>());

        assertThat(fetcher.fetchOnDemand(site(1L, 54.576))).isNull();
    }
}
