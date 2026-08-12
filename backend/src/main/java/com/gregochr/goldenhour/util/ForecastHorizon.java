package com.gregochr.goldenhour.util;

import java.time.Clock;
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
 * (FORCE_IMMINENT's same-day test and NEXT_EVENT_ONLY's search window), {@code ForecastController}
 * (the {@code POST /run} default date, and the {@code GET} serve window that has to agree with what
 * the engine now forecasts) and {@code BriefingEvaluationController} (the sibling serve window,
 * which shares two constants with that one and so has to share its anchor). See
 * {@code docs/engineering/intraday-settled-refresh-plan.md} §8a and §8b.
 *
 * <p><b>What is deliberately still UTC — enumerated, because "everything routes through here" is
 * exactly the kind of claim that rots.</b>
 * <ul>
 *   <li>{@code OptimisationSkipEvaluator}'s FORCE_STALE, alone in that class. It measures the date
 *       of a <em>stored UTC instant</em> against today, and what matters is that both sides share a
 *       calendar: on a UK "today", an evaluation written at 23:30 UTC on a BST evening would be
 *       called stale half an hour later.</li>
 *   <li>{@code PromptTestService.resolveDates} — the admin prompt-test harness. Its range would
 *       belong here, but the same class decides which target types a date still has via
 *       {@code resolveTargetTypesForDate}, whose day comes from a caller-supplied UTC instant.
 *       Converting the range alone would split one class across two calendars, which is the defect
 *       this class exists to prevent rather than a step towards fixing it.</li>
 * </ul>
 *
 * <p>{@code ForceSubmitBatchService}'s four-day JFDI range was on this list as known-wrong rather
 * than deliberate; it moved here in its own change (§8c), so the list above is now the whole of it.
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
}
