package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link SurgeCalibrationLogger}.
 */
@ExtendWith(MockitoExtension.class)
class SurgeCalibrationLoggerTest {

    @InjectMocks
    private SurgeCalibrationLogger logger;

    @Test
    @DisplayName("Significant surge is logged without error")
    void logSignificantSurge() {
        var surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test explanation");

        assertThatCode(() ->
                logger.logPrediction(10L, "Craster", Instant.now(), surge)
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Insignificant surge is silently skipped")
    void skipInsignificantSurge() {
        var surge = new StormSurgeBreakdown(
                0.01, 0.01, 0.02, 1015.0, 3.0, 180.0, 0.0,
                TideRiskLevel.NONE, "No significant surge expected");

        assertThatCode(() ->
                logger.logPrediction(10L, "Craster", Instant.now(), surge)
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Zero surge (none()) is skipped")
    void skipZeroSurge() {
        assertThatCode(() ->
                logger.logPrediction(1L, "Test", Instant.now(), StormSurgeBreakdown.none())
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null location name does not cause error")
    void nullLocationName() {
        var surge = new StormSurgeBreakdown(
                0.25, 0.15, 0.40, 985.0, 18.0, 80.0, 0.85,
                TideRiskLevel.MODERATE, "Test");

        assertThatCode(() ->
                logger.logPrediction(null, null, Instant.now(), surge)
        ).doesNotThrowAnyException();
    }
}
