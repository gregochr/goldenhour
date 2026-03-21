package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
                any(com.gregochr.goldenhour.entity.RunType.class),
                anyBoolean(), any(), any());
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
}
