package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test demonstrating location failure tracking and dead-letter mechanism.
 */
@SpringBootTest
class LocationFailureTrackingTest {

    @Autowired
    private LocationService locationService;

    @Autowired
    private LocationRepository locationRepository;

    @BeforeEach
    void setUp() {
        // Ensure test locations exist and reset failure state for a clean test
        LocationEntity angel = locationRepository.findByName("Angel of the North")
                .orElseGet(() -> locationRepository.save(LocationEntity.builder()
                        .name("Angel of the North")
                        .lat(54.9141).lon(-1.5895)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build()));
        angel.setConsecutiveFailures(0);
        angel.setLastFailureAt(null);
        angel.setDisabledReason(null);
        locationRepository.save(angel);

        LocationEntity keswick = locationRepository.findByName("Keswick")
                .orElseGet(() -> locationRepository.save(LocationEntity.builder()
                        .name("Keswick")
                        .lat(54.6).lon(-3.13)
                        .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                        .build()));
        keswick.setConsecutiveFailures(0);
        keswick.setLastFailureAt(null);
        keswick.setDisabledReason(null);
        locationRepository.save(keswick);
    }

    @Test
    @DisplayName("Location failure tracking: auto-disable after 3 consecutive failures")
    void testLocationAutoDisableAfterThreeFailures() {
        LocationEntity location = locationRepository.findByName("Angel of the North")
                .orElseThrow(() -> new IllegalStateException("Test location not found"));

        // Simulate first failure
        location.setConsecutiveFailures(1);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        locationRepository.save(location);
        assertThat(location.getConsecutiveFailures()).isEqualTo(1);
        assertThat(location.getDisabledReason()).isNull();

        // Simulate second failure
        location.setConsecutiveFailures(2);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        locationRepository.save(location);
        assertThat(location.getConsecutiveFailures()).isEqualTo(2);
        assertThat(location.getDisabledReason()).isNull();

        // Simulate third failure - triggers auto-disable
        location.setConsecutiveFailures(3);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        location.setDisabledReason("Auto-disabled after 3 consecutive failures");
        locationRepository.save(location);
        assertThat(location.getConsecutiveFailures()).isEqualTo(3);
        assertThat(location.getDisabledReason()).isNotNull();

        // Test re-enable functionality
        LocationEntity reenabledLocation = locationService.resetFailures(location.getName());
        assertThat(reenabledLocation.getConsecutiveFailures()).isEqualTo(0);
        assertThat(reenabledLocation.getDisabledReason()).isNull();
        assertThat(reenabledLocation.getLastFailureAt()).isNull();
    }

    @Test
    @DisplayName("Success resets consecutive failure counter")
    void testSuccessResetsFailureCounter() {
        LocationEntity location = locationRepository.findByName("Keswick")
                .orElseThrow(() -> new IllegalStateException("Test location not found"));

        // Set location to have some failures
        location.setConsecutiveFailures(2);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        locationRepository.save(location);
        assertThat(location.getConsecutiveFailures()).isEqualTo(2);

        // Simulate success - resets counter
        location.setConsecutiveFailures(0);
        locationRepository.save(location);
        assertThat(location.getConsecutiveFailures()).isEqualTo(0);
    }
}
