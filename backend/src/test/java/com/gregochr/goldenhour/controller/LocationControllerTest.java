package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.GoldenHourType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AddLocationRequest;
import com.gregochr.goldenhour.model.UpdateLocationRequest;
import com.gregochr.goldenhour.service.LocationService;
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
import java.util.Set;

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
 * Integration tests for {@link LocationController}.
 *
 * <p>Loads the full application context and mocks only {@link LocationService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;

    @Test
    @WithMockUser
    @DisplayName("GET /api/locations returns 200 with all locations")
    void getLocations_returnsAllLocations() throws Exception {
        when(locationService.findAll()).thenReturn(List.of(
                buildEntity(1L, "Ambleside", 54.43, -2.96),
                buildEntity(2L, "Durham UK", 54.7753, -1.5849)));

        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Ambleside"))
                .andExpect(jsonPath("$[1].name").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/locations returns 200 with empty list when no locations exist")
    void getLocations_noLocations_returnsEmptyList() throws Exception {
        when(locationService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/locations returns 200 with the saved entity for valid input")
    void addLocation_validRequest_returnsSavedEntity() throws Exception {
        LocationEntity saved = buildEntity(3L, "Bamburgh Castle", 55.6090, -1.7099);
        when(locationService.add(any(AddLocationRequest.class))).thenReturn(saved);

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bamburgh Castle\",\"lat\":55.609,\"lon\":-1.7099}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bamburgh Castle"))
                .andExpect(jsonPath("$.lat").value(55.6090))
                .andExpect(jsonPath("$.lon").value(-1.7099));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/locations with metadata returns 200")
    void addLocation_withMetadata_returnsSavedEntity() throws Exception {
        LocationEntity saved = LocationEntity.builder()
                .id(3L).name("Bamburgh").lat(55.6).lon(-1.7)
                .goldenHourType(GoldenHourType.SUNSET)
                .locationType(Set.of(LocationType.SEASCAPE))
                .tideType(Set.of(TideType.HIGH, TideType.MID, TideType.LOW))
                .createdAt(LocalDateTime.of(2026, 2, 28, 12, 0))
                .build();
        when(locationService.add(any(AddLocationRequest.class))).thenReturn(saved);

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bamburgh\",\"lat\":55.6,\"lon\":-1.7,"
                                + "\"goldenHourType\":\"SUNSET\","
                                + "\"locationType\":\"SEASCAPE\","
                                + "\"tideTypes\":[\"HIGH\",\"MID\",\"LOW\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bamburgh"))
                .andExpect(jsonPath("$.goldenHourType").value("SUNSET"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/locations returns 400 when the service rejects a duplicate name")
    void addLocation_duplicateName_returns400() throws Exception {
        when(locationService.add(any(AddLocationRequest.class)))
                .thenThrow(new IllegalArgumentException("A location named 'Durham UK' already exists"));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Durham UK\",\"lat\":54.7753,\"lon\":-1.5849}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A location named 'Durham UK' already exists"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/locations returns 400 when the service rejects an invalid latitude")
    void addLocation_invalidLatitude_returns400() throws Exception {
        when(locationService.add(any(AddLocationRequest.class)))
                .thenThrow(new IllegalArgumentException("Latitude must be between -90 and 90"));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"lat\":999,\"lon\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Latitude must be between -90 and 90"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/{id} updates location metadata")
    void updateLocation_validRequest_returnsUpdatedEntity() throws Exception {
        LocationEntity updated = buildEntity(1L, "Durham UK", 54.7753, -1.5849);
        updated.setGoldenHourType(GoldenHourType.SUNSET);
        when(locationService.update(eq(1L), any(UpdateLocationRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goldenHourType\":\"SUNSET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goldenHourType").value("SUNSET"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/{id}/enabled toggles enabled state")
    void setLocationEnabled_validRequest_returnsUpdatedEntity() throws Exception {
        LocationEntity entity = buildEntity(1L, "Durham UK", 54.7753, -1.5849);
        entity.setEnabled(false);
        when(locationService.setEnabled(1L, false)).thenReturn(entity);

        mockMvc.perform(put("/api/locations/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/locations/{id} returns 403 for non-ADMIN")
    void updateLocation_nonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/locations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goldenHourType\":\"SUNSET\"}"))
                .andExpect(status().isForbidden());
    }

    // --- resetLocationFailures endpoint ---

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/reset-failures returns 200 with the reset entity")
    void resetFailures_asAdmin_returnsResetEntity() throws Exception {
        LocationEntity entity = buildEntity(1L, "Durham UK", 54.7753, -1.5849);
        when(locationService.resetFailures("Durham UK")).thenReturn(entity);

        mockMvc.perform(put("/api/locations/reset-failures?name=Durham UK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/locations/reset-failures returns 403 for non-ADMIN")
    void resetFailures_nonAdmin_returns403() throws Exception {
        mockMvc.perform(put("/api/locations/reset-failures?name=Durham UK"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/reset-failures returns 404 when location not found")
    void resetFailures_unknownLocation_returns404() throws Exception {
        when(locationService.resetFailures("Unknown"))
                .thenThrow(new NoSuchElementException("Location not found: Unknown"));

        mockMvc.perform(put("/api/locations/reset-failures?name=Unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/locations/reset-failures returns 401 when unauthenticated")
    void resetFailures_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/locations/reset-failures?name=Test"))
                .andExpect(status().isUnauthorized());
    }

    // --- 404 edge cases for existing endpoints ---

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/{id} returns 404 when location does not exist")
    void updateLocation_notFound_returns404() throws Exception {
        when(locationService.update(eq(999L), any(UpdateLocationRequest.class)))
                .thenThrow(new NoSuchElementException("Location not found: 999"));

        mockMvc.perform(put("/api/locations/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goldenHourType\":\"SUNSET\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/locations/{id}/enabled returns 404 when location does not exist")
    void setLocationEnabled_notFound_returns404() throws Exception {
        when(locationService.setEnabled(999L, false))
                .thenThrow(new NoSuchElementException("Location not found: 999"));

        mockMvc.perform(put("/api/locations/999/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/locations returns 401 when unauthenticated")
    void getLocations_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isUnauthorized());
    }

    private LocationEntity buildEntity(Long id, String name, double lat, double lon) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(lat)
                .lon(lon)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0))
                .build();
    }
}
