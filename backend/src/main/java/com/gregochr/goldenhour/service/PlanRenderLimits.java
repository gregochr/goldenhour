package com.gregochr.goldenhour.service;

import java.time.LocalDate;

/**
 * The Plan tab's rendered-event horizon: which of the forecast's solar events the window-first
 * cards, the Map's heat field and every picker of a window may name.
 *
 * <p>The rule is a <b>date bound over whole days</b>: every non-past solar event whose date lies in
 * {@code [today, today + 3]} is rendered, in payload order ({@link #withinHorizon}). The day count
 * is owned by {@link PlanHorizon} ({@link PlanHorizon#PLAN_OWNED_DAYS}); this class only reads it,
 * so there is no second day constant to drift. It used to be an event count (the leading six),
 * which was three whole days before today's sunrise and three and a half after it, so At a Glance
 * grew a half-empty fourth column once the morning had gone and the Map painted no field for a
 * window outside the six. See {@code docs/engineering/whole-day-horizon-plan.md}.
 *
 * <p>One owner, referenced everywhere the horizon matters: {@link PlanWindowProjector} (which
 * windows the forecast-wide picks may land on), {@link
 * com.gregochr.goldenhour.service.evaluation.BriefingRollupBuilder} (which events the best-bet
 * advisor's prompt may name) and {@link BriefingDigestService} (the digest's default list).
 *
 * <p><b>The client keeps no copy of this.</b> The projector publishes
 * {@code DailyBriefingResponse.renderedEvents} and the client renders that list.
 */
public final class PlanRenderLimits {

    /**
     * The most events the horizon can ever hold: two solar events (sunrise and sunset) on each of
     * {@link PlanHorizon#PLAN_OWNED_DAYS} days. A compile-time constant, because it is used in an
     * annotation; it is a ceiling, not a rule: the rule is {@link #withinHorizon}.
     */
    public static final int MAX_VISIBLE_EVENTS = PlanHorizon.PLAN_OWNED_DAYS * 2;

    private PlanRenderLimits() {
    }

    /**
     * Whether a date is inside the rendered horizon: today or later, and no later than
     * {@link PlanHorizon#lastPlanDate(LocalDate)}.
     *
     * <p>The lower bound is part of the rule: an aged briefing's first day is yesterday, and an
     * untimed event on it counts as current, so an upper bound alone would render it.
     *
     * @param date  the event's date
     * @param today the UK civil date, derived through {@code ForecastHorizon} (never from a UTC
     *              {@code LocalDateTime})
     * @return true when {@code date} is in {@code [today, today + 3]}
     */
    public static boolean withinHorizon(LocalDate date, LocalDate today) {
        return !date.isBefore(today) && !date.isAfter(PlanHorizon.lastPlanDate(today));
    }
}
