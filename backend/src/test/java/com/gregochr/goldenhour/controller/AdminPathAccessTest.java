package com.gregochr.goldenhour.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves, through the real filter chain, that {@code /api/admin/**} is refused to non-admin callers with
 * 403 before argument binding, whether or not the parameters are well-formed, and that an admin with a
 * malformed parameter gets 400.
 */
class AdminPathAccessTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("PRO_USER with a valid parameter on an admin path is 403")
    void proUser_validParams_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/sub-region-policy").param("candidate", "2"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("PRO_USER with a malformed parameter on an admin path is 403, never 500")
    void proUser_malformedParams_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/sub-region-policy").param("candidate", "abc"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/batches/not-a-number"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("LITE_USER on an admin path is 403 with valid and malformed parameters")
    void liteUser_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/sub-region-policy").param("candidate", "2"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/sub-region-policy").param("candidate", "abc"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("ADMIN with a malformed parameter on an admin path is 400")
    void admin_malformedParams_badRequest() throws Exception {
        mockMvc.perform(get("/api/admin/sub-region-policy").param("candidate", "abc"))
                .andExpect(status().isBadRequest());
    }
}
