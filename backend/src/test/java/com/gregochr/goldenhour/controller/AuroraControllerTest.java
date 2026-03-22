package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.SolarWindReading;
import com.gregochr.goldenhour.service.aurora.AuroraOrchestrator;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.aurora.BortleEnrichmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AuroraController} role-based access and endpoint behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuroraControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuroraStateCache stateCache;

    @MockitoBean
    private NoaaSwpcClient noaaClient;

    // Mock admin dependencies so the Spring context loads correctly
    @MockitoBean
    private AuroraOrchestrator orchestrator;

    @MockitoBean
    private BortleEnrichmentService bortleEnrichmentService;

    @BeforeEach
    void setUp() {
        when(stateCache.isActive()).thenReturn(false);
        when(stateCache.getCurrentLevel()).thenReturn(null);
        when(stateCache.getCachedScores()).thenReturn(List.of());
        // Avoid HTTP calls in tests
        when(noaaClient.fetchKp()).thenReturn(List.of());
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
}
