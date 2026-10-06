package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.both;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.northumberland;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The typed-answer cache: its key (scope, UK date, build, question, window, user), personal answers
 * that never cross users (two users, one with drive data), the all-or-nothing freshness re-check on
 * every hit, the TTL and size bounds at their boundaries, and the things that are never stored.
 */
class CaffeineAskAnswerCacheTest {

    private static final Instant FRIDAY = Instant.parse("2026-10-09T12:00:00Z");
    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final DisplayVerdict V5 = DisplayVerdict.resolve(5, Verdict.GO);
    private static final AskUserContext ALICE = new AskUserContext(1L, UserRole.PRO_USER, true);
    private static final AskUserContext BOB = new AskUserContext(2L, UserRole.LITE_USER, false);
    private static final AskUserContext ANONYMOUS = AskUserContext.userLess();

    private final AskProperties properties = new AskProperties();
    private final RegionRepository regions = mock(RegionRepository.class);
    private final HotTopicSimulationService hotTopicSimulation = mock(HotTopicSimulationService.class);
    private final AuroraStateCache auroraStateCache = mock(AuroraStateCache.class);
    private final MutableClock clock = new MutableClock(FRIDAY);
    private CaffeineAskAnswerCache cache;

    @BeforeEach
    void setUp() {
        when(regions.findAllById(Set.of(1L))).thenReturn(List.of(region(1L, "Northumberland")));
        when(regions.findAllById(Set.of(2L))).thenReturn(List.of(region(2L, "Teesdale")));
        when(regions.findAllById(Set.of(1L, 2L)))
                .thenReturn(List.of(region(1L, "Northumberland"), region(2L, "Teesdale")));
        cache = newCache();
    }

    private CaffeineAskAnswerCache newCache() {
        return new CaffeineAskAnswerCache(properties, regions, hotTopicSimulation, auroraStateCache, clock);
    }

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true).build();
    }

    private static AskQuestion question(String normalised, String windowId, Long... regionIds) {
        return new AskQuestion(normalised, normalised, windowId, List.of(regionIds), "map");
    }

    private static AskQuestion question(String normalised) {
        return question(normalised, null);
    }

    private static AskPick bamburgh() {
        return ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", SAT_SUNSET, 5, V5);
    }

    private static AskOutcome ok(boolean personal) {
        return new AskOutcome(AskOutcome.Status.OK, ReadyFixtures.answer(bamburgh()), personal, 2);
    }

    /** Friday noon: Bamburgh is a 5★ pick at Saturday's sunset. */
    private static AskSnapshot live() {
        return live(northumberland());
    }

    private static AskSnapshot live(BriefingRegion saturday) {
        return ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON, ReadyFixtures.both(oct(9), northumberland()),
                ReadyFixtures.day(oct(10), true, true, null, saturday), both(oct(11), northumberland()));
    }

    private static AskSnapshot liveBuiltAt(LocalDateTime builtAt) {
        AskSnapshot base = live();
        return new AskSnapshot(builtAt, base.runLabel(), base.today(), base.windows(), base.hotTopics(),
                base.comingUp());
    }

    // -- hit, miss, and what a hit is --------------------------------------------------------------

    @Test
    @DisplayName("a stored shared answer is a hit for the same question, for any user, re-decorated from live data")
    void sharedHit() {
        cache.store(question("best spot saturday sunset"), live(), ALICE, ok(false));

        for (AskUserContext reader : List.of(ALICE, BOB, ANONYMOUS)) {
            Optional<AskAnswer> hit = cache.lookup(question("best spot saturday sunset"), live(), reader);

            assertThat(hit).isPresent();
            assertThat(hit.get().picks()).singleElement().satisfies(p -> {
                assertThat(p.locationName()).isEqualTo("Bamburgh");
                assertThat(p.windowId()).isEqualTo(SAT_SUNSET);
            });
        }
    }

    @Test
    @DisplayName("a hit is the live data's: a renamed spot shows its live name, the stored prose is kept")
    void hitIsReDecorated() {
        cache.store(question("q"), live(), ALICE, ok(false));
        BriefingRegion renamed = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh Castle", 5, "HIGH", true));

        Optional<AskAnswer> hit = cache.lookup(question("q"), live(renamed), BOB);

        assertThat(hit).isPresent();
        assertThat(hit.get().picks().getFirst().locationName()).isEqualTo("Bamburgh Castle");
        assertThat(hit.get().picks().getFirst().why()).isEqualTo("Clear sky.");
        assertThat(hit.get().summary()).isEqualTo("A good one.");
    }

    @Test
    @DisplayName("nothing stored means a miss")
    void emptyIsAMiss() {
        assertThat(cache.lookup(question("anything"), live(), ALICE)).isEmpty();
    }

    // -- freshness ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a hit that fails the live freshness test is a miss and the entry is evicted: a changed "
            + "rating, a changed verdict, a vanished spot, a passed window")
    void staleHitIsAMissAndEvicted() {
        BriefingRegion ratingDown = AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true));
        BriefingRegion verdictDown = AskFixtures.region("Northumberland", true,
                ReadyFixtures.withVerdict(AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                        DisplayVerdict.resolve(3, Verdict.GO)));
        BriefingRegion spotGone = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(2L, "Cheviot Edge", 4));
        for (BriefingRegion changed : List.of(ratingDown, verdictDown, spotGone)) {
            cache.store(question("q"), live(), ALICE, ok(false));
            assertThat(cache.size()).isEqualTo(1);

            assertThat(cache.lookup(question("q"), live(changed), BOB)).isEmpty();
            assertThat(cache.size()).as("evicted").isZero();
        }

        cache.store(question("q"), live(), ALICE, ok(false));
        AskSnapshot nextWeek = ReadyFixtures.at(LocalDateTime.of(2026, 10, 12, 12, 0),
                both(oct(13), northumberland()));
        assertThat(cache.lookup(question("q"), nextWeek, BOB)).isEmpty();
    }

    @Test
    @DisplayName("an event answer is a hit only while its topic is live, and picks the live safety note up")
    void eventFreshness() {
        AskEvent aurora = new AskEvent("AURORA", "Aurora tonight", oct(12), "Kp 6 is forecast.", null);
        AskOutcome outcome = new AskOutcome(AskOutcome.Status.OK,
                new AskAnswer(true, "Aurora is coming.", List.of(), List.of(aurora), null), false, 2);
        cache.store(question("aurora"), live(), ALICE, outcome);
        AskSnapshot base = live();
        AskSnapshot withTopic = new AskSnapshot(base.generatedAt(), base.runLabel(), base.today(),
                base.windows(), List.of(new AskSnapshot.Topic("AURORA", "Aurora (live label)", "Kp 6", oct(12),
                        List.of(), "Live warning")), List.of());

        Optional<AskAnswer> hit = cache.lookup(question("aurora"), withTopic, BOB);

        assertThat(hit).isPresent();
        assertThat(hit.get().events().getFirst().label()).isEqualTo("Aurora (live label)");
        assertThat(hit.get().events().getFirst().safetyNote()).isEqualTo("Live warning");
        assertThat(cache.lookup(question("aurora"), base, BOB)).as("topic gone").isEmpty();
        assertThat(cache.size()).isZero();
    }

    // -- personal answers never cross users -------------------------------------------------------

    @Test
    @DisplayName("a personal answer (one that used the asker's drive times) is served to that user alone: "
            + "another user, an anonymous reader and a user with no drive data all miss")
    void personalAnswersNeverCrossUsers() {
        cache.store(question("within an hour of home"), live(), ALICE, ok(true));

        assertThat(cache.lookup(question("within an hour of home"), live(), ALICE)).isPresent();
        assertThat(cache.lookup(question("within an hour of home"), live(), BOB)).isEmpty();
        assertThat(cache.lookup(question("within an hour of home"), live(), ANONYMOUS)).isEmpty();
        assertThat(cache.lookup(question("within an hour of home"), live(),
                new AskUserContext(3L, UserRole.PRO_USER, true))).isEmpty();
    }

    @Test
    @DisplayName("a user's personal answer and another user's shared answer to the same question coexist, "
            + "and neither is served for the other's key")
    void personalAndSharedCoexist() {
        AskAnswer hers = new AskAnswer(true, "Within an hour of Alice's home.",
                List.of(bamburgh()), List.of(), null);
        AskAnswer shared = new AskAnswer(true, "A shared answer.", List.of(bamburgh()), List.of(), null);
        cache.store(question("q"), live(), ALICE, new AskOutcome(AskOutcome.Status.OK, hers, true, 2));
        cache.store(question("q"), live(), BOB, new AskOutcome(AskOutcome.Status.OK, shared, false, 2));

        assertThat(cache.lookup(question("q"), live(), BOB)).get()
                .extracting(AskAnswer::summary).isEqualTo("A shared answer.");
        assertThat(cache.lookup(question("q"), live(), ANONYMOUS)).get()
                .extracting(AskAnswer::summary).isEqualTo("A shared answer.");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a personal answer with nobody to keep it for is never stored, so it can never be shared")
    void personalWithoutAUserIsDropped() {
        cache.store(question("q"), live(), ANONYMOUS, ok(true));
        cache.store(question("q"), live(), null, ok(true));

        assertThat(cache.size()).isZero();
    }

    // -- the key -----------------------------------------------------------------------------------

    @Test
    @DisplayName("the key separates region sets, ignores their order and duplicates, and treats none as ALL")
    void keySeparatesScopes() {
        cache.store(question("q", null, 1L), live(), ALICE, ok(false));

        assertThat(cache.lookup(question("q", null, 1L), live(), BOB)).isPresent();
        assertThat(cache.lookup(question("q", null, 1L, 1L), live(), BOB)).isPresent();
        assertThat(cache.lookup(question("q", null, 2L), live(), BOB)).isEmpty();
        assertThat(cache.lookup(question("q", null, 1L, 2L), live(), BOB)).isEmpty();
        assertThat(cache.lookup(question("q"), live(), BOB)).as("ALL").isEmpty();

        cache.store(question("q", null, 2L, 1L), live(), ALICE, ok(false));
        assertThat(cache.lookup(question("q", null, 1L, 2L), live(), BOB)).isPresent();
        assertThat(CaffeineAskAnswerCache.scopeOf(List.of(2L, 1L, 2L))).isEqualTo("1,2");
        assertThat(CaffeineAskAnswerCache.scopeOf(List.of())).isEqualTo("ALL");
        assertThat(CaffeineAskAnswerCache.scopeOf(null)).isEqualTo("ALL");
    }

    @Test
    @DisplayName("the key separates the normalised question, the context window and the briefing build")
    void keySeparatesQuestionWindowAndBuild() {
        cache.store(question("q", SAT_SUNSET), live(), ALICE, ok(false));

        assertThat(cache.lookup(question("q", SAT_SUNSET), live(), BOB)).isPresent();
        assertThat(cache.lookup(question("q", null), live(), BOB)).as("no window").isEmpty();
        assertThat(cache.lookup(question("q", "2026-10-10_sunrise"), live(), BOB)).isEmpty();
        assertThat(cache.lookup(question("other", SAT_SUNSET), live(), BOB)).isEmpty();
        assertThat(cache.lookup(question("q", SAT_SUNSET), liveBuiltAt(AskFixtures.GENERATED_AT.plusHours(12)),
                BOB)).as("a newer build is a new key").isEmpty();
    }

    @Test
    @DisplayName("the key carries the UK civil date: at 00:00 BST (23:00 UTC) the date has moved on")
    void ukMidnightUnderBst() {
        clock.set(Instant.parse("2026-10-09T22:59:59Z"));
        cache.store(question("q"), live(), ALICE, ok(false));

        assertThat(cache.lookup(question("q"), live(), BOB)).isPresent();
        clock.set(Instant.parse("2026-10-09T23:00:00Z"));
        assertThat(cache.lookup(question("q"), live(), BOB)).as("00:00 on the 10th in London").isEmpty();
        cache.store(question("q"), live(), ALICE, ok(false));
        assertThat(cache.lookup(question("q"), live(), BOB)).isPresent();
    }

    @Test
    @DisplayName("in winter (GMT) the UK date changes at 00:00 UTC, not an hour earlier")
    void ukMidnightUnderGmt() {
        clock.set(Instant.parse("2026-12-09T23:30:00Z"));
        cache.store(question("q"), live(), ALICE, ok(false));

        assertThat(cache.lookup(question("q"), live(), BOB)).isPresent();
        clock.set(Instant.parse("2026-12-10T00:00:00Z"));
        assertThat(cache.lookup(question("q"), live(), BOB)).isEmpty();
    }

    // -- bounds ------------------------------------------------------------------------------------

    @Test
    @DisplayName("an entry lives ttl-minutes from its write: a hit at 29:59, a miss at 30:00")
    void ttlBoundary() {
        cache.store(question("q"), live(), ALICE, ok(false));

        clock.advance(Duration.ofMinutes(29).plusSeconds(59));
        assertThat(cache.lookup(question("q"), live(), BOB)).as("29:59").isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.lookup(question("q"), live(), BOB)).as("30:00").isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("the ttl follows the property")
    void ttlFollowsTheProperty() {
        properties.getCache().setTtlMinutes(5);
        cache = newCache();
        cache.store(question("q"), live(), ALICE, ok(false));

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(cache.lookup(question("q"), live(), BOB)).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.lookup(question("q"), live(), BOB)).isEmpty();
    }

    @Test
    @DisplayName("the size is bounded by max-entries: N entries fit, the N+1th makes room by evicting one")
    void sizeBoundary() {
        properties.getCache().setMaxEntries(3);
        cache = newCache();

        for (int i = 1; i <= 3; i++) {
            cache.store(question("q" + i), live(), ALICE, ok(false));
        }
        assertThat(cache.size()).as("N").isEqualTo(3);
        cache.store(question("q4"), live(), ALICE, ok(false));
        assertThat(cache.size()).as("N+1").isEqualTo(3);
        for (int i = 5; i < 50; i++) {
            cache.store(question("q" + i), live(), ALICE, ok(false));
            assertThat(cache.size()).isLessThanOrEqualTo(3);
        }
    }

    // -- what is never stored ----------------------------------------------------------------------

    @Test
    @DisplayName("nothing is stored while a hot-topic simulation or an aurora simulation is active")
    void nothingDuringASimulation() {
        when(hotTopicSimulation.isEnabled()).thenReturn(true);
        cache.store(question("q"), live(), ALICE, ok(false));
        assertThat(cache.size()).isZero();

        when(hotTopicSimulation.isEnabled()).thenReturn(false);
        when(auroraStateCache.getSimulatedData())
                .thenReturn(new AuroraStateCache.SimulatedNoaaData(7.0, 45.0, -12.0, "G3"));
        cache.store(question("q"), live(), ALICE, ok(false));
        assertThat(cache.size()).isZero();

        when(auroraStateCache.getSimulatedData()).thenReturn(null);
        cache.store(question("q"), live(), ALICE, ok(false));
        assertThat(cache.size()).as("the simulation is over").isEqualTo(1);
    }

    @Test
    @DisplayName("only an OK, answerable outcome with a pick or an event to re-check is stored")
    void onlyWhatCanBeRechecked() {
        AskAnswer noPicks = new AskAnswer(true, "Nothing is worth it today.", List.of(), List.of(), null);
        cache.store(question("a"), live(), ALICE, new AskOutcome(AskOutcome.Status.OK, noPicks, false, 1));
        cache.store(question("b"), live(), ALICE, new AskOutcome(AskOutcome.Status.CANT,
                new AskAnswer(false, "No.", List.of(), List.of(), "car park information"), false, 1));
        cache.store(question("c"), live(), ALICE, new AskOutcome(AskOutcome.Status.FAILED, null, false, 1));
        cache.store(question("d"), live(), ALICE, new AskOutcome(AskOutcome.Status.OK, null, false, 1));
        cache.store(question("e"), live(), ALICE, new AskOutcome(AskOutcome.Status.OK,
                new AskAnswer(false, "x", List.of(bamburgh()), List.of(), null), false, 1));
        cache.store(question("f"), live(), ALICE, null);

        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("an answer for a scope that no longer resolves is not stored")
    void unresolvableScopeIsNotStored() {
        cache.store(question("q", null, 99L), live(), ALICE, ok(false));

        assertThat(cache.size()).isZero();
    }
}
