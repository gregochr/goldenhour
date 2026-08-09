package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AlmanacService}. */
class AlmanacServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

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

        List<AlmanacEvent> feed = new AlmanacService(List.of(late, early)).build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type).containsExactly("early", "late");
    }

    @Test
    @DisplayName("two entries starting the same day order the shorter span first, then by type, "
            + "so the feed is stable across rebuilds")
    void ordersDeterministicallyOnTies() {
        AlmanacSource longSpan = (f, t) -> List.of(event(DAY, DAY.plusDays(5), "season"));
        AlmanacSource shortSpan = (f, t) -> List.of(event(DAY, DAY, "meteor"));
        AlmanacSource alsoShort = (f, t) -> List.of(event(DAY, DAY, "aurora"));

        List<AlmanacEvent> feed = new AlmanacService(List.of(longSpan, shortSpan, alsoShort))
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
                new AlmanacService(List.of(broken, healthy)).build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type).containsExactly("solstice");
    }

    @Test
    @DisplayName("every source failing yields an empty feed rather than an exception")
    void allSourcesFailingIsStillAFeed() {
        AlmanacSource broken = (f, t) -> {
            throw new IllegalStateException("nope");
        };

        assertThat(new AlmanacService(List.of(broken, broken)).build(DAY, DAY)).isEmpty();
    }

    @Test
    @DisplayName("a second request for the same day and length reuses the built feed")
    void cachesWithinTheDay() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source));

        service.getFeed(30);
        service.getFeed(30);

        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a different length rebuilds — a 30-day answer is not a prefix of a 90-day one, "
            + "because a span can start before the window and be reported either way")
    void aDifferentLengthRebuilds() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source));

        service.getFeed(30);
        service.getFeed(90);

        assertThat(source.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("evict() forces the next request to rebuild")
    void evictForcesARebuild() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source));

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

        new AlmanacService(List.of(recorder)).getFeed();

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
        AlmanacService service = new AlmanacService(List.of(recorder));

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
        assertThat(new AlmanacService(List.of()).getFeed(10)).isEmpty();
    }

    @Test
    @DisplayName("an entry whose span runs backwards is rejected at construction")
    void backwardsSpanIsRejected() {
        assertThatThrownBy(() -> event(DAY.plusDays(2), DAY, "broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before startDate");
    }
}
