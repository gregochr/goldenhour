package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TideService}.
 */
@ExtendWith(MockitoExtension.class)
class TideServiceTest {

    @Mock
    private WebClient webClient;

    private TideService tideService;

    @BeforeEach
    void setUp() {
        tideService = new TideService(webClient, new ObjectMapper());
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — single preferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH tide aligns with HIGH_TIDE preference")
    void calculateTideAligned_highTide_highPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.HIGH_TIDE)));
    }

    @Test
    @DisplayName("LOW tide does not align with HIGH_TIDE preference")
    void calculateTideAligned_lowTide_highPreference_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.HIGH_TIDE)));
    }

    @Test
    @DisplayName("LOW tide aligns with LOW_TIDE preference")
    void calculateTideAligned_lowTide_lowPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.LOW_TIDE)));
    }

    @Test
    @DisplayName("MID tide aligns with MID_TIDE preference")
    void calculateTideAligned_midTide_midPreference_aligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.MID), Set.of(TideType.MID_TIDE)));
    }

    @Test
    @DisplayName("MID tide does not align with HIGH_TIDE or LOW_TIDE preference")
    void calculateTideAligned_midTide_highOrLowPreference_notAligned() {
        assertFalse(tideService.calculateTideAligned(
                tideData(TideState.MID), Set.of(TideType.HIGH_TIDE, TideType.LOW_TIDE)));
    }

    @Test
    @DisplayName("ANY_TIDE preference aligns with any tide state")
    void calculateTideAligned_anyTidePreference_alwaysAligned() {
        assertTrue(tideService.calculateTideAligned(tideData(TideState.LOW), Set.of(TideType.ANY_TIDE)));
        assertTrue(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.ANY_TIDE)));
        assertTrue(tideService.calculateTideAligned(tideData(TideState.MID), Set.of(TideType.ANY_TIDE)));
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — multiple preferences
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH tide aligns when HIGH_TIDE is one of multiple preferences")
    void calculateTideAligned_multiplePreferences_highMatches() {
        assertTrue(tideService.calculateTideAligned(
                tideData(TideState.HIGH), Set.of(TideType.HIGH_TIDE, TideType.LOW_TIDE)));
    }

    @Test
    @DisplayName("MID tide aligns when MID_TIDE is one of multiple preferences")
    void calculateTideAligned_multiplePreferences_midMatches() {
        assertTrue(tideService.calculateTideAligned(
                tideData(TideState.MID), Set.of(TideType.HIGH_TIDE, TideType.MID_TIDE)));
    }

    // -------------------------------------------------------------------------
    // calculateTideAligned — edge cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Empty preference set returns false")
    void calculateTideAligned_emptyPreferences_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of()));
    }

    @Test
    @DisplayName("NOT_COASTAL preference returns false")
    void calculateTideAligned_notCoastal_notAligned() {
        assertFalse(tideService.calculateTideAligned(tideData(TideState.HIGH), Set.of(TideType.NOT_COASTAL)));
    }

    // -------------------------------------------------------------------------
    // getTideData
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTideData() returns empty Optional (stub implementation)")
    void getTideData_returnsEmpty() {
        assertTrue(tideService.getTideData(54.77, -1.58, LocalDateTime.now()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TideData tideData(TideState state) {
        return new TideData(
                state,
                LocalDateTime.of(2026, 2, 24, 14, 30),
                BigDecimal.valueOf(1.50),
                LocalDateTime.of(2026, 2, 24, 20, 45),
                BigDecimal.valueOf(0.30));
    }
}
