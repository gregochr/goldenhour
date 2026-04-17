package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraViewlineResponse;
import com.gregochr.goldenhour.model.KpReading;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

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
        when(stateCache.isSimulated()).thenReturn(false);
        when(stateCache.getCurrentLevel()).thenReturn(null);
        when(stateCache.getCachedScores()).thenReturn(List.of());
        // Avoid HTTP calls in tests
        when(noaaClient.fetchKp()).thenReturn(List.of());
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        when(noaaClient.fetchOvation()).thenReturn(null);
        when(noaaClient.fetchSolarWind()).thenReturn(List.of());
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
        when(noaaClient.fetchSolarWind()).thenReturn(List.of(reading));

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bzNanoTesla").value(-8.3));
    }

    @Test
    @DisplayName("GET /api/aurora/status returns null bzNanoTesla when solar wind list is empty")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_noSolarWind_returnsBzNull() throws Exception {
        when(noaaClient.fetchSolarWind()).thenReturn(List.of());

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bzNanoTesla").doesNotExist());
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
    @DisplayName("GET /api/aurora/viewline resolves Kp from latest reading and returns capped viewline")
    @WithMockUser(roles = {"ADMIN"})
    void getViewline_usesLatestKpReading() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaClient.fetchKp()).thenReturn(List.of(new KpReading(now, 6.0)));
        AuroraViewlineResponse viewline = new AuroraViewlineResponse(
                List.of(), "Visible as far south as northern England",
                54.0, now, true, false);
        when(noaaClient.fetchViewline(6.0)).thenReturn(viewline);

        mockMvc.perform(get("/api/aurora/viewline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.southernmostLatitude").value(54.0))
                .andExpect(jsonPath("$.summary").value("Visible as far south as northern England"));
    }

    @Test
    @DisplayName("GET /api/aurora/viewline falls back to default Kp 4.0 when no data available")
    @WithMockUser(roles = {"ADMIN"})
    void getViewline_noKpData_usesDefault() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        when(noaaClient.fetchKp()).thenReturn(List.of());
        when(noaaClient.fetchKpForecast()).thenReturn(List.of());
        AuroraViewlineResponse viewline = new AuroraViewlineResponse(
                List.of(), "Visible as far south as northern Scotland",
                58.0, now, true, false);
        when(noaaClient.fetchViewline(4.0)).thenReturn(viewline);

        mockMvc.perform(get("/api/aurora/viewline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.southernmostLatitude").value(58.0));
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
    @DisplayName("GET /api/aurora/viewline/forecast returns forecast viewline with isForecast=true")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_withTriggerKp_returnsForecastViewline() throws Exception {
        when(stateCache.getLastTriggerKp()).thenReturn(6.0);
        AuroraViewlineResponse forecast = new AuroraViewlineResponse(
                List.of(new AuroraViewlineResponse.ViewlinePoint(-12.0, 54.0),
                        new AuroraViewlineResponse.ViewlinePoint(4.0, 54.0)),
                "Visible as far south as northern England",
                54.0, ZonedDateTime.now(ZoneOffset.UTC), true, true);
        when(noaaClient.buildForecastViewline(6.0)).thenReturn(forecast);

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isForecast").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.southernmostLatitude").value(54.0));
    }

    @Test
    @DisplayName("GET /api/aurora/viewline/forecast returns inactive when no trigger Kp")
    @WithMockUser(roles = {"ADMIN"})
    void getForecastViewline_noTriggerKp_returnsInactive() throws Exception {
        when(stateCache.getLastTriggerKp()).thenReturn(null);

        mockMvc.perform(get("/api/aurora/viewline/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isForecast").value(true))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.summary").value("No forecast available"));
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
        when(stateCache.isSimulated()).thenReturn(true);
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
                .andExpect(jsonPath("$.level").value("STRONG"));
    }

    @Test
    @DisplayName("GET /api/aurora/status returns simulated=false in normal mode")
    @WithMockUser(roles = {"ADMIN"})
    void getStatus_notSimulated_returnsSimulatedFalse() throws Exception {
        when(stateCache.isSimulated()).thenReturn(false);

        mockMvc.perform(get("/api/aurora/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(false));
    }
}
