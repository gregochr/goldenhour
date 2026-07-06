package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NlcSightingClient;
import com.gregochr.goldenhour.config.NlcProperties;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.NlcSightingReport;
import com.gregochr.goldenhour.model.NlcSightingResponse;
import com.gregochr.goldenhour.model.NlcWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NlcSightingService} — the season / freshness / clear-sky gate.
 *
 * <p>A fixed clock pins "today" to an in-season night so the gates are deterministic year-round.
 * Each test flips exactly one condition to prove it is load-bearing.
 */
@ExtendWith(MockitoExtension.class)
class NlcSightingServiceTest {

    /** 2026-06-20 21:00Z — a summer evening well inside NLC season. */
    private static final Instant NOW = Instant.parse("2026-06-20T21:00:00Z");
    private static final LocalDate TONIGHT = LocalDate.of(2026, 6, 20);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final NlcWindow EVENING = new NlcWindow("22:46", "23:52", "NW");
    private static final NlcWindow MORNING = new NlcWindow("02:10", "03:18", "NE");

    @Mock
    private NlcSightingClient client;
    @Mock
    private NlcClarityService clarityService;
    @Mock
    private DynamicSchedulerService schedulerService;

    private NlcSightingService service;

    @BeforeEach
    void setUp() {
        service = new NlcSightingService(
                client, clarityService, new NlcProperties(), schedulerService, CLOCK);
    }

    /** A sighting reported an hour ago — inside the 6-hour freshness window. */
    private static NlcSightingReport freshReport() {
        return new NlcSightingReport("Alan C Tough", "Elgin, Scotland", NOW.minusSeconds(3600));
    }

    private void inSeason() {
        when(clarityService.isNlcSeason(TONIGHT)).thenReturn(true);
    }

    private void clearTonight(int locations) {
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TONIGHT, locations, List.of("Scotland"),
                        EVENING, MORNING))));
    }

    @Test
    @DisplayName("fresh report + in season + clear tonight → active, honest reactive copy")
    void currentSighting_allGatesPass_active() {
        inSeason();
        when(client.getReports()).thenReturn(List.of(freshReport()));
        clearTonight(64);

        NlcSightingResponse response = service.currentSighting();

        assertThat(response.active()).isTrue();
        assertThat(response.observerLocation()).isEqualTo("Elgin");
        assertThat(response.region()).isEqualTo("Scotland");
        assertThat(response.description()).isEqualTo("Noctilucent cloud reported over Scotland");
        assertThat(response.clearTonight()).isTrue();
        assertThat(response.darkSkyLocationCount()).isEqualTo(64);
        assertThat(response.source()).isEqualTo("NLCNET");
        assertThat(response.reportedAt()).isEqualTo(NOW.minusSeconds(3600));
    }

    @Test
    @DisplayName("disabled feature → inactive, no scrape or clarity read")
    void currentSighting_disabled_inactive() {
        NlcProperties disabled = new NlcProperties();
        disabled.setSightingEnabled(false);
        NlcSightingService disabledService = new NlcSightingService(
                client, clarityService, disabled, schedulerService, CLOCK);

        assertThat(disabledService.currentSighting().active()).isFalse();
    }

    @Test
    @DisplayName("out of season → inactive")
    void currentSighting_outOfSeason_inactive() {
        when(clarityService.isNlcSeason(TONIGHT)).thenReturn(false);

        assertThat(service.currentSighting().active()).isFalse();
    }

    @Test
    @DisplayName("no reports → inactive")
    void currentSighting_noReports_inactive() {
        inSeason();
        when(client.getReports()).thenReturn(List.of());

        assertThat(service.currentSighting().active()).isFalse();
    }

    @Test
    @DisplayName("stale report (older than freshness window) → inactive")
    void currentSighting_staleReport_inactive() {
        inSeason();
        NlcSightingReport stale = new NlcSightingReport(
                "Old Observer", "Elgin, Scotland", NOW.minusSeconds(24 * 3600));
        when(client.getReports()).thenReturn(List.of(stale));

        assertThat(service.currentSighting().active()).isFalse();
    }

    @Test
    @DisplayName("fresh report but skies not clear tonight → inactive (honesty gate)")
    void currentSighting_notClearTonight_inactive() {
        inSeason();
        when(client.getReports()).thenReturn(List.of(freshReport()));
        // Clarity has a clear night, but for a different date — tonight is not confirmed clear.
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(TONIGHT.plusDays(1), 5, List.of("Scotland"),
                        EVENING, MORNING))));

        assertThat(service.currentSighting().active()).isFalse();
    }

    @Test
    @DisplayName("fresh report but no clarity computed yet → inactive (cannot confirm clear)")
    void currentSighting_noClarity_inactive() {
        inSeason();
        when(client.getReports()).thenReturn(List.of(freshReport()));
        when(clarityService.getCached()).thenReturn(null);

        assertThat(service.currentSighting().active()).isFalse();
    }

    @Test
    @DisplayName("region derivation skips a trailing country token")
    void currentSighting_regionSkipsCountryToken() {
        inSeason();
        when(client.getReports()).thenReturn(List.of(new NlcSightingReport(
                "Ken Kennedy", "Broughty Ferry, Dundee, Scotland, UK", NOW.minusSeconds(1800))));
        clearTonight(3);

        NlcSightingResponse response = service.currentSighting();

        assertThat(response.observerLocation()).isEqualTo("Broughty Ferry");
        assertThat(response.region()).isEqualTo("Scotland");
    }

    @Test
    @DisplayName("scrape target is a no-op when the feature is disabled")
    void scrapeIfEnabled_disabled_noScrape() {
        NlcProperties disabled = new NlcProperties();
        disabled.setSightingEnabled(false);
        lenient().when(client.refresh()).thenReturn(List.of());
        NlcSightingService disabledService = new NlcSightingService(
                client, clarityService, disabled, schedulerService, CLOCK);

        disabledService.scrapeIfEnabled();

        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).refresh();
    }

    @Test
    @DisplayName("scrape target re-scrapes when the feature is enabled")
    void scrapeIfEnabled_enabled_scrapes() {
        when(client.refresh()).thenReturn(List.of());

        service.scrapeIfEnabled();

        org.mockito.Mockito.verify(client).refresh();
    }

    @Test
    @DisplayName("registers the scrape target with the dynamic scheduler under its job key")
    void registerJob_registersTarget() {
        service.registerJob();

        org.mockito.Mockito.verify(schedulerService)
                .registerJobTarget(org.mockito.ArgumentMatchers.eq(NlcSightingService.SCRAPE_JOB_KEY),
                        org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    // ── End-to-end over the real live NLCNET table snapshot ─────────────────────

    /** Newest entry in the live 2026-07 fixture: Tomasz Adam, Kraków, 2026-07-03T00:30Z. */
    private static final Instant LIVE_NEWEST = Instant.parse("2026-07-03T00:30:00Z");

    private static String liveTableSnapshot() {
        try (InputStream in = NlcSightingServiceTest.class
                .getResourceAsStream("/nlc/nlcnet-live-table-2026-07.html")) {
            assertThat(in).as("live-table fixture present").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("end-to-end: a fresh live-table entry under clear skies activates the signal")
    void currentSighting_liveTableFreshEntry_active() {
        // Clock 3.5h after the newest live entry — inside the 6h freshness window.
        Clock clock = Clock.fixed(LIVE_NEWEST.plusSeconds(3600 * 3 + 1800), ZoneOffset.UTC);
        LocalDate today = LocalDate.of(2026, 7, 3);
        RestClient rest = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(rest.get().uri(anyString()).retrieve().body(String.class)).thenReturn(liveTableSnapshot());
        NlcSightingClient realClient = new NlcSightingClient(rest, new NlcProperties());
        when(clarityService.isNlcSeason(today)).thenReturn(true);
        when(clarityService.getCached()).thenReturn(new NlcNightClarity(List.of(
                new NlcNightClarity.ClearNight(today, 12, List.of("Poland"), EVENING, MORNING))));
        NlcSightingService liveService = new NlcSightingService(
                realClient, clarityService, new NlcProperties(), schedulerService, clock);

        NlcSightingResponse response = liveService.currentSighting();

        assertThat(response.active()).isTrue();
        assertThat(response.reportedAt()).isEqualTo(LIVE_NEWEST);
        assertThat(response.observerLocation()).isEqualTo("Kraków");
        assertThat(response.region()).isEqualTo("Poland");
    }

    @Test
    @DisplayName("end-to-end: the same live entry a week later is stale and stays dark")
    void currentSighting_liveTableStale_inactive() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T04:00:00Z"), ZoneOffset.UTC);
        LocalDate today = LocalDate.of(2026, 7, 10);
        RestClient rest = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(rest.get().uri(anyString()).retrieve().body(String.class)).thenReturn(liveTableSnapshot());
        NlcSightingClient realClient = new NlcSightingClient(rest, new NlcProperties());
        when(clarityService.isNlcSeason(today)).thenReturn(true);
        NlcSightingService liveService = new NlcSightingService(
                realClient, clarityService, new NlcProperties(), schedulerService, clock);

        assertThat(liveService.currentSighting().active()).isFalse();
    }
}
