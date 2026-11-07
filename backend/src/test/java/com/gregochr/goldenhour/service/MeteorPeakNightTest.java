package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MeteorPeakNight}.
 */
class MeteorPeakNightTest {

    @Test
    @DisplayName("every timed seed row, both years, maps its maximum to the seeded night")
    void everyTimedSeedRow_mapsToItsNight() {
        List<MeteorSeed.Row> timed = MeteorSeed.ROWS.stream().filter(r -> r.maxUtc() != null).toList();

        assertThat(timed).hasSize(15);
        for (MeteorSeed.Row row : timed) {
            assertThat(MeteorPeakNight.of(row.maxUtc()))
                    .as("%s %d", row.code(), row.year())
                    .isEqualTo(row.night());
        }
    }

    @Test
    @DisplayName("Geminids 2026: a maximum at 14:00 UT on 14 Dec is the night of 13 Dec")
    void geminids2026_isTheNightOf13Dec() {
        assertThat(MeteorPeakNight.of(Instant.parse("2026-12-14T14:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 12, 13));
    }

    @Test
    @DisplayName("the night turns over at 18:00 UK time in winter (GMT)")
    void boundary_winter() {
        assertThat(MeteorPeakNight.of(Instant.parse("2026-12-13T17:59:00Z"))).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(MeteorPeakNight.of(Instant.parse("2026-12-13T18:00:00Z"))).isEqualTo(LocalDate.of(2026, 12, 13));
    }

    @Test
    @DisplayName("the night turns over at 18:00 UK time in summer (BST, 17:00 UT)")
    void boundary_summer() {
        assertThat(MeteorPeakNight.of(Instant.parse("2026-07-14T16:59:00Z"))).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(MeteorPeakNight.of(Instant.parse("2026-07-14T17:00:00Z"))).isEqualTo(LocalDate.of(2026, 7, 14));
    }

    @Test
    @DisplayName("a date-only maximum for a pre-dawn shower is the evening before")
    void dateOnly_preDawn_minusOneDay() {
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 10, 21), "02:00–05:00"))
                .isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 5, 6), "03:00–dawn"))
                .isEqualTo(LocalDate.of(2026, 5, 5));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 7, 31), "01:00–04:00"))
                .isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 12, 22), "00:00–dawn"))
                .isEqualTo(LocalDate.of(2026, 12, 21));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 6, 10), "05:59–dawn"))
                .isEqualTo(LocalDate.of(2026, 6, 9));
    }

    @Test
    @DisplayName("a date-only maximum for an evening shower is the date itself")
    void dateOnly_evening_sameDay() {
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 11, 5), "23:00–03:00"))
                .isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 10, 8), "20:00–23:00"))
                .isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 6, 10), "06:00–dawn"))
                .isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    @DisplayName("an unparseable best-hours string leaves the date alone")
    void dateOnly_unparseable_sameDay() {
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 6, 10), "all night"))
                .isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    @DisplayName("garbage best hours (non-ASCII digits, empty) leave the date alone")
    void dateOnly_garbage_sameDay() {
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 6, 10), "٠٢:٠٠–dawn"))
                .isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(MeteorPeakNight.ofDateOnly(LocalDate.of(2026, 6, 10), "")).isEqualTo(LocalDate.of(2026, 6, 10));
    }
}
