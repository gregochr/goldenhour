package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.HotTopicSimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link HotTopicSimulationController}.
 */
class HotTopicSimulationControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/hot-topics/simulation returns 200 for ADMIN")
    void getState_adminSuccess() throws Exception {
        when(hotTopicSimulationService.isEnabled()).thenReturn(false);
        when(hotTopicSimulationService.getAllTypes()).thenReturn(List.of(
                new HotTopicSimulationService.SimulatableType("BLUEBELL", "Bluebell conditions", false),
                new HotTopicSimulationService.SimulatableType("AURORA", "Aurora possible", true)
        ));

        mockMvc.perform(get("/api/admin/hot-topics/simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.types.length()").value(2))
                .andExpect(jsonPath("$.types[0].type").value("BLUEBELL"))
                .andExpect(jsonPath("$.types[1].type").value("AURORA"))
                .andExpect(jsonPath("$.types[1].active").value(true));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/hot-topics/simulation returns 403 for non-admin")
    void getState_nonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/hot-topics/simulation"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/hot-topics/simulation returns 401 without authentication")
    void getState_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/hot-topics/simulation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /toggle flips disabled→enabled and returns updated state")
    void toggleEnabled_adminFlipsState() throws Exception {
        // Initial state is disabled; toggle reads isEnabled()=false → calls setEnabled(true)
        when(hotTopicSimulationService.isEnabled()).thenReturn(false);
        when(hotTopicSimulationService.getAllTypes()).thenReturn(List.of());

        mockMvc.perform(post("/api/admin/hot-topics/simulation/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)); // mock still returns false

        verify(hotTopicSimulationService).setEnabled(true);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /type/{type}/toggle activates an inactive type")
    void toggleType_inactiveType_activates() throws Exception {
        when(hotTopicSimulationService.getActiveTypes()).thenReturn(Set.of());
        when(hotTopicSimulationService.isEnabled()).thenReturn(true);
        when(hotTopicSimulationService.getAllTypes()).thenReturn(List.of(
                new HotTopicSimulationService.SimulatableType("BLUEBELL", "Bluebell conditions", true)
        ));

        mockMvc.perform(post("/api/admin/hot-topics/simulation/type/BLUEBELL/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].active").value(true));

        verify(hotTopicSimulationService).setTypeActive("BLUEBELL", true);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /type/{type}/toggle deactivates an active type")
    void toggleType_activeType_deactivates() throws Exception {
        when(hotTopicSimulationService.getActiveTypes()).thenReturn(Set.of("AURORA"));
        when(hotTopicSimulationService.isEnabled()).thenReturn(true);
        when(hotTopicSimulationService.getAllTypes()).thenReturn(List.of(
                new HotTopicSimulationService.SimulatableType("AURORA", "Aurora possible", false)
        ));

        mockMvc.perform(post("/api/admin/hot-topics/simulation/type/AURORA/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].active").value(false));

        verify(hotTopicSimulationService).setTypeActive("AURORA", false);
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /toggle returns 403 for non-admin")
    void toggleEnabled_nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/hot-topics/simulation/toggle"))
                .andExpect(status().isForbidden());
    }
}
