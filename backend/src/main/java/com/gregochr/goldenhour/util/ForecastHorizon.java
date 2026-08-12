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
 * derivation of a <em>horizon</em> — a T+N — routes through here. Several classes still call
 * {@code LocalDate.now(...)} in {@code Europe/London} for their own "today"
 * ({@code ForceEvalHeadlineSelector}, {@code BatchRetryService},
 * {@code ScheduledBatchEvaluationService}, {@code BriefingRollupBuilder}); they agree with this
 * class on the calendar, so they are duplication rather than divergence. The synchronous engine's
 * date <em>range</em> is a genuine exception and is still UTC-derived — see
 * {@code docs/engineering/intraday-settled-refresh-plan.md} §8a.
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
