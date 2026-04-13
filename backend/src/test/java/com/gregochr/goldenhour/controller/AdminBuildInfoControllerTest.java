package com.gregochr.goldenhour.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AdminBuildInfoController}.
 */
class AdminBuildInfoControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/build-info returns 200 with version and deployedAt for ADMIN")
    void buildInfo_returnsVersionAndDeployedAt_forAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/build-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.deployedAt").exists());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/build-info returns 403 for PRO_USER")
    void buildInfo_returns403_forProUser() throws Exception {
        mockMvc.perform(get("/api/admin/build-info"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/admin/build-info returns 403 for LITE_USER")
    void buildInfo_returns403_forLiteUser() throws Exception {
        mockMvc.perform(get("/api/admin/build-info"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/build-info returns 401 for unauthenticated request")
    void buildInfo_returns401_forUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/build-info"))
                .andExpect(status().isUnauthorized());
    }
}
