package com.gregochr.goldenhour.service.ask;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AskUsageRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AskService}: the typed endpoint's guards in the plan's order, with the real rate limiter,
 * the real spend guard and the real usage store (on H2, committing for real so refunds and races are
 * genuine) and a scripted engine over a real snapshot. Boundaries are tested at N-1, N and N+1, and
 * the UK-midnight refund under both BST and GMT.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AskServiceTest {

    private static final Instant MONDAY_NOON = Instant.parse("2026-10-05T12:00:00Z");
    private static final long CAP = 500_000L;

    @Autowired
    private AskUsageRepository usageRepository;

    private final AskProperties properties = new AskProperties();
    private final MutableClock clock = new MutableClock(MONDAY_NOON);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final AskSnapshotBuilder snapshotBuilder = mock(AskSnapshotBuilder.class);
    private final AskEngine engine = mock(AskEngine.class);
    private final AskJobRunService jobRuns = mock(AskJobRunService.class);
    private final AdminAlertService alerts = mock(AdminAlertService.class);
    private final AskReadyServing readyServing = mock(AskReadyServing.class);
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final AskPreFilter preFilter = mock(AskPreFilter.class);
    private final AskIntentMatcher matcher = mock(AskIntentMatcher.class);
    private final AskAnswerCache cache = mock(AskAnswerCache.class);
    private final AskLog askLog = mock(AskLog.class);
    private final AskDenialCounter denials = mock(AskDenialCounter.class);

    private AskSnapshot snapshot;
    private AskUsageStore usageStore;
    private AskService service;
    private UserRole role = UserRole.LITE_USER;
    private long spent;

    @BeforeEach
    void setUp() {
        properties.setRatePerMinute(600);
        snapshot = ReadyFixtures.at(ReadyFixtures.MONDAY_NOON,
                ReadyFixtures.both(ReadyFixtures.oct(5), ReadyFixtures.northumberland()),
                ReadyFixtures.both(ReadyFixtures.oct(6), ReadyFixtures.northumberland()));
        when(snapshotBuilder.current()).thenReturn(Optional.of(snapshot));
        when(users.findByUsername("reader")).thenAnswer(inv ->
                Optional.of(AppUserEntity.builder().id(41L).username("reader").role(role).build()));
        when(driveTimes.hasDriveTimes(41L)).thenReturn(true);
        when(jobRuns.accountingAvailable()).thenReturn(true);
        when(jobRuns.typedSpendTodayMicroDollars()).thenAnswer(inv -> spent);
        when(preFilter.refuse(any())).thenReturn(Optional.empty());
        when(matcher.match(any(), any())).thenReturn(Optional.empty());
        when(cache.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(readyServing.suggestions(any(), any(), anyInt())).thenReturn(List.of());
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> ok());
        properties.setDailySpendCapUsd(0.5);
        usageStore = new AskUsageStore(usageRepository);
        rebuild();
    }

    @AfterEach
    void clean() {
        usageRepository.deleteAll();
    }

    private void rebuild() {
        AskSpendGuard guard = new AskSpendGuard(properties, jobRuns, alerts, clock);
        service = new AskService(properties, new AskRateLimiter(properties, clock), users, regions,
                snapshotBuilder, engine, usageStore, guard, readyServing, driveTimes, preFilter, matcher,
                cache, askLog, denials, clock);
    }

    private static Authentication reader() {
        return new TestingAuthenticationToken("reader", "n/a");
    }

    private static AskRequest request(String question) {
        return new AskRequest(question, null, List.of(), "plan");
    }

    /** One request as the web layer makes it: admitted (counted once) and then answered. */
    private AskResponse submit(AskRequest request) {
        return service.ask(service.admit(reader()), request);
    }

    private AskResponse ask(String question) {
        return submit(request(question));
    }

    private static AskRun ok() {
        AskPick pick = ReadyFixtures.pick(1, 1L, "Bamburgh", "Northumberland", "2026-10-05_sunset", 5,
                DisplayVerdict.WORTH_IT);
        return new AskRun(new AskOutcome(AskOutcome.Status.OK, ReadyFixtures.answer(pick), false, 2),
                List.of(), null);
    }

    private static AskRun cantRun() {
        AskAnswer answer = new AskAnswer(false, "PhotoCast does not know about parking.", List.of(), List.of(),
                "parking");
        return new AskRun(new AskOutcome(AskOutcome.Status.CANT, answer, false, 1), List.of(), null);
    }

    private static AskRun failedRun(String reason) {
        return new AskRun(new AskOutcome(AskOutcome.Status.FAILED, null, false, 1), List.of(), reason);
    }

    private AskUsageStore.Usage usage() {
        return usageStore.read(41L, LocalDate.of(2026, 10, 5));
    }

    private AskErrorCode codeOf(AskRequest request) {
        try {
            submit(request);
        } catch (AskRefusal e) {
            return e.code();
        }
        throw new AssertionError("expected a refusal");
    }

    // -- the happy path ---------------------------------------------------------------------

    @Test
    @DisplayName("an answered question is kind own, charged, with the allowance left, the served picks and the "
            + "briefing's own time")
    void answered() {
        AskResponse response = ask("  Best spot   tonight?  ");

        assertThat(response.answerable()).isTrue();
        assertThat(response.kind()).isEqualTo("own");
        assertThat(response.charged()).isTrue();
        assertThat(response.allowanceLimit()).isEqualTo(3);
        assertThat(response.allowanceLeft()).isEqualTo(2);
        assertThat(response.picks()).singleElement().satisfies(p -> {
            assertThat(p.locationName()).isEqualTo("Bamburgh");
            assertThat(p.windowId()).isEqualTo("2026-10-05_sunset");
        });
        assertThat(response.missing()).isNull();
        assertThat(response.tryThese()).isEmpty();
        assertThat(response.generatedAt()).isEqualTo(snapshot.generatedAt());
        assertThat(response.runLabel()).isEqualTo(snapshot.runLabel());
        assertThat(usage()).isEqualTo(new AskUsageStore.Usage(1, 1));
    }

    @Test
    @DisplayName("the engine gets the sanitised text, the normalised key, the asker's context and no Ready options")
    void engineInputs() {
        ask("Please, could you tell me the Best Spot tonight?");

        ArgumentCaptor<AskQuestion> question = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskUserContext> context = ArgumentCaptor.forClass(AskUserContext.class);
        ArgumentCaptor<AskRunOptions> options = ArgumentCaptor.forClass(AskRunOptions.class);
        verify(engine).run(question.capture(), eq(snapshot), context.capture(), options.capture());
        assertThat(question.getValue().sanitised()).isEqualTo("Please, could you tell me the Best Spot tonight?");
        assertThat(question.getValue().normalised()).isEqualTo("best spot tonight");
        assertThat(question.getValue().view()).isEqualTo("plan");
        assertThat(context.getValue()).isEqualTo(new AskUserContext(41L, UserRole.LITE_USER, true));
        assertThat(options.getValue()).isEqualTo(AskRunOptions.none());
    }

    // -- 1. rate limit ------------------------------------------------------------------------

    @Test
    @DisplayName("the 5th request in a minute is answered and the 6th is RATE_LIMITED — and the snapshot is never "
            + "built for the 6th")
    void rateLimitFiresBeforeTheSnapshot() {
        properties.setRatePerMinute(5);
        properties.setLimitLite(100);
        rebuild();
        for (int i = 0; i < 5; i++) {
            ask("Best spot tonight?");
        }
        verify(snapshotBuilder, times(5)).current();

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.RATE_LIMITED);

        verify(snapshotBuilder, times(5)).current();
        verify(engine, times(5)).run(any(), any(), any(), any());
        assertThat(usage().used()).as("a rate-limited request uses nothing").isEqualTo(5);
    }

    @Test
    @DisplayName("the limit resets after 60 seconds")
    void rateLimitResets() {
        properties.setRatePerMinute(5);
        properties.setLimitLite(100);
        rebuild();
        for (int i = 0; i < 5; i++) {
            ask("Best spot tonight?");
        }
        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.RATE_LIMITED);

        clock.advance(Duration.ofSeconds(60));

        assertThat(ask("Best spot tonight?").kind()).isEqualTo("own");
    }

    @Test
    @DisplayName("the rate limit comes before validation: a sixth invalid request is RATE_LIMITED, not INVALID")
    void rateLimitBeforeValidation() {
        properties.setRatePerMinute(1);
        rebuild();
        assertThat(codeOf(new AskRequest("", null, null, "plan"))).isEqualTo(AskErrorCode.INVALID);

        assertThat(codeOf(new AskRequest("", null, null, "plan"))).isEqualTo(AskErrorCode.RATE_LIMITED);
    }

    @Test
    @DisplayName("admit counts exactly one slot, and answering an admitted user counts none: a request is "
            + "counted once")
    void admitCountsOnceAndAskCountsNone() {
        properties.setRatePerMinute(2);
        properties.setLimitLite(100);
        rebuild();

        AppUserEntity first = service.admit(reader());
        service.ask(first, request("Best spot tonight?"));
        service.ask(first, request("Best spot tonight?"));
        service.ask(first, request("Best spot tonight?"));

        assertThat(service.admit(reader())).as("only one slot was taken so far").isNotNull();
        assertThatThrownBy(() -> service.admit(reader())).isInstanceOfSatisfying(AskRefusal.class,
                e -> assertThat(e.code()).isEqualTo(AskErrorCode.RATE_LIMITED));
    }

    @Test
    @DisplayName("an unknown user is UNAUTHENTICATED and nothing else is touched")
    void unknownUser() {
        AskRequest valid = request("Best spot tonight?");
        Authentication ghost = new TestingAuthenticationToken("ghost", "n/a");
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.admit(ghost)).isInstanceOfSatisfying(AskRefusal.class,
                e -> assertThat(e.code()).isEqualTo(AskErrorCode.UNAUTHENTICATED));
        verifyNoInteractions(snapshotBuilder, engine);
    }

    // -- 2. validation ------------------------------------------------------------------------

    @Test
    @DisplayName("every bad request is 400 INVALID, with a sentence, and builds no snapshot")
    void invalidRequests() {
        List<AskRequest> bad = new ArrayList<>();
        bad.add(null);
        bad.add(new AskRequest(null, null, null, "plan"));
        bad.add(new AskRequest("   ", null, null, "plan"));
        bad.add(new AskRequest("Best spot tonight?", null, null, null));
        bad.add(new AskRequest("Best spot tonight?", null, null, "calendar"));
        bad.add(new AskRequest("Best spot tonight?", null, null, "MAP"));
        bad.add(new AskRequest("Best spot 🌅", null, null, "plan"));
        bad.add(new AskRequest("Best​spot", null, null, "plan"));
        bad.add(new AskRequest("a".repeat(201), null, null, "map"));
        for (AskRequest request : bad) {
            assertThatThrownBy(() -> submit(request)).isInstanceOfSatisfying(AskRefusal.class, e -> {
                assertThat(e.code()).isEqualTo(AskErrorCode.INVALID);
                assertThat(e.body()).containsEntry("code", "INVALID");
                assertThat(e.getMessage()).isNotBlank();
            });
        }
        verifyNoInteractions(snapshotBuilder, engine);
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @ParameterizedTest
    @CsvSource({"map", "plan", "coming-up"})
    @DisplayName("the three views are accepted")
    void viewsAccepted(String view) {
        assertThat(submit(new AskRequest("Best spot tonight?", null, null, view)).kind())
                .isEqualTo("own");
    }

    @Test
    @DisplayName("200 code points are accepted and 201 are INVALID")
    void lengthBoundary() {
        assertThat(submit(new AskRequest("a".repeat(200), null, null, "plan")).kind())
                .isEqualTo("own");
        assertThat(codeOf(new AskRequest("a".repeat(201), null, null, "plan"))).isEqualTo(AskErrorCode.INVALID);
    }

    @Test
    @DisplayName("an unknown or disabled region id, more than 20 ids, or a null id is INVALID")
    void invalidRegions() {
        when(regions.findAllById(Set.of(9L))).thenReturn(List.of());
        when(regions.findAllById(Set.of(8L))).thenReturn(List.of(
                RegionEntity.builder().id(8L).name("Retired").enabled(false).build()));
        List<Long> twentyOne = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            twentyOne.add(i);
        }
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);

        assertThat(codeOf(new AskRequest("Best spot?", null, List.of(9L), "map"))).isEqualTo(AskErrorCode.INVALID);
        assertThat(codeOf(new AskRequest("Best spot?", null, List.of(8L), "map"))).isEqualTo(AskErrorCode.INVALID);
        assertThat(codeOf(new AskRequest("Best spot?", null, twentyOne, "map"))).isEqualTo(AskErrorCode.INVALID);
        assertThat(codeOf(new AskRequest("Best spot?", null, withNull, "map"))).isEqualTo(AskErrorCode.INVALID);
        verifyNoInteractions(snapshotBuilder, engine);
    }

    @Test
    @DisplayName("one region scopes the question and its suggestions to that region; several use the whole set")
    void regionScopes() {
        when(regions.findAllById(Set.of(3L))).thenReturn(List.of(
                RegionEntity.builder().id(3L).name("Northumberland").enabled(true).build()));
        when(regions.findAllById(Set.of(3L, 4L))).thenReturn(List.of(
                RegionEntity.builder().id(3L).name("Northumberland").enabled(true).build(),
                RegionEntity.builder().id(4L).name("Teesdale").enabled(true).build()));
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());

        submit(new AskRequest("Parking?", null, List.of(3L), "map"));
        submit(new AskRequest("Parking?", null, List.of(3L, 4L), "map"));

        verify(readyServing).suggestions(eq(AskScope.of(List.of(3L), Set.of("Northumberland"))), eq(snapshot),
                eq(2));
        verify(readyServing).suggestions(eq(AskScope.ALL), eq(snapshot), eq(2));
    }

    @Test
    @DisplayName("the region ids are read from the database once per typed question, and the question the "
            + "engine, the cache and the matcher are given carries the resolved scope")
    void regionIdsAreReadOnce() {
        when(regions.findAllById(Set.of(3L, 4L))).thenReturn(List.of(
                RegionEntity.builder().id(3L).name("Northumberland").enabled(true).build(),
                RegionEntity.builder().id(4L).name("Teesdale").enabled(true).build()));

        submit(new AskRequest("Best spot tonight?", null, List.of(4L, 3L), "map"));

        verify(regions, times(1)).findAllById(Set.of(3L, 4L));
        AskScope scope = AskScope.of(List.of(4L, 3L), Set.of("Northumberland", "Teesdale"));
        ArgumentCaptor<AskQuestion> matched = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskQuestion> looked = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskQuestion> ran = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskQuestion> stored = ArgumentCaptor.forClass(AskQuestion.class);
        ArgumentCaptor<AskLog.Entry> logged = ArgumentCaptor.forClass(AskLog.Entry.class);
        verify(matcher).match(matched.capture(), any());
        verify(cache).lookup(looked.capture(), any(), any());
        verify(engine).run(ran.capture(), any(), any(), any());
        verify(cache).store(stored.capture(), any(), any(), any());
        verify(askLog).record(logged.capture());
        assertThat(List.of(matched.getValue(), looked.getValue(), ran.getValue(), stored.getValue()))
                .extracting(AskQuestion::scope).containsOnly(scope);
        // Several regions are logged, and routed to the Ready set, as the whole catalogue.
        assertThat(logged.getValue().scopeKey()).isEqualTo("ALL");
    }

    // -- 4. snapshot --------------------------------------------------------------------------

    @Test
    @DisplayName("no briefing built yet is 503 TYPED_UNAVAILABLE and nothing is reserved")
    void noSnapshot() {
        when(snapshotBuilder.current()).thenReturn(Optional.empty());

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        verifyNoInteractions(engine);
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("a windowId in the window set reaches the engine; one outside it, malformed or blank is ignored, "
            + "not 400")
    void windowIdIsIgnoredWhenUnknown() {
        properties.setLimitLite(100);
        rebuild();
        for (String id : new String[] {"2026-10-05_sunset", "2026-10-06_sunrise"}) {
            submit(new AskRequest("Best?", id, null, "map"));
        }
        for (String id : new String[] {"2026-10-05_sunrise", "2026-10-30_sunset", "nonsense", "  ", ""}) {
            submit(new AskRequest("Best?", id, null, "map"));
        }

        ArgumentCaptor<AskQuestion> captured = ArgumentCaptor.forClass(AskQuestion.class);
        verify(engine, times(7)).run(captured.capture(), any(), any(), any());
        List<String> windows = new ArrayList<>();
        captured.getAllValues().forEach(q -> windows.add(q.windowId()));
        assertThat(windows).containsExactly("2026-10-05_sunset", "2026-10-06_sunrise", null, null, null, null, null);
    }

    // -- the typed seams ------------------------------------------------------------------------------

    @Test
    @DisplayName("the steps run in the plan's order: pre-filter, snapshot, Ready match, cache, engine, cache "
            + "store, log")
    void order() {
        ask("Best spot tonight?");

        InOrder order = inOrder(preFilter, snapshotBuilder, matcher, cache, engine, askLog);
        order.verify(preFilter).refuse(any());
        order.verify(snapshotBuilder).current();
        order.verify(matcher).match(any(), any());
        order.verify(cache).lookup(any(), any(), any());
        order.verify(engine).run(any(), any(), any(), any());
        order.verify(cache).store(any(), any(), any(), any());
        order.verify(askLog).record(any());
    }

    @Test
    @DisplayName("a pre-filter refusal is kind cant, free, with no snapshot-dependent spend and no reservation")
    void preFilterRefuses() {
        AskAnswer cant = new AskAnswer(false, "PhotoCast has no parking information.", List.of(), List.of(),
                "parking");
        when(preFilter.refuse(any())).thenReturn(Optional.of(cant));

        AskResponse response = ask("Is the car park busy?");

        assertThat(response.kind()).isEqualTo("cant");
        assertThat(response.answerable()).isFalse();
        assertThat(response.charged()).isFalse();
        assertThat(response.missing()).isEqualTo("parking");
        assertThat(response.picks()).isEmpty();
        assertThat(response.allowanceLeft()).isEqualTo(3);
        verifyNoInteractions(engine, matcher, cache);
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
        ArgumentCaptor<AskLog.Entry> entry = ArgumentCaptor.forClass(AskLog.Entry.class);
        verify(askLog).record(entry.capture());
        assertThat(entry.getValue().outcome()).isEqualTo(AskLog.Outcome.PREFILTER_CANT);
    }

    @Test
    @DisplayName("a Ready match is kind ready, free, carries the Ready question's own time, and skips the cache, "
            + "the cap and the engine")
    void readyMatch() {
        spent = CAP + 1;
        AskReadyResponse.Answer answer = new AskReadyResponse.Answer(true, "ready", "Bamburgh.", List.of(),
                List.of(), null, List.of(new AskReadyResponse.Suggestion("RARE_EVENTS", "Any rare events coming up?")));
        AskReadyResponse.Question matched = new AskReadyResponse.Question("BEST_NEXT", "Best spot tonight?",
                List.of("plan"), java.time.LocalDateTime.of(2026, 10, 5, 4, 0), "05:00", answer);
        when(matcher.match(any(), any())).thenReturn(Optional.of(matched));

        AskResponse response = ask("Best spot tonight?");

        assertThat(response.kind()).isEqualTo("ready");
        assertThat(response.charged()).isFalse();
        assertThat(response.summary()).isEqualTo("Bamburgh.");
        assertThat(response.runLabel()).isEqualTo("05:00");
        assertThat(response.tryThese()).hasSize(1);
        verifyNoInteractions(engine, cache);
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("a cache hit is kind own, not charged, served even when the spend cap is reached, and reserves "
            + "nothing")
    void cacheHit() {
        spent = CAP;
        AskAnswer cached = ok().outcome().answer();
        when(cache.lookup(any(), any(), any())).thenReturn(Optional.of(cached));

        AskResponse response = ask("Best spot tonight?");

        assertThat(response.kind()).isEqualTo("own");
        assertThat(response.charged()).isFalse();
        assertThat(response.allowanceLeft()).isEqualTo(3);
        verifyNoInteractions(engine);
        verify(cache, never()).store(any(), any(), any(), any());
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("the cache is offered an answered outcome only: never a can't, never a failure")
    void cacheIsOfferedAnswersOnly() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());
        ask("Parking?");
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> failedRun("x"));
        assertThat(codeOf(request("Parking again?"))).isEqualTo(AskErrorCode.ENGINE_FAILED);

        verify(cache, never()).store(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a cache or log that throws never costs the reader an answer they paid for")
    void sidecarsCannotFailTheAnswer() {
        doThrow(new IllegalStateException("cache down")).when(cache).store(any(), any(), any(), any());
        doThrow(new IllegalStateException("log down")).when(askLog).record(any());

        assertThat(ask("Best spot tonight?").kind()).isEqualTo("own");
    }

    // -- 7. spend cap and latch -----------------------------------------------------------------

    @Test
    @DisplayName("the cap blocks at exactly the cap and not a micro-dollar before; nothing is reserved")
    void capBoundary() {
        spent = CAP - 1;
        assertThat(ask("Best spot tonight?").kind()).isEqualTo("own");
        usageRepository.deleteAll();

        spent = CAP;
        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);
        spent = CAP + 1;
        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        verify(engine, times(1)).run(any(), any(), any(), any());
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("the cap alerts the admins once per UK day, however many questions it refuses, and again the "
            + "next day")
    void capAlertsOncePerUkDay() {
        spent = CAP;
        for (int i = 0; i < 3; i++) {
            codeOf(request("Best spot tonight?"));
        }
        verify(alerts, times(1)).sendAskSpendCapAlert(LocalDate.of(2026, 10, 5), CAP, CAP);

        clock.advance(Duration.ofHours(10));
        codeOf(request("Best spot tonight?"));
        verify(alerts, times(1)).sendAskSpendCapAlert(any(), anyLong(), anyLong());

        clock.advance(Duration.ofHours(1));
        codeOf(request("Best spot tonight?"));
        verify(alerts).sendAskSpendCapAlert(LocalDate.of(2026, 10, 6), CAP, CAP);
        verify(alerts, times(2)).sendAskSpendCapAlert(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("a spend figure that cannot be read refuses the question: the cap fails closed")
    void capFailsClosed() {
        when(jobRuns.typedSpendTodayMicroDollars()).thenThrow(new IllegalStateException("db down"));

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        verifyNoInteractions(engine);
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("while an unrecorded paid call holds the accounting latch the answer is 503 and no allowance is "
            + "reserved")
    void latchRefusesWithoutReserving() {
        when(jobRuns.accountingAvailable()).thenReturn(false);

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        verifyNoInteractions(engine);
        assertThat(usage()).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("the cap crossed between the first check and the engine refuses the question and gives the "
            + "allowance back")
    void capCrossedBetweenCheckAndReservation() {
        when(jobRuns.typedSpendTodayMicroDollars()).thenReturn(CAP - 1, CAP);

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        verifyNoInteractions(engine);
        assertThat(usage().used()).as("the allowance was refunded").isZero();
    }

    // -- 8. ceilings ------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"LITE_USER,3", "PRO_USER,30", "ADMIN,30"})
    @DisplayName("the allowance is LITE 3, PRO 30, ADMIN 30: the Nth is answered, the N+1th is ALLOWANCE_EXHAUSTED")
    void roleAllowances(UserRole asRole, int limit) {
        role = asRole;
        for (int i = 1; i <= limit; i++) {
            AskResponse response = ask("Best spot tonight?");
            assertThat(response.allowanceLimit()).isEqualTo(limit);
            assertThat(response.allowanceLeft()).isEqualTo(limit - i);
        }

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ALLOWANCE_EXHAUSTED);
        verify(engine, times(limit)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unanswerable question every time hits DAILY_LIMIT at 3 x the allowance with used still 0")
    void dailyLimit() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());

        for (int i = 1; i <= 9; i++) {
            AskResponse response = ask("Parking " + i + "?");
            assertThat(response.kind()).isEqualTo("cant");
            assertThat(response.allowanceLeft()).isEqualTo(3);
        }

        assertThat(codeOf(request("Parking 10?"))).isEqualTo(AskErrorCode.DAILY_LIMIT);
        assertThat(usage()).isEqualTo(new AskUsageStore.Usage(0, 9));
        verify(engine, times(9)).run(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the allowance and the ceiling follow the configured limits and multiplier")
    void configuredLimits() {
        properties.setLimitLite(1);
        properties.setEngineCeilingMultiplier(2);
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());

        ask("Parking?");
        ask("Parking?");

        assertThat(codeOf(request("Parking?"))).isEqualTo(AskErrorCode.DAILY_LIMIT);
    }

    @Test
    @DisplayName("a database that cannot reserve is 503 and the engine never runs")
    void reservationFailsClosed() {
        AskUsageStore broken = mock(AskUsageStore.class);
        when(broken.reserve(anyLong(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("db"));
        usageStore = broken;
        rebuild();

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        verifyNoInteractions(engine);
    }

    // -- 9. the engine's outcome ----------------------------------------------------------------

    @Test
    @DisplayName("answerable:false is kind cant, refunded, never charged, with up to two Ready suggestions")
    void cantIsRefunded() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());
        List<AskReadyResponse.Suggestion> suggestions = List.of(
                new AskReadyResponse.Suggestion("BEST_NEXT", "Best spot tonight?"),
                new AskReadyResponse.Suggestion("RARE_EVENTS", "Any rare events coming up?"));
        when(readyServing.suggestions(any(), any(), anyInt())).thenReturn(suggestions);

        AskResponse response = ask("Is the car park free?");

        assertThat(response.kind()).isEqualTo("cant");
        assertThat(response.answerable()).isFalse();
        assertThat(response.charged()).isFalse();
        assertThat(response.picks()).isEmpty();
        assertThat(response.events()).isEmpty();
        assertThat(response.missing()).isEqualTo("parking");
        assertThat(response.tryThese()).isEqualTo(suggestions);
        assertThat(response.allowanceLeft()).isEqualTo(3);
        assertThat(usage()).as("used refunded, engine_calls kept").isEqualTo(new AskUsageStore.Usage(0, 1));
    }

    @Test
    @DisplayName("a failed run is 502 ENGINE_FAILED and refunded; engine_calls stays")
    void failureIsRefunded() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> failedRun("timed out"));

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ENGINE_FAILED);

        assertThat(usage()).isEqualTo(new AskUsageStore.Usage(0, 1));
        ArgumentCaptor<AskLog.Entry> entry = ArgumentCaptor.forClass(AskLog.Entry.class);
        verify(askLog).record(entry.capture());
        assertThat(entry.getValue().outcome()).isEqualTo(AskLog.Outcome.CLAUDE_FAILED);
    }

    @Test
    @DisplayName("a run that stopped because accounting is unavailable is 503, not 502, and is refunded")
    void accountingUnavailableIs503() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> failedRun(AskRun.ACCOUNTING_UNAVAILABLE));

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.TYPED_UNAVAILABLE);

        assertThat(usage().used()).isZero();
    }

    @Test
    @DisplayName("an engine that throws is treated as a failed run: 502 and refunded")
    void engineThrows() {
        when(engine.run(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("bug"));

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ENGINE_FAILED);

        assertThat(usage()).isEqualTo(new AskUsageStore.Usage(0, 1));
    }

    /** Makes every usage read fail from here on, as a transient database failure would; returns the real store. */
    private AskUsageStore failUsageReads() {
        AskUsageStore real = usageStore;
        usageStore = spy(real);
        doThrow(new IllegalStateException("db down")).when(usageStore).read(anyLong(), any());
        rebuild();
        return real;
    }

    @Test
    @DisplayName("an answer whose usage read fails still arrives, charged, with NO allowanceLeft (null, not 0): "
            + "the client re-reads rather than applying a count of zero")
    void answerSurvivesAFailedUsageRead() {
        AskUsageStore real = failUsageReads();

        AskResponse response = ask("Best spot tonight?");

        assertThat(response.kind()).isEqualTo("own");
        assertThat(response.charged()).isTrue();
        assertThat(response.picks()).hasSize(1);
        assertThat(response.allowanceLeft()).isNull();
        assertThat(response.allowanceLimit()).isEqualTo(3);
        assertThat(real.read(41L, LocalDate.of(2026, 10, 5))).isEqualTo(new AskUsageStore.Usage(1, 1));
    }

    @Test
    @DisplayName("a pre-filter can't whose usage read fails carries a null allowanceLeft and is still free")
    void preFilterCantWithAFailedUsageRead() {
        AskAnswer cant = new AskAnswer(false, "PhotoCast has no parking information.", List.of(), List.of(),
                "parking");
        when(preFilter.refuse(any())).thenReturn(Optional.of(cant));
        AskUsageStore real = failUsageReads();

        AskResponse response = ask("Is the car park busy?");

        assertThat(response.kind()).isEqualTo("cant");
        assertThat(response.charged()).isFalse();
        assertThat(response.allowanceLeft()).isNull();
        assertThat(real.read(41L, LocalDate.of(2026, 10, 5))).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("an engine can't whose usage read fails still refunds the question and carries a null "
            + "allowanceLeft")
    void engineCantWithAFailedUsageRead() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());
        AskUsageStore real = failUsageReads();

        AskResponse response = ask("Parking?");

        assertThat(response.kind()).isEqualTo("cant");
        assertThat(response.allowanceLeft()).isNull();
        assertThat(response.allowanceLimit()).isEqualTo(3);
        assertThat(real.read(41L, LocalDate.of(2026, 10, 5))).isEqualTo(new AskUsageStore.Usage(0, 1));
    }

    /** Runs {@code action} with an appender on {@link AskService}'s logger and returns what it logged. */
    private static List<ILoggingEvent> logged(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AskService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return List.copyOf(appender.list);
    }

    @Test
    @DisplayName("a failed typed run is logged once at INFO with [ASK], the user, the turns and the reason "
            + "the engine gave: the only trace of why it failed")
    void failedRunReasonIsLogged() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv ->
                AskRun.failed("the answer was discarded: pick 1 is not on the BEST BET window "
                        + "2026-10-05_sunset", 3, false, List.of()));

        List<ILoggingEvent> events = logged(() ->
                assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ENGINE_FAILED));

        assertThat(events).filteredOn(e -> e.getLevel() == Level.INFO).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).startsWith("[ASK]").contains("user 41").contains("3 turn(s)")
                    .contains("the answer was discarded: pick 1 is not on the BEST BET window 2026-10-05_sunset");
            assertThat(e.getFormattedMessage()).as("never the question").doesNotContain("Best spot tonight");
        });
    }

    @Test
    @DisplayName("the reason is flattened to one line before it is logged, whatever the engine put in it")
    void failedRunReasonIsOneLine() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv ->
                failedRun("the model call failed: boom\n2026-10-05 [ASK] second line\r end"));

        List<ILoggingEvent> events = logged(() -> codeOf(request("Best spot tonight?")));

        assertThat(events).filteredOn(e -> e.getLevel() == Level.INFO).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).doesNotContain("\n").doesNotContain("\r")
                        .doesNotContain(" ").contains("second line"));
    }

    @Test
    @DisplayName("an engine that throws is logged with its class name as the reason")
    void thrownEngineReasonIsLogged() {
        when(engine.run(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("bug"));

        List<ILoggingEvent> events = logged(() -> codeOf(request("Best spot tonight?")));

        assertThat(events).filteredOn(e -> e.getLevel() == Level.INFO).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).contains("the engine threw: IllegalArgumentException")
                        .contains("0 turn(s)"));
    }

    @Test
    @DisplayName("an answered question, and an honest 'not in the forecast', log no failure line")
    void answeredRunsLogNoFailure() {
        List<ILoggingEvent> ok = logged(() -> ask("Best spot tonight?"));
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());
        List<ILoggingEvent> cant = logged(() -> ask("Is parking busy tonight?"));

        assertThat(ok).filteredOn(e -> e.getLevel() == Level.INFO).isEmpty();
        assertThat(cant).filteredOn(e -> e.getLevel() == Level.INFO).isEmpty();
    }

    @Test
    @DisplayName("an OK outcome with no answer is a failure, not an NPE")
    void okWithoutAnswer() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv ->
                new AskRun(new AskOutcome(AskOutcome.Status.OK, null, false, 1), List.of(), null));

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ENGINE_FAILED);
    }

    @Test
    @DisplayName("a refund never takes the counter below zero: a second refund of the same question is a no-op")
    void refundNeverBelowZero() {
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> {
            // Something else already gave the question back before the service's own refund runs.
            usageStore.refund(41L, LocalDate.of(2026, 10, 5));
            return cantRun();
        });

        AskResponse response = ask("Parking?");

        assertThat(response.allowanceLeft()).isEqualTo(3);
        assertThat(usage()).isEqualTo(new AskUsageStore.Usage(0, 1));
    }

    @Test
    @DisplayName("a refund that itself fails does not fail the response: the answer is still returned")
    void refundFailureDoesNotFailTheResponse() {
        AskUsageRepository failing = mock(AskUsageRepository.class);
        when(failing.existsByUserIdAndUsageDate(anyLong(), any())).thenReturn(true);
        when(failing.reserve(anyLong(), any(), anyInt(), anyInt())).thenReturn(1);
        when(failing.refund(anyLong(), any())).thenThrow(new IllegalStateException("db down"));
        when(failing.findCountsByUserIdAndUsageDate(anyLong(), any())).thenReturn(Optional.empty());
        usageStore = new AskUsageStore(failing);
        rebuild();
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> cantRun());

        AskResponse response = ask("Parking?");

        assertThat(response.kind()).isEqualTo("cant");
        verify(failing).refund(eq(41L), any());
    }

    // -- the refund goes to the reserved date ---------------------------------------------------

    @Test
    @DisplayName("BST: a question reserved at 23:59:30 London that finishes after midnight is refunded on the "
            + "day it was reserved, not on the new day")
    void refundAcrossUkMidnightBst() {
        clock.set(Instant.parse("2026-10-05T22:59:30Z"));
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(60));
            return cantRun();
        });

        ask("Parking?");

        assertThat(usageStore.read(41L, LocalDate.of(2026, 10, 5))).isEqualTo(new AskUsageStore.Usage(0, 1));
        assertThat(usageStore.read(41L, LocalDate.of(2026, 10, 6))).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("BST: at 22:59:30 UTC it is still the 5th in London, and at 23:00 UTC it is the 6th")
    void bstDayBoundary() {
        clock.set(Instant.parse("2026-10-05T22:59:59Z"));
        ask("Best spot tonight?");
        clock.set(Instant.parse("2026-10-05T23:00:00Z"));
        rebuild();
        ask("Best spot tonight?");

        assertThat(usageStore.read(41L, LocalDate.of(2026, 10, 5)).used()).isEqualTo(1);
        assertThat(usageStore.read(41L, LocalDate.of(2026, 10, 6)).used()).isEqualTo(1);
    }

    @Test
    @DisplayName("GMT: a failure that finishes after midnight is refunded on the reserved day")
    void refundAcrossUkMidnightGmt() {
        clock.set(Instant.parse("2027-01-15T23:59:30Z"));
        when(engine.run(any(), any(), any(), any())).thenAnswer(inv -> {
            clock.advance(Duration.ofSeconds(60));
            return failedRun("timed out");
        });

        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ENGINE_FAILED);

        assertThat(usageStore.read(41L, LocalDate.of(2027, 1, 15))).isEqualTo(new AskUsageStore.Usage(0, 1));
        assertThat(usageStore.read(41L, LocalDate.of(2027, 1, 16))).isEqualTo(AskUsageStore.Usage.NONE);
    }

    @Test
    @DisplayName("a new UK day starts a new allowance")
    void newDayNewAllowance() {
        for (int i = 0; i < 3; i++) {
            ask("Best spot tonight?");
        }
        assertThat(codeOf(request("Best spot tonight?"))).isEqualTo(AskErrorCode.ALLOWANCE_EXHAUSTED);

        clock.advance(Duration.ofHours(12));

        assertThat(ask("Best spot tonight?").allowanceLeft()).isEqualTo(2);
    }

    // -- settings ---------------------------------------------------------------------------------

    @Test
    @DisplayName("settings with Ask off are enabled:false and zeros, and touch nothing")
    void settingsOff() {
        AskSettingsResponse response = service.settings(reader());

        assertThat(response).isEqualTo(AskSettingsResponse.off());
        assertThat(response.enabled()).isFalse();
        verifyNoInteractions(users);
    }

    @Test
    @DisplayName("settings with Ask on carry used, limit, left and typedAvailable")
    void settingsOn() {
        properties.setEnabled(true);
        ask("Best spot tonight?");

        assertThat(service.settings(reader())).isEqualTo(new AskSettingsResponse(true, 1, 3, 2, true));
    }

    @Test
    @DisplayName("typedAvailable is false at the cap and while the accounting latch holds, and left never goes "
            + "negative when the limit was lowered")
    void settingsAvailability() {
        properties.setEnabled(true);
        spent = CAP;
        assertThat(service.settings(reader()).typedAvailable()).isFalse();
        spent = CAP - 1;
        assertThat(service.settings(reader()).typedAvailable()).isTrue();
        when(jobRuns.accountingAvailable()).thenReturn(false);
        assertThat(service.settings(reader()).typedAvailable()).isFalse();
        when(jobRuns.accountingAvailable()).thenReturn(true);

        for (int i = 0; i < 3; i++) {
            ask("Best spot tonight?");
        }
        properties.setLimitLite(1);
        AskSettingsResponse lowered = service.settings(reader());
        assertThat(lowered.used()).isEqualTo(3);
        assertThat(lowered.left()).isZero();
    }

    @Test
    @DisplayName("reading the settings never sends the cap alert")
    void settingsNeverAlert() {
        properties.setEnabled(true);
        spent = CAP * 2;

        service.settings(reader());

        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("an unknown user on the settings read is UNAUTHENTICATED")
    void settingsUnknownUser() {
        properties.setEnabled(true);
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settings(new TestingAuthenticationToken("ghost", "n/a")))
                .isInstanceOfSatisfying(AskRefusal.class,
                        e -> assertThat(e.code()).isEqualTo(AskErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("the refusal body is exactly {error, code} and each code fixes its status")
    void refusalBodies() {
        for (AskErrorCode code : AskErrorCode.values()) {
            AskRefusal refusal = new AskRefusal(code);
            assertThat(refusal.body()).containsOnlyKeys("error", "code").containsEntry("code", code.name());
            assertThat(refusal.body().get("error")).isNotBlank();
        }
        assertThat(AskErrorCode.INVALID.status().value()).isEqualTo(400);
        assertThat(AskErrorCode.RATE_LIMITED.status().value()).isEqualTo(429);
        assertThat(AskErrorCode.ALLOWANCE_EXHAUSTED.status().value()).isEqualTo(429);
        assertThat(AskErrorCode.DAILY_LIMIT.status().value()).isEqualTo(429);
        assertThat(AskErrorCode.ENGINE_FAILED.status().value()).isEqualTo(502);
        assertThat(AskErrorCode.TYPED_UNAVAILABLE.status().value()).isEqualTo(503);
    }
}
