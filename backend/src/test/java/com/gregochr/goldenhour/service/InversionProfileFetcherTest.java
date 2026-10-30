package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch prefetch and on-demand fetch of the v2 valley profile: eligibility, one request per distinct
 * valley-floor point, the location-id keying, the memo, and "a failure is no profile, never an exception".
 * Every stub and verify names the exact [lat, lng, floor] points it expects to be sent.
 */
class InversionProfileFetcherTest {

    private OpenMeteoClient client;
    private InversionProfileFetcher fetcher;
    private long now;

    @BeforeEach
    void setUp() {
        client = mock(OpenMeteoClient.class);
        fetcher = new InversionProfileFetcher(client);
        now = 1_000_000L;
        fetcher.nowMs = () -> now;
    }

    private static LocationEntity site(long id, int elevation, boolean water, Integer floor, Double lat,
            Double lng) {
        return LocationEntity.builder().id(id).name("Site " + id).lat(54.5).lon(-3.1)
                .elevationMetres(elevation).overlooksWater(water)
                .valleyFloorMetres(floor).valleyFloorLat(lat).valleyFloorLng(lng).build();
    }

    private static double latOf(long id) {
        return 54.575 + id * 0.001;
    }

    private static LocationEntity eligible(long id) {
        return site(id, 451, true, 80, latOf(id), -3.152);
    }

    /** The exact points a request must carry, in order. */
    private static ArgumentMatcher<List<double[]>> sending(double[]... expected) {
        return actual -> actual != null && actual.size() == expected.length
                && java.util.stream.IntStream.range(0, expected.length)
                        .allMatch(i -> Arrays.equals(actual.get(i), expected[i]));
    }

    private static double[] point(double lat, double lng, double floor) {
        return new double[]{lat, lng, floor};
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

    private static List<OpenMeteoForecastResponse> responses(OpenMeteoForecastResponse... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    @Test
    @DisplayName("eligibility: elevation >= 200, overlooks water and a complete valley floor")
    void eligibility() {
        assertThat(InversionProfileEligibility.isEligible(eligible(1))).isTrue();
        assertThat(InversionProfileEligibility.isEligible(site(2, 200, true, 80, 54.5, -3.1))).isTrue();
        assertThat(InversionProfileEligibility.isEligible(site(3, 199, true, 80, 54.5, -3.1))).isFalse();
        assertThat(InversionProfileEligibility.isEligible(site(4, 451, false, 80, 54.5, -3.1))).isFalse();
        assertThat(InversionProfileEligibility.isEligible(site(5, 451, true, null, 54.5, -3.1))).isFalse();
        assertThat(InversionProfileEligibility.isEligible(site(6, 451, true, 80, null, -3.1))).isFalse();
        assertThat(InversionProfileEligibility.isEligible(site(7, 451, true, 80, 54.5, null))).isFalse();
        assertThat(InversionProfileEligibility.isEligible(
                LocationEntity.builder().id(8L).name("No elevation").lat(54.5).lon(-3.1).overlooksWater(true)
                        .valleyFloorMetres(80).valleyFloorLat(54.5).valleyFloorLng(-3.1).build())).isFalse();
        assertThat(InversionProfileEligibility.isEligible(null)).isFalse();
    }

    @Test
    @DisplayName("prefetch fetches only eligible sites and keys the result by LOCATION ID")
    void prefetchKeysByLocationId() {
        LocationEntity ineligible = site(3, 100, true, 80, 54.5, -3.1);
        when(client.fetchInversionProfileBatch(argThat(sending(
                point(latOf(1), -3.152, 80), point(latOf(2), -3.152, 80)))))
                .thenReturn(responses(response(80), response(81)));

        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(eligible(1), ineligible, eligible(2)));

        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(1L).cellElevationMetres()).isEqualTo(80.0);
        assertThat(result.get(2L).cellElevationMetres()).isEqualTo(81.0);
        assertThat(result.get(1L).valleyFloorMetres()).isEqualTo(80);
        assertThat(result.get(1L).slotTime()).isNull();
        assertThat(result.get(1L).hours()).hasSize(2);
    }

    @Test
    @DisplayName("prefetch sends [lat, lng, floor] per distinct request, sharing one fetch between twin sites")
    void prefetchDeduplicatesByRequest() {
        LocationEntity a = site(1, 451, true, 80, 54.575, -3.152);
        LocationEntity twin = site(2, 368, true, 80, 54.575, -3.152);
        LocationEntity other = site(3, 451, true, 92, 54.593, -3.121);
        when(client.fetchInversionProfileBatch(argThat(sending(
                point(54.575, -3.152, 80), point(54.593, -3.121, 92)))))
                .thenReturn(responses(response(80), response(92)));

        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(a, twin, other));

        assertThat(result).containsOnlyKeys(1L, 2L, 3L);
        assertThat(result.get(2L)).isSameAs(result.get(1L));
        assertThat(result.get(3L).cellElevationMetres()).isEqualTo(92.0);
    }

    @Test
    @DisplayName("prefetch with nothing eligible makes no request")
    void prefetchNothingEligible() {
        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(site(1, 100, true, 80, 54.5, -3.1)));

        assertThat(result).isEmpty();
        verify(client, never()).fetchInversionProfileBatch(any());
    }

    @Test
    @DisplayName("prefetch: a null response (failed chunk) leaves that site out; the others keep theirs")
    void prefetchPartialFailure() {
        when(client.fetchInversionProfileBatch(argThat(sending(
                point(latOf(1), -3.152, 80), point(latOf(2), -3.152, 80)))))
                .thenReturn(responses(response(80), null));

        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(eligible(1), eligible(2)));

        assertThat(result).containsOnlyKeys(1L);
    }

    @Test
    @DisplayName("prefetch: one malformed cell (bad time string) loses only its own profile")
    void prefetchMalformedCellLosesOnlyItself() {
        OpenMeteoForecastResponse malformed = response(81);
        malformed.getHourly().setTime(List.of("not-a-time", "2025-10-15T07:00"));
        when(client.fetchInversionProfileBatch(argThat(sending(
                point(latOf(1), -3.152, 80), point(latOf(2), -3.152, 80), point(latOf(3), -3.152, 80)))))
                .thenReturn(responses(response(80), malformed, response(82)));

        Map<Long, InversionProfile> result = fetcher.prefetch(List.of(eligible(1), eligible(2), eligible(3)));

        assertThat(result).containsOnlyKeys(1L, 3L);
        assertThat(result.get(3L).cellElevationMetres()).isEqualTo(82.0);
    }

    @Test
    @DisplayName("prefetch: a response with no hourly data is no profile")
    void prefetchEmptyResponse() {
        when(client.fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80)))))
                .thenReturn(responses(new OpenMeteoForecastResponse()));

        assertThat(fetcher.prefetch(List.of(eligible(1)))).isEmpty();
    }

    @Test
    @DisplayName("prefetch never throws: a client failure is an empty map")
    void prefetchNeverThrows() {
        when(client.fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80)))))
                .thenThrow(new RuntimeException("boom"));

        assertThat(fetcher.prefetch(List.of(eligible(1)))).isEmpty();
    }

    @Test
    @DisplayName("on demand: an ineligible site is null with no request")
    void onDemandIneligible() {
        assertThat(fetcher.fetchOnDemand(site(1, 100, true, 80, 54.5, -3.1))).isNull();
        verify(client, never()).fetchInversionProfileBatch(any());
    }

    @Test
    @DisplayName("on demand: a site called for many dates and events costs one request, for its own point")
    void onDemandIsMemoised() {
        LocationEntity site = eligible(1);
        when(client.fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80)))))
                .thenReturn(responses(response(80)));

        InversionProfile first = fetcher.fetchOnDemand(site);
        for (int i = 0; i < 7; i++) {
            assertThat(fetcher.fetchOnDemand(site)).isSameAs(first);
        }

        assertThat(first).isNotNull();
        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80))));
    }

    @Test
    @DisplayName("on demand: two sites with the same valley-floor request share one fetch")
    void onDemandSharedByRequestKey() {
        when(client.fetchInversionProfileBatch(argThat(sending(point(54.575, -3.152, 80)))))
                .thenReturn(responses(response(80)));

        InversionProfile a = fetcher.fetchOnDemand(site(1, 451, true, 80, 54.575, -3.152));
        InversionProfile b = fetcher.fetchOnDemand(site(2, 368, true, 80, 54.575, -3.152));

        assertThat(b).isSameAs(a);
        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(54.575, -3.152, 80))));
    }

    @Test
    @DisplayName("on demand: a different valley-floor point or height is a different request")
    void onDemandDistinctKeys() {
        when(client.fetchInversionProfileBatch(argThat(sending(point(54.575, -3.152, 80)))))
                .thenReturn(responses(response(80)));
        when(client.fetchInversionProfileBatch(argThat(sending(point(54.575, -3.152, 81)))))
                .thenReturn(responses(response(81)));
        when(client.fetchInversionProfileBatch(argThat(sending(point(54.576, -3.152, 80)))))
                .thenReturn(responses(response(82)));

        InversionProfile a = fetcher.fetchOnDemand(site(1, 451, true, 80, 54.575, -3.152));
        InversionProfile b = fetcher.fetchOnDemand(site(2, 451, true, 81, 54.575, -3.152));
        InversionProfile c = fetcher.fetchOnDemand(site(3, 451, true, 80, 54.576, -3.152));

        assertThat(a.cellElevationMetres()).isEqualTo(80.0);
        assertThat(b.cellElevationMetres()).isEqualTo(81.0);
        assertThat(c.cellElevationMetres()).isEqualTo(82.0);
        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(54.575, -3.152, 80))));
        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(54.575, -3.152, 81))));
        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(54.576, -3.152, 80))));
    }

    @Test
    @DisplayName("on demand: the memo expires after its time to live")
    void onDemandMemoExpires() {
        LocationEntity site = eligible(1);
        when(client.fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80)))))
                .thenReturn(responses(response(80)));

        fetcher.fetchOnDemand(site);
        now += InversionProfileFetcher.MEMO_TTL_MS - 1;
        fetcher.fetchOnDemand(site);
        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80))));

        now += 1;
        fetcher.fetchOnDemand(site);
        verify(client, times(2)).fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80))));
    }

    @Test
    @DisplayName("on demand: a failure is remembered too, so a failing site is not retried per slot")
    void onDemandFailureIsMemoised() {
        LocationEntity site = eligible(1);
        when(client.fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80)))))
                .thenThrow(new RuntimeException("429"));

        assertThat(fetcher.fetchOnDemand(site)).isNull();
        assertThat(fetcher.fetchOnDemand(site)).isNull();

        verify(client, times(1)).fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80))));
    }

    @Test
    @DisplayName("on demand: a null response entry is no profile")
    void onDemandNullResponse() {
        List<OpenMeteoForecastResponse> nullResponse = new ArrayList<>();
        nullResponse.add(null);
        when(client.fetchInversionProfileBatch(argThat(sending(point(latOf(1), -3.152, 80)))))
                .thenReturn(nullResponse);

        assertThat(fetcher.fetchOnDemand(eligible(1))).isNull();
    }
}
