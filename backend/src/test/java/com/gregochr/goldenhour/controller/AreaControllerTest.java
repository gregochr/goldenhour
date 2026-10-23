package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.exception.NameClashException;
import com.gregochr.goldenhour.model.AreaDto;
import com.gregochr.goldenhour.model.AreaLocationsRequest;
import com.gregochr.goldenhour.model.AreaRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AreaController}: each status code, and 403 for every non-admin role.
 */
class AreaControllerTest extends AbstractControllerTest {

    private static final String WALL = "{\"name\":\"Hadrian's Wall\",\"aliases\":[\"Roman Wall\"]}";

    @Autowired
    private MockMvc mockMvc;

    private static AreaDto wall() {
        return new AreaDto(7L, "Hadrian's Wall", List.of("Roman Wall"), List.of(1L, 2L));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/areas returns 200 with each area's id, name, aliases and members")
    void getAreas_asAdmin_returnsAreas() throws Exception {
        when(areaService.findAll()).thenReturn(List.of(wall()));

        mockMvc.perform(get("/api/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Hadrian's Wall"))
                .andExpect(jsonPath("$[0].aliases[0]").value("Roman Wall"))
                .andExpect(jsonPath("$[0].locationIds.length()").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/areas returns 201 with the new area and hands the service the typed request")
    void createArea_asAdmin_returns201() throws Exception {
        when(areaService.create(any(AreaRequest.class))).thenReturn(wall());

        mockMvc.perform(post("/api/areas").contentType(MediaType.APPLICATION_JSON).content(WALL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Hadrian's Wall"));

        ArgumentCaptor<AreaRequest> captor = ArgumentCaptor.forClass(AreaRequest.class);
        verify(areaService).create(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Hadrian's Wall");
        assertThat(captor.getValue().aliases()).containsExactly("Roman Wall");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/areas returns 409 with the clash named when a name clashes")
    void createArea_nameClash_returns409() throws Exception {
        when(areaService.create(any(AreaRequest.class)))
                .thenThrow(new NameClashException("'Roman Wall' is already a name for the named area 'X'"));

        mockMvc.perform(post("/api/areas").contentType(MediaType.APPLICATION_JSON).content(WALL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("'Roman Wall' is already a name for the named area 'X'"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/areas returns 400 when the shape is wrong")
    void createArea_badShape_returns400() throws Exception {
        when(areaService.create(any(AreaRequest.class)))
                .thenThrow(new IllegalArgumentException("An area name must be 2 to 80 characters"));

        mockMvc.perform(post("/api/areas").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"aliases\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/areas/{id} returns 200 with the renamed area")
    void updateArea_asAdmin_returns200() throws Exception {
        when(areaService.update(any(Long.class), any(AreaRequest.class))).thenReturn(wall());

        mockMvc.perform(put("/api/areas/7").contentType(MediaType.APPLICATION_JSON).content(WALL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationIds.length()").value(2));
        verify(areaService).update(any(Long.class), any(AreaRequest.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/areas/{id} returns 404 for an unknown area")
    void updateArea_unknown_returns404() throws Exception {
        when(areaService.update(any(Long.class), any(AreaRequest.class)))
                .thenThrow(new NoSuchElementException("No named area with id 9"));

        mockMvc.perform(put("/api/areas/9").contentType(MediaType.APPLICATION_JSON).content(WALL))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/areas/{id}/locations returns 200, and 400 for an unknown location id")
    void setAreaLocations_statuses() throws Exception {
        when(areaService.setLocations(any(Long.class), any(AreaLocationsRequest.class))).thenReturn(wall());
        mockMvc.perform(put("/api/areas/7/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[1,2]}"))
                .andExpect(status().isOk());

        when(areaService.setLocations(any(Long.class), any(AreaLocationsRequest.class)))
                .thenThrow(new IllegalArgumentException("No location with id 99"));
        mockMvc.perform(put("/api/areas/7/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[99]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /api/areas/{id} returns 204, and 404 for an unknown area")
    void deleteArea_statuses() throws Exception {
        mockMvc.perform(delete("/api/areas/7")).andExpect(status().isNoContent());
        verify(areaService).delete(7L);

        org.mockito.Mockito.doThrow(new NoSuchElementException("No named area with id 8"))
                .when(areaService).delete(8L);
        mockMvc.perform(delete("/api/areas/8")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("every /api/areas mapping is 403 for PRO_USER and never reaches the service")
    void areas_proUser_returns403() throws Exception {
        assertForbiddenEverywhere();
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("every /api/areas mapping is 403 for LITE_USER and never reaches the service")
    void areas_liteUser_returns403() throws Exception {
        assertForbiddenEverywhere();
    }

    private void assertForbiddenEverywhere() throws Exception {
        mockMvc.perform(get("/api/areas")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/areas").contentType(MediaType.APPLICATION_JSON).content(WALL))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/areas/7").contentType(MediaType.APPLICATION_JSON).content(WALL))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/areas/7/locations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationIds\":[1]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/areas/7")).andExpect(status().isForbidden());
        verifyNoInteractions(areaService);
    }
}
