package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.NlcSightingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link NlcController} role-based access and endpoint behaviour.
 *
 * <p>Mirrors {@code AuroraControllerTest}: the NLC signal is gated to ADMIN/PRO, LITE receives 403.
 */
class NlcControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static NlcSightingResponse activeResponse() {
        return new NlcSightingResponse(true, Instant.parse("2026-06-20T21:10:00Z"), "Elgin",
                "Scotland", "NLCNET", Boolean.TRUE, 64, "N–NW", "#8E86D6",
                "Noctilucent cloud reported over Scotland");
    }

    @Test
    @DisplayName("GET /api/nlc/sighting returns 403 for LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void getSighting_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/nlc/sighting"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/nlc/sighting returns the active signal for PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void getSighting_proUser_returnsSignal() throws Exception {
        when(nlcSightingService.currentSighting()).thenReturn(activeResponse());

        mockMvc.perform(get("/api/nlc/sighting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.region").value("Scotland"))
                .andExpect(jsonPath("$.source").value("NLCNET"));
    }

    @Test
    @DisplayName("GET /api/nlc/sighting returns an inactive body for ADMIN when nothing to show")
    @WithMockUser(roles = {"ADMIN"})
    void getSighting_admin_inactive() throws Exception {
        when(nlcSightingService.currentSighting()).thenReturn(NlcSightingResponse.inactive());

        mockMvc.perform(get("/api/nlc/sighting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }
}
