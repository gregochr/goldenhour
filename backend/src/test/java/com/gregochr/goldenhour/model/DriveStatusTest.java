package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three-way drive classification the reach tiers rely on: a row is MEASURED, and a missing row is UNROUTABLE
 * only when the origin was fully measured after the location's routing point last changed, by more than the
 * roster-visibility margin.
 */
class DriveStatusTest {

    private static final Duration MARGIN = Duration.ofHours(1);
    private static final Instant STAMP = Instant.parse("2026-11-05T02:00:00Z");

    @Test
    @DisplayName("a stored row is MEASURED, even with no stamp or an older one")
    void aRowIsMeasured() {
        assertThat(DriveStatus.of(true, null, null, MARGIN)).isEqualTo(DriveStatus.MEASURED);
        assertThat(DriveStatus.of(true, STAMP, LocalDateTime.of(2026, 11, 6, 0, 0), MARGIN))
                .isEqualTo(DriveStatus.MEASURED);
    }

    @Test
    @DisplayName("no row and no stamp is PENDING")
    void noStampIsPending() {
        assertThat(DriveStatus.of(false, null, LocalDateTime.of(2026, 1, 1, 0, 0), MARGIN))
                .isEqualTo(DriveStatus.PENDING);
    }

    @Test
    @DisplayName("no row and an unknown routing-point change is PENDING, never a guess at UNROUTABLE")
    void unknownTargetChangeIsPending() {
        assertThat(DriveStatus.of(false, STAMP, null, MARGIN)).isEqualTo(DriveStatus.PENDING);
    }

    @Test
    @DisplayName("no row, measured in full after the location last moved, is UNROUTABLE")
    void measuredAfterTheMoveIsUnroutable() {
        assertThat(DriveStatus.of(false, STAMP, LocalDateTime.of(2026, 3, 1, 12, 0), MARGIN))
                .isEqualTo(DriveStatus.UNROUTABLE);
    }

    @Test
    @DisplayName("the margin boundary: exactly one margin before the stamp is PENDING, one second earlier is not")
    void theMarginBoundary() {
        // 01:00:00 is exactly stamp - margin: not strictly before it, so the location may not have been visible.
        assertThat(DriveStatus.of(false, STAMP, LocalDateTime.of(2026, 11, 5, 1, 0, 0), MARGIN))
                .isEqualTo(DriveStatus.PENDING);
        assertThat(DriveStatus.of(false, STAMP, LocalDateTime.of(2026, 11, 5, 0, 59, 59), MARGIN))
                .isEqualTo(DriveStatus.UNROUTABLE);
    }

    @Test
    @DisplayName("a location that moved after the stamp is PENDING")
    void movedAfterTheStampIsPending() {
        assertThat(DriveStatus.of(false, STAMP, LocalDateTime.of(2026, 11, 5, 3, 0), MARGIN))
                .isEqualTo(DriveStatus.PENDING);
    }
}
