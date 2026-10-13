package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SolarEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TriggerContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins {@link SolarOffsetTrigger} to solar-relative-refresh-plan A2's six rules, one test per
 * behaviour. Every test drives the trigger from a fixed clock; the real {@link SolarService} is used
 * wherever the actual sun matters (DST, the clamp, the year sweep) and a hand-built source where a
 * failure has to be forced.
 */
class SolarOffsetTriggerTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final double DURHAM_LAT = 54.776;
    private static final double DURHAM_LON = -1.575;
    private static final double LERWICK_LAT = 60.15;
    private static final double LERWICK_LON = -1.15;
    private static final String CRON_0100 = "0 0 1 * * *";

    private final SolarService solar = new SolarService();

    private SolarOffsetTrigger.EventSource sunAt(double lat, double lon) {
        return (event, date) -> event == SolarEvent.SUNRISE
                ? solar.sunriseUtc(lat, lon, date) : solar.sunsetUtc(lat, lon, date);
    }

    private SolarOffsetTrigger durham(SolarEvent event, int offset) {
        return new SolarOffsetTrigger("job", event, offset, CRON_0100, sunAt(DURHAM_LAT, DURHAM_LON));
    }

    private static TriggerContext context(Instant now, Instant lastScheduled) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new TriggerContext() {
            @Override
            public Clock getClock() {
                return clock;
            }

            @Override
            public Instant lastScheduledExecution() {
                return lastScheduled;
            }

            @Override
            public Instant lastActualExecution() {
                return lastScheduled;
            }

            @Override
            public Instant lastCompletion() {
                return lastScheduled;
            }
        };
    }

    private static LocalTime ukTime(Instant instant) {
        return instant.atZone(LONDON).toLocalTime();
    }

    // ---------------------------------------------------------------------------------------
    // Rule 1 — instants, not wall-clock times
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("rule 1: the fire is exactly the calculator's UTC event instant minus the offset")
    void fire_isEventInstantMinusOffset() {
        LocalDate day = LocalDate.of(2026, 10, 13);
        SolarOffsetTrigger.Fire fire = durham(SolarEvent.SUNRISE, 255).fireFor(day);

        Instant sunrise = solar.sunriseUtc(DURHAM_LAT, DURHAM_LON, day).toInstant(ZoneOffset.UTC);
        assertThat(fire.usable()).isTrue();
        assertThat(fire.eventInstant()).isEqualTo(sunrise);
        assertThat(fire.fire()).isEqualTo(sunrise.minus(Duration.ofMinutes(255)));
        assertThat(fire.clamped()).isFalse();
    }

    @Test
    @DisplayName("rule 1: on the spring-forward day a fire whose local wall-clock time would be the "
            + "nonexistent 01:30 is still event minus offset in real time")
    void fire_acrossSpringForward_keepsExactOffset() {
        LocalDate day = LocalDate.of(2026, 3, 29);
        Instant sunrise = solar.sunriseUtc(DURHAM_LAT, DURHAM_LON, day).toInstant(ZoneOffset.UTC);
        // Choose the offset that puts the fire at 00:30 UTC: 30 minutes before the clocks go
        // forward. Subtracting that offset from the BST wall-clock sunrise instead gives ~01:30 BST,
        // a time that never existed that morning.
        int offset = (int) Duration.between(day.atTime(0, 30).toInstant(ZoneOffset.UTC), sunrise)
                .toMinutes();
        assertThat(offset).as("test premise").isBetween(1, 720);

        SolarOffsetTrigger.Fire fire = durham(SolarEvent.SUNRISE, offset).fireFor(day);

        assertThat(fire.usable()).isTrue();
        assertThat(Duration.between(fire.fire(), fire.eventInstant()))
                .isEqualTo(Duration.ofMinutes(offset));
        assertThat(fire.fire()).isAfterOrEqualTo(day.atTime(0, 29).toInstant(ZoneOffset.UTC))
                .isBeforeOrEqualTo(day.atTime(0, 31).toInstant(ZoneOffset.UTC));
        assertThat(fire.fire().atZone(LONDON).getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("rule 1: on the fall-back day the gap is still exactly the offset, for both events")
    void fire_acrossFallBack_keepsExactOffset() {
        LocalDate day = LocalDate.of(2026, 10, 25);
        SolarOffsetTrigger.Fire morning = durham(SolarEvent.SUNRISE, 255).fireFor(day);
        SolarOffsetTrigger.Fire afternoon = durham(SolarEvent.SUNSET, 240).fireFor(day);

        assertThat(Duration.between(morning.fire(), morning.eventInstant()))
                .isEqualTo(Duration.ofMinutes(255));
        assertThat(Duration.between(afternoon.fire(), afternoon.eventInstant()))
                .isEqualTo(Duration.ofMinutes(240));
        // After the 02:00 BST -> 01:00 GMT change, both fires are in GMT.
        assertThat(afternoon.fire().atZone(LONDON).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(ukTime(afternoon.fire())).isBetween(LocalTime.of(11, 0), LocalTime.of(13, 0));
    }

    // ---------------------------------------------------------------------------------------
    // Rule 2 — never before D at 00:05 UK
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("rule 2: a sunrise-255 fire that would land before UK midnight is clamped to 00:05 UK")
    void fire_clampedToJustAfterUkMidnight() {
        LocalDate day = LocalDate.of(2026, 6, 18);
        SolarOffsetTrigger lerwick = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, CRON_0100,
                sunAt(LERWICK_LAT, LERWICK_LON));

        Instant sunrise = solar.sunriseUtc(LERWICK_LAT, LERWICK_LON, day).toInstant(ZoneOffset.UTC);
        Instant unclamped = sunrise.minus(Duration.ofMinutes(255));
        Instant midnightUk = day.atStartOfDay(LONDON).toInstant();
        assertThat(unclamped).as("test premise: the unclamped fire is the previous UK day")
                .isBefore(midnightUk);

        SolarOffsetTrigger.Fire fire = lerwick.fireFor(day);

        assertThat(fire.usable()).isTrue();
        assertThat(fire.clamped()).isTrue();
        assertThat(fire.fire()).isEqualTo(day.atTime(0, 5).atZone(LONDON).toInstant());
        assertThat(fire.fire().atZone(LONDON).toLocalDate()).isEqualTo(day);
    }

    @Test
    @DisplayName("rule 2: at Durham the midsummer morning fire needs no clamp (about 00:13)")
    void fire_atDurhamMidsummer_isNotClamped() {
        SolarOffsetTrigger.Fire fire = durham(SolarEvent.SUNRISE, 255).fireFor(LocalDate.of(2026, 6, 18));

        assertThat(fire.clamped()).isFalse();
        assertThat(ukTime(fire.fire())).isBetween(LocalTime.of(0, 10), LocalTime.of(0, 20));
    }

    // ---------------------------------------------------------------------------------------
    // Rule 3 — never null: cron fallback
    // ---------------------------------------------------------------------------------------

    private static final Instant NOON_JUNE_18 = Instant.parse("2026-06-18T12:00:00Z");

    @Test
    @DisplayName("rule 3: a calculator that throws falls back to the stored cron, not null")
    void nextExecution_calculatorThrows_usesCron() {
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, CRON_0100,
                (event, date) -> {
                    throw new IllegalStateException("boom");
                });

        Instant next = trigger.nextExecution(context(NOON_JUNE_18, null));

        assertThat(next).isEqualTo(Instant.parse("2026-06-19T01:00:00Z"));
    }

    @Test
    @DisplayName("rule 3: a calculator that returns no time falls back to the stored cron")
    void nextExecution_calculatorReturnsNull_usesCron() {
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, CRON_0100,
                (event, date) -> null);

        assertThat(trigger.nextExecution(context(NOON_JUNE_18, null)))
                .isEqualTo(Instant.parse("2026-06-19T01:00:00Z"));
    }

    @Test
    @DisplayName("rule 3: the midnight sentinel (the event does not occur) falls back to the stored cron")
    void nextExecution_midnightSentinel_usesCron() {
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, CRON_0100,
                (event, date) -> date.atStartOfDay());

        SolarOffsetTrigger.Fire fire = trigger.fireFor(LocalDate.of(2026, 6, 18));

        assertThat(fire.usable()).isFalse();
        assertThat(fire.fallbackReason()).contains("does not occur");
        assertThat(trigger.nextExecution(context(NOON_JUNE_18, null)))
                .isEqualTo(Instant.parse("2026-06-19T01:00:00Z"));
    }

    @Test
    @DisplayName("rule 3: the real calculator fed NaN or a pole never makes the trigger return null "
            + "or a time in the past")
    void nextExecution_realCalculatorAtNanAndPole_neverNullAndAlwaysFuture() {
        for (double lat : new double[] {Double.NaN, 89.9, -89.9}) {
            SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255,
                    CRON_0100, sunAt(lat, 0.0));

            Instant next = trigger.nextExecution(context(NOON_JUNE_18, null));

            assertThat(next).as("latitude " + lat).isNotNull().isAfter(NOON_JUNE_18);
        }
    }

    @Test
    @DisplayName("rule 3: with a calculator that fails and no usable cron, a time a day on is "
            + "returned rather than null")
    void nextExecution_noCronAtAll_returnsLastResortNotNull() {
        for (String cron : new String[] {null, "", "not a cron"}) {
            SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, cron,
                    (event, date) -> {
                        throw new IllegalStateException("boom");
                    });

            assertThat(trigger.nextExecution(context(NOON_JUNE_18, null)))
                    .as("cron '" + cron + "'")
                    .isEqualTo(NOON_JUNE_18.plus(Duration.ofHours(SolarOffsetTrigger.LAST_RESORT_HOURS)));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rule 4 — strictly after now and the last scheduled execution
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("rule 4: before today's fire the next execution is today's fire")
    void nextExecution_beforeTodaysFire_isToday() {
        SolarOffsetTrigger trigger = durham(SolarEvent.SUNRISE, 255);
        LocalDate day = LocalDate.of(2026, 10, 13);
        Instant todaysFire = trigger.fireFor(day).fire();

        Instant next = trigger.nextExecution(context(todaysFire.minusSeconds(1), null));

        assertThat(next).isEqualTo(todaysFire);
    }

    @Test
    @DisplayName("rule 4: at exactly today's fire the next execution is tomorrow's (strictly after now)")
    void nextExecution_atTodaysFire_isTomorrow() {
        SolarOffsetTrigger trigger = durham(SolarEvent.SUNRISE, 255);
        Instant todaysFire = trigger.fireFor(LocalDate.of(2026, 10, 13)).fire();

        Instant next = trigger.nextExecution(context(todaysFire, null));

        assertThat(next).isEqualTo(trigger.fireFor(LocalDate.of(2026, 10, 14)).fire());
    }

    @Test
    @DisplayName("rule 4: a clock that reads earlier than the last scheduled execution still yields "
            + "a time strictly after that execution")
    void nextExecution_clockBehindLastScheduled_isStrictlyAfterLast() {
        SolarOffsetTrigger trigger = durham(SolarEvent.SUNRISE, 255);
        Instant todaysFire = trigger.fireFor(LocalDate.of(2026, 10, 13)).fire();

        // The scheduler last scheduled today's fire, but the clock has been stepped back before it.
        Instant next = trigger.nextExecution(context(todaysFire.minusSeconds(600), todaysFire));

        assertThat(next).isEqualTo(trigger.fireFor(LocalDate.of(2026, 10, 14)).fire());
    }

    @Test
    @DisplayName("rule 4: feeding each result back as the last execution never repeats a fire and "
            + "advances one UK day at a time")
    void nextExecution_chained_neverDoubleFires() {
        SolarOffsetTrigger trigger = durham(SolarEvent.SUNSET, 240);
        Instant last = null;
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        LocalDate previousDay = null;
        for (int i = 0; i < 10; i++) {
            Instant next = trigger.nextExecution(context(now, last));
            assertThat(next).isAfter(now);
            if (last != null) {
                assertThat(next).isAfter(last);
            }
            LocalDate day = next.atZone(LONDON).toLocalDate();
            if (previousDay != null) {
                assertThat(day).isEqualTo(previousDay.plusDays(1));
            }
            previousDay = day;
            last = next;
            now = next.plusSeconds(30);
        }
    }

    @Test
    @DisplayName("rule 4: the cron fallback is also strictly after the last scheduled execution")
    void nextExecution_cronFallback_isStrictlyAfterLast() {
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, CRON_0100,
                (event, date) -> null);
        Instant last = Instant.parse("2026-06-19T01:00:00Z");

        Instant next = trigger.nextExecution(context(Instant.parse("2026-06-19T00:30:00Z"), last));

        assertThat(next).isEqualTo(Instant.parse("2026-06-20T01:00:00Z"));
    }

    // ---------------------------------------------------------------------------------------
    // Rule 5 — sanity bounds
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("rule 5: a sunrise fire later than 06:00 UK is refused and the cron is used")
    void sunriseFireAfterSix_fallsBackToCron() {
        // A "sunrise" at 12:00 UTC would fire at 08:45 UTC (09:45 BST): impossible, so a bug.
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 255, CRON_0100,
                (event, date) -> date.atTime(12, 0));
        LocalDate day = LocalDate.of(2026, 6, 18);

        SolarOffsetTrigger.Fire fire = trigger.fireFor(day);

        assertThat(fire.usable()).isFalse();
        assertThat(fire.fallbackReason()).contains("outside the sane range");
        assertThat(trigger.nextExecution(context(Instant.parse("2026-06-18T00:00:00Z"), null)))
                .isEqualTo(Instant.parse("2026-06-18T01:00:00Z"));
    }

    @Test
    @DisplayName("rule 5: a sunset fire earlier than 10:00 or later than 20:00 UK is refused")
    void sunsetFireOutsideTenToTwenty_isRefused() {
        LocalDate day = LocalDate.of(2026, 10, 13);
        SolarOffsetTrigger early = new SolarOffsetTrigger("job", SolarEvent.SUNSET, 240, CRON_0100,
                (event, date) -> date.atTime(12, 0));
        SolarOffsetTrigger late = new SolarOffsetTrigger("job", SolarEvent.SUNSET, 0, CRON_0100,
                (event, date) -> date.atTime(21, 0));

        assertThat(early.fireFor(day).usable()).as("12:00Z - 4h = 08:00Z = 09:00 BST").isFalse();
        assertThat(late.fireFor(day).usable()).as("21:00Z = 22:00 BST").isFalse();
    }

    @Test
    @DisplayName("rule 5: the bounds are inclusive, and a Durham sunset-240 fire sits well inside them")
    void sanityBounds_areInclusive() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        // UTC in January is UK time: a 14:00 event minus 240 min fires at exactly 10:00.
        SolarOffsetTrigger atLowerBound = new SolarOffsetTrigger("job", SolarEvent.SUNSET, 240,
                CRON_0100, (event, date) -> date.atTime(14, 0));
        SolarOffsetTrigger atUpperBound = new SolarOffsetTrigger("job", SolarEvent.SUNSET, 0,
                CRON_0100, (event, date) -> date.atTime(20, 0));

        assertThat(atLowerBound.fireFor(day).usable()).isTrue();
        assertThat(atUpperBound.fireFor(day).usable()).isTrue();
        assertThat(durham(SolarEvent.SUNSET, 240).fireFor(day).usable()).isTrue();
    }

    // ---------------------------------------------------------------------------------------
    // Year sweep at Durham against plan A5
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a 365-day sweep at Durham keeps every sunrise-255 fire within 00:05-04:20 UK and "
            + "every sunset-240 fire within 11:35-17:55 UK, with A5's extremes")
    void yearSweep_atDurham_matchesPlanRanges() {
        SolarOffsetTrigger morning = durham(SolarEvent.SUNRISE, 255);
        SolarOffsetTrigger afternoon = durham(SolarEvent.SUNSET, 240);
        LocalTime morningMin = LocalTime.MAX;
        LocalTime morningMax = LocalTime.MIN;
        LocalTime afternoonMin = LocalTime.MAX;
        LocalTime afternoonMax = LocalTime.MIN;
        LocalDate morningMinDay = null;
        LocalDate morningMaxDay = null;
        LocalDate afternoonMinDay = null;
        LocalDate afternoonMaxDay = null;

        for (LocalDate day = LocalDate.of(2026, 1, 1); day.isBefore(LocalDate.of(2027, 1, 1));
                day = day.plusDays(1)) {
            SolarOffsetTrigger.Fire m = morning.fireFor(day);
            SolarOffsetTrigger.Fire a = afternoon.fireFor(day);
            assertThat(m.usable()).as("morning " + day).isTrue();
            assertThat(a.usable()).as("afternoon " + day).isTrue();
            assertThat(m.clamped()).as("morning clamp " + day).isFalse();
            LocalTime mt = ukTime(m.fire());
            LocalTime at = ukTime(a.fire());
            assertThat(mt).as("morning " + day).isBetween(LocalTime.of(0, 5), LocalTime.of(4, 20));
            assertThat(at).as("afternoon " + day).isBetween(LocalTime.of(11, 35), LocalTime.of(17, 55));
            if (mt.isBefore(morningMin)) {
                morningMin = mt;
                morningMinDay = day;
            }
            if (mt.isAfter(morningMax)) {
                morningMax = mt;
                morningMaxDay = day;
            }
            if (at.isBefore(afternoonMin)) {
                afternoonMin = at;
                afternoonMinDay = day;
            }
            if (at.isAfter(afternoonMax)) {
                afternoonMax = at;
                afternoonMaxDay = day;
            }
        }

        // Plan A5: morning 00:13 (18 Jun) .. 04:17 (29 Dec); afternoon 11:39 (14 Dec) .. 17:50 (24 Jun).
        assertThat(morningMin).isBetween(LocalTime.of(0, 11), LocalTime.of(0, 15));
        assertThat(morningMinDay).isBetween(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 21));
        assertThat(morningMax).isBetween(LocalTime.of(4, 15), LocalTime.of(4, 19));
        assertThat(morningMaxDay).isBetween(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31));
        assertThat(afternoonMin).isBetween(LocalTime.of(11, 37), LocalTime.of(11, 41));
        assertThat(afternoonMinDay).isBetween(LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 18));
        assertThat(afternoonMax).isBetween(LocalTime.of(17, 48), LocalTime.of(17, 52));
        assertThat(afternoonMaxDay).isBetween(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 28));
    }

    // ---------------------------------------------------------------------------------------
    // Rule 6 — logging; construction guards
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("rule 6: each computed fire writes exactly one INFO line naming job, event, instants "
            + "and the clamp/fallback flags")
    void nextExecution_logsOneInfoLinePerFire() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(SolarOffsetTrigger.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SolarOffsetTrigger trigger = new SolarOffsetTrigger("nightly", SolarEvent.SUNRISE, 255,
                    CRON_0100, sunAt(LERWICK_LAT, LERWICK_LON));

            Instant next = trigger.nextExecution(context(Instant.parse("2026-06-17T12:00:00Z"), null));

            assertThat(appender.list).hasSize(1);
            ch.qos.logback.classic.spi.ILoggingEvent line = appender.list.get(0);
            assertThat(line.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            String text = line.getFormattedMessage();
            assertThat(text).contains("nightly", "SUNRISE", "offset=255min", "fire=" + next,
                    "eventInstant=", "clamped=true", "fallback=false");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("a fallback is logged at WARN with its reason, once")
    void nextExecution_fallbackLogsWarn() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(SolarOffsetTrigger.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SolarOffsetTrigger trigger = new SolarOffsetTrigger("nightly", SolarEvent.SUNRISE, 255,
                    CRON_0100, (event, date) -> null);

            trigger.nextExecution(context(NOON_JUNE_18, null));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("nightly", "fell back", "returned no time");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("construction refuses a missing event and an offset outside 0..720")
    void constructor_refusesInvalidRule() {
        SolarOffsetTrigger.EventSource source = (event, date) -> LocalDateTime.of(date, LocalTime.NOON);

        assertThatThrownBy(() -> new SolarOffsetTrigger("j", null, 10, CRON_0100, source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SolarOffsetTrigger("j", SolarEvent.SUNRISE, -1, CRON_0100, source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SolarOffsetTrigger("j", SolarEvent.SUNRISE, 721, CRON_0100, source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new SolarOffsetTrigger("j", SolarEvent.SUNRISE, 0, CRON_0100, source).offsetMinutes())
                .isZero();
        assertThat(new SolarOffsetTrigger("j", SolarEvent.SUNSET, 720, CRON_0100, source).event())
                .isEqualTo(SolarEvent.SUNSET);
    }
}
