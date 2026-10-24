package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.exception.NameClashException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link LocationAliasController}: each status code, and 403 for every non-admin role.
 */
class LocationAliasControllerTest extends AbstractControllerTest {

    private static final String BODY = "{\"aliases\":[\"The Heugh\"]}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/locations/aliases returns 200 keyed by location id")
    void getAliases_asAdmin_returnsMap() throws Exception {
        when(locationAliasService.findAll()).thenReturn(Map.of(5L, List.of("The Heugh")));

        mockMvc.perform(get("/api/locations/aliases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['5'][0]").value("The Heugh"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/{id}/aliases returns 200 with the saved aliases")
    void setAliases_asAdmin_returns200() throws Exception {
        when(locationAliasService.replace(5L, List.of("The Heugh"))).thenReturn(List.of("The Heugh"));

        mockMvc.perform(put("/api/locations/5/aliases").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("The Heugh"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/{id}/aliases returns 404 for an unknown location and 409 for a clash")
    void setAliases_errors() throws Exception {
        when(locationAliasService.replace(9L, List.of("The Heugh")))
                .thenThrow(new NoSuchElementException("No location with id 9"));
        mockMvc.perform(put("/api/locations/9/aliases").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());

        when(locationAliasService.replace(5L, List.of("The Heugh")))
                .thenThrow(new NameClashException("'The Heugh' is already the location 'X'"));
        mockMvc.perform(put("/api/locations/5/aliases").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("'The Heugh' is already the location 'X'"));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("both alias mappings are 403 for PRO_USER and never reach the service")
    void aliases_proUser_returns403() throws Exception {
        assertForbidden();
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("both alias mappings are 403 for LITE_USER and never reach the service")
    void aliases_liteUser_returns403() throws Exception {
        assertForbidden();
    }

    private void assertForbidden() throws Exception {
        mockMvc.perform(get("/api/locations/aliases")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/locations/5/aliases").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(locationAliasService);
    }
}
