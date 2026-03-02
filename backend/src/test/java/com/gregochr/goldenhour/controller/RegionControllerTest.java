package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import com.gregochr.goldenhour.service.RegionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RegionController}.
 *
 * <p>Loads the full application context and mocks only {@link RegionService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegionService regionService;

    @Test
    @WithMockUser
    @DisplayName("GET /api/regions returns 200 with all regions")
    void getRegions_returnsAllRegions() throws Exception {
        when(regionService.findAll()).thenReturn(List.of(
                buildRegion(1L, "Northumberland"),
                buildRegion(2L, "Tyne and Wear")));

        mockMvc.perform(get("/api/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Northumberland"))
                .andExpect(jsonPath("$[1].name").value("Tyne and Wear"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/regions returns 200 with empty list when no regions exist")
    void getRegions_noRegions_returnsEmptyList() throws Exception {
        when(regionService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/regions returns 200 with the saved entity for valid input")
    void addRegion_validRequest_returnsSavedEntity() throws Exception {
        RegionEntity saved = buildRegion(3L, "The Lake District");
        when(regionService.add(any(AddRegionRequest.class))).thenReturn(saved);

        mockMvc.perform(post("/api/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"The Lake District\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("The Lake District"))
                .andExpect(jsonPath("$.id").value(3));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/regions returns 400 when duplicate name")
    void addRegion_duplicateName_returns400() throws Exception {
        when(regionService.add(any(AddRegionRequest.class)))
                .thenThrow(new IllegalArgumentException(
                        "A region named 'Northumberland' already exists"));

        mockMvc.perform(post("/api/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Northumberland\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "A region named 'Northumberland' already exists"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/regions returns 403 for non-ADMIN")
    void addRegion_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id} updates region name")
    void updateRegion_validRequest_returnsUpdatedEntity() throws Exception {
        RegionEntity updated = buildRegion(1L, "New Name");
        when(regionService.update(eq(1L), any(UpdateRegionRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/regions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/regions/{id} returns 403 for non-ADMIN")
    void updateRegion_nonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/regions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id}/enabled toggles enabled state")
    void setRegionEnabled_validRequest_returnsUpdatedEntity() throws Exception {
        RegionEntity entity = buildRegion(1L, "Northumberland");
        entity.setEnabled(false);
        when(regionService.setEnabled(1L, false)).thenReturn(entity);

        mockMvc.perform(put("/api/regions/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/regions/{id}/enabled returns 403 for non-ADMIN")
    void setRegionEnabled_nonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/regions/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    // --- 404 edge cases ---

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id} returns 404 when region does not exist")
    void updateRegion_notFound_returns404() throws Exception {
        when(regionService.update(eq(999L), any(UpdateRegionRequest.class)))
                .thenThrow(new NoSuchElementException("Region not found: 999"));

        mockMvc.perform(put("/api/regions/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id}/enabled returns 404 when region does not exist")
    void setRegionEnabled_notFound_returns404() throws Exception {
        when(regionService.setEnabled(999L, false))
                .thenThrow(new NoSuchElementException("Region not found: 999"));

        mockMvc.perform(put("/api/regions/999/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/regions returns 401 when unauthenticated")
    void getRegions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/regions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/regions returns 401 when unauthenticated")
    void addRegion_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/regions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
    }

    private RegionEntity buildRegion(Long id, String name) {
        return RegionEntity.builder()
                .id(id)
                .name(name)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();
    }
}
