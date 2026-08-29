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
    @DisplayName("evict() forces the next request to rebuild")
    void evictForcesARebuild() {
        CountingSource source = new CountingSource(List.of(event(DAY, DAY, "x")));
        AlmanacService service = new AlmanacService(List.of(source), CLOCK, assembler(), conditionsBuilder());

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
