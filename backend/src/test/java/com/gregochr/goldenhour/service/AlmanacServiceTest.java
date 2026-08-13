package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AlmanacService}. */
class AlmanacServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    /**
     * Fixed, for the cases that do not care which day it is. Not {@code Clock.systemUTC()}: six of
     * these tests call {@code getFeed}, which now reads this clock, and two of those compare the
     * results of two consecutive calls — on a real clock they would disagree across a midnight
     * rollover. Far from any real date so it can never coincide with one.
     */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2027-03-05T12:00:00Z"), ZoneOffset.UTC);

    /** A source that records how many times it was asked, so caching can be observed. */
    private static final class CountingSource implements AlmanacSource {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<AlmanacEvent> events;

        CountingSource(List<AlmanacEvent> events) {
            this.events = events;
        }

        @Override
        public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
            calls.incrementAndGet();
            return events;
        }
    }

    private static AlmanacEvent event(LocalDate start, LocalDate end, String type) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, type, type, "detail",
                Map.of(), List.of());
    }

    @Test
    @DisplayName("entries from every source are merged and sorted by start date")
    void mergesAndSortsAcrossSources() {
        AlmanacSource late = (f, t) -> List.of(event(DAY.plusDays(10), DAY.plusDays(10), "late"));
        AlmanacSource early = (f, t) -> List.of(event(DAY, DAY, "early"));

        List<AlmanacEvent> feed = new AlmanacService(List.of(late, early), CLOCK).build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type).containsExactly("early", "late");
    }

    @Test
    @DisplayName("two entries starting the same day order the shorter span first, then by type, "
            + "so the feed is stable across rebuilds")
    void ordersDeterministicallyOnTies() {
        AlmanacSource longSpan = (f, t) -> List.of(event(DAY, DAY.plusDays(5), "season"));
        AlmanacSource shortSpan = (f, t) -> List.of(event(DAY, DAY, "meteor"));
        AlmanacSource alsoShort = (f, t) -> List.of(event(DAY, DAY, "aurora"));

        List<AlmanacEvent> feed = new AlmanacService(List.of(longSpan, shortSpan, alsoShort), CLOCK)
                .build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type)
                .containsExactly("aurora", "meteor", "season");
    }

    @Test
    @DisplayName("one failing source does not blank the feed — an empty tab is worse than a "
            + "missing row")
    void oneFailingSourceIsIsolated() {
        AlmanacSource broken = (f, t) -> {
            throw new IllegalStateException("db down");
        };
        AlmanacSource healthy = (f, t) -> List.of(event(DAY, DAY, "solstice"));

        List<AlmanacEvent> feed =
                new AlmanacService(List.of(broken, healthy), CLOCK).build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type).containsExactly("solstice");
    }

    @Test
    @DisplayName("every source failing yields an empty feed rather than an exception")
    void allSourcesFailingIsStillAFeed() {
        AlmanacSource broken = (f, t) -> {
            throw new IllegalStateException("nope");
        };

        assertThat(new AlmanacService(List.of(broken, broken), CLOCK).build(DAY, DAY)).isEmpty();
    }

    @Test
    @DisplayName("a second request for the same day and length reuses the built feed")
    void cachesWithinTheDay() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK);

        service.getFeed(30);
        service.getFeed(30);

        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a different length rebuilds — a 30-day answer is not a prefix of a 90-day one, "
            + "because a span can start before the window and be reported either way")
    void aDifferentLengthRebuilds() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK);

        service.getFeed(30);
        service.getFeed(90);

        assertThat(source.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("evict() forces the next request to rebuild")
    void evictForcesARebuild() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK);

        service.getFeed(30);
        service.evict();
        service.getFeed(30);

        assertThat(source.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the default horizon is 90 days, matching what the tide fetch window is sized for")
    void defaultHorizonIsNinetyDays() {
        AtomicInteger span = new AtomicInteger();
        AlmanacSource recorder = (f, t) -> {
            span.set((int) java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1);
            return List.of();
        };

        new AlmanacService(List.of(recorder), CLOCK).getFeed();

        assertThat(span.get()).isEqualTo(AlmanacService.DEFAULT_DAYS).isEqualTo(90);
    }

    @Test
    @DisplayName("a nonsensical length is clamped rather than rejected")
    void lengthIsClamped() {
        AtomicInteger span = new AtomicInteger();
        AlmanacSource recorder = (f, t) -> {
            span.set((int) java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1);
            return List.of();
        };
        AlmanacService service = new AlmanacService(List.of(recorder), CLOCK);

        service.getFeed(0);
        assertThat(span.get()).isEqualTo(AlmanacService.MIN_DAYS);

        service.getFeed(100_000);
        assertThat(span.get()).isEqualTo(AlmanacService.MAX_DAYS);

        service.getFeed(-5);
        assertThat(span.get()).isEqualTo(AlmanacService.MIN_DAYS);
    }

    @Test
    @DisplayName("no sources at all is an empty feed, not a failure")
    void noSourcesIsEmpty() {
        assertThat(new AlmanacService(List.of(), CLOCK).getFeed(10)).isEmpty();
    }

    @Test
    @DisplayName("the feed starts on the UK's today, so nothing already over can lead \"Coming up\"")
    void feedStartsOnTheUkCivilDate() {
        // 00:30 on 12 August in Europe/London (BST); still 23:30 on the 11th in UTC. A year in the
        // future on purpose: the mutation this guards against is a revert to LocalDate.now(UTC),
        // which ignores the injected clock and reads the real system date — so a fixture resolving
        // to *today's* real date would agree with the broken code by coincidence.
        Clock lateBstEvening = Clock.fixed(Instant.parse("2027-08-11T23:30:00Z"), ZoneOffset.UTC);
        LocalDate ukToday = LocalDate.of(2027, 8, 12);

        // Through ForecastHorizon, not java.time: a premise with no production code on either side
        // cannot fail on any change to this repo, and worse, it would report "premise holds" while
        // the real assertions below failed. The UTC line is the contrast the feed used to be on.
        assertThat(ForecastHorizon.today(lateBstEvening)).isEqualTo(ukToday);
        assertThat(LocalDate.now(lateBstEvening.withZone(ZoneOffset.UTC)))
                .as("premise: the UK has turned the page and UTC has not")
                .isEqualTo(ukToday.minusDays(1));

        AtomicInteger calls = new AtomicInteger();
        LocalDate[] seen = new LocalDate[2];
        AlmanacSource recorder = (from, to) -> {
            calls.incrementAndGet();
            seen[0] = from;
            seen[1] = to;
            return List.of();
        };

        new AlmanacService(List.of(recorder), lateBstEvening).getFeed(30);

        // Every entry this feed carries is a UK-dated event — a spring tide run, an equinox, an
        // NLC season. On the UTC anchor it opened on 2027-08-11, a day the UK had already finished.
        assertThat(seen[0]).isEqualTo(ukToday);
        assertThat(seen[1]).isEqualTo(ukToday.plusDays(29));
        assertThat(calls.get()).isOne();

        // The cache key rides the same `today` local as the range — one variable, read twice — so
        // it cannot be on a different calendar and needs no separate case. A separate one was
        // written and deleted, and the exact reason matters: with a *fixed* clock two calls hit the
        // cache whatever calendar the key is on, so it could not fail. A mutable clock stepping
        // 22:30Z → 23:30Z on 11 Aug would have separated them. So the justification is the shared
        // local, not an impossibility.
    }

    @Test
    @DisplayName("an entry whose span runs backwards is rejected at construction")
    void backwardsSpanIsRejected() {
        assertThatThrownBy(() -> event(DAY.plusDays(2), DAY, "broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before startDate");
    }
}
