package com.gregochr.goldenhour.util;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The single home of the {@code daysAhead} rule: how far ahead of "today" a forecast target date is.
 *
 * <p><b>"Today" is {@code Europe/London}, not UTC.</b> A forecast target date names a solar event at
 * a UK location — a sunrise in Northumberland on April 19th BST is what matters, not the UTC date
 * that instant happens to fall on. Under BST the two calendars disagree between 23:00 and 00:00 UTC,
 * where UTC is a day behind and therefore overstates every horizon by one.
 *
 * <p>That divergence was not inert. It reached an eligibility policy that branches on the horizon
 * ({@code IntradayEligibilityPolicy}), a stability gate that branches on it
 * ({@code NightlyEligibilityPolicy}), the persisted {@code forecast_evaluation.days_ahead} column and
 * the {@code confidence} band derived from it. Those consumers used to derive the horizon on two
 * different calendars, so this class exists to make a second basis impossible rather than merely
 * absent.
 *
 * <p><b>Scope, stated precisely because the looser version of this sentence was wrong.</b> Every
 * derivation of a <em>horizon</em> — a T+N — routes through here, including the four classes
 * ({@code ForceEvalHeadlineSelector}, {@code BatchRetryService},
 * {@code ScheduledBatchEvaluationService}, {@code BriefingRollupBuilder}) that used to hand-roll
 * {@code LocalDate.now(...)} in {@code Europe/London} for the same "today" — collapsed here since
 * they already agreed with this class on the calendar and the swap was behaviourally inert. The
 * synchronous engine's date <em>range</em> was the one genuine exception; it is no longer, and the
 * reason it had to stop being one is worth keeping. {@link #today} answers "which day is it", and
 * moving that answer to the UK calendar moved the single day
 * {@code ForecastCommandExecutor}'s already-past gate guards. Anything still handing that engine a
 * UTC "today" would therefore be naming a day the gate had just let go of — and, in the hour where
 * the two calendars differ, a day whose events are all over. A half-converted engine was worse than
 * an unconverted one, so everything that hands that engine a "today" moved in the same commit:
 * {@code ForecastCommandFactory} (the default range), {@code OptimisationSkipEvaluator}
 * (FORCE_IMMINENT's same-day test and NEXT_EVENT_ONLY's search window — since deleted along with
 * the strategies it evaluated, none of which could act; V153), {@code ForecastController}
 * (the {@code POST /run} default date, and the {@code GET} serve window that has to agree with what
 * the engine now forecasts) and {@code BriefingEvaluationController} (the sibling serve window,
 * which shares two constants with that one and so has to share its anchor). See
 * {@code docs/engineering/intraday-settled-refresh-plan.md} §8a and §8b.
 *
 * <p><b>What is deliberately still UTC — enumerated, because "everything routes through here" is
 * exactly the kind of claim that rots.</b>
 * <ul>
 *   <li>{@code PromptTestService.resolveDates} — the admin prompt-test harness. Its range would
 *       belong here, but the same class decides which target types a date still has via
 *       {@code resolveTargetTypesForDate}, whose day comes from a caller-supplied UTC instant.
 *       Converting the range alone would split one class across two calendars, which is the defect
 *       this class exists to prevent rather than a step towards fixing it.</li>
 * </ul>
 *
 * <p><b>And what is still UTC without a defence — a separate list, because collapsing the two is
 * how a hedge becomes a completeness claim.</b> {@code ForceSubmitBatchService}'s JFDI range headed
 * this list and moved in §8c; the two {@code EvaluationTask.Aurora} label dates
 * ({@code ScheduledBatchEvaluationService}, {@code AuroraOrchestrator}) and {@code AlmanacService}'s
 * feed anchor followed. {@code AuroraForecastRunService} left it by a different door — it stopped
 * asking a calendar at all, resolving its night on an instant instead
 * ({@code currentNightDate()}); see {@code docs/engineering/aurora-night-selection.md} for why no
 * zone was the right answer there. <b>What remains — bounded, as before, by "reached from a
 * forecast or almanac path", because a bare count is how a hedge becomes a completeness claim
 * again:</b>
 * <ul>
 *   <li>{@code TideService} anchors a refresh window and a stats cutoff on
 *       {@code LocalDate.now(ZoneOffset.UTC)} over UK tide dates.</li>
 *   <li>{@code CalibrationController} and {@code CloudVerificationController} default an open-ended
 *       range's end to a bare {@code LocalDate.now()} — JVM default zone, the same unpinned
 *       construct this class has now removed from two other places.</li>
 * </ul>
 *
 * <p>Outside that bound and deliberately not tracked here: {@code ExchangeRateService}, whose day is
 * an ECB publication day rather than a UK one, and anything keying a cache purely by "when did I
 * last fetch". All the entries above are pre-existing and none was introduced by the changes above.
 * They are listed rather than fixed because each wants its own reasoning about what its date
 * <em>means</em> — the lesson {@code PromptTestService} taught, and the one
 * {@code AuroraForecastRunService} then made concrete by being the first case where the answer came
 * back "no calendar at all".
 *
 * <p><b>An instant is not a calendar.</b> Nothing here answers "has this moment passed". That
 * question is settled by comparing UTC instants — {@code ForecastCommandExecutor} draws both from
 * the same injected clock, but reads the day in {@code Europe/London} and the moment in UTC. Do not
 * collapse the two.
 *
 * <p>The clock is passed in rather than read from the system so a cycle sees one stable reference
 * date and tests can pin the disagreeing hour.
 */
public final class ForecastHorizon {

    /** Solar events are for UK locations, so the UK civil calendar defines "today". */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * How many UK civil days before today the forecast SERVE window reaches back, alongside the
     * forward horizon — the one number behind {@code GET /api/forecast},
     * {@code GET /api/briefing/evaluate/scores} ({@code BriefingEvaluationController} shares it, so
     * changing it moves both endpoints' windows) and the Rewind menu ({@code RewindEventService};
     * {@code RewindFilter} refuses an instant older than this plus a day). It lived on
     * {@code ForecastController} until the rewind feature needed it from a service, and a service
     * reading a controller's constant is the dependency arrow backwards.
     *
     * <p>Was 7, added by f58621f0 together with the DateStrip's dimmed past chips (the DateStrip
     * has since been retired). Reduced to 2 because the payload is cached client-side for instant
     * paint and the past half of it was the larger half: past days are fully dense (every one was
     * scored when it was T+0, and each WILDLIFE-only hide carries one HOURLY comfort row per
     * daylight hour — roughly 8 to 18 a day — written for today through T+5 by
     * {@code WildlifeComfortRefreshJob}, so a past day's rows are whatever the last refresh left;
     * waterfalls never carry any) while T+4 and beyond are never batch-evaluated.
     *
     * <p><b>Not zero, for two reasons the frontend depends on</b> — neither visible from here:
     *
     * <ul>
     *   <li><b>The aurora night in progress.</b> A night runs dusk to dawn, so before dawn it is
     *       YESTERDAY's date. The frontend's {@code mapDates.resolveMapDate}, which picks the Map
     *       tab's date, honours a selection naming that night — set by the aurora banner, and by the
     *       tab's own auto-jump to a night with a stored run — only if the date is in the set this
     *       endpoint returns. At zero T-1 would never be served, and the exemption would refuse the
     *       night silently: no error, the tab just falls through to today, taking its aurora
     *       viewline off the night the alert is about. (The banner's own overlay reads its date
     *       directly and would be unaffected; it is the Map tab that depends on this.)</li>
     *   <li><b>A forecast outage.</b> With no rows from today forward, the past rows are what keep
     *       the client's date set non-empty — and the frontend offers the Map tab only while that set
     *       is non-empty. Once it empties, the Map tab is withheld outright instead of opening onto
     *       its "No forecast to show." empty state.</li>
     * </ul>
     *
     * <p>⚠️ <b>The two need different depths, and only the first is fixed.</b> The aurora night needs
     * exactly one day: the night in progress began at most yesterday. The outage case
     * <b>scales</b>. The most recent row the client can hold is the last date any run forecast, so
     * the Map tab stays reachable for exactly this many days after that date passes — each past day
     * buys one more. A reader who has been away gets the empty state rather than a missing tab for
     * that long; at 1 they would lose it a day sooner. So <b>reducing this is
     * not correctness-neutral</b>, whatever the payload saving: it shortens how long an outage can
     * run before the tab disappears. (An earlier revision of this comment claimed both reasons need
     * one day and called a reduction "a payload decision rather than a correctness one" — true of
     * the aurora case, false of this one, and caught in review before it merged.)
     *
     * <p>The reason this javadoc used to give for "not zero" is gone: it said
     * {@code computeAutoSelection} picked the browser's <em>local</em> date, so a reader west of the
     * UK could legitimately ask for T-1. That stopped being true when it moved to the UK calendar
     * ({@code ukDateStr}); both sides of that comparison are now {@code Europe/London}.
     *
     * <p>Anything older belongs in {@code GET /api/forecast/history}, the ADMIN-only backtesting
     * endpoint, which takes explicit from/to dates and is unaffected by this bound.
     */
    public static final int SERVE_PAST_DAYS = 2;

    private ForecastHorizon() {
    }

    /**
     * The current date on the UK civil calendar.
     *
     * @param clock clock supplying "now" (typically UTC; interpreted in {@code Europe/London})
     * @return today's date in {@code Europe/London}
     */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(LONDON));
    }

    /**
     * Days from today ({@code Europe/London}) to the given date. Negative for past dates.
     *
     * @param date  the forecast target date
     * @param clock clock supplying "today" (via {@code Europe/London})
     * @return the number of days ahead; 0 for today, negative for past dates
     */
    public static int daysAhead(LocalDate date, Clock clock) {
        return (int) ChronoUnit.DAYS.between(today(clock), date);
    }

    /**
     * The UK civil date a stored instant fell on.
     *
     * <p>The serve-time half of the rule {@link #today} states for "now": a stored UTC timestamp
     * compared against UK-dated content must be read on the UK calendar, or for the hour after UK
     * midnight in summer the comparison is off by a day. Lives here so the zone choice stays in
     * this one class rather than leaking into whichever DTO mapper needs it (first consumer: the
     * "Coming up" badge's {@code comingUpLastSeenDate}, plan D3 — the client compares two ISO date
     * strings and no timezone rule reaches the browser).
     *
     * @param instant the stored instant; may be null
     * @return the date {@code instant} fell on in {@code Europe/London}, or null for null input
     */
    public static LocalDate civilDate(Instant instant) {
        return instant == null ? null : LocalDate.ofInstant(instant, LONDON);
    }
}
