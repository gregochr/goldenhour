package com.gregochr.goldenhour.util;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimeSlotUtils}.
 */
class TimeSlotUtilsTest {

    @Test
    @DisplayName("Sunset picks slot before event, not the closer one after")
    void sunset_prefersSlotBefore() {
        List<String> times = List.of("2026-03-05T17:00", "2026-03-05T18:00");
        // Sunset at 17:35 — 18:00 is closer (25 min) but 17:00 should be chosen (35 min before)
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 5, 17, 35);

        int idx = TimeSlotUtils.findBestIndex(times, sunset, TargetType.SUNSET);

        assertThat(idx).isEqualTo(0); // 17:00
    }

    @Test
    @DisplayName("Sunrise picks slot after event, not the closer one before")
    void sunrise_prefersSlotAfter() {
        List<String> times = List.of("2026-03-05T06:00", "2026-03-05T07:00");
        // Sunrise at 06:25 — 06:00 is closer (25 min) but 07:00 should be chosen (35 min after)
        LocalDateTime sunrise = LocalDateTime.of(2026, 3, 5, 6, 25);

        int idx = TimeSlotUtils.findBestIndex(times, sunrise, TargetType.SUNRISE);

        assertThat(idx).isEqualTo(1); // 07:00
    }

    @Test
    @DisplayName("Falls back to nearest when no slot on preferred side")
    void fallsBackToNearest() {
        List<String> times = List.of("2026-03-05T19:00", "2026-03-05T20:00");
        // Sunset at 17:35 — both slots are after sunset, should fall back to nearest (19:00)
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 5, 17, 35);

        int idx = TimeSlotUtils.findBestIndex(times, sunset, TargetType.SUNSET);

        assertThat(idx).isEqualTo(0); // 19:00 (nearest)
    }

    @Test
    @DisplayName("Sunset picks exact match at event time")
    void sunset_exactMatch() {
        List<String> times = List.of("2026-03-05T17:00", "2026-03-05T18:00");
        LocalDateTime sunset = LocalDateTime.of(2026, 3, 5, 17, 0);

        int idx = TimeSlotUtils.findBestIndex(times, sunset, TargetType.SUNSET);

        assertThat(idx).isEqualTo(0); // exact match at 17:00
    }

    @Test
    @DisplayName("findNearestIndex picks absolute nearest slot regardless of direction")
    void findNearestIndex_picksAbsoluteNearest() {
        List<String> times = List.of(
                "2026-03-30T18:00", "2026-03-30T19:00", "2026-03-30T20:00");
        // Target at 19:15 — nearest is 19:00 (index 1, 15 min away)
        LocalDateTime target = LocalDateTime.of(2026, 3, 30, 19, 15);

        int idx = TimeSlotUtils.findNearestIndex(times, target);

        assertThat(idx).isEqualTo(1);
    }

    @Test
    @DisplayName("findNearestIndex with exact match returns that index")
    void findNearestIndex_exactMatch() {
        List<String> times = List.of(
                "2026-03-30T18:00", "2026-03-30T19:00", "2026-03-30T20:00");
        LocalDateTime target = LocalDateTime.of(2026, 3, 30, 19, 0);

        int idx = TimeSlotUtils.findNearestIndex(times, target);

        assertThat(idx).isEqualTo(1);
    }

    @Test
    @DisplayName("findNearestIndex with single slot returns index 0")
    void findNearestIndex_singleSlot() {
        List<String> times = List.of("2026-03-30T18:00");
        LocalDateTime target = LocalDateTime.of(2026, 3, 30, 22, 0);

        int idx = TimeSlotUtils.findNearestIndex(times, target);

        assertThat(idx).isEqualTo(0);
    }
}
