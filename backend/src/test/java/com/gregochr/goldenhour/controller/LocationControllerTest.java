package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.service.LocationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockBean
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
        when(locationService.add(
                eq("Bamburgh Castle"), eq(55.6090), eq(-1.7099)))
                .thenReturn(saved);

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
    @DisplayName("POST /api/locations returns 400 when the service rejects a duplicate name")
    void addLocation_duplicateName_returns400() throws Exception {
        when(locationService.add(anyString(), anyDouble(), anyDouble()))
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
        when(locationService.add(anyString(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalArgumentException("Latitude must be between -90 and 90"));

        mockMvc.perform(post("/api/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test\",\"lat\":999,\"lon\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Latitude must be between -90 and 90"));
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
