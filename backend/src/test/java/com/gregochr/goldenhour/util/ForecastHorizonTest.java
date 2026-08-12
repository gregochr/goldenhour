package com.gregochr.goldenhour.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForecastHorizon} — the single derivation of the {@code daysAhead}
 * horizon.
 *
 * <p>The rule is one line of arithmetic; what needs pinning is the <em>calendar</em> it runs
 * on. Every case here fixes an instant where UTC and {@code Europe/London} give different
 * answers, or deliberately one where they do not, so the tests fail if the zone is dropped
 * rather than merely if the subtraction breaks.
 */
class ForecastHorizonTest {

    /** 23:30 UTC on 11 Aug 2026 — BST, so London has already turned over to the 12th. */
    private static final Clock BST_PRE_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-08-11T23:30:00Z"), ZoneOffset.UTC);

    /** 23:30 UTC on 11 Jan 2026 — GMT, so London and UTC are the same date. */
    private static final Clock GMT_PRE_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-01-11T23:30:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("in the BST hour before midnight UTC, today is already the UK's tomorrow-by-UTC")
    void bstPreMidnight_todayIsUkDate() {
        assertThat(ForecastHorizon.today(BST_PRE_MIDNIGHT)).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("an event on the UK's today is T+0 in that same hour")
    void bstPreMidnight_ukTodayIsZero() {
        // On a UTC basis this returned 1, which is what made the intraday policy treat
        // tonight's sunset as tomorrow's and skip it.
        assertThat(ForecastHorizon.daysAhead(LocalDate.of(2026, 8, 12), BST_PRE_MIDNIGHT)).isZero();
    }

    @Test
    @DisplayName("later dates count forward from the UK today, not from the UTC one")
    void bstPreMidnight_countsForwardFromUkToday() {
        assertThat(ForecastHorizon.daysAhead(LocalDate.of(2026, 8, 15), BST_PRE_MIDNIGHT))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("past dates are negative — the past-date filter depends on the sign")
    void pastDatesAreNegative() {
        assertThat(ForecastHorizon.daysAhead(LocalDate.of(2026, 8, 10), BST_PRE_MIDNIGHT))
                .isEqualTo(-2);
    }

    @Test
    @DisplayName("under GMT the same hour is unremarkable — the zone, not the hour, is the rule")
    void gmtPreMidnight_agreesWithUtc() {
        // Pins that the fix is a zone rule and not an offset hack applied to late evenings:
        // in winter the two calendars agree and nothing shifts.
        assertThat(ForecastHorizon.today(GMT_PRE_MIDNIGHT)).isEqualTo(LocalDate.of(2026, 1, 11));
        assertThat(ForecastHorizon.daysAhead(LocalDate.of(2026, 1, 11), GMT_PRE_MIDNIGHT)).isZero();
    }

    @Test
    @DisplayName("the horizon is stable across a day: same date, different hours, same answer")
    void sameDateDifferentHours_agree() {
        Clock morning = Clock.fixed(Instant.parse("2026-08-11T06:00:00Z"), ZoneOffset.UTC);
        Clock afternoon = Clock.fixed(Instant.parse("2026-08-11T14:00:00Z"), ZoneOffset.UTC);
        LocalDate target = LocalDate.of(2026, 8, 13);

        assertThat(ForecastHorizon.daysAhead(target, morning))
                .isEqualTo(ForecastHorizon.daysAhead(target, afternoon))
                .isEqualTo(2);
    }
}
