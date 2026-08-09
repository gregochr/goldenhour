package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.WorldTidesResponse;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.util.RestClientMocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code TideService.resolveFetchWindow} — the decision that sets both the
 * WorldTides credit cost and the range the merge deletes.
 *
 * <p>Before this existed the weekly refresh re-fetched the entire forward window every Monday:
 * ~14 credits per coastal location for roughly 90 days of data already stored, and ~375 rows
 * deleted and rewritten bit-identically. Tide predictions are deterministic astronomy, so almost
 * none of that bought anything.
 */
@ExtendWith(MockitoExtension.class)
class TideFetchWindowTest {

    private static final Long LOCATION = 7L;
    private static final LocalDateTime START_OF_DAY = LocalDate.of(2026, 8, 10).atStartOfDay();
    private static final LocalDateTime HORIZON = START_OF_DAY.plusDays(97);

    @Mock
    private RestClient restClient;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private WorldTidesProperties worldTidesProperties;

    @Mock
    private JobRunService jobRunService;

    private TideService service() {
        return new TideService(restClient, tideExtremeRepository, worldTidesProperties,
                jobRunService);
    }

    @Test
    @DisplayName("a location with no forward data is seeded across the whole window")
    void noForwardDataSeeds() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any())).thenReturn(null);

        var window = service().resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON);

        assertThat(window).isNotNull();
        assertThat(window.from()).isEqualTo(START_OF_DAY);
        assertThat(window.to()).isEqualTo(HORIZON);
        assertThat(window.reason()).startsWith("seed");
        assertThat(window.reason()).doesNotContain("re-seed");
    }

    @Test
    @DisplayName("a location already covered to T+90 fetches only the seven-day tail, not the "
            + "whole window again")
    void coveredLocationFetchesOnlyTheTail() {
        // A real frontier is a tide instant, not a midnight — MAX(event_time) over stored rows.
        // The fixture used to sit on midnight, which made the span look like exactly seven days
        // and a one-credit fetch. It is not: the horizon IS midnight, so the tail spans seven days
        // plus up to one inter-extreme gap, and bills two credits under the per-seven-days rule.
        // Still ~4.8x cheaper than re-buying the whole 97-day window every Monday.
        LocalDateTime frontier = START_OF_DAY.plusDays(90).plusHours(17).plusMinutes(52);
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(frontier);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(START_OF_DAY.minusDays(7));

        var window = service().resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON);

        assertThat(window).isNotNull();
        assertThat(window.from()).isEqualTo(frontier);
        assertThat(window.to()).isEqualTo(HORIZON);
        assertThat(window.lengthSeconds())
                .isGreaterThan(6L * 24 * 3600)
                .isLessThanOrEqualTo(8L * 24 * 3600);
        assertThat(window.reason()).contains("tail");
    }

    @Test
    @DisplayName("a missed refresh heals itself — the tail is simply longer, no gap is left behind")
    void aMissedWeekProducesALongerTail() {
        // Last Monday failed, so the frontier is 83 days out instead of 90.
        LocalDateTime frontier = START_OF_DAY.plusDays(83);
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(frontier);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(START_OF_DAY.minusDays(14));

        var window = service().resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON);

        assertThat(window).isNotNull();
        assertThat(window.from()).isEqualTo(frontier);
        assertThat(window.lengthSeconds()).isEqualTo(14L * 24 * 3600);
    }

    @Test
    @DisplayName("coverage already at the horizon fetches nothing at all, rather than buying a "
            + "zero-day window")
    void alreadyAtHorizonSkipsTheCall() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(HORIZON.plusDays(1));
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(START_OF_DAY.minusDays(1));

        assertThat(service().resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON)).isNull();
    }

    @Test
    @DisplayName("a forward window older than the re-seed interval is refetched in full, so an "
            + "upstream revision is eventually picked up")
    void staleForwardWindowTriggersAReseed() {
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(START_OF_DAY.plusDays(90));
        // Oldest forward row was fetched 91 days ago — past the 90-day interval.
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(START_OF_DAY.minusDays(91));

        var window = service().resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON);

        assertThat(window).isNotNull();
        assertThat(window.from()).isEqualTo(START_OF_DAY);
        assertThat(window.to()).isEqualTo(HORIZON);
        assertThat(window.reason()).contains("re-seed");
    }

    @Test
    @DisplayName("one day inside the re-seed interval still takes the tail — the boundary is a "
            + "boundary, not a suggestion")
    void justInsideTheReseedIntervalStillTails() {
        LocalDateTime frontier = START_OF_DAY.plusDays(90);
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(frontier);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(START_OF_DAY.minusDays(89));

        var window = service().resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON);

        assertThat(window.reason()).contains("tail");
        assertThat(window.from()).isEqualTo(frontier);
    }

    @Test
    @DisplayName("⚠️ every branch starts at or after the start of today — the delete is derived "
            + "from this, and backfilled history sits strictly before it")
    void noBranchEverReachesBackwards() {
        // The 12-month backfill writes strictly into the past. If any branch returned a window
        // starting before today, the windowed delete would destroy it.
        record Case(String name, LocalDateTime frontier, LocalDateTime oldestFetch) { }
        var cases = new Case[]{
            new Case("seed", null, null),
            new Case("tail", START_OF_DAY.plusDays(90), START_OF_DAY.minusDays(1)),
            new Case("re-seed", START_OF_DAY.plusDays(90), START_OF_DAY.minusDays(400)),
            new Case("frontier in the past somehow", START_OF_DAY, START_OF_DAY.minusDays(1)),
        };

        for (Case c : cases) {
            var repo = org.mockito.Mockito.mock(TideExtremeRepository.class);
            org.mockito.Mockito.lenient()
                    .when(repo.findLatestEventTimeFrom(eq(LOCATION), any()))
                    .thenReturn(c.frontier());
            org.mockito.Mockito.lenient()
                    .when(repo.findOldestFetchedAtFrom(eq(LOCATION), any()))
                    .thenReturn(c.oldestFetch());
            var svc = new TideService(restClient, repo, worldTidesProperties, jobRunService);

            var window = svc.resolveFetchWindow(LOCATION, START_OF_DAY, HORIZON);
            if (window != null) {
                assertThat(window.from())
                        .as("%s must not start before the start of today", c.name())
                        .isAfterOrEqualTo(START_OF_DAY);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Wiring — the resolver decides, but the delete bounds and the API call are what
    // actually happen. Nothing tested the seam between them, so a correct resolver
    // feeding a wrong delete would have shipped green.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the tail branch deletes only the tail, leaving stored forward days untouched")
    void tailBranchNarrowsTheDeleteToTheFetchedSpan() {
        LocalDateTime frontier = START_OF_DAY.plusDays(90).plusHours(17).plusMinutes(52);
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(frontier);
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(LocalDateTime.now(ZoneOffset.UTC).minusDays(7));

        RestClient client = mock(RestClient.class);
        RestClientMocks.stubGet(client, WorldTidesResponse.class, oneExtremeResponse());
        TideService service = new TideService(client, tideExtremeRepository,
                worldTidesProperties, jobRunService);

        service.fetchAndStoreTideExtremes(location());

        ArgumentCaptor<LocalDateTime> bounds = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tideExtremeRepository).deleteByLocationIdAndEventTimeBetween(
                eq(LOCATION), bounds.capture(), bounds.capture());

        // The lower bound is the frontier, NOT the start of today — which is the whole point.
        // Deleting from today would destroy the 90 stored days this fetch did not re-buy.
        assertThat(bounds.getAllValues().get(0)).isEqualTo(frontier);
        assertThat(bounds.getAllValues().get(1))
                .isAfterOrEqualTo(frontier)
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).atStartOfDay().plusDays(97));
    }

    @Test
    @DisplayName("a location already at the horizon makes no HTTP call, deletes nothing and "
            + "writes nothing")
    void skipBranchTouchesNothingAtAll() {
        LocalDateTime horizonNow = LocalDate.now(ZoneOffset.UTC).atStartOfDay().plusDays(97);
        when(worldTidesProperties.getApiKey()).thenReturn("test-key");
        when(tideExtremeRepository.findLatestEventTimeFrom(eq(LOCATION), any()))
                .thenReturn(horizonNow.plusDays(1));
        when(tideExtremeRepository.findOldestFetchedAtFrom(eq(LOCATION), any()))
                .thenReturn(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));

        RestClient client = mock(RestClient.class);
        TideService service = new TideService(client, tideExtremeRepository,
                worldTidesProperties, jobRunService);

        service.fetchAndStoreTideExtremes(location());

        // No credits spent, and — more importantly — no delete. An early return that still
        // deleted would silently erase a whole window for the locations it meant to spare.
        verifyNoInteractions(client);
        verify(tideExtremeRepository, never()).deleteByLocationIdAndEventTimeBetween(
                any(), any(), any());
        verify(tideExtremeRepository, never()).saveAll(any());
    }

    private static LocationEntity location() {
        return LocationEntity.builder().id(LOCATION).name("Bamburgh Beach")
                .lat(55.6086).lon(-1.7092).build();
    }

    private static WorldTidesResponse oneExtremeResponse() {
        WorldTidesResponse response = new WorldTidesResponse();
        response.setStatus(200);
        response.setCallCount(2);
        WorldTidesResponse.Extreme extreme = new WorldTidesResponse.Extreme();
        extreme.setDt(LocalDateTime.now(ZoneOffset.UTC).plusDays(93)
                .toEpochSecond(ZoneOffset.UTC));
        extreme.setHeight(4.2);
        extreme.setType("High");
        response.setExtremes(List.of(extreme));
        return response;
    }
}
