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
 * This class gives that boundary one name rather than a hardcoded {@code plusDays(3)}. It no longer
 * drives Coming up eligibility (every event that has not ended is eligible,
 * coming-up-near-term-plan.md); the hot-topic window is its remaining use.
 *
 * <p>This is also the owner of the rendered horizon: {@link PlanRenderLimits#withinHorizon} renders
 * every non-past solar event dated in {@code [today, lastPlanDate(today)]}, so At a Glance, the Map
 * and the advisor's rollup all end on the same whole day. The projector's non-past filter is the
 * only briefing-state input; this boundary stays a pure function of the date.
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
