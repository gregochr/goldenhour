package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.TideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link TideController}.
 *
 * <p>Loads the full application context and mocks only {@link LocationService}
 * and {@link TideService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TideControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocationService locationService;

    @MockitoBean
    private TideService tideService;

    @Test
    @WithMockUser
    @DisplayName("GET /api/tides returns 200 with extremes for a valid location and date")
    void getTidesForDate_validLocation_returnsExtremes() throws Exception {
        LocationEntity location = LocationEntity.builder()
                .id(1L)
                .name("Berwick-Upon-Tweed")
                .lat(55.7702)
                .lon(-2.0054)
                .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0))
                .build();

        List<TideExtremeEntity> extremes = List.of(
                extreme(1L, TideExtremeType.LOW, LocalDateTime.of(2026, 2, 24, 1, 15), -1.4),
                extreme(2L, TideExtremeType.HIGH, LocalDateTime.of(2026, 2, 24, 6, 20), 1.8),
                extreme(3L, TideExtremeType.LOW, LocalDateTime.of(2026, 2, 24, 12, 45), 0.2),
                extreme(4L, TideExtremeType.HIGH, LocalDateTime.of(2026, 2, 24, 18, 55), 1.7));

        when(locationService.findByName("Berwick-Upon-Tweed")).thenReturn(location);
        when(tideService.getTidesForDate(eq(1L), eq(LocalDate.of(2026, 2, 24)))).thenReturn(extremes);

        mockMvc.perform(get("/api/tides")
                        .param("locationName", "Berwick-Upon-Tweed")
                        .param("date", "2026-02-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].type").value("LOW"))
                .andExpect(jsonPath("$[1].type").value("HIGH"))
                .andExpect(jsonPath("$[2].type").value("LOW"))
                .andExpect(jsonPath("$[3].type").value("HIGH"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/tides returns 404 when location name is not found")
    void getTidesForDate_unknownLocation_returns404() throws Exception {
        when(locationService.findByName("Unknown Place"))
                .thenThrow(new NoSuchElementException("No location named 'Unknown Place'"));

        mockMvc.perform(get("/api/tides")
                        .param("locationName", "Unknown Place")
                        .param("date", "2026-02-24"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/tides/stats returns 200 with stats for a coastal location")
    void getTideStats_validLocation_returnsStats() throws Exception {
        LocationEntity location = LocationEntity.builder()
                .id(1L).name("Berwick-Upon-Tweed").lat(55.7702).lon(-2.0054)
                .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0)).build();

        TideStats stats = new TideStats(
                BigDecimal.valueOf(1.400), BigDecimal.valueOf(1.800),
                BigDecimal.valueOf(-1.200), BigDecimal.valueOf(-1.500), 20,
                BigDecimal.valueOf(2.600), BigDecimal.valueOf(1.600),
                BigDecimal.valueOf(1.750), BigDecimal.valueOf(1.780),
                2L, BigDecimal.valueOf(0.100));

        when(locationService.findByName("Berwick-Upon-Tweed")).thenReturn(location);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));

        mockMvc.perform(get("/api/tides/stats")
                        .param("locationName", "Berwick-Upon-Tweed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avgHighMetres").value(1.4))
                .andExpect(jsonPath("$.maxHighMetres").value(1.8))
                .andExpect(jsonPath("$.avgLowMetres").value(-1.2))
                .andExpect(jsonPath("$.minLowMetres").value(-1.5))
                .andExpect(jsonPath("$.dataPoints").value(20));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/tides/stats returns 204 when no tide data stored")
    void getTideStats_noData_returns204() throws Exception {
        LocationEntity location = LocationEntity.builder()
                .id(1L).name("Inland Place").lat(54.0).lon(-1.0)
                .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0)).build();

        when(locationService.findByName("Inland Place")).thenReturn(location);
        when(tideService.getTideStats(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tides/stats")
                        .param("locationName", "Inland Place"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/tides/stats/all returns stats for all coastal locations")
    void getAllTideStats_returnsCombinedStats() throws Exception {
        LocationEntity coastal = LocationEntity.builder()
                .id(1L).name("Berwick-Upon-Tweed").lat(55.77).lon(-2.00)
                .tideType(java.util.Set.of(com.gregochr.goldenhour.entity.TideType.HIGH))
                .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0)).build();
        LocationEntity inland = LocationEntity.builder()
                .id(2L).name("Durham").lat(54.77).lon(-1.58)
                .tideType(java.util.Set.of())
                .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0)).build();

        TideStats stats = new TideStats(
                BigDecimal.valueOf(1.400), BigDecimal.valueOf(1.800),
                BigDecimal.valueOf(-1.200), BigDecimal.valueOf(-1.500), 20,
                BigDecimal.valueOf(2.600), BigDecimal.valueOf(1.600),
                BigDecimal.valueOf(1.750), BigDecimal.valueOf(1.780),
                2L, BigDecimal.valueOf(0.100));

        when(locationService.findAllEnabled()).thenReturn(java.util.List.of(coastal, inland));
        when(tideService.hasStoredExtremes(1L)).thenReturn(true);
        when(tideService.hasStoredExtremes(2L)).thenReturn(false);
        when(tideService.getTideStats(1L)).thenReturn(Optional.of(stats));

        mockMvc.perform(get("/api/tides/stats/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['Berwick-Upon-Tweed'].avgHighMetres").value(1.4))
                .andExpect(jsonPath("$.['Berwick-Upon-Tweed'].p95HighMetres").value(1.78))
                .andExpect(jsonPath("$.['Durham']").doesNotExist());
    }

    private TideExtremeEntity extreme(Long id, TideExtremeType type, LocalDateTime eventTime, double height) {
        return TideExtremeEntity.builder()
                .id(id)
                .locationId(1L)
                .type(type)
                .eventTime(eventTime)
                .heightMetres(BigDecimal.valueOf(height).setScale(3, java.math.RoundingMode.HALF_UP))
                .fetchedAt(LocalDateTime.of(2026, 2, 24, 2, 0))
                .build();
    }
}
