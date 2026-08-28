package com.gregochr.goldenhour.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlanHorizon} — Plan's four-day boundary and the "today" it is measured
 * from.
 */
class PlanHorizonTest {

    /** 23:30 UTC on 11 Aug 2026 — BST, so London has already turned over to the 12th. */
    private static final Clock BST_PRE_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-08-11T23:30:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("today routes through ForecastHorizon's UK civil calendar, not UTC")
    void today_usesUkCalendar() {
        assertThat(PlanHorizon.today(BST_PRE_MIDNIGHT)).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("Plan's last date is today plus three — a four-day window")
    void lastPlanDate_isThreeDaysAhead() {
        LocalDate today = LocalDate.of(2026, 8, 12);
        assertThat(PlanHorizon.lastPlanDate(today)).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    @DisplayName("PLAN_OWNED_DAYS and lastPlanDate agree — the constant drives the arithmetic")
    void lastPlanDate_matchesPlanOwnedDays() {
        LocalDate today = LocalDate.of(2026, 8, 12);
        assertThat(PlanHorizon.lastPlanDate(today))
                .isEqualTo(today.plusDays(PlanHorizon.PLAN_OWNED_DAYS - 1L));
    }
}
