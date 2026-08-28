package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.util.ForecastHorizon;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Where Plan's four-day window ends, so Coming up knows where it may start.
 *
 * <p>{@code BriefingService} already hardcoded {@code today .. today+3} as the hot-topic window at
 * two call sites (the serve-time overlay and the build path), both deriving {@code today} as
 * {@code LocalDate.now(clock.withZone(Europe/London))} — identical to {@link ForecastHorizon#today}.
 * This class gives that boundary one name so the Coming up feed's eligibility rule (an entry
 * qualifies only once it ends beyond Plan's last day) can point at the same constant rather than a
 * second hardcoded {@code plusDays(3)}.
 *
 * <p>Deliberately not the last-rendered-events horizon ({@code PlanWindowProjector.renderHorizon}):
 * that figure is computable only at briefing serve time, varies with how many events have already
 * passed, and reading it here would tie the almanac cache's purity to briefing state. Plan's
 * boundary is the fixed four-day window it has always advertised.
 */
public final class PlanHorizon {

    /** Plan owns today plus the next three days — four days total. */
    public static final int PLAN_OWNED_DAYS = 4;

    private PlanHorizon() {
    }

    /**
     * The current date on the UK civil calendar.
     *
     * @param clock clock supplying "now" (typically UTC; interpreted in {@code Europe/London})
     * @return today's date in {@code Europe/London}
     */
    public static LocalDate today(Clock clock) {
        return ForecastHorizon.today(clock);
    }

    /**
     * The last date Plan renders.
     *
     * @param today today's date
     * @return {@code today + (PLAN_OWNED_DAYS - 1)}
     */
    public static LocalDate lastPlanDate(LocalDate today) {
        return today.plusDays(PLAN_OWNED_DAYS - 1L);
    }
}
