package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ActualOutcomeEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.OutcomeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link OutcomeController}.
 *
 * <p>Loads the full application context with test configuration and mocks only
 * the {@link OutcomeService} dependency.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OutcomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutcomeService outcomeService;

    @Test
    @WithMockUser
    @DisplayName("GET /api/outcome returns outcomes for a location and date range")
    void getOutcomes_validRequest_returnsOutcomes() throws Exception {
        when(outcomeService.query(eq(54.7753), eq(-1.5849), any(), any()))
                .thenReturn(List.of(buildOutcomeEntity()));

        mockMvc.perform(get("/api/outcome")
                        .param("lat", "54.7753")
                        .param("lon", "-1.5849")
                        .param("from", "2026-02-01")
                        .param("to", "2026-02-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/outcome returns 400 when from is after to")
    void getOutcomes_fromAfterTo_returns400() throws Exception {
        when(outcomeService.query(any(Double.class), any(Double.class), any(), any()))
                .thenThrow(new IllegalArgumentException("'from' must not be after 'to'"));

        mockMvc.perform(get("/api/outcome")
                        .param("lat", "54.7753")
                        .param("lon", "-1.5849")
                        .param("from", "2026-02-28")
                        .param("to", "2026-02-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'from' must not be after 'to'"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/outcome returns 201 with the saved entity")
    void recordOutcome_validRequest_returns201() throws Exception {
        ActualOutcomeEntity saved = buildOutcomeEntity();
        when(outcomeService.record(any())).thenReturn(saved);

        mockMvc.perform(post("/api/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOutcomeJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/outcome returns 400 when the service rejects an invalid score")
    void recordOutcome_serviceRejectsScore_returns400() throws Exception {
        when(outcomeService.record(any()))
                .thenThrow(new IllegalArgumentException("fierySkyActual must be between 0 and 100"));

        mockMvc.perform(post("/api/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validOutcomeJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("fierySkyActual must be between 0 and 100"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/outcome returns 404 when location name is not found")
    void recordOutcome_unknownLocation_returns404() throws Exception {
        when(outcomeService.record(any()))
                .thenThrow(new NoSuchElementException("No location named 'Nowhere'"));

        mockMvc.perform(post("/api/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationLat": 0.0,
                                  "locationLon": 0.0,
                                  "locationName": "Nowhere",
                                  "outcomeDate": "2026-02-20",
                                  "targetType": "SUNSET",
                                  "wentOut": true
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No location named 'Nowhere'"));
    }

    private ActualOutcomeEntity buildOutcomeEntity() {
        LocationEntity durham = LocationEntity.builder()
                .id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();
        return ActualOutcomeEntity.builder()
                .id(1L)
                .location(durham)
                .locationLat(BigDecimal.valueOf(54.7753))
                .locationLon(BigDecimal.valueOf(-1.5849))
                .outcomeDate(LocalDate.of(2026, 2, 20))
                .targetType(TargetType.SUNSET)
                .wentOut(true)
                .fierySkyActual(68)
                .goldenHourActual(75)
                .recordedAt(LocalDateTime.of(2026, 2, 20, 21, 0))
                .build();
    }

    private String validOutcomeJson() {
        return """
                {
                  "locationLat": 54.7753,
                  "locationLon": -1.5849,
                  "locationName": "Durham UK",
                  "outcomeDate": "2026-02-20",
                  "targetType": "SUNSET",
                  "wentOut": true,
                  "fierySkyActual": 68,
                  "goldenHourActual": 75,
                  "notes": "Beautiful orange sky"
                }
                """;
    }
}
