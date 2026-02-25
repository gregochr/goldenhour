package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * One-off test to set up a persistent UI demo state showing disabled locations.
 * Run this once, then refresh the frontend to see the LocationAlerts component.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LocationFailureTrackingUIDemo {

    @Autowired
    private LocationRepository locationRepository;

    @Test
    @DisplayName("Setup UI demo: disable a location for visual testing")
    void setupUIDemo() {
        // Disable Angel of the North
        LocationEntity location1 = locationRepository.findByName("Angel of the North")
                .orElseThrow(() -> new IllegalStateException("Angel of the North not found"));
        location1.setConsecutiveFailures(3);
        location1.setLastFailureAt(LocalDateTime.of(2026, 2, 25, 16, 45, 0));
        location1.setDisabledReason("Auto-disabled after 3 consecutive failures");
        locationRepository.save(location1);

        // Set Keswick to show warning state (1 failure)
        LocationEntity location2 = locationRepository.findByName("Keswick")
                .orElseThrow(() -> new IllegalStateException("Keswick not found"));
        location2.setConsecutiveFailures(1);
        location2.setLastFailureAt(LocalDateTime.of(2026, 2, 25, 17, 0, 0));
        location2.setDisabledReason(null);
        locationRepository.save(location2);

        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║         UI Demo Setup Complete — Refresh the Frontend!          ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println("\n✓ Angel of the North: DISABLED (3 failures)");
        System.out.println("  → Shows in LocationAlerts with red ⚠️ Disabled badge and Re-enable button");
        System.out.println("  → Last failure: 2026-02-25T16:45:00");
        System.out.println("\n✓ Keswick: WARNING (1 failure)");
        System.out.println("  → Shows amber 🔄 1 badge on location tab");
        System.out.println("  → Last failure: 2026-02-25T17:00:00");
        System.out.println("\n📍 Go to Frontend → Manage Tab → Locations:");
        System.out.println("   1. See LocationAlerts card at top showing disabled locations");
        System.out.println("   2. See badges on location tabs");
        System.out.println("   3. Click 'Re-enable' to test the reset functionality\n");
    }
}
