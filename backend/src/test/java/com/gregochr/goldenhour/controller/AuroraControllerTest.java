package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.AuroraViewlineRule;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AuroraController} role-based access and endpoint behaviour.
 */
class AuroraControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(stateCache.isActive()).thenReturn(false);
        when(stateCache.getCurrentLevel()).thenReturn(null);
        when(stateCache.getCachedScores()).thenReturn(List.of());
        // Avoid HTTP calls in tests
        when(noaaClient.getOrRefreshAsyncKp()).thenReturn(List.of());
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(noaaClient.getOrRefreshAsyncOvation()).thenReturn(null);
        when(noaaClient.getOrRefreshAsyncSolarWind()).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // Role-based access — /api/aurora/status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/status returns 403 for LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void getStatus_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/aurora/status returns 200 for ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("GET /api/aurora/status returns 200 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void getStatus_proUser_returns200() throws Exception {
        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/aurora/status returns 401 for unauthenticated request")
    void getStatus_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Status endpoint content
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/status returns QUIET level and active=false when IDLE")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_idle_returnsQuietAndNotActive() throws Exception {
        when(stateCache.isActive()).thenReturn(false);
        when(stateCache.getCurrentLevel()).thenReturn(null);
        when(stateCache.getCachedScores()).thenReturn(List.of());

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("QUIET"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.eligibleLocations").value(0));
    }

    @Test
    @DisplayName("GET /api/aurora/status returns MODERATE and active=true during active event")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_activeModerate_returnsModerateAndActive() throws Exception {
        when(stateCache.isActive()).thenReturn(true);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(stateCache.getCachedScores()).thenReturn(List.of(stubScore(3), stubScore(4)));

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("MODERATE"))
                .andExpect(jsonPath("$.hexColour").value("#ff9900"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.eligibleLocations").value(2));
    }

    @Test
    @DisplayName("GET /api/aurora/status includes bzNanoTesla when solar wind data is available")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_withSolarWind_returnsBz() throws Exception {
        SolarWindReading reading = new SolarWindReading(ZonedDateTime.now(), -8.3, 450.0, 5.0);
        when(noaaClient.getOrRefreshAsyncSolarWind()).thenReturn(List.of(reading));

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bzNanoTesla").value(-8.3));
    }

    @Test
    @DisplayName("GET /api/aurora/status reads NOAA through the cached async methods, never the synchronous fetches")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_usesCachedAsyncReads() throws Exception {
        mockMvc.perform(get("/api/aurora/status")).andExpect(status().isOk());

        verify(noaaClient).getOrRefreshAsyncKp();
        verify(noaaClient).getOrRefreshAsyncOvation();
        verify(noaaClient).getOrRefreshAsyncSolarWind();
        verify(noaaClient, never()).fetchKp();
        verify(noaaClient, never()).fetchOvation();
        verify(noaaClient, never()).fetchSolarWind();
    }

    @Test
    @DisplayName("GET /api/aurora/status returns null bzNanoTesla when solar wind list is empty")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_noSolarWind_returnsBzNull() throws Exception {
        when(noaaClient.getOrRefreshAsyncSolarWind()).thenReturn(List.of());

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bzNanoTesla").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/aurora/status serves the run service's night and its end, not a calendar date")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_carriesCurrentNightFromRunService() throws Exception {
        // A fixed date in the past, deliberately never today: the whole point of this field is that
        // the night in progress is NOT derivable from a calendar, so a controller that re-derived
        // one — on any zone — would fail here rather than agreeing by coincidence. The end is an
        // instant no clock-reading controller would produce either.
        when(forecastRunService.currentNight()).thenReturn(
                new CurrentNight(LocalDate.of(2026, 4, 1), Instant.parse("2026-04-02T04:31:00Z")));

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentNightDate").value("2026-04-01"))
                .andExpect(jsonPath("$.currentNightEndsAt").value("2026-04-02T04:31:00Z"));
        // Both halves from ONE call. A date and an end fetched by two calls are two reads of the
        // clock, which can straddle dawn and pair last night's date with tomorrow's dawn.
        verify(forecastRunService).currentNight();
        verify(forecastRunService, never()).currentNightDate();
    }

    @Test
    @DisplayName("GET /api/aurora/status still answers, carrying no date, if the night is ever null")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_nullCurrentNight_stillAnswersWithNoDate() throws Exception {
        // Defensive pass-through only, and worth being honest about what it does and does not say.
        //
        // The server state is not currently reachable: currentNight() always builds a night, from
        // `today` or `today.minusDays(1)`, and SolarCalculator.civilDawn is pure arithmetic with no
        // null path. What this pins is that the controller relays whatever the service returns
        // rather than substituting a calendar date of its own — the substitution being the entire
        // defect this field exists to fix.
        //
        // ⚠️ `doesNotExist()` asserts the JSON path resolves to null, which is true both when the
        // key is absent AND when it is present as an explicit null. No profile sets
        // spring.jackson.default-property-inclusion, so Jackson's ALWAYS default means the wire
        // almost certainly carries `"currentNightDate": null` rather than omitting the key. This
        // assertion cannot tell those apart and is not claiming to. It does not matter to any
        // consumer: the map's `resolveAuroraNight` treats an absent and a null date identically,
        // falling back to the UK date, and that degrade is tested on the frontend in
        // `mapDates.test.js`.
        when(forecastRunService.currentNight()).thenReturn(null);

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("QUIET"))
                .andExpect(jsonPath("$.currentNightDate").doesNotExist())
                .andExpect(jsonPath("$.currentNightEndsAt").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Role-based access — /api/aurora/locations
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/locations returns 403 for LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void getLocations_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/aurora/locations"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/aurora/locations returns 200 for ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void getLocations_admin_returns200() throws Exception {
        mockMvc.perform(get("/api/aurora/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // Locations endpoint filtering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/locations returns empty list when IDLE")
    @WithMockUser(roles = {"ADMIN"})
    void getLocations_idle_returnsEmpty() throws Exception {
        when(stateCache.getCachedScores()).thenReturn(List.of());

        mockMvc.perform(get("/api/aurora/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("GET /api/aurora/locations with minStars=4 filters low-rated locations")
    @WithMockUser(roles = {"ADMIN"})
    void getLocations_minStarsFilter_filtersLowRated() throws Exception {
        when(stateCache.getCachedScores()).thenReturn(List.of(
                stubScore(2), stubScore(4), stubScore(5)));

        mockMvc.perform(get("/api/aurora/locations").param("minStars", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("GET /api/aurora/locations with maxBortle=2 filters light-polluted locations")
    @WithMockUser(roles = {"ADMIN"})
    void getLocations_maxBortleFilter_filtersLightPolluted() throws Exception {
        var dark = stubScoreWithBortle(4, 1);      // Bortle 1 — passes
        var moderate = stubScoreWithBortle(3, 3);  // Bortle 3 — filtered out
        when(stateCache.getCachedScores()).thenReturn(List.of(dark, moderate));

        mockMvc.perform(get("/api/aurora/locations").param("maxBortle", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // -------------------------------------------------------------------------
    // Viewline endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/viewline serves the live contour as the client returns it, whatever the Kp")
    @WithMockUser(roles = {"ADMIN"})
    void getViewline_servesContourUnclamped() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        // A published Kp of 6 used to clamp this to 54N; the controller no longer reads a Kp at all.
        when(noaaClient.fetchKp()).thenReturn(List.of(new KpReading(now, 6.0)));
        AuroraViewlineResponse viewline = new AuroraViewlineResponse(
                List.of(new AuroraViewlineResponse.ViewlinePoint(0.0, 49.0)),
                "Visible as far south as the whole UK", 49.0, now, true, false, null, now);
        when(noaaClient.fetchViewline()).thenReturn(viewline);

        mockMvc.perform(get("/api/aurora/viewline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.isForecast").value(false))
                .andExpect(jsonPath("$.southernmostLatitude").value(49.0))
                .andExpect(jsonPath("$.points[0].latitude").value(49.0))
                .andExpect(jsonPath("$.kp").doesNotExist())
                .andExpect(jsonPath("$.asOf").exists());
        verify(noaaClient, never()).fetchKp();
    }

    @Test
    @DisplayName("GET /api/aurora/viewline returns 403 for LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void getViewline_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/aurora/viewline"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Forecast viewline endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/viewline/forecast draws the rest-of-tonight Kp from NOAA's live forecast")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_usesLiveRestOfTonightKp() throws Exception {
        // A higher block that has already ended is in the product too: the Kp drawn is the service's
        // rest-of-tonight figure, never the raw maximum of the list.
        List<KpForecast> forecast = List.of(kpBlock(9.0), kpBlock(6.67));
        when(noaaClient.fetchKpForecast()).thenReturn(forecast);
        when(forecastRunService.maxKpRestOfCurrentNight(forecast)).thenReturn(6.67);
        when(stateCache.getLastTriggerKp()).thenReturn(5.0);
        stubForecastViewlineFromTheRule();

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isForecast").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.kp").value(6.67))
                .andExpect(jsonPath("$.asOf").exists())
                .andExpect(jsonPath("$.points.length()").value(17))
                .andExpect(jsonPath("$.southernmostLatitude")
                        .value(AuroraViewlineRule.line(6.67).get(0).latitude()));
        verify(noaaClient, never()).buildForecastViewline(5.0);
    }

    @Test
    @DisplayName("a forecast revised 5.67 to 7.0 moves the line with no NOTIFY and no change to the stored trigger Kp")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_revisedForecast_movesLineWithoutNotify() throws Exception {
        List<KpForecast> before = List.of(kpBlock(5.67));
        List<KpForecast> after = List.of(kpBlock(7.0));
        when(stateCache.getLastTriggerKp()).thenReturn(5.67);
        when(forecastRunService.maxKpRestOfCurrentNight(before)).thenReturn(5.67);
        when(forecastRunService.maxKpRestOfCurrentNight(after)).thenReturn(7.0);
        stubForecastViewlineFromTheRule();

        when(noaaClient.fetchKpForecast()).thenReturn(before);
        double first = southernmostOf(mockMvc.perform(get("/api/aurora/viewline/forecast")).andReturn());
        when(noaaClient.fetchKpForecast()).thenReturn(after);
        double second = southernmostOf(mockMvc.perform(get("/api/aurora/viewline/forecast")).andReturn());

        assertThat(second).isLessThan(first);
        assertThat(second).isEqualTo(AuroraViewlineRule.line(7.0).get(0).latitude());
        // The line was built for each request's own Kp, and the alert's stored trigger (5.67 throughout)
        // was only ever read, never written: there was no NOTIFY.
        verify(noaaClient).buildForecastViewline(5.67);
        verify(noaaClient).buildForecastViewline(7.0);
        verify(stateCache, never()).updateTrigger(any(), anyDouble());
    }

    @Test
    @DisplayName("during an admin simulation the forecast line follows the simulated Kp, not NOAA's")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_simulation_usesSimulatedKp() throws Exception {
        when(stateCache.getSimulatedData())
                .thenReturn(new AuroraStateCache.SimulatedNoaaData(8.0, 45.0, -12.0, "G4"));
        when(stateCache.getLastTriggerKp()).thenReturn(8.0);
        when(noaaClient.fetchKpForecast()).thenReturn(List.of(kpBlock(2.0)));
        stubForecastViewlineFromTheRule();

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kp").value(8.0));
        verify(noaaClient, never()).fetchKpForecast();
    }

    @Test
    @DisplayName("forecast viewline falls back to the alert's trigger Kp when NOAA's forecast is empty")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_emptyForecast_fallsBackToTriggerKp() throws Exception {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(stateCache.getLastTriggerKp()).thenReturn(6.0);
        stubForecastViewlineFromTheRule();

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kp").value(6.0));
    }

    @Test
    @DisplayName("forecast viewline falls back to the trigger Kp when the forecast read throws")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_forecastThrows_fallsBackToTriggerKp() throws Exception {
        when(noaaClient.fetchKpForecast()).thenThrow(new IllegalStateException("NOAA down"));
        when(stateCache.getLastTriggerKp()).thenReturn(6.0);
        stubForecastViewlineFromTheRule();

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kp").value(6.0));
    }

    @Test
    @DisplayName("forecast viewline falls back to the trigger Kp when nothing of tonight is forecast")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_nothingTonight_fallsBackToTriggerKp() throws Exception {
        List<KpForecast> forecast = List.of(kpBlock(3.0));
        when(noaaClient.fetchKpForecast()).thenReturn(forecast);
        when(forecastRunService.maxKpRestOfCurrentNight(forecast)).thenReturn(0.0);
        when(stateCache.getLastTriggerKp()).thenReturn(6.0);
        stubForecastViewlineFromTheRule();

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kp").value(6.0));
    }

    @Test
    @DisplayName("GET /api/aurora/viewline/forecast returns inactive when there is neither a forecast nor a trigger Kp")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_noKpAnywhere_returnsInactive() throws Exception {
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(stateCache.getLastTriggerKp()).thenReturn(null);

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isForecast").value(true))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.summary").value("No forecast available"))
                .andExpect(jsonPath("$.kp").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/aurora/viewline/forecast returns 403 for LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void getForecastViewline_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A Kp forecast block covering the current hour, so a real-clock "rest of tonight" would see it. */
    private static KpForecast kpBlock(double kp) {
        ZonedDateTime from = ZonedDateTime.now(ZoneOffset.UTC).minusHours(1);
        return new KpForecast(from, from.plusHours(3), kp);
    }

    /** Makes the mocked client answer {@code buildForecastViewline(kp)} with the real rule's line. */
    private void stubForecastViewlineFromTheRule() {
        when(noaaClient.buildForecastViewline(anyDouble())).thenAnswer(invocation -> {
            double kp = invocation.getArgument(0);
            List<AuroraViewlineResponse.ViewlinePoint> points = AuroraViewlineRule.line(kp);
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            return new AuroraViewlineResponse(points, "forecast",
                    points.stream().mapToDouble(AuroraViewlineResponse.ViewlinePoint::latitude).min().orElse(90.0),
                    now, true, true, kp, now);
        });
    }

    private static double southernmostOf(MvcResult result) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), "$.southernmostLatitude");
        return value.doubleValue();
    }

    private AuroraForecastScore stubScore(int stars) {
        return stubScoreWithBortle(stars, 3);
    }

    private AuroraForecastScore stubScoreWithBortle(int stars, int bortleClass) {
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name("Test").lat(54.78).lon(-1.58)
                .bortleClass(bortleClass).build();
        return new AuroraForecastScore(loc, stars, AlertLevel.MODERATE, 25,
                "★".repeat(stars) + " summary", "detail");
    }

    // -------------------------------------------------------------------------
    // Simulation mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/aurora/status returns simulated=true and uses simulated NOAA values")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_simulated_returnsFakeNoaaValues() throws Exception {
        AuroraStateCache.SimulatedNoaaData simData =
                new AuroraStateCache.SimulatedNoaaData(7.0, 45.0, -12.0, "G3");
        when(stateCache.getSimulatedData()).thenReturn(simData);
        when(stateCache.isActive()).thenReturn(true);
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(stateCache.getCachedScores()).thenReturn(List.of());

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.kp").value(7.0))
                .andExpect(jsonPath("$.ovationProbability").value(45.0))
                .andExpect(jsonPath("$.bzNanoTesla").value(-12.0))
                .andExpect(jsonPath("$.level").value("STRONG"))
                .andExpect(jsonPath("$.gScale").value("G3"));
    }

    @Test
    @DisplayName("GET /api/aurora/status returns simulated=false in normal mode")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_notSimulated_returnsSimulatedFalse() throws Exception {
        when(stateCache.getSimulatedData()).thenReturn(null);

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(false));
    }

    @Test
    @DisplayName("GET /api/aurora/status derives the G-scale from the alert's trigger Kp")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_live_derivesGScaleFromTriggerKp() throws Exception {
        when(stateCache.getCurrentLevel()).thenReturn(AlertLevel.STRONG);
        when(stateCache.getCachedScores()).thenReturn(List.of());
        when(stateCache.getLastTriggerKp()).thenReturn(8.0);

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gScale").value("G4"));
    }
}
