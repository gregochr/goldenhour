package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.JobRunService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AuroraAdminController} access control and endpoint behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuroraAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BortleEnrichmentService enrichmentService;

    @MockitoBean
    private AuroraProperties auroraProperties;

    @MockitoBean
    private AuroraStateCache stateCache;

    @MockitoBean
    private JobRunService jobRunService;

    @MockitoBean
    private LocationRepository locationRepository;

    @BeforeEach
    void setUp() {
        JobRunEntity stubJobRun = new JobRunEntity();
        stubJobRun.setId(42L);
        when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenReturn(stubJobRun);
    }

    // -------------------------------------------------------------------------
    // Role-based access
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle returns 403 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void enrichBortle_proUser_returns403() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle returns 403 for LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void enrichBortle_liteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/reset returns 403 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void reset_proUser_returns403() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/reset"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // enrich-bortle endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle returns 400 when API key is not configured")
    @WithMockUser(roles = {"ADMIN"})
    void enrichBortle_noApiKey_returns400() throws Exception {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn(null);

        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle returns 400 when API key is blank")
    @WithMockUser(roles = {"ADMIN"})
    void enrichBortle_blankApiKey_returns400() throws Exception {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn("  ");

        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle returns 202 Accepted with jobRunId")
    @WithMockUser(roles = {"ADMIN"})
    void enrichBortle_validApiKey_returns202() throws Exception {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn("valid-key");

        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Light pollution enrichment started"))
                .andExpect(jsonPath("$.runType").value("LIGHT_POLLUTION"))
                .andExpect(jsonPath("$.jobRunId").value(42));
    }

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle calls jobRunService.startRun with LIGHT_POLLUTION")
    @WithMockUser(roles = {"ADMIN"})
    void enrichBortle_validApiKey_startsJobRun() throws Exception {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn("valid-key");

        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isAccepted());

        verify(jobRunService).startRun(
                eq(com.gregochr.goldenhour.entity.RunType.LIGHT_POLLUTION),
                eq(true), isNull(), isNull());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/enrich-bortle passes API key and jobRun to enrichmentService")
    @WithMockUser(roles = {"ADMIN"})
    void enrichBortle_validApiKey_passesKeyAndJobRunToService() throws Exception {
        when(auroraProperties.getLightPollutionApiKey()).thenReturn("valid-key");

        mockMvc.perform(post("/api/aurora/admin/enrich-bortle"))
                .andExpect(status().isAccepted());

        verify(enrichmentService, org.mockito.Mockito.timeout(2000))
                .enrichAll(eq("valid-key"), any(JobRunEntity.class));
    }

    @Test
    @DisplayName("POST /api/aurora/admin/simulate passes STRONG alert level for Kp 7")
    @WithMockUser(roles = {"ADMIN"})
    void simulate_admin_passesCorrectAlertLevel() throws Exception {
        AuroraProperties.BortleThreshold bortleThreshold = new AuroraProperties.BortleThreshold();
        bortleThreshold.setModerate(4);
        bortleThreshold.setStrong(5);
        when(auroraProperties.getBortleThreshold()).thenReturn(bortleThreshold);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(new LocationEntity()));

        mockMvc.perform(post("/api/aurora/admin/simulate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"kp\":7.0,\"ovationProbability\":45.0,\"bzNanoTesla\":-12.0,\"gScale\":\"G3\"}"))
                .andExpect(status().isOk());

        verify(stateCache).activateSimulation(
                eq(AlertLevel.STRONG), any(AuroraStateCache.SimulatedNoaaData.class));
    }

    // -------------------------------------------------------------------------
    // reset endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/aurora/admin/reset returns 200 for ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void reset_admin_returns200() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Aurora state machine reset to IDLE"));
    }

    @Test
    @DisplayName("POST /api/aurora/admin/reset calls stateCache.reset()")
    @WithMockUser(roles = {"ADMIN"})
    void reset_admin_callsStateCacheReset() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/reset"))
                .andExpect(status().isOk());

        verify(stateCache).reset();
    }

    // -------------------------------------------------------------------------
    // simulate endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/aurora/admin/simulate returns 403 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void simulate_proUser_returns403() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/simulate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"kp\":7.0,\"ovationProbability\":45.0,\"bzNanoTesla\":-12.0,\"gScale\":\"G3\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/simulate activates simulation and returns 200 for ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void simulate_admin_returns200() throws Exception {
        AuroraProperties.BortleThreshold bortleThreshold = new AuroraProperties.BortleThreshold();
        bortleThreshold.setModerate(4);
        bortleThreshold.setStrong(5);
        when(auroraProperties.getBortleThreshold()).thenReturn(bortleThreshold);
        when(locationRepository.findByBortleClassLessThanEqualAndEnabledTrue(anyInt()))
                .thenReturn(List.of(new LocationEntity()));

        mockMvc.perform(post("/api/aurora/admin/simulate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"kp\":7.0,\"ovationProbability\":45.0,\"bzNanoTesla\":-12.0,\"gScale\":\"G3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("STRONG"))
                .andExpect(jsonPath("$.eligibleLocations").value(1));

        verify(stateCache).activateSimulation(any(AlertLevel.class), any(AuroraStateCache.SimulatedNoaaData.class));
    }

    // -------------------------------------------------------------------------
    // simulate/clear endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/aurora/admin/simulate/clear returns 403 for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void simulateClear_proUser_returns403() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/simulate/clear"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/aurora/admin/simulate/clear returns 200 for ADMIN and resets state")
    @WithMockUser(roles = {"ADMIN"})
    void simulateClear_admin_returns200() throws Exception {
        mockMvc.perform(post("/api/aurora/admin/simulate/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Aurora simulation cleared"));

        verify(stateCache).reset();
    }
}
