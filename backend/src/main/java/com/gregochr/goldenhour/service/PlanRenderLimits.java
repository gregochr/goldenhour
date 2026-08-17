package com.gregochr.goldenhour.service;

/**
 * The Plan tab's rendered-event horizon — how many of the forecast's leading solar events the
 * window-first cards show.
 *
 * <p>One number, one owner, referenced everywhere the horizon matters: {@link PlanWindowProjector}
 * (which windows the forecast-wide picks may land on) and
 * {@link com.gregochr.goldenhour.service.evaluation.BriefingRollupBuilder} (which events the
 * best-bet advisor's prompt may name). Before this, the projector kept its own, date-counted copy
 * — 4 <em>dates</em>, up to 8 events — which silently diverged from this event-counted 6 the
 * moment a day carried two events instead of one. See
 * {@code docs/engineering/plan-verdict-consolidation-plan.md} §1 D3.
 *
 * <p><b>The v2 arm no longer keeps a copy of this.</b> Phase 3 of the same plan retired
 * {@code WindowFirstBriefingContext.jsx MAX_VISIBLE_EVENTS} and the ordering beside it: the
 * projector now publishes {@code DailyBriefingResponse.renderedEvents} and the client renders that
 * list, so this constant is the only place the horizon is decided for every surface that reads the
 * projection.
 *
 * <p><b>One hand-kept copy survives, in the frozen arm.</b> {@code DailyBriefing.jsx} — the v1 Plan
 * tab, held byte-identical as the pilot's comparison control — still has its own
 * {@code MAX_VISIBLE_EVENTS = 6} and its own selector, deliberately: rewiring it would move the
 * control. Keep the two equal by hand until the v1 arm is removed, at which point this note goes
 * with it.
 */
public final class PlanRenderLimits {

    /** How many of the forecast's leading solar events are rendered. */
    public static final int MAX_VISIBLE_EVENTS = 6;

    private PlanRenderLimits() {
    }
}
