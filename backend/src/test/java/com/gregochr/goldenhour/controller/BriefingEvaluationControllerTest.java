package com.gregochr.goldenhour.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the surviving endpoints on {@link BriefingEvaluationController}.
 *
 * <p>The SSE evaluation endpoint, GET {@code /cache} and GET {@code /cache/timestamp}
 * were removed in Pass 3.3.3. Coverage for the merged-view endpoint ({@code /scores})
 * is exercised through its own controller test family.
 */
class BriefingEvaluationControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("DELETE /cache?confirm=true clears cache and returns count for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void clearCache_adminWithConfirmReturnsCount() throws Exception {
        when(evaluationService.clearCache()).thenReturn(5);

        mockMvc.perform(delete("/api/briefing/evaluate/cache").param("confirm", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(5));

        verify(evaluationService).clearCache();
    }

    @Test
    @DisplayName("DELETE /cache without confirm is rejected and does not clear")
    @WithMockUser(roles = "ADMIN")
    void clearCache_withoutConfirmRejected() throws Exception {
        mockMvc.perform(delete("/api/briefing/evaluate/cache"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Confirmation required"));

        verify(evaluationService, never()).clearCache();
    }

    @Test
    @DisplayName("DELETE /cache denied for PRO_USER")
    @WithMockUser(roles = "PRO_USER")
    void clearCache_proUserDenied() throws Exception {
        mockMvc.perform(delete("/api/briefing/evaluate/cache"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /cache denied for unauthenticated request")
    void clearCache_unauthenticatedDenied() throws Exception {
        mockMvc.perform(delete("/api/briefing/evaluate/cache"))
                .andExpect(status().isUnauthorized());
    }
}
