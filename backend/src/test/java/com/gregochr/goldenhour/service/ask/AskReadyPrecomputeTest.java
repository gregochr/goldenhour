package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AskReadyPrecompute}: what a precompute runs and stores, every way it refuses or stops, the
 * one-question-fails-the-rest-carry-on rule, the deadline and the per-day ceiling at their
 * boundaries. What a serve returns is {@link AskReadyServingTest}. The engine is a fake that plays a well-behaved
 * model over the real snapshot; the snapshot, the predicates and the validator are the real ones.
 */
class AskReadyPrecomputeTest {

    private static final long JOB_RUN_ID = 900L;
    private static final Instant FRIDAY = Instant.parse("2026-10-09T12:00:00Z");
    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 9, 5, 2, 11);

    /**
     * The questions the Friday forecast does not offer: BEST_SOON in every scope (a weekend is on) and
     * COASTAL_HIGH for Teesdale (no coast), plus NIGHT_SKY in all three scopes (the fixture holds no nights).
     */
    private static final int NOT_OFFERED = 4 + 3;

    private final AskProperties properties = new AskProperties();
    private final AskSnapshotBuilder snapshotBuilder = mock(AskSnapshotBuilder.class);
    private final RegionRepository regions = mock(RegionRepository.class);
    private final AskReadyStore store = mock(AskReadyStore.class);
    private final JobRunService jobRunService = mock(JobRunService.class);
    private final JobRunRepository jobRunRepository = mock(JobRunRepository.class);
    private final HotTopicSimulationService hotTopicSimulation = mock(HotTopicSimulationService.class);
    private final AuroraStateCache auroraStateCache = mock(AuroraStateCache.class);
    private final MutableClock clock = new MutableClock(FRIDAY);
    private final JobRunEntity job = JobRunEntity.builder().id(JOB_RUN_ID).runType(RunType.ASK_READY).build();

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private Function<Call, AskRun> model;
    private AskSnapshot snapshot;
    private AskReadyPrecompute service;

    /** One engine call, as the fake engine saw it. */
    private record Call(AskQuestion question, AskUserContext user, AskRunOptions options) {
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        snapshot = ReadyFixtures.fridaySnapshot();
        when(snapshotBuilder.build()).thenReturn(Optional.of(snapshot));
        when(regions.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(region(1L, "Northumberland"),
                region(2L, "Teesdale")));
        when(jobRunService.startRun(any(RunType.class), anyBoolean(), any(EvaluationModel.class)))
                .thenReturn(job);
        model = call -> goodModel(call, snapshot);
        service = newService(Duration.ofMinutes(5));
    }

    private AskReadyPrecompute newService(Duration deadline) {
        AskEngine engine = (question, snap, user, options) -> {
            Call call = new Call(question, user, options);
            calls.add(call);
            return model.apply(call);
        };
        return new AskReadyPrecompute(properties, snapshotBuilder, engine, regions, store, jobRunService,
                jobRunRepository, hotTopicSimulation, auroraStateCache, clock, deadline);
    }

    private static RegionEntity region(long id, String name) {
        return RegionEntity.builder().id(id).name(name).enabled(true).build();
    }

    // -- a well-behaved model ---------------------------------------------------------------

    /** Answers any Ready question the way a model that read the tools and obeyed the rules would. */
    private static AskRun goodModel(Call call, AskSnapshot snap) {
        AskQuestion q = call.question();
        AskScope scope = q.scope();
        ReadyQuestion asked = null;
        ReadyQuestion.Offer offer = null;
        for (ReadyQuestion candidate : ReadyQuestion.values()) {
            Optional<ReadyQuestion.Offer> o = candidate.offer(snap, scope);
            if (o.isPresent() && o.get().text().equals(q.sanitised())) {
                asked = candidate;
                offer = o.get();
            }
        }
        if (asked == null) {
            throw new AssertionError("not a question of the catalogue: " + q.sanitised());
        }
        if (asked.nightBound()) {
            AskSnapshot.NightCandidate night = snap.nightCandidates(snap.nights().tonightAstro().orElseThrow(),
                    scope, false).getFirst();
            AskPick pick = new AskPick(1, night.spot().locationId(), night.spot().name(), night.spot().regionName(),
                    night.night().date(), AskWindowKind.ASTRO, night.night().id(), "Clear and dark.",
                    night.spot().astroStars(), null);
            return ok(new AskAnswer(true, "Clear tonight.", List.of(pick), List.of(), null));
        }
        if (!asked.picks()) {
            String type = asked == ReadyQuestion.SNOW_TOPS ? "SNOW_TOPS" : "AURORA";
            AskSnapshot.Topic topic = snap.hotTopics().stream().filter(t -> t.type().equals(type)).findFirst()
                    .orElseThrow();
            AskEvent event = new AskEvent(type, topic.label(), topic.date(), "It is coming.", null);
            return ok(new AskAnswer(true, "Something is coming.", List.of(), List.of(event), null));
        }
        AskSnapshot.Candidate chosen = chosenCandidate(snap, scope, asked, offer, call.options());
        AskPick pick = new AskPick(1, chosen.slot().locationId(), chosen.slot().name(), chosen.region().name(),
                chosen.window().date(), AskWindowKind.of(chosen.window().targetType()), chosen.window().id(), "Why.",
                chosen.slot().rating(), chosen.slot().verdict().name());
        return ok(new AskAnswer(true, "Go there.", List.of(pick), List.of(), null));
    }

    private static AskSnapshot.Candidate chosenCandidate(AskSnapshot snap, AskScope scope, ReadyQuestion asked,
            ReadyQuestion.Offer offer, AskRunOptions options) {
        Optional<AskSnapshot.Window> lead = options.anchor() == null ? Optional.empty()
                : AskAnswerValidator.anchoredWindow(snap, options.anchor(), scope);
        for (String windowId : lead.map(w -> List.of(w.id())).orElse(offer.windowIds())) {
            for (AskSnapshot.Candidate c : snap.candidates(snap.window(windowId).orElseThrow(), scope)) {
                if (asked != ReadyQuestion.COASTAL_HIGH || "HIGH".equals(c.slot().tideState())) {
                    return c;
                }
            }
        }
        throw new AssertionError("nothing to pick for " + asked);
    }

    private static AskRun ok(AskAnswer answer) {
        return new AskRun(new AskOutcome(AskOutcome.Status.OK, answer, false, 2), List.of(), null);
    }

    private static AskRun failed(String reason) {
        return new AskRun(new AskOutcome(AskOutcome.Status.FAILED, null, false, 1), List.of(), reason);
    }

    private List<String> storedKeys() {
        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ReadyQuestion> question = ArgumentCaptor.forClass(ReadyQuestion.class);
        verify(store, org.mockito.Mockito.atLeast(0)).upsert(scope.capture(), question.capture(), any(), any(),
                any(), any(), any());
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < scope.getAllValues().size(); i++) {
            keys.add(scope.getAllValues().get(i) + "/" + question.getAllValues().get(i));
        }
        return keys;
    }

    // -- what a precompute does -------------------------------------------------------------

    @Test
    @DisplayName("a precompute runs every available question of every scope, user-less, one at a time, and "
            + "stores each answer with the question's text and windows")
    void runsEveryAvailableQuestion() {
        AskReadyPrecompute.Result result = service.precompute(42L);

        // ALL: 6 (BEST_SOON off with a weekend on); Northumberland: 6; Teesdale: 5 (no coastal high water).
        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(17, NOT_OFFERED, 0, null));
        assertThat(storedKeys()).containsExactly(
                "ALL/BEST_WEEKEND", "ALL/BEST_NEXT", "ALL/COASTAL_HIGH", "ALL/AM_OR_PM", "ALL/RARE_EVENTS",
                "ALL/SNOW_TOPS",
                "1/BEST_WEEKEND", "1/BEST_NEXT", "1/COASTAL_HIGH", "1/AM_OR_PM", "1/RARE_EVENTS", "1/SNOW_TOPS",
                "2/BEST_WEEKEND", "2/BEST_NEXT", "2/AM_OR_PM", "2/RARE_EVENTS", "2/SNOW_TOPS");
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.user().hasUser()).isFalse();
            assertThat(c.options().readyJobRunId()).isEqualTo(JOB_RUN_ID);
            assertThat(c.question().view()).isEqualTo("plan");
        });
        ArgumentCaptor<ReadyQuestion.Offer> offer = ArgumentCaptor.forClass(ReadyQuestion.Offer.class);
        verify(store, org.mockito.Mockito.times(17)).upsert(any(), any(), offer.capture(), any(), any(), any(),
                any());
        assertThat(offer.getAllValues()).extracting(ReadyQuestion.Offer::text).contains("Best spot this weekend?",
                "Best spot this evening?", "Best coastal spot at high tide?", "Sunrise or sunset tomorrow?",
                "Any rare events coming up?", "Is there snow on the tops?");
    }

    @Test
    @DisplayName("only a BEST_* question carries an anchor, over its own windows; BEST_NEXT also names its "
            + "window as the reader's context; the others carry neither")
    void anchorsAndContext() {
        service.precompute(42L);

        Call weekend = callFor("Best spot this weekend?", List.of());
        assertThat(weekend.options().anchor().windowIds()).containsExactlyInAnyOrder("2026-10-10_sunrise",
                "2026-10-10_sunset", "2026-10-11_sunrise", "2026-10-11_sunset");
        assertThat(weekend.question().windowId()).isNull();
        Call next = callFor("Best spot this evening?", List.of());
        assertThat(next.options().anchor().windowIds()).containsExactly("2026-10-09_sunset");
        assertThat(next.question().windowId()).isEqualTo("2026-10-09_sunset");
        assertThat(callFor("Best coastal spot at high tide?", List.of()).options().anchor()).isNull();
        assertThat(callFor("Sunrise or sunset tomorrow?", List.of()).options().anchor()).isNull();
        assertThat(callFor("Any rare events coming up?", List.of()).options().anchor()).isNull();
        assertThat(callFor("Is there snow on the tops?", List.of()).options().anchor()).isNull();
    }

    private Call callFor(String text, List<Long> regionIds) {
        return calls.stream().filter(c -> c.question().sanitised().equals(text)
                && c.question().regionIds().equals(regionIds)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the scheduled precompute starts one ASK_READY job run as scheduled and completes it with "
            + "its counts, and each answer remembers the pipeline run and the briefing build")
    void jobRunAndProvenance() {
        service.precompute(42L);

        verify(jobRunService).startRun(RunType.ASK_READY, false, properties.getModel());
        verify(jobRunService).completeRun(job, 17, 0);
        assertThat(job.getLocationsProcessed()).isEqualTo(17);
        verify(store, org.mockito.Mockito.times(17)).upsert(any(), any(), any(), any(), eq(snapshot.generatedAt()),
                eq(42L), isNull());
    }

    @Test
    @DisplayName("an on-demand precompute is a manual run with no pipeline run behind it")
    void onDemandIsManual() {
        AskReadyPrecompute.Result result = service.precomputeOnDemand();

        assertThat(result.written()).isEqualTo(17);
        verify(jobRunService).startRun(RunType.ASK_READY, true, properties.getModel());
        verify(store, org.mockito.Mockito.times(17)).upsert(any(), any(), any(), any(), any(), isNull(),
                isNull());
    }

    @Test
    @DisplayName("running twice writes the same scope and question keys twice: the store upserts, so the "
            + "second run replaces the first")
    void idempotentKeys() {
        service.precompute(1L);
        List<String> first = storedKeys();
        service.precompute(2L);

        assertThat(storedKeys()).hasSize(first.size() * 2);
        assertThat(storedKeys().subList(first.size(), first.size() * 2)).isEqualTo(first);
    }

    @Test
    @DisplayName("two regions whose names differ only by case are two scopes, each answered, and neither "
            + "breaks the run")
    void regionsDifferingByCase() {
        when(regions.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(region(1L, "Northumberland"),
                region(2L, "northumberland")));

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result.failed()).isZero();
        assertThat(storedKeys()).contains("1/BEST_WEEKEND", "2/BEST_WEEKEND");
    }

    // -- refusals ---------------------------------------------------------------------------

    private void assertRefused(AskReadyPrecompute.Result result, String reasonPart) {
        assertThat(result.wasRefused()).isTrue();
        assertThat(result.refusal()).contains(reasonPart);
        assertThat(result.written() + result.skipped() + result.failed()).isZero();
        assertThat(calls).isEmpty();
        verifyNoInteractions(jobRunService);
        verify(store, never()).upsert(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("refused, with no run started and no engine call, when Ask is switched off")
    void flagOff() {
        properties.setEnabled(false);

        assertRefused(service.precompute(1L), "switched off");
        assertRefused(service.precomputeOnDemand(), "switched off");
    }

    @Test
    @DisplayName("refused while either simulation is active: the hot topic one or the aurora one")
    void simulationActive() {
        when(hotTopicSimulation.isEnabled()).thenReturn(true);
        assertRefused(service.precompute(1L), "simulation");

        when(hotTopicSimulation.isEnabled()).thenReturn(false);
        when(auroraStateCache.getSimulatedData()).thenReturn(mock(AuroraStateCache.SimulatedNoaaData.class));
        assertRefused(service.precomputeOnDemand(), "simulation");
    }

    @Test
    @DisplayName("refused when there is no briefing, when it is last-known-good, or when it has no build time")
    void noFreshBriefing() {
        when(snapshotBuilder.build()).thenReturn(Optional.empty());
        assertRefused(service.precompute(1L), "no briefing");

        when(snapshotBuilder.build()).thenReturn(Optional.of(new AskSnapshot(snapshot.generatedAt(),
                snapshot.runLabel(), snapshot.today(), snapshot.windows(), snapshot.hotTopics(),
                snapshot.comingUp(), true)));
        assertRefused(service.precompute(1L), "last-known-good");

        when(snapshotBuilder.build()).thenReturn(Optional.of(new AskSnapshot(null, null, snapshot.today(),
                snapshot.windows(), snapshot.hotTopics(), snapshot.comingUp(), false)));
        assertRefused(service.precompute(1L), "no build time");
    }

    // -- the per-day ceiling ----------------------------------------------------------------

    private void todaysScheduledRuns(long count) {
        when(jobRunRepository.countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(eq(RunType.ASK_READY),
                eq(false), any())).thenReturn(count);
    }

    @Test
    @DisplayName("the ceiling at its boundary: 5 runs already today allows the sixth, 6 and 7 refuse")
    void ceilingBoundary() {
        todaysScheduledRuns(5);
        assertThat(service.precompute(1L).wasRefused()).isFalse();

        calls.clear();
        todaysScheduledRuns(6);
        AskReadyPrecompute.Result atCeiling = service.precompute(2L);
        assertThat(atCeiling.wasRefused()).isTrue();
        assertThat(atCeiling.refusal()).contains("ceiling of 6");
        assertThat(calls).isEmpty();

        todaysScheduledRuns(7);
        assertThat(service.precompute(3L).wasRefused()).isTrue();
    }

    @Test
    @DisplayName("the ceiling follows the configured value, and a refused cycle starts no job run, so a "
            + "refusal does not use up a cycle")
    void ceilingIsConfigurable() {
        properties.getReady().setMaxCyclesPerDay(2);
        todaysScheduledRuns(2);

        assertThat(service.precompute(1L).refusal()).contains("ceiling of 2");
        verifyNoInteractions(jobRunService);
    }

    @Test
    @DisplayName("an on-demand precompute is neither counted against the ceiling nor stopped by it")
    void onDemandBypassesTheCeiling() {
        todaysScheduledRuns(99);

        assertThat(service.precomputeOnDemand().wasRefused()).isFalse();
        verify(jobRunRepository, never()).countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(any(),
                anyBoolean(), any());
    }

    @Test
    @DisplayName("the count is since UK midnight: one second before it is still the old day's, at it is the "
            + "new day's (BST midnight is 23:00 UTC)")
    void ceilingCountsFromUkMidnight() {
        clock.set(Instant.parse("2026-10-05T22:59:59Z"));
        service.precompute(1L);
        verify(jobRunRepository).countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(RunType.ASK_READY,
                false, LocalDateTime.of(2026, 10, 4, 23, 0));

        clock.set(Instant.parse("2026-10-05T23:00:00Z"));
        service.precompute(2L);
        verify(jobRunRepository).countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(RunType.ASK_READY,
                false, LocalDateTime.of(2026, 10, 5, 23, 0));
    }

    // -- one question failing does not stop the rest ----------------------------------------

    @Test
    @DisplayName("a thrown question, a FAILED run and a 'can't' are each counted and logged and the rest "
            + "still run and are stored")
    void oneFailureDoesNotStopTheRest() {
        model = call -> {
            String text = call.question().sanitised();
            if (text.equals("Best spot this weekend?") && call.question().regionIds().isEmpty()) {
                throw new IllegalStateException("boom");
            }
            if (text.equals("Best spot this evening?") && call.question().regionIds().isEmpty()) {
                return failed("the model refused");
            }
            if (text.equals("Best coastal spot at high tide?") && call.question().regionIds().isEmpty()) {
                return new AskRun(new AskOutcome(AskOutcome.Status.CANT, new AskAnswer(false, "No.", List.of(),
                        List.of(), "tide data"), false, 1), List.of(), null);
            }
            return goodModel(call, snapshot);
        };

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(14, NOT_OFFERED, 3, null));
        assertThat(calls).hasSize(17);
        verify(jobRunService).completeRun(job, 14, 3);
    }

    @Test
    @DisplayName("a store failure for one answer is counted and the next question is still stored")
    void storeFailureDoesNotStopTheRest() {
        doThrow(new IllegalStateException("db down")).when(store).upsert(eq("ALL"),
                eq(ReadyQuestion.BEST_WEEKEND), any(), any(), any(), any(), any());

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(16, NOT_OFFERED, 1, null));
    }

    @Test
    @DisplayName("an answer is not stored when it does not suit its question: no pick for a pick question, "
            + "a pick outside the question's windows, a personal answer; one with nothing to show is skipped")
    void unsuitableAnswersAreNotStored() {
        model = call -> {
            String text = call.question().sanitised();
            AskRun good = goodModel(call, snapshot);
            if (!call.question().regionIds().isEmpty()) {
                return good;
            }
            AskAnswer answer = good.outcome().answer();
            return switch (text) {
                case "Best spot this weekend?" -> ok(new AskAnswer(true, "None.", List.of(), List.of(), null));
                case "Best spot this evening?" -> ok(new AskAnswer(true, "Saturday.", List.of(onWindow(answer.picks()
                        .getFirst(), "2026-10-10_sunset")), List.of(), null));
                case "Best coastal spot at high tide?" -> new AskRun(new AskOutcome(AskOutcome.Status.OK, answer,
                        true, 2), List.of(), null);
                case "Any rare events coming up?" -> ok(new AskAnswer(true, "Nothing.", List.of(), List.of(), null));
                default -> good;
            };
        };

        AskReadyPrecompute.Result result = service.precompute(1L);

        // ALL: weekend (no pick), tonight (outside its windows), coastal (personal) fail; rare is skipped.
        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.written()).isEqualTo(13);
        assertThat(result.skipped()).isEqualTo(NOT_OFFERED + 1);
        assertThat(storedKeys()).doesNotContain("ALL/BEST_WEEKEND", "ALL/BEST_NEXT", "ALL/COASTAL_HIGH",
                "ALL/RARE_EVENTS");
    }

    /** The answer stored for a scope and question, from the captured upserts. */
    private AskAnswer storedAnswer(String scope, ReadyQuestion question) {
        ArgumentCaptor<String> scopes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ReadyQuestion> questions = ArgumentCaptor.forClass(ReadyQuestion.class);
        ArgumentCaptor<AskAnswer> answers = ArgumentCaptor.forClass(AskAnswer.class);
        verify(store, org.mockito.Mockito.atLeast(0)).upsert(scopes.capture(), questions.capture(), any(),
                answers.capture(), any(), any(), any());
        for (int i = 0; i < scopes.getAllValues().size(); i++) {
            if (scopes.getAllValues().get(i).equals(scope) && questions.getAllValues().get(i) == question) {
                return answers.getAllValues().get(i);
            }
        }
        return null;
    }

    @Test
    @DisplayName("at store time a SNOW_TOPS answer loses an aurora beside its snow event and keeps the snow; "
            + "a pick question loses an event it should not carry; RARE_EVENTS keeps an aurora")
    void irrelevantEventsAreStrippedAtStoreTime() {
        AskEvent aurora = new AskEvent("AURORA", "Aurora tonight", oct(12), "Kp 6.", null);
        AskEvent snow = new AskEvent("SNOW_TOPS", "Snow on the Cheviot", oct(12), "Fresh snow.", null);
        model = call -> {
            AskRun good = goodModel(call, snapshot);
            AskAnswer answer = good.outcome().answer();
            String text = call.question().sanitised();
            if (text.equals("Is there snow on the tops?")) {
                return ok(new AskAnswer(true, "Snow and an aurora.", List.of(), List.of(aurora, snow), null));
            }
            if (text.equals("Best spot this evening?")) {
                return ok(new AskAnswer(true, answer.summary(), answer.picks(), List.of(aurora), null));
            }
            return good;
        };

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result.failed()).isZero();
        assertThat(storedAnswer("ALL", ReadyQuestion.SNOW_TOPS).events()).extracting(AskEvent::type)
                .containsExactly("SNOW_TOPS");
        assertThat(storedAnswer("ALL", ReadyQuestion.BEST_NEXT).events()).isEmpty();
        assertThat(storedAnswer("ALL", ReadyQuestion.BEST_NEXT).picks()).hasSize(1);
        assertThat(storedAnswer("ALL", ReadyQuestion.RARE_EVENTS).events()).extracting(AskEvent::type)
                .containsExactly("AURORA");
    }

    @Test
    @DisplayName("a SNOW_TOPS answer left with no snow event once the irrelevant ones are removed is not "
            + "stored: a 'no snow' summary cannot be verified; it counts as skipped, not failed")
    void nothingRelevantLeftIsNotStored() {
        AskEvent aurora = new AskEvent("AURORA", "Aurora tonight", oct(12), "Kp 6.", null);
        AskRun onlyAnAurora = ok(new AskAnswer(true, "No snow, but an aurora.", List.of(), List.of(aurora),
                null));
        model = call -> call.question().sanitised().equals("Is there snow on the tops?")
                ? onlyAnAurora : goodModel(call, snapshot);

        AskReadyPrecompute.Result result = service.precompute(1L);

        // The three SNOW_TOPS questions (ALL and both regions) are skipped; nothing failed.
        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(14, NOT_OFFERED + 3, 0, null));
        assertThat(storedKeys()).doesNotContain("ALL/SNOW_TOPS", "1/SNOW_TOPS", "2/SNOW_TOPS");
    }

    private static AskRun cant(String missing) {
        return new AskRun(new AskOutcome(AskOutcome.Status.CANT, new AskAnswer(false, "No.", List.of(),
                List.of(), missing), false, 1), List.of(), null);
    }

    @Test
    @DisplayName("a 'can't' on the events question SNOW_TOPS is a skip, not a failure, and nothing is stored")
    void cantOnAnEventsQuestionIsSkipped() {
        model = call -> call.question().sanitised().equals("Is there snow on the tops?")
                ? cant("snow data") : goodModel(call, snapshot);

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(14, NOT_OFFERED + 3, 0, null));
        assertThat(storedKeys()).doesNotContain("ALL/SNOW_TOPS", "1/SNOW_TOPS", "2/SNOW_TOPS");
        verify(jobRunService).completeRun(job, 14, 0);
    }

    @Test
    @DisplayName("a 'can't' on the pick question BEST_NEXT still counts as failed")
    void cantOnAPickQuestionStaysFailed() {
        model = call -> call.question().sanitised().equals("Best spot this evening?")
                && call.question().regionIds().isEmpty() ? cant("nothing") : goodModel(call, snapshot);

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(storedKeys()).doesNotContain("ALL/BEST_NEXT");
        verify(jobRunService).completeRun(job, result.written(), 1);
    }

    @Test
    @DisplayName("a FAILED status on SNOW_TOPS is still a failure")
    void failedOnAnEventsQuestionStaysFailed() {
        model = call -> call.question().sanitised().equals("Is there snow on the tops?")
                ? failed("the model refused") : goodModel(call, snapshot);

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result.failed()).isEqualTo(3);
        verify(jobRunService).completeRun(job, result.written(), 3);
    }

    @Test
    @DisplayName("an answer that would lose an event carrying a safety warning is not stored at all")
    void droppedWarningIsNotStored() {
        AskEvent eclipse = new AskEvent("ECLIPSE", "Partial solar eclipse", oct(12), "Visible.",
                "Solar filter");
        AskEvent snow = new AskEvent("SNOW_TOPS", "Snow", oct(12), "Snow.", null);
        AskRun eclipseAndSnow = ok(new AskAnswer(true, "An eclipse and snow.", List.of(),
                List.of(eclipse, snow), null));
        model = call -> call.question().sanitised().equals("Is there snow on the tops?")
                ? eclipseAndSnow : goodModel(call, snapshot);

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(result.failed()).isEqualTo(3);
        assertThat(storedKeys()).doesNotContain("ALL/SNOW_TOPS");
    }

    private static AskPick onWindow(AskPick pick, String windowId) {
        AskWindowId.Parts parts = AskWindowId.parse(windowId).orElseThrow();
        return new AskPick(pick.rank(), pick.locationId(), pick.locationName(), pick.regionName(), parts.date(),
                parts.kind(), windowId, pick.why(), pick.ratingAtAnswer(), pick.verdictAtAnswer());
    }

    // -- the last-moment re-checks and the deadline -----------------------------------------

    @Test
    @DisplayName("a simulation switched on while a question runs stops the precompute, and the answer built "
            + "under it is not stored")
    void simulationMidRun() {
        model = call -> {
            AskRun run = goodModel(call, snapshot);
            if (calls.size() == 2) {
                when(hotTopicSimulation.isEnabled()).thenReturn(true);
            }
            return run;
        };

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(calls).hasSize(2);
        assertThat(result.written()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(NOT_OFFERED + 1 + 15);
    }

    @Test
    @DisplayName("switching Ask off mid-run stops the precompute before the next question")
    void flagOffMidRun() {
        model = call -> {
            properties.setEnabled(false);
            return goodModel(call, snapshot);
        };

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(calls).hasSize(1);
        assertThat(result.written()).isEqualTo(1);
        verify(jobRunService).completeRun(job, 1, 0);
    }

    @Test
    @DisplayName("the deadline stops it cleanly between questions: each call takes two minutes of a "
            + "five-minute deadline, so three run, and the rest count as skipped; the run is still completed")
    void deadlineStopsBetweenQuestions() {
        model = call -> {
            clock.advance(Duration.ofMinutes(2));
            return goodModel(call, snapshot);
        };

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(calls).hasSize(3);
        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(3, NOT_OFFERED + 14, 0, null));
        verify(jobRunService).completeRun(job, 3, 0);
    }

    @Test
    @DisplayName("at the deadline's boundary: a precompute that has used 4:59 goes on, one that has used "
            + "exactly 5:00 stops")
    void deadlineBoundary() {
        model = call -> {
            clock.advance(Duration.ofSeconds(299));
            return goodModel(call, snapshot);
        };
        service.precompute(1L);
        // 299s after the first call: still before the deadline, so a second runs, then 598s: stop.
        assertThat(calls).hasSize(2);

        calls.clear();
        clock.set(FRIDAY);
        model = call -> {
            clock.advance(Duration.ofMinutes(5));
            return goodModel(call, snapshot);
        };
        service.precompute(2L);
        assertThat(calls).hasSize(1);
    }

    @Test
    @DisplayName("an unrecordable model cost stops the whole precompute: every later call would be refused too")
    void accountingUnavailableStops() {
        model = call -> calls.size() == 2 ? failed(AskRun.ACCOUNTING_UNAVAILABLE) : goodModel(call, snapshot);

        AskReadyPrecompute.Result result = service.precompute(1L);

        assertThat(calls).hasSize(2);
        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(1, NOT_OFFERED + 15, 1, null));
    }

    @Test
    @DisplayName("only one precompute runs at a time: a second, started while the first is in its engine "
            + "call, is refused rather than queued, and the guard is released afterwards")
    void singleFlight() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model = call -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return goodModel(call, snapshot);
        };
        ExecutorService other = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<AskReadyPrecompute.Result> first = other.submit(() -> service.precompute(1L));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            AskReadyPrecompute.Result second = service.precomputeOnDemand();
            assertThat(second.refusal()).contains("already running");

            release.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).written()).isEqualTo(17);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
        assertThat(service.precomputeOnDemand().wasRefused()).isFalse();
    }

    // -- NIGHT_SKY (ask-night plan section 2.5) -----------------------------------------------------------------

    private static final String NIGHT_TEXT = "Anything for the night sky tonight?";
    private static final String NIGHT_ID = "2026-10-09_astro";

    /** The Friday forecast carrying tonight's (Friday's) astro night of these spots, scored by the fixtures' run. */
    private AskSnapshot fridayWithNights(AskSnapshot.Night... nights) {
        AskSnapshot base = ReadyFixtures.fridaySnapshot();
        return new AskSnapshot(base.generatedAt(), base.runLabel(), base.today(), base.windows(), base.hotTopics(),
                base.comingUp(), false, new AskSnapshot.Nights(oct(9), List.of(nights), null));
    }

    private static AskSnapshot.Night astroTonight(AskSnapshot.NightSpot... spots) {
        return new AskSnapshot.Night(NIGHT_ID, oct(9), AskWindowKind.ASTRO, AskNightFixtures.DUSK,
                AskNightFixtures.DAWN, AskNightFixtures.GENERATION, List.of(spots));
    }

    /** Whitby (Northumberland) at 5 stars, and a 3 star Teesdale spot that is not a pick. */
    private AskSnapshot.Night northernNight() {
        return astroTonight(AskNightFixtures.astro(1L, "Whitby", "Northumberland", 5, 3),
                AskNightFixtures.astro(2L, "Hamsterley", "Teesdale", 3, 4));
    }

    private void useSnapshot(AskSnapshot night) {
        snapshot = night;
        when(snapshotBuilder.build()).thenReturn(Optional.of(night));
    }

    @Test
    @DisplayName("NIGHT_SKY is asked and stored only for the scopes whose night holds a 4 star spot, each stored "
            + "with the night's version; the question carries no anchor and no context window")
    void nightSkyIsStoredPerScopeThatHasASpot() {
        useSnapshot(fridayWithNights(northernNight()));

        AskReadyPrecompute.Result result = service.precompute(42L);

        // ALL and Northumberland offer it; Teesdale's only spot is a 3 star, so it is skipped, not asked.
        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(19, NOT_OFFERED - 2, 0, null));
        assertThat(storedKeys()).contains("ALL/NIGHT_SKY", "1/NIGHT_SKY").doesNotContain("2/NIGHT_SKY");
        ArgumentCaptor<ReadyQuestion> questions = ArgumentCaptor.forClass(ReadyQuestion.class);
        ArgumentCaptor<ReadyQuestion.Offer> offers = ArgumentCaptor.forClass(ReadyQuestion.Offer.class);
        ArgumentCaptor<String> versions = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.times(19)).upsert(any(), questions.capture(), offers.capture(), any(), any(),
                any(), versions.capture());
        for (int i = 0; i < questions.getAllValues().size(); i++) {
            if (questions.getAllValues().get(i) == ReadyQuestion.NIGHT_SKY) {
                assertThat(offers.getAllValues().get(i).text()).isEqualTo(NIGHT_TEXT);
                assertThat(offers.getAllValues().get(i).windowIds()).containsExactly(NIGHT_ID);
                assertThat(versions.getAllValues().get(i))
                        .isEqualTo("n1;2026-10-09=" + AskNightFixtures.GENERATION.toEpochMilli());
            } else {
                assertThat(versions.getAllValues().get(i)).isNull();
            }
        }
        Call call = callFor(NIGHT_TEXT, List.of());
        assertThat(call.options().anchor()).isNull();
        assertThat(call.question().windowId()).isNull();
        assertThat(call.user().hasUser()).isFalse();
        assertThat(storedAnswer("ALL", ReadyQuestion.NIGHT_SKY).picks()).singleElement().satisfies(p -> {
            assertThat(p.targetType()).isEqualTo(AskWindowKind.ASTRO);
            assertThat(p.locationName()).isEqualTo("Whitby");
            assertThat(p.verdictAtAnswer()).isNull();
            assertThat(p.ratingAtAnswer()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("NIGHT_SKY is not asked at all when no astro night is eligible: no engine call is paid for it")
    void nightSkyIsNotAskedWithoutAnEligibleSpot() {
        useSnapshot(fridayWithNights(astroTonight(AskNightFixtures.astro(1L, "Whitby", "Northumberland", 3, 3))));

        AskReadyPrecompute.Result result = service.precompute(42L);

        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(17, NOT_OFFERED, 0, null));
        assertThat(calls).extracting(c -> c.question().sanitised()).doesNotContain(NIGHT_TEXT);
    }

    @Test
    @DisplayName("an answer that cites an AURORA window is discarded, never stored: a shared Ready answer carries "
            + "no aurora figure, whatever the model returned")
    void anAuroraPickIsNeverStored() {
        AskSnapshot.Night aurora = new AskSnapshot.Night("2026-10-09_aurora", oct(9), AskWindowKind.AURORA,
                AskNightFixtures.DUSK, AskNightFixtures.DAWN, AskNightFixtures.GENERATION,
                List.of(AskNightFixtures.aurora(1L, "Whitby", "Northumberland", 5, 5, 3)));
        useSnapshot(fridayWithNights(northernNight(), aurora));
        model = call -> {
            AskRun good = goodModel(call, snapshot);
            if (!call.question().sanitised().equals(NIGHT_TEXT)) {
                return good;
            }
            return ok(new AskAnswer(true, "Aurora tonight.", List.of(onWindow(good.outcome().answer().picks()
                    .getFirst(), "2026-10-09_aurora")), List.of(), null));
        };

        AskReadyPrecompute.Result result = service.precompute(42L);

        assertThat(result.failed()).isEqualTo(2);
        assertThat(storedKeys()).doesNotContain("ALL/NIGHT_SKY", "1/NIGHT_SKY");
    }

    @Test
    @DisplayName("a night answer keeps the AURORA hot topic card beside its picks and loses every other event")
    void nightSkyKeepsOnlyTheAuroraCard() {
        useSnapshot(fridayWithNights(northernNight()));
        AskEvent aurora = new AskEvent("AURORA", "Aurora tonight", oct(12), "Kp 6.", null);
        AskEvent snow = new AskEvent("SNOW_TOPS", "Snow on the Cheviot", oct(12), "Fresh snow.", null);
        model = call -> {
            AskRun good = goodModel(call, snapshot);
            if (!call.question().sanitised().equals(NIGHT_TEXT)) {
                return good;
            }
            return ok(new AskAnswer(true, "Clear, with an aurora forecast.", good.outcome().answer().picks(),
                    List.of(aurora, snow), null));
        };

        AskReadyPrecompute.Result result = service.precompute(42L);

        assertThat(result.failed()).isZero();
        assertThat(storedAnswer("ALL", ReadyQuestion.NIGHT_SKY).events()).extracting(AskEvent::type)
                .containsExactly("AURORA");
        assertThat(storedAnswer("ALL", ReadyQuestion.NIGHT_SKY).picks()).hasSize(1);
    }

    // -- a filtered precompute (the astro job's night-only dispatch) --------------------------------------------

    private static final Set<ReadyQuestion> NIGHT_ONLY = Set.of(ReadyQuestion.NIGHT_SKY);

    @Test
    @DisplayName("a filtered precompute asks and writes only the listed questions: every other question's stored "
            + "row is untouched, and the skipped count covers the filter alone")
    void filteredPrecomputeLeavesTheOtherQuestionsAlone() {
        useSnapshot(fridayWithNights(northernNight()));

        AskReadyPrecompute.Result result = service.precompute(null, NIGHT_ONLY);

        // ALL and Northumberland offer it; Teesdale does not (its only spot is a 3 star): skipped, not asked.
        assertThat(result).isEqualTo(new AskReadyPrecompute.Result(2, 1, 0, null));
        assertThat(storedKeys()).containsExactly("ALL/NIGHT_SKY", "1/NIGHT_SKY");
        assertThat(calls).extracting(c -> c.question().sanitised()).containsExactly(NIGHT_TEXT, NIGHT_TEXT);
        verify(store, never()).upsert(any(), eq(ReadyQuestion.BEST_NEXT), any(), any(), any(), any(), any());
        verify(store, never()).upsert(any(), eq(ReadyQuestion.RARE_EVENTS), any(), any(), any(), any(), any());
        verify(store, never()).upsert(any(), eq(ReadyQuestion.SNOW_TOPS), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a filtered scheduled precompute is still one ASK_READY job run counted against the ceiling, and "
            + "is refused while another precompute runs")
    void filteredPrecomputeKeepsTheGuards() throws Exception {
        useSnapshot(fridayWithNights(northernNight()));
        service.precompute(null, NIGHT_ONLY);
        verify(jobRunService).startRun(RunType.ASK_READY, false, properties.getModel());
        verify(jobRunService).completeRun(job, 2, 0);

        todaysScheduledRuns(6);
        assertThat(service.precompute(null, NIGHT_ONLY).refusal()).contains("ceiling of 6");

        todaysScheduledRuns(0);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model = call -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return goodModel(call, snapshot);
        };
        ExecutorService other = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<AskReadyPrecompute.Result> first = other.submit(() -> service.precompute(1L));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.precompute(null, NIGHT_ONLY).refusal()).contains("already running");
            release.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
    }

    @Test
    @DisplayName("the on-demand filtered precompute is a manual run, exempt from the ceiling; an empty filter runs "
            + "nothing")
    void filteredOnDemandAndEmptyFilter() {
        useSnapshot(fridayWithNights(northernNight()));
        todaysScheduledRuns(99);

        AskReadyPrecompute.Result result = service.precomputeOnDemand(NIGHT_ONLY);

        assertThat(result.written()).isEqualTo(2);
        verify(jobRunService).startRun(RunType.ASK_READY, true, properties.getModel());
        assertThat(service.precompute(null, Set.of()).written()).isZero();
    }

    // -- the window figures and the honest no-pick answer (ask-window-context plan §2.3, §2.4) ------------

    @Test
    @DisplayName("what the precompute stores carries the answer's window figures: relevantPart does not "
            + "drop them, so a stored row can be rechecked against live data")
    void storedAnswerCarriesItsWindows() {
        model = call -> {
            AskRun good = goodModel(call, snapshot);
            AskAnswer answer = good.outcome().answer();
            if (!"Best spot this weekend?".equals(call.question().sanitised())) {
                return good;
            }
            List<AskAnswer.WindowSpread> windows = snapshot.windows().stream()
                    .map(w -> new AskAnswer.WindowSpread(w.id(),
                            AskSnapshot.Spread.of(w, call.question().scope())))
                    .toList();
            return ok(answer.withWindows(windows));
        };

        service.precompute(1L);

        AskAnswer stored = storedAnswer("ALL", ReadyQuestion.BEST_WEEKEND);
        assertThat(stored.windows()).isNotEmpty();
        assertThat(stored.windows().getFirst().spread().over()).isEqualTo("every region");
    }

    @Test
    @DisplayName("on a weekend with nothing game on, a no-pick BEST_WEEKEND answer is stored, not counted as a "
            + "failure: the question was offered (a 3★ is a candidate) and the honest answer is admitted")
    void aStayHomeWeekendStoresTheNoPickAnswer() {
        com.gregochr.goldenhour.model.BriefingRegion stayHome =
                AskFixtures.bandedRegion("Northumberland", 1L, 0, 5, 10, 0, 0);
        snapshot = ReadyFixtures.at(ReadyFixtures.FRIDAY_NOON,
                ReadyFixtures.day(ReadyFixtures.oct(10), true, true, null, stayHome),
                ReadyFixtures.day(ReadyFixtures.oct(11), true, true, null, stayHome));
        when(snapshotBuilder.build()).thenReturn(Optional.of(snapshot));
        model = call -> "Best spot this weekend?".equals(call.question().sanitised())
                ? ok(new AskAnswer(true, "Not a weekend to go out for.", List.of(), List.of(), null))
                : failed("not under test");

        service.precompute(1L);

        assertThat(storedKeys()).contains("ALL/BEST_WEEKEND");
        assertThat(storedAnswer("ALL", ReadyQuestion.BEST_WEEKEND).picks()).isEmpty();
    }

    @Test
    @DisplayName("the options handed to the engine carry the offer, so the prompt, the validator and the "
            + "freshness check judge the same covered windows")
    void theEngineIsHandedTheOffer() {
        service.precompute(1L);

        AskRunOptions weekend = callFor("Best spot this weekend?", List.of()).options();
        assertThat(weekend.readyBasis().question()).isEqualTo(ReadyQuestion.BEST_WEEKEND);
        assertThat(weekend.readyBasis().offer().coveredWindowIds()).isNotEmpty();
        assertThat(callFor("Best coastal spot at high tide?", List.of()).options().readyBasis().question())
                .isEqualTo(ReadyQuestion.COASTAL_HIGH);
    }
}
