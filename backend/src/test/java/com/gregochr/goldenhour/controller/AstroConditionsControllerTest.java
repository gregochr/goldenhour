package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AstroConditionsController} endpoint behaviour and role-based access.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AstroConditionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AstroConditionsRepository astroConditionsRepository;

    // -------------------------------------------------------------------------
    // GET /api/astro/conditions — data tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/astro/conditions returns scores for a given date")
    @WithMockUser(roles = {"ADMIN"})
    void getConditions_returnsScoresForDate() throws Exception {
        AstroConditionsEntity entity1 = buildEntity("Dark Sky Park", 4);
        AstroConditionsEntity entity2 = buildEntity("Hilltop Observatory", 2);

        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of(entity1, entity2));

        mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationName").value("Dark Sky Park"))
                .andExpect(jsonPath("$[0].stars").value(4))
                .andExpect(jsonPath("$[0].summary").value("Test summary"))
                .andExpect(jsonPath("$[0].cloudExplanation").value("Clear skies"))
                .andExpect(jsonPath("$[0].visibilityExplanation").value("Good visibility"))
                .andExpect(jsonPath("$[0].moonExplanation").value("Moon below horizon"))
                .andExpect(jsonPath("$[0].bortleClass").value(3))
                .andExpect(jsonPath("$[0].moonPhase").value("NEW_MOON"))
                .andExpect(jsonPath("$[0].moonIlluminationPct").value(2.0))
                .andExpect(jsonPath("$[1].locationName").value("Hilltop Observatory"))
                .andExpect(jsonPath("$[1].stars").value(2));
    }

    @Test
    @DisplayName("GET /api/astro/conditions returns empty array when no data exists")
    @WithMockUser(roles = {"ADMIN"})
    void getConditions_returnsEmptyForNoData() throws Exception {
        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // -------------------------------------------------------------------------
    // GET /api/astro/conditions/available-dates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/astro/conditions/available-dates returns distinct dates")
    @WithMockUser(roles = {"ADMIN"})
    void getAvailableDates_returnsDistinctDates() throws Exception {
        when(astroConditionsRepository.findDistinctForecastDates())
                .thenReturn(List.of(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 2)));

        mockMvc.perform(get("/api/astro/conditions/available-dates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0]").value("2026-04-01"))
                .andExpect(jsonPath("$[1]").value("2026-04-02"));
    }

    // -------------------------------------------------------------------------
    // Role-based access — all authenticated users allowed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/astro/conditions is accessible by LITE_USER")
    @WithMockUser(roles = {"LITE_USER"})
    void getConditions_accessibleByLiteUser() throws Exception {
        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/astro/conditions is accessible by PRO_USER")
    @WithMockUser(roles = {"PRO_USER"})
    void getConditions_accessibleByProUser() throws Exception {
        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/astro/conditions is accessible by ADMIN")
    @WithMockUser(roles = {"ADMIN"})
    void getConditions_accessibleByAdmin() throws Exception {
        when(astroConditionsRepository.findByForecastDate(LocalDate.of(2026, 4, 1)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/astro/conditions returns 401 for unauthenticated request")
    void getConditions_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/astro/conditions").param("date", "2026-04-01"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AstroConditionsEntity buildEntity(String locationName, int stars) {
        LocationEntity loc = LocationEntity.builder()
                .id(1L).name(locationName).lat(54.78).lon(-1.58).bortleClass(3).build();
        AstroConditionsEntity entity = new AstroConditionsEntity();
        entity.setId(1L);
        entity.setLocation(loc);
        entity.setForecastDate(LocalDate.of(2026, 4, 1));
        entity.setRunTimestamp(Instant.parse("2026-04-01T12:00:00Z"));
        entity.setStars(stars);
        entity.setSummary("Test summary");
        entity.setCloudExplanation("Clear skies");
        entity.setVisibilityExplanation("Good visibility");
        entity.setMoonExplanation("Moon below horizon");
        entity.setCloudModifier(1.0);
        entity.setVisibilityModifier(0.5);
        entity.setMoonModifier(0.5);
        entity.setFogCapped(false);
        entity.setMoonPhase("NEW_MOON");
        entity.setMoonIlluminationPct(2.0);
        return entity;
    }
}
