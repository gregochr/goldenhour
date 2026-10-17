package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The denied-request counter: one INFO line per user per hour, bounded memory, and the two codes that
 * are not denials.
 */
class AskDenialCounterTest {

    private static final Instant NOON = Instant.parse("2026-10-06T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOON);
    private final List<AskDenialCounter.Report> reports = new ArrayList<>();
    private final AskDenialCounter counter = new AskDenialCounter(clock, reports::add);

    @Test
    @DisplayName("denials within an hour are held, and reported once, by reason, when the user's next hour begins")
    void oneLinePerUserPerHour() {
        counter.record(7L, AskErrorCode.RATE_LIMITED);
        counter.record(7L, AskErrorCode.RATE_LIMITED);
        counter.record(7L, AskErrorCode.ALLOWANCE_EXHAUSTED);
        clock.advance(Duration.ofMinutes(59).plusSeconds(59));
        counter.record(7L, AskErrorCode.RATE_LIMITED);
        assertThat(reports).as("still the same hour at 12:59:59").isEmpty();

        clock.advance(Duration.ofSeconds(1));
        counter.record(7L, AskErrorCode.INVALID);

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.userId()).isEqualTo(7L);
            assertThat(report.hourStart()).isEqualTo(NOON);
            assertThat(report.total()).isEqualTo(4);
            assertThat(report.counts()).containsEntry(AskErrorCode.RATE_LIMITED, 3)
                    .containsEntry(AskErrorCode.ALLOWANCE_EXHAUSTED, 1).doesNotContainKey(AskErrorCode.INVALID);
        });
    }

    @Test
    @DisplayName("each user has their own hour: one user's denials never carry another's")
    void perUser() {
        counter.record(1L, AskErrorCode.RATE_LIMITED);
        counter.record(2L, AskErrorCode.DAILY_LIMIT);
        clock.advance(Duration.ofHours(1));
        counter.record(1L, AskErrorCode.RATE_LIMITED);

        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.userId()).isEqualTo(1L);
            assertThat(report.counts()).containsOnlyKeys(AskErrorCode.RATE_LIMITED);
        });
    }

    @Test
    @DisplayName("an unauthenticated token and an engine failure are not denials and are never counted")
    void notDenials() {
        counter.record(7L, AskErrorCode.UNAUTHENTICATED);
        counter.record(7L, AskErrorCode.ENGINE_FAILED);

        assertThat(counter.trackedUsers()).isZero();
        clock.advance(Duration.ofHours(2));
        counter.record(7L, AskErrorCode.TYPED_UNAVAILABLE);
        assertThat(reports).isEmpty();
    }

    @Test
    @DisplayName("a user denied once and never again is reported by a later sweep and forgotten: memory is bounded")
    void sweepReportsAndForgets() {
        for (long user = 1; user <= 10; user++) {
            counter.record(user, AskErrorCode.RATE_LIMITED);
        }
        assertThat(counter.trackedUsers()).isEqualTo(10);
        clock.advance(Duration.ofHours(1));

        for (int i = 0; i < AskDenialCounter.SWEEP_EVERY - 10; i++) {
            counter.record(99L, AskErrorCode.RATE_LIMITED);
        }

        assertThat(reports).hasSize(10).extracting(AskDenialCounter.Report::userId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(counter.trackedUsers()).as("only the user active this hour is held").isEqualTo(1);
    }

    @Test
    @DisplayName("the sweep does not report an hour that is still open")
    void sweepKeepsTheOpenHour() {
        for (int i = 0; i < AskDenialCounter.SWEEP_EVERY; i++) {
            counter.record(5L, AskErrorCode.RATE_LIMITED);
        }

        assertThat(reports).isEmpty();
        assertThat(counter.trackedUsers()).isEqualTo(1);
    }

    @Test
    @DisplayName("the default sink is the INFO log: reporting never throws")
    void defaultSinkLogs() {
        AskDenialCounter logging = new AskDenialCounter(clock);
        logging.record(3L, AskErrorCode.RATE_LIMITED);
        clock.advance(Duration.ofHours(1));
        logging.record(3L, AskErrorCode.RATE_LIMITED);

        assertThat(logging.trackedUsers()).isEqualTo(1);
    }
}
