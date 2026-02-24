package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TideService}.
 */
@ExtendWith(MockitoExtension.class)
class TideServiceTest {

    @Mock
    private WebClient webClient;

    private ObjectMapper objectMapper;
    private TideService tideService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tideService = new TideService(webClient, objectMapper);
    }

    @Test
    void testCalculateTideAligned_HighTide_WithHighTidePreference() {
        TideData tideData = new TideData(
                "HIGH",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_LowTide_WithHighTidePreference() {
        TideData tideData = new TideData(
                "LOW",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.HIGH_TIDE));
        assertFalse(aligned);
    }

    @Test
    void testCalculateTideAligned_LowTide_WithLowTidePreference() {
        TideData tideData = new TideData(
                "LOW",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.LOW_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_Rising_WithMidTidePreference() {
        TideData tideData = new TideData(
                "RISING",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.MID_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_Falling_WithMidTidePreference() {
        TideData tideData = new TideData(
                "FALLING",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.MID_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_AnyTidePreference() {
        TideData tideData = new TideData(
                "LOW",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.ANY_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_EmptyPreferences() {
        TideData tideData = new TideData(
                "HIGH",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of());
        assertFalse(aligned);
    }

    @Test
    void testCalculateTideAligned_NotCoastal() {
        TideData tideData = new TideData(
                "HIGH",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(tideData, Set.of(TideType.NOT_COASTAL));
        assertFalse(aligned);
    }

    @Test
    void testCalculateTideAligned_MultiplePreferences_HighMatches() {
        TideData tideData = new TideData(
                "HIGH",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(
                tideData,
                Set.of(TideType.HIGH_TIDE, TideType.LOW_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_MultiplePreferences_MidMatches() {
        TideData tideData = new TideData(
                "RISING",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(
                tideData,
                Set.of(TideType.HIGH_TIDE, TideType.MID_TIDE));
        assertTrue(aligned);
    }

    @Test
    void testCalculateTideAligned_MultiplePreferences_NoMatch() {
        TideData tideData = new TideData(
                "FALLING",
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(-0.30));

        boolean aligned = tideService.calculateTideAligned(
                tideData,
                Set.of(TideType.HIGH_TIDE, TideType.LOW_TIDE));
        assertFalse(aligned);
    }

    @Test
    void testGetTideData_NetworkFailureReturnsEmpty() {
        // When network error occurs, getTideData returns Optional.empty()
        var result = tideService.getTideData(54.77, -1.58, LocalDateTime.now());
        assertTrue(result.isEmpty());
    }
}
