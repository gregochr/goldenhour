package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.AddRegionRequest;
import com.gregochr.goldenhour.model.SetRegionBaseRequest;
import com.gregochr.goldenhour.model.UpdateRegionRequest;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
class RegionControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    // ------------------------------------------------------------------------------------------
    // The shared region-base drive-time matrix (heat-field plan P7)
    // ------------------------------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("GET /api/regions/drive-times is open to any authenticated reader, not just ADMIN")
    void getRegionDriveTimes_anyAuthenticatedUser_returnsMatrix() throws Exception {
        when(regionDriveDurationService.getMatrix())
                .thenReturn(Map.of(7L, Map.of(11L, 25, 12L, 40)));

        mockMvc.perform(get("/api/regions/drive-times"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.7.11").value(25))
                .andExpect(jsonPath("$.7.12").value(40));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("GET /api/regions/drive-times is not gated on PRO — the origin move is ungated for the pilot")
    void getRegionDriveTimes_liteUser_returnsMatrix() throws Exception {
        when(regionDriveDurationService.getMatrix()).thenReturn(Map.of(7L, Map.of(11L, 25)));

        mockMvc.perform(get("/api/regions/drive-times"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.7.11").value(25));
    }

    @Test
    @DisplayName("GET /api/regions/drive-times rejects an anonymous caller")
    void getRegionDriveTimes_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/regions/drive-times"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/regions/drive-times serves an empty object before the first sweep")
    void getRegionDriveTimes_noRows_returnsEmptyObject() throws Exception {
        when(regionDriveDurationService.getMatrix()).thenReturn(Map.of());

        mockMvc.perform(get("/api/regions/drive-times"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id}/base passes the body through, and returns the saved region")
    void setRegionBase_admin_passesTheBodyThrough() throws Exception {
        RegionEntity saved = buildRegion(1L, "Lake District");
        saved.setBaseName("Keswick");
        saved.setBaseLat(54.601);
        saved.setBaseLon(-3.135);
        when(regionService.setBase(eq(1L), any(SetRegionBaseRequest.class))).thenReturn(saved);

        mockMvc.perform(put("/api/regions/1/base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseName\":\"Keswick\",\"baseLat\":54.601,\"baseLon\":-3.135}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseName").value("Keswick"))
                .andExpect(jsonPath("$.baseLat").value(54.601));

        // ⚠️ Captured, not `any()`. With the body unasserted, a controller returning
        // `setBase(id, new SetRegionBaseRequest(null, null, null))` passes every status assertion
        // here — and every admin base save silently CLEARS the base and discards that region's ORS
        // matrix. This is also the only place the record's JSON deserialisation is pinned.
        ArgumentCaptor<SetRegionBaseRequest> body =
                ArgumentCaptor.forClass(SetRegionBaseRequest.class);
        verify(regionService).setBase(eq(1L), body.capture());
        assertThat(body.getValue().baseName()).isEqualTo("Keswick");
        assertThat(body.getValue().baseLat()).isEqualTo(54.601);
        assertThat(body.getValue().baseLon()).isEqualTo(-3.135);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id}/base carries an all-null body through as a clear")
    void setRegionBase_allNull_isPassedThroughAsAClear() throws Exception {
        when(regionService.setBase(eq(1L), any(SetRegionBaseRequest.class)))
                .thenReturn(buildRegion(1L, "Lake District"));

        mockMvc.perform(put("/api/regions/1/base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseName\":null,\"baseLat\":null,\"baseLon\":null}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SetRegionBaseRequest> body =
                ArgumentCaptor.forClass(SetRegionBaseRequest.class);
        verify(regionService).setBase(eq(1L), body.capture());
        assertThat(body.getValue().baseName()).isNull();
        assertThat(body.getValue().baseLat()).isNull();
        assertThat(body.getValue().baseLon()).isNull();
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/regions/{id}/base is ADMIN-only")
    void setRegionBase_nonAdmin_isForbidden() throws Exception {
        mockMvc.perform(put("/api/regions/1/base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseName\":\"Keswick\",\"baseLat\":54.601,\"baseLon\":-3.135}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id}/base returns 400 for a partial base")
    void setRegionBase_partial_isBadRequest() throws Exception {
        when(regionService.setBase(eq(1L), any(SetRegionBaseRequest.class)))
                .thenThrow(new IllegalArgumentException("A region base needs a town name, a "
                        + "latitude and a longitude — or none of them"));

        mockMvc.perform(put("/api/regions/1/base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseName\":\"Keswick\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/regions/{id}/base returns 404 for an unknown region")
    void setRegionBase_unknownRegion_isNotFound() throws Exception {
        when(regionService.setBase(eq(99L), any(SetRegionBaseRequest.class)))
                .thenThrow(new NoSuchElementException("No region with id 99"));

        mockMvc.perform(put("/api/regions/99/base")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseName\":\"Keswick\",\"baseLat\":54.6,\"baseLon\":-3.1}"))
                .andExpect(status().isNotFound());
    }

    private RegionEntity buildRegion(Long id, String name) {
        return RegionEntity.builder()
                .id(id)
                .name(name)
                .createdAt(LocalDateTime.of(2026, 3, 1, 12, 0))
                .build();
    }
}
