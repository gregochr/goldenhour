package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpAssembler;
import com.gregochr.goldenhour.service.comingup.ComingUpConditionsBuilder;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
import com.gregochr.goldenhour.service.comingup.TideRunPeakHistory;
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
import static org.mockito.Mockito.mock;

/** Unit tests for {@link AlmanacService}. */
class AlmanacServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    /**
     * A real {@link ComingUpAssembler} over mocked collaborators, shared by every test in this
     * file. None of the fixtures below use a real tide-run type, so the assembler's DB-backed
     * tide-magnitude path is never exercised here — {@link ComingUpAssemblerTest} covers that.
     */
    private static ComingUpAssembler assembler() {
        return new ComingUpAssembler(
                mock(LocationRepository.class),
                mock(TideRunBuilder.class),
                mock(TideRunPeakHistory.class),
                mock(TideService.class),
                new ComingUpScoringProperties());
    }

    /**
     * A mock rather than a real {@link ComingUpConditionsBuilder}: none of the tests in this file
     * assert on {@code conditions}, and Mockito's default answer for a {@code List}-returning
     * method is an empty list — matching this class's own pre-P4 expectation everywhere except the
     * dedicated {@code ComingUpConditionsBuilderTest}, which exercises the real thing.
     */
    private static ComingUpConditionsBuilder conditionsBuilder() {
        return mock(ComingUpConditionsBuilder.class);
    }

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

        List<AlmanacEvent> feed = new AlmanacService(List.of(late, early), CLOCK, assembler(), conditionsBuilder())
                .build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type).containsExactly("early", "late");
    }

    @Test
    @DisplayName("two entries starting the same day order the shorter span first, then by type, "
            + "so the feed is stable across rebuilds")
    void ordersDeterministicallyOnTies() {
        AlmanacSource longSpan = (f, t) -> List.of(event(DAY, DAY.plusDays(5), "season"));
        AlmanacSource shortSpan = (f, t) -> List.of(event(DAY, DAY, "meteor"));
        AlmanacSource alsoShort = (f, t) -> List.of(event(DAY, DAY, "aurora"));

        List<AlmanacEvent> feed = new AlmanacService(List.of(longSpan, shortSpan, alsoShort), CLOCK,
                assembler(), conditionsBuilder())
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
                new AlmanacService(List.of(broken, healthy), CLOCK,
                        assembler(), conditionsBuilder()).build(DAY, DAY.plusDays(20));

        assertThat(feed).extracting(AlmanacEvent::type).containsExactly("solstice");
    }

    @Test
    @DisplayName("every source failing yields an empty feed rather than an exception")
    void allSourcesFailingIsStillAFeed() {
        AlmanacSource broken = (f, t) -> {
            throw new IllegalStateException("nope");
        };

        assertThat(new AlmanacService(List.of(broken, broken), CLOCK,
                assembler(), conditionsBuilder()).build(DAY, DAY)).isEmpty();
    }

    @Test
    @DisplayName("a second request for the same day and length reuses the built feed")
    void cachesWithinTheDay() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());

        service.getFeed(30);
        service.getFeed(30);

        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a different length rebuilds — a 30-day answer is not a prefix of a 90-day one, "
            + "because a span can start before the window and be reported either way")
    void aDifferentLengthRebuilds() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());

        service.getFeed(30);
        service.getFeed(90);

        assertThat(source.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a rewound request neither reads the day cache nor writes it — the next live request "
            + "still gets the live feed, and a rewound one never gets the cached live feed")
    void rewoundRequestsBypassTheCache() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());
        try {
            service.getFeed(30);                                        // live: builds and caches (1)
            com.gregochr.goldenhour.util.Rewind.set(CLOCK.instant().minusSeconds(3600));
            service.getFeed(30);                                        // rewound: rebuilds (2), caches nothing
            service.getFeed(30);                                        // rewound again: rebuilds (3)
        } finally {
            com.gregochr.goldenhour.util.Rewind.clear();
        }
        service.getFeed(30);                                            // live: the cache from (1) still stands

        assertThat(source.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("evict() forces the next request to rebuild")
    void evictForcesARebuild() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());

        service.getFeed(30);
        service.evict();
        service.getFeed(30);

        assertThat(source.calls.get()).isEqualTo(2);
    }

    // ── refresh and the startup warm ────────────────────────────────────────

    @Test
    @DisplayName("refresh() fills an empty cache, so the first reader of the day pays for no build")
    void refreshWarmsAnEmptyCache() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());

        assertThat(service.refresh()).isTrue();
        service.getFeed();

        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("refresh() replaces a cache that is already warm with a rebuild from current data")
    void refreshReplacesAWarmCache() {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        List<AlmanacEvent> served = new java.util.ArrayList<>(List.of(event(beyond, beyond, "meteor")));
        AlmanacSource source = (f, t) -> List.copyOf(served);
        AlmanacService service = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK, assembler(),
                conditionsBuilder());
        ComingUpResponse before = service.getFeed();

        // The pipeline has written new data since the day's first request.
        served.add(event(beyond.plusDays(1), beyond.plusDays(1), "eclipse"));
        service.refresh();
        ComingUpResponse after = service.getFeed();

        assertThat(after).isNotSameAs(before);
        assertThat(after.entries()).hasSizeGreaterThan(before.entries().size());
        // Served from the refreshed cache, not rebuilt a third time.
        assertThat(service.getFeed()).isSameAs(after);
    }

    @Test
    @DisplayName("a reader who arrives while refresh() is building is served the previous feed, "
            + "never an empty slot — build first, then replace")
    void aReaderDuringRefreshSeesThePreviousFeed() {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<AlmanacService> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ComingUpResponse> seenMidBuild =
                new java.util.concurrent.atomic.AtomicReference<>();
        AlmanacSource source = (f, t) -> {
            // The second call is the refresh's build. A getFeed() from inside it stands in for a
            // request that arrives while it runs: with evict-then-build it would find nothing and
            // build recursively; with build-then-replace it is served what was cached before.
            if (calls.incrementAndGet() == 2) {
                seenMidBuild.set(holder.get().getFeed());
            }
            return List.of(event(DAY, DAY, "x"));
        };
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());
        holder.set(service);
        ComingUpResponse before = service.getFeed();

        service.refresh();

        assertThat(seenMidBuild.get()).isSameAs(before);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a reader's miss-build that finishes after a refresh() does not overwrite the "
            + "refreshed feed — the slot is only filled if it is still the one the reader found empty")
    void aMissBuildThatOutlivesARefreshDoesNotOverwriteIt() {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<AlmanacService> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        AlmanacSource source = (f, t) -> {
            // Call 1 is the reader's build on a cold cache; a refresh() from inside it stands in for
            // the pipeline tail landing while the reader's build is still running. The refresh's
            // own build (call 2) sees one more event, so the two responses can be told apart.
            if (calls.incrementAndGet() == 1) {
                holder.get().refresh();
                return List.of(event(beyond, beyond, "meteor"));
            }
            return List.of(event(beyond, beyond, "meteor"), event(beyond.plusDays(1), beyond.plusDays(1), "eclipse"));
        };
        AlmanacService service = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK, assembler(),
                conditionsBuilder());
        holder.set(service);

        ComingUpResponse readersOwn = service.getFeed();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(readersOwn.entries()).hasSize(1);
        // The cache keeps the refresh's answer, built from the newer data, not the reader's older one.
        assertThat(service.getFeed().entries()).hasSize(2);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("two overlapping refreshes run one at a time, so the one that started last — on "
            + "the newer data — is the one that publishes last")
    void overlappingRefreshesAreSerialised() throws Exception {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.CountDownLatch firstStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AlmanacSource source = (f, t) -> {
            // Call 1 is the first refresh's build, captured BEFORE the data changed; it is held
            // until the second refresh has been asked for. Call 2 is the second refresh's build,
            // which sees one more event. Unserialised, the second would finish first and the
            // first would then publish its older answer over it.
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(event(beyond, beyond, "meteor"));
            }
            return List.of(event(beyond, beyond, "meteor"), event(beyond.plusDays(1), beyond.plusDays(1), "eclipse"));
        };
        AlmanacService service = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK, assembler(),
                conditionsBuilder());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> first = pool.submit(service::refresh);
            assertThat(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<Boolean> second = pool.submit(service::refresh);
            // Serialised, the second cannot finish while the first is held: this wait times out
            // deterministically. Unserialised, the second completes within milliseconds and this
            // assertion is what fails — before the first's older answer gets to overwrite it.
            assertThatThrownBy(() -> second.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(calls.get()).isEqualTo(2);
        assertThat(service.getFeed().entries()).hasSize(2);
    }

    @Test
    @DisplayName("a refresh during which a source fails keeps the complete feed already standing for "
            + "today, rather than publishing a rebuild with that source's events missing")
    void aRefreshWithAFailedSourceKeepsTheCompleteFeed() {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        AtomicInteger tideCalls = new AtomicInteger();
        AlmanacSource meteors = (f, t) -> List.of(event(beyond, beyond, "meteor"));
        AlmanacSource tides = (f, t) -> {
            // Healthy for the day's first build, down for the refresh.
            if (tideCalls.incrementAndGet() > 1) {
                throw new IllegalStateException("db down");
            }
            return List.of(event(beyond.plusDays(1), beyond.plusDays(1), "spring-tide"));
        };
        AlmanacService service = new AlmanacService(List.of(meteors, tides), ELIGIBILITY_CLOCK, assembler(),
                conditionsBuilder());
        ComingUpResponse complete = service.getFeed();
        assertThat(complete.entries()).hasSize(2);

        assertThat(service.refresh()).isFalse();

        // Served as before: the tide run is still there, not silently gone until midnight.
        assertThat(service.getFeed()).isSameAs(complete);
        assertThat(tideCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("on a cold cache a refresh with a failed source still publishes the partial feed — the "
            + "same answer a reader's own miss-build would have cached")
    void aColdRefreshWithAFailedSourcePublishesWhatItHas() {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        AlmanacSource meteors = (f, t) -> List.of(event(beyond, beyond, "meteor"));
        AlmanacSource broken = (f, t) -> {
            throw new IllegalStateException("db down");
        };
        CountingSource counter = new CountingSource(List.of());
        AlmanacService service = new AlmanacService(List.of(meteors, broken, counter), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder());

        assertThat(service.refresh()).isTrue();

        assertThat(service.getFeed().entries()).extracting(ComingUpEntry::type).containsExactly("meteor");
        // Served from the cache the refresh filled, not rebuilt.
        assertThat(counter.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a refresh whose build fails leaves the previous feed in place and propagates")
    void aFailedRefreshKeepsThePreviousFeed() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        ComingUpConditionsBuilder conditions = conditionsBuilder();
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditions);
        ComingUpResponse before = service.getFeed();
        org.mockito.Mockito.when(conditions.build(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("conditions broke"));

        assertThatThrownBy(service::refresh).isInstanceOf(IllegalStateException.class);

        assertThat(service.getFeed()).isSameAs(before);
    }

    @Test
    @DisplayName("refresh() refuses to build while an admin rewind is set, and caches nothing")
    void refreshIsRefusedUnderARewind() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());
        try {
            com.gregochr.goldenhour.util.Rewind.set(CLOCK.instant().minusSeconds(3600));
            assertThat(service.refresh()).isFalse();
        } finally {
            com.gregochr.goldenhour.util.Rewind.clear();
        }

        assertThat(source.calls.get()).isZero();
        service.getFeed();
        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the startup warm is handed to the executor, not run on the calling thread, and "
            + "fills the cache when it runs")
    void startupWarmRunsOnTheExecutor() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder(),
                queued::add);

        service.warmOnStartup();

        assertThat(queued).hasSize(1);
        assertThat(source.calls.get()).isZero();
        queued.getFirst().run();
        service.getFeed();
        assertThat(source.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a startup warm that fails, or an executor that refuses it, is logged and ignored")
    void startupWarmNeverThrows() {
        ComingUpConditionsBuilder conditions = conditionsBuilder();
        org.mockito.Mockito.when(conditions.build(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("conditions broke"));
        AlmanacService failing = new AlmanacService(List.of(), CLOCK, assembler(), conditions, Runnable::run);
        AlmanacService refused = new AlmanacService(List.of(), CLOCK, assembler(), conditionsBuilder(),
                task -> {
                    throw new java.util.concurrent.RejectedExecutionException("shut down");
                });

        org.assertj.core.api.Assertions.assertThatCode(failing::warmOnStartup).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(refused::warmOnStartup).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the default horizon is 90 days, matching what the tide fetch window is sized for")
    void defaultHorizonIsNinetyDays() {
        AtomicInteger span = new AtomicInteger();
        AlmanacSource recorder = (f, t) -> {
            span.set((int) java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1);
            return List.of();
        };

        new AlmanacService(List.of(recorder), CLOCK, assembler(), conditionsBuilder()).getFeed();

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
        AlmanacService service = new AlmanacService(List.of(recorder), CLOCK, assembler(), conditionsBuilder());

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
        assertThat(new AlmanacService(List.of(), CLOCK,
                assembler(), conditionsBuilder()).getFeed(10).entries()).isEmpty();
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

        new AlmanacService(List.of(recorder), lateBstEvening, assembler(), conditionsBuilder()).getFeed(30);

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

    // ── Eligibility (plan D1) — endDate > PlanHorizon.lastPlanDate(today) ──────

    /** GMT in March, so {@code ForecastHorizon.today(ELIGIBILITY_CLOCK)} is this date exactly. */
    private static final LocalDate ELIGIBILITY_TODAY = LocalDate.of(2027, 3, 5);
    private static final Clock ELIGIBILITY_CLOCK =
            Clock.fixed(ELIGIBILITY_TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    /** {@code today + 3} — Plan's last day under {@link #ELIGIBILITY_CLOCK}. */
    private static final LocalDate LAST_PLAN_DATE = ELIGIBILITY_TODAY.plusDays(3);

    @Test
    @DisplayName("an entry ending on Plan's last day is excluded — it is strip material, not "
            + "chronology material")
    void entryEndingOnLastPlanDate_isExcluded() {
        AlmanacSource source = (f, t) -> List.of(event(LAST_PLAN_DATE, LAST_PLAN_DATE, "inside"));

        var entries = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(90).entries();

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("an entry ending the day after Plan's last day is included")
    void entryEndingJustBeyondLastPlanDate_isIncluded() {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        AlmanacSource source = (f, t) -> List.of(event(beyond, beyond, "beyond"));

        var entries = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(90).entries();

        assertThat(entries).extracting(ComingUpEntry::type).containsExactly("beyond");
    }

    @Test
    @DisplayName("a run straddling Plan's boundary is eligible, because its dates say so")
    void straddlingRun_isEligible() {
        AlmanacSource source = (f, t) -> List.of(
                event(LAST_PLAN_DATE.minusDays(1), LAST_PLAN_DATE.plusDays(1), "straddler"));

        var entries = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(90).entries();

        assertThat(entries).extracting(ComingUpEntry::type).containsExactly("straddler");
    }

    // ── enteredWindow (plan D3) — startDate − (DEFAULT_DAYS − 1), fixed at 90 ──

    @Test
    @DisplayName("an entry starting on builtFor + (DEFAULT_DAYS - 1) entered the window on builtFor "
            + "itself — the far edge of the default 90-day feed")
    void enteredWindow_atTheFarEdge_equalsBuiltFor() {
        LocalDate farEdge = ELIGIBILITY_TODAY.plusDays(AlmanacService.DEFAULT_DAYS - 1L);
        AlmanacSource source = (f, t) -> List.of(event(farEdge, farEdge, "far"));

        var entries = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(90).entries();

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().enteredWindow()).isEqualTo(ELIGIBILITY_TODAY);
    }

    @Test
    @DisplayName("enteredWindow is measured against the fixed default horizon, never the caller's "
            + "clamped days — a ?days=30 caller cannot redefine another user's arrival badge")
    void enteredWindow_ignoresTheRequestedLength() {
        LocalDate farEdge = ELIGIBILITY_TODAY.plusDays(AlmanacService.DEFAULT_DAYS - 1L);
        AlmanacSource source = (f, t) -> List.of(event(farEdge, farEdge, "far"));

        // Requested at a 30-day horizon — a wrong implementation keying enteredWindow off the
        // request would compute farEdge.minusDays(29), not the fixed DEFAULT_DAYS basis.
        var entries = new AlmanacService(List.of(source), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(30).entries();

        assertThat(entries.getFirst().enteredWindow()).isEqualTo(ELIGIBILITY_TODAY);
    }

    @Test
    @DisplayName("the response's builtFor is the UK civil today, matching the range it was built for")
    void responseCarriesBuiltFor() {
        ComingUpResponse response = new AlmanacService(List.of(), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(30);

        assertThat(response.builtFor()).isEqualTo(ELIGIBILITY_TODAY);
        assertThat(response.conditions()).isEmpty();
    }

    // ── P2: bands and counts (plan §5) ──────────────────────────────────────

    @Test
    @DisplayName("bands are populated from the scoring properties, not left null (plan P2)")
    void bandsComeFromScoringProperties() {
        ComingUpResponse response = new AlmanacService(List.of(), ELIGIBILITY_CLOCK,
                assembler(), conditionsBuilder()).getFeed(30);

        assertThat(response.bands()).isNotNull();
        assertThat(response.bands().list()).isEqualTo(5.0);
        assertThat(response.bands().announce()).isEqualTo(7.5);
        // 10.0, not the pre-census 9.5 — see ComingUpScoringProperties.Bands' own Javadoc (P5's
        // census).
        assertThat(response.bands().interrupt()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("counts reflect the eligible entries actually served, not a hardcoded figure")
    void countsReflectEligibleEntries() {
        LocalDate beyond = LAST_PLAN_DATE.plusDays(1);
        AlmanacSource source = (f, t) -> List.of(
                event(beyond, beyond, "meteor"), event(beyond.plusDays(1), beyond.plusDays(1), "eclipse"));

        ComingUpResponse response =
                new AlmanacService(List.of(source), ELIGIBILITY_CLOCK, assembler(), conditionsBuilder()).getFeed(90);

        assertThat(response.counts().fixed()).isEqualTo(2);
        assertThat(response.counts().forecast()).isZero();
        assertThat(response.counts().byFamily()).containsEntry("night-sky", 1)
                .containsEntry("eclipse", 1);
    }
}
