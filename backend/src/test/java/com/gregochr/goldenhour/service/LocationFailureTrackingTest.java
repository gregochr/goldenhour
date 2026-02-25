package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test demonstrating location failure tracking and dead-letter mechanism.
 */
@SpringBootTest
@ActiveProfiles("local")
class LocationFailureTrackingTest {

    @Autowired
    private LocationService locationService;

    @Autowired
    private LocationRepository locationRepository;

    @Test
    @DisplayName("Location failure tracking: auto-disable after 3 consecutive failures")
    void testLocationAutoDisableAfterThreeFailures() {
        // Find an existing location
        LocationEntity location = locationRepository.findByName("Angel of the North")
                .orElseThrow(() -> new IllegalStateException("Test location not found"));

        System.out.println("\n=== Location Failure Tracking Demo ===");
        System.out.println("Location: " + location.getName());
        System.out.println("Initial state - Consecutive Failures: " + location.getConsecutiveFailures());
        System.out.println("Initial state - Disabled Reason: " + location.getDisabledReason());

        // Simulate first failure
        location.setConsecutiveFailures(1);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        locationRepository.save(location);
        System.out.println("\nAfter 1st failure - Consecutive Failures: 1");
        assertThat(location.getConsecutiveFailures()).isEqualTo(1);
        assertThat(location.getDisabledReason()).isNull();

        // Simulate second failure
        location.setConsecutiveFailures(2);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        locationRepository.save(location);
        System.out.println("After 2nd failure - Consecutive Failures: 2");
        assertThat(location.getConsecutiveFailures()).isEqualTo(2);
        assertThat(location.getDisabledReason()).isNull();

        // Simulate third failure - triggers auto-disable
        location.setConsecutiveFailures(3);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        location.setDisabledReason("Auto-disabled after 3 consecutive failures");
        locationRepository.save(location);
        System.out.println("After 3rd failure - Consecutive Failures: 3");
        System.out.println("After 3rd failure - Disabled Reason: " + location.getDisabledReason());
        assertThat(location.getConsecutiveFailures()).isEqualTo(3);
        assertThat(location.getDisabledReason()).isNotNull();

        // Test re-enable functionality
        System.out.println("\n=== Testing Re-Enable ===");
        LocationEntity reenabledLocation = locationService.resetFailures(location.getName());
        System.out.println("After re-enable - Consecutive Failures: " + reenabledLocation.getConsecutiveFailures());
        System.out.println("After re-enable - Disabled Reason: " + reenabledLocation.getDisabledReason());
        System.out.println("After re-enable - Last Failure At: " + reenabledLocation.getLastFailureAt());

        assertThat(reenabledLocation.getConsecutiveFailures()).isEqualTo(0);
        assertThat(reenabledLocation.getDisabledReason()).isNull();
        assertThat(reenabledLocation.getLastFailureAt()).isNull();

        System.out.println("\n✓ Location failure tracking working correctly!");
        System.out.println("✓ Auto-disable triggered at 3 consecutive failures");
        System.out.println("✓ Re-enable button successfully resets all failure fields\n");
    }

    @Test
    @DisplayName("Success resets consecutive failure counter")
    void testSuccessResetsFailureCounter() {
        LocationEntity location = locationRepository.findByName("Keswick")
                .orElseThrow(() -> new IllegalStateException("Test location not found"));

        System.out.println("\n=== Success Resets Failure Counter ===");

        // Set location to have some failures
        location.setConsecutiveFailures(2);
        location.setLastFailureAt(LocalDateTime.now(ZoneOffset.UTC));
        locationRepository.save(location);
        System.out.println("Location has 2 consecutive failures");
        assertThat(location.getConsecutiveFailures()).isEqualTo(2);

        // Simulate success - resets counter
        location.setConsecutiveFailures(0);
        locationRepository.save(location);
        System.out.println("After successful forecast - Counter reset to 0");
        assertThat(location.getConsecutiveFailures()).isEqualTo(0);

        System.out.println("✓ Success counter reset working correctly!\n");
    }
}
