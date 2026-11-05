package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gregochr.goldenhour.entity.SolarEvent;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TriggerContext;

/**
 * Last-resort edges of {@link SolarOffsetTrigger}: a stored cron that can never fire, and a last scheduled
 * execution too far out for the UK calendar to represent.
 */
class SolarOffsetTriggerEdgeTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

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

    @Test
    @DisplayName("an unusable solar day with a stored cron that never fires falls back a fixed 24 hours")
    void nextAfter_cronNeverFires_fallsBackToLastResort() {
        // 31 February never occurs, so the parsed cron has no next time.
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 60, "0 0 0 31 2 *",
                (event, date) -> null);

        SolarOffsetTrigger.Next next = trigger.nextAfter(NOW, null);

        assertThat(next.fallback()).isTrue();
        assertThat(next.instant()).isEqualTo(NOW.plus(Duration.ofHours(SolarOffsetTrigger.LAST_RESORT_HOURS)));
        assertThat(next.fallbackReason())
                .isEqualTo("the solar calculation returned no time; and no stored cron could supply a time");
    }

    @Test
    @DisplayName("a last scheduled execution beyond the UK calendar's range cannot be turned into a day")
    void nextExecution_lastExecutionOutOfCalendarRange_throws() {
        SolarOffsetTrigger trigger = new SolarOffsetTrigger("job", SolarEvent.SUNRISE, 60, "0 0 1 * * *",
                (event, date) -> null);

        // Instant.MAX is later than now, so it becomes the threshold; no UK civil date exists for it, and
        // the last-resort handler needs the same date, so the failure reaches the caller.
        assertThatThrownBy(() -> trigger.nextExecution(context(NOW, Instant.MAX)))
                .isInstanceOf(DateTimeException.class);
    }
}
