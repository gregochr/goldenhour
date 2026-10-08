package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AskUsageRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.both;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.northumberland;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AskService} with the four real B5 seams in place — the real pre-filter, intent matcher
 * (over the real Ready freshness), cache and a recording log — and the real usage store, so what is
 * proved is the order and the money: the pre-filter beats the matcher, a Ready match and a cache hit
 * cost nothing and are served even with the allowance used up, a personal answer never reaches
 * another user, and a denied request writes no log row.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AskTypedSeamsTest {

    private static final Instant FRIDAY = Instant.parse("2026-10-09T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 9);
    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final List<String> WEEKEND = List.of("2026-10-10_sunrise", SAT_SUNSET,
            "2026-10-11_sunrise", "2026-10-11_sunset");
    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    @Autowired
    private AskUsageRepository usageRepository;

    private final AskProperties properties = new AskProperties();
    private final MutableClock clock = new MutableClock(FRIDAY);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final AskSnapshotBuilder snapshotBuilder = mock(AskSnapshotBuilder.class);
    private final AskEngine engine = mock(AskEngine.class);
    private final AskJobRunService jobRuns = mock(AskJobRunService.class);
    private final AskReadyStore readyStore = mock(AskReadyStore.class);
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final List<AskLog.Entry> logged = new CopyOnWriteArrayList<>();
    private final List<String> denied = new CopyOnWriteArrayList<>();

    private AskUsageStore usageStore;
    private AskService service;
    private AskReadyFixturesHolder world;

    /** The Friday-noon world and the services built over it. */
    private record AskReadyFixturesHolder(AskSnapshot snapshot, AskReadyService readyService) {
    }

    @BeforeEach
    void setUp() {
        properties.setRatePerMinute(600);
        properties.setLimitLite(3);
        properties.setLimitPro(30);
        properties.setDailySpendCapUsd(0.5);
        AskSnapshot snapshot = ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                day(oct(9), false, true, null, northumberland()),
                day(oct(10), true, true, AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland",
                        "Bamburgh", 1L), northumberland()),
                both(oct(11), northumberland()));
        AskReadyService readyService = new AskReadyService(properties, snapshotBuilder, engine, regions,
                readyStore, mock(JobRunService.class), mock(JobRunRepository.class),
                mock(HotTopicSimulationService.class), mock(AuroraStateCache.class), clock,
                Duration.ofMinutes(5));
        world = new AskReadyFixturesHolder(snapshot, readyService);
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(readyStore.findScope("ALL")).thenReturn(List.of(new AskReadyStore.Stored("ALL", "BEST_WEEKEND",
                "Best spot this weekend?", WEEKEND, AskFixtures.GENERATED_AT, bamburghAnswer())));
        when(regions.findAllById(Set.of(1L))).thenReturn(List.of(
                RegionEntity.builder().id(1L).name("Northumberland").enabled(true).build()));
        when(users.findByUsername("alice")).thenReturn(Optional.of(AppUserEntity.builder().id(ALICE)
                .username("alice").role(UserRole.PRO_USER).build()));
        when(users.findByUsername("bob")).thenReturn(Optional.of(AppUserEntity.builder().id(BOB)
                .username("bob").role(UserRole.LITE_USER).build()));
        when(driveTimes.hasDriveTimes(ALICE)).thenReturn(true);
        when(driveTimes.hasDriveTimes(BOB)).thenReturn(false);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> {
            AskQuestion question = inv.getArgument(0);
            AskUserContext user = inv.getArgument(2);
            // The tools mark a conversation personal the moment it asks for the asker's drive times.
            boolean personal = question.normalised().contains("home");
            return run(new AskOutcome(AskOutcome.Status.OK, novelAnswer(user), personal, 2));
        });
        usageStore = new AskUsageStore(usageRepository);
        rebuild();
    }

    @AfterEach
    void clean() {
        usageRepository.deleteAll();
    }

    private void rebuild() {
        AskSpendGuard guard = new AskSpendGuard(properties, jobRuns, mock(AdminAlertService.class), clock);
        AskAnswerCache cache = new CaffeineAskAnswerCache(properties,
                mock(HotTopicSimulationService.class), mock(AuroraStateCache.class), clock);
        AskDenialCounter counter = new AskDenialCounter(clock, report -> denied.add(report.userId() + ":"
                + report.counts()));
        service = new AskService(properties, new AskRateLimiter(properties, clock), users, regions,
                snapshotBuilder, engine, usageStore, guard, world.readyService(), driveTimes,
                new PhraseAskPreFilter(), new KeywordAskIntentMatcher(world.readyService()), cache,
                logged::add, counter, clock);
    }

    private static AskRun run(AskOutcome outcome) {
        return new AskRun(outcome, List.of(), null);
    }

    private static AskPick bamburgh() {
        return ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", SAT_SUNSET, 5,
                DisplayVerdict.resolve(5, Verdict.GO));
    }

    private static AskAnswer bamburghAnswer() {
        return ReadyFixtures.answer(bamburgh());
    }

    private static AskAnswer novelAnswer(AskUserContext user) {
        return new AskAnswer(true, "Bamburgh for " + (user.userId() == ALICE ? "Alice" : "you") + ".",
                List.of(bamburgh()), List.of(), null);
    }

    private static Authentication auth(String name) {
        return new TestingAuthenticationToken(name, "n/a");
    }

    private AskResponse ask(String user, String question) {
        return service.ask(service.admit(auth(user)), new AskRequest(question, null, List.of(), "map"));
    }

    private AskErrorCode refusedAs(String user, String question) {
        try {
            ask(user, question);
        } catch (AskRefusal e) {
            return e.code();
        }
        throw new AssertionError("expected a refusal");
    }

    private AskUsageStore.Usage usage(long user) {
        return usageStore.read(user, DAY);
    }

    // -- step 3 before step 5 ---------------------------------------------------------------------

    @Test
    @DisplayName("\"is the car park busy this weekend\" is a can't-answer, never the Ready weekend answer: the "
            + "pre-filter runs before the matcher, free, with no engine call")
    void preFilterBeatsTheMatcher() {
        AskResponse response = ask("bob", "Is the car park busy this weekend?");

        assertThat(response.kind()).isEqualTo("cant");
        assertThat(response.answerable()).isFalse();
        assertThat(response.missing()).isEqualTo(PhraseAskPreFilter.PARKING_AND_CROWDS);
        assertThat(response.picks()).isEmpty();
        assertThat(response.charged()).isFalse();
        assertThat(response.allowanceLeft()).isEqualTo(3);
        assertThat(usage(BOB)).isEqualTo(AskUsageStore.Usage.NONE);
        verify(engine, never()).run(any(), any(), any(), any());
        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AskLog.Outcome.PREFILTER_CANT);
            assertThat(entry.missing()).isEqualTo(PhraseAskPreFilter.PARKING_AND_CROWDS);
        });
    }

    @Test
    @DisplayName("a can't-answer from the pre-filter suggests fresh Ready questions to try instead")
    void preFilterSuggestsReadyQuestions() {
        AskResponse response = ask("bob", "Are there toilets at Bamburgh?");

        assertThat(response.tryThese()).extracting(AskReadyResponse.Suggestion::id).containsExactly("BEST_WEEKEND");
    }

    // -- step 5: a Ready match ---------------------------------------------------------------------

    @Test
    @DisplayName("a typed question that is the Ready weekend question is served that answer: kind ready, "
            + "uncharged, nothing reserved, no engine call, even with today's allowance used up")
    void readyMatchIsFree() {
        AskResponse response = ask("bob", "Where's good this weekend?");

        assertThat(response.kind()).isEqualTo("ready");
        assertThat(response.charged()).isFalse();
        assertThat(response.answerable()).isTrue();
        assertThat(response.picks()).singleElement()
                .satisfies(p -> assertThat(p.locationName()).isEqualTo("Bamburgh"));
        assertThat(response.allowanceLeft()).isEqualTo(3);
        assertThat(usage(BOB)).isEqualTo(AskUsageStore.Usage.NONE);
        verify(engine, never()).run(any(), any(), any(), any());
        assertThat(logged).singleElement().extracting(AskLog.Entry::outcome)
                .isEqualTo(AskLog.Outcome.READY_MATCH);

        for (int i = 0; i < 3; i++) {
            usageStore.reserve(BOB, DAY, 3, 9);
        }
        assertThat(ask("bob", "Best spot this weekend?").kind()).as("allowance used up").isEqualTo("ready");
    }

    @Test
    @DisplayName("a qualified version of a Ready question is not matched: it goes to the engine and is charged")
    void qualifiedQuestionGoesToTheEngine() {
        AskResponse response = ask("bob", "Where's good this weekend within an hour of home?");

        assertThat(response.kind()).isEqualTo("own");
        assertThat(response.charged()).isTrue();
        verify(engine, times(1)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a Ready answer that has gone stale is not served: the question goes to the engine")
    void staleReadyAnswerFallsThrough() {
        when(readyStore.findScope("ALL")).thenReturn(List.of(new AskReadyStore.Stored("ALL", "BEST_WEEKEND",
                "Best spot this weekend?", WEEKEND, AskFixtures.GENERATED_AT,
                ReadyFixtures.answer(ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", SAT_SUNSET, 4,
                        DisplayVerdict.resolve(4, Verdict.GO))))));

        AskResponse response = ask("bob", "Best spot this weekend?");

        assertThat(response.kind()).isEqualTo("own");
        verify(engine, times(1)).run(any(), any(), any(), any());
    }

    // -- step 6: the cache -------------------------------------------------------------------------

    @Test
    @DisplayName("the second reader of a novel question is a cache hit: kind own, uncharged, the allowance "
            + "unchanged, and the engine ran once")
    void cacheHitIsFree() {
        AskResponse first = ask("bob", "Which spot has the best light on the water at dusk?");
        AskResponse second = ask("alice", "which spot has the best LIGHT on the water at dusk");

        assertThat(first.kind()).isEqualTo("own");
        assertThat(first.charged()).isTrue();
        assertThat(first.allowanceLeft()).isEqualTo(2);
        assertThat(second.kind()).isEqualTo("own");
        assertThat(second.charged()).isFalse();
        assertThat(second.allowanceLeft()).isEqualTo(30);
        assertThat(second.summary()).isEqualTo(first.summary());
        assertThat(usage(ALICE)).isEqualTo(AskUsageStore.Usage.NONE);
        verify(engine, times(1)).run(any(), any(), any(), any());
        assertThat(logged).extracting(AskLog.Entry::outcome)
                .containsExactly(AskLog.Outcome.CLAUDE_OK, AskLog.Outcome.CACHE_HIT);
    }

    @Test
    @DisplayName("a cache hit is served with the allowance used up and is not charged")
    void cacheHitNeedsNoAllowance() {
        ask("alice", "Which spot has the best light on the water at dusk?");
        for (int i = 0; i < 3; i++) {
            usageStore.reserve(BOB, DAY, 3, 9);
        }

        AskResponse hit = ask("bob", "Which spot has the best light on the water at dusk?");

        assertThat(hit.charged()).isFalse();
        assertThat(hit.allowanceLeft()).isZero();
        assertThat(refusedAs("bob", "Something new about the sky?")).isEqualTo(AskErrorCode.ALLOWANCE_EXHAUSTED);
    }

    @Test
    @DisplayName("a personal answer (drive times) is never served to another user: two users, the same "
            + "question, one with drive data")
    void personalAnswersStayWithTheirUser() {
        AskResponse alice = ask("alice", "Best spot within an hour of home at dusk?");
        AskResponse bob = ask("bob", "Best spot within an hour of home at dusk?");
        AskResponse aliceAgain = ask("alice", "Best spot within an hour of home at dusk?");

        assertThat(alice.charged()).isTrue();
        assertThat(alice.summary()).isEqualTo("Bamburgh for Alice.");
        assertThat(bob.charged()).as("Bob is not served Alice's answer: he is charged for his own").isTrue();
        assertThat(bob.summary()).isEqualTo("Bamburgh for you.");
        assertThat(aliceAgain.charged()).as("Alice's own answer is a hit for Alice").isFalse();
        assertThat(aliceAgain.summary()).isEqualTo("Bamburgh for Alice.");
        verify(engine, times(2)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a cached answer whose rating has changed is not served: the live data is asked, the engine runs")
    void staleCacheHitIsAMiss() {
        ask("alice", "Which spot has the best light on the water at dusk?");
        AskSnapshot ratingDown = ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null, ReadyFixtures.coastAt("HIGH", 4)),
                both(oct(11), northumberland()));
        when(snapshotBuilder.current()).thenReturn(Optional.of(ratingDown));

        AskResponse response = ask("bob", "Which spot has the best light on the water at dusk?");

        assertThat(response.charged()).isTrue();
        verify(engine, times(2)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a can't-answer from the engine is refunded, logs its question and missing phrase, and is "
            + "never cached")
    void engineCantIsNotCached() {
        doReturn(run(new AskOutcome(AskOutcome.Status.CANT,
                new AskAnswer(false, "Not in the forecast.", List.of(), List.of(), "ferry times"), false, 1)))
                .when(engine).run(any(), any(), any(), any());

        AskResponse first = ask("bob", "When is the next ferry?");
        AskResponse second = ask("bob", "When is the next ferry?");

        assertThat(first.kind()).isEqualTo("cant");
        assertThat(first.charged()).isFalse();
        assertThat(usage(BOB).used()).isZero();
        verify(engine, times(2)).run(any(), any(), any(), any());
        assertThat(logged).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.outcome()).isEqualTo(AskLog.Outcome.CLAUDE_CANT);
            assertThat(entry.missing()).isEqualTo("ferry times");
            assertThat(entry.normalisedQuestion()).isEqualTo("when is next ferry");
        });
        assertThat(second.missing()).isEqualTo("ferry times");
    }

    // -- denied requests --------------------------------------------------------------------------

    @Test
    @DisplayName("a denied request writes no log row: rate limited, allowance exhausted and an invalid "
            + "question are counted in memory instead")
    void deniedRequestsWriteNoRow() {
        properties.setRatePerMinute(3);
        rebuild();
        for (int i = 0; i < 3; i++) {
            usageStore.reserve(BOB, DAY, 3, 9);
        }

        assertThat(refusedAs("bob", "Something new about the sky?")).isEqualTo(AskErrorCode.ALLOWANCE_EXHAUSTED);
        assertThat(refusedAs("bob", "")).isEqualTo(AskErrorCode.INVALID);
        assertThat(refusedAs("bob", "Another new sky question?")).isEqualTo(AskErrorCode.ALLOWANCE_EXHAUSTED);
        assertThat(refusedAs("bob", "And another?")).isEqualTo(AskErrorCode.RATE_LIMITED);

        assertThat(logged).isEmpty();
        // The counter holds them for the hourly INFO line; nothing reports until the hour is over.
        assertThat(denied).isEmpty();
        clock.advance(Duration.ofHours(1));
        assertThat(refusedAs("bob", "")).isEqualTo(AskErrorCode.INVALID);
        assertThat(denied).singleElement().asString().startsWith(BOB + ":")
                .contains("ALLOWANCE_EXHAUSTED=2").contains("INVALID=1").contains("RATE_LIMITED=1");
    }

    @Test
    @DisplayName("an engine failure is logged as CLAUDE_FAILED (an answered request) and is not a denial")
    void engineFailureIsLoggedNotDenied() {
        doReturn(run(new AskOutcome(AskOutcome.Status.FAILED, null, false, 1)))
                .when(engine).run(any(), any(), any(), any());

        assertThat(refusedAs("bob", "A question the model fails?")).isEqualTo(AskErrorCode.ENGINE_FAILED);

        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AskLog.Outcome.CLAUDE_FAILED);
            assertThat(entry.userId()).isEqualTo(BOB);
        });
        assertThat(denied).isEmpty();
    }
}
