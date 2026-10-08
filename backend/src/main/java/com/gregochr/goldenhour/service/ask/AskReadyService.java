package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ready answers (plan §2.4): precomputes one user-less answer per scope and {@link ReadyQuestion},
 * and serves them.
 *
 * <h2>Precompute</h2>
 * Dispatched by {@code PipelineOrchestrator} <b>after</b> a cycle's run is finished (never inside
 * it: a run held RUNNING forces every later tail settle to {@code RESETS_ONLY}) and by the admin
 * endpoint on demand. Scopes are every enabled region and {@code ALL}; questions are the
 * catalogue's available ones; each is one engine run, one at a time, with no user, billed to one
 * {@code ASK_READY} job run per precompute.
 * <ul>
 *   <li><b>Refused as a whole</b> when Ask is off, a simulation is active (hot topic or aurora),
 *       another precompute is running, the briefing is missing, last-known-good or has no build
 *       time, or — for a scheduled run only — {@code photocast.ask.ready.max-cycles-per-day}
 *       scheduled {@code ASK_READY} runs have already started since UK midnight. That count is a
 *       query on {@code job_run}, so it is durable across a restart and needs no table of its own.
 *       An admin's on-demand run is a person's decision, as the dry-run is: it is not counted
 *       against the ceiling and not stopped by it, so a lever pressed locally cannot lock the
 *       nightly out.</li>
 *   <li><b>Checked again at the last moment</b>: before every question the flag, the simulations and
 *       the deadline are asked again, and after the engine returns the simulation is asked once more
 *       before anything is written, so an answer built while a simulation switched on is dropped.</li>
 *   <li><b>One question's failure does not stop the rest</b>: a FAILED run, a "can't", an exception,
 *       a validator discard or an answer that breaks the question's own rules is logged and counted;
 *       the exception is the accounting latch, which refuses every later call too, so it stops the
 *       run.</li>
 *   <li><b>A deadline of {@link #PRECOMPUTE_DEADLINE}</b>, checked between questions: an overrun
 *       stops cleanly and the remaining questions count as skipped.</li>
 *   <li>Nothing the model returns is stored unless it is the kind of answer the question wants
 *       ({@link ReadyQuestion#violation}).</li>
 * </ul>
 *
 * <h2>Serve</h2>
 * {@link #serve} returns the stored answers that are still true against live data, whole or not at
 * all ({@link AskReadyFreshness}), re-decorated from the live snapshot.
 */
@Service
public class AskReadyService {

    private static final Logger LOG = LoggerFactory.getLogger(AskReadyService.class);

    /** The longest a precompute may run before it stops between questions. */
    public static final Duration PRECOMPUTE_DEADLINE = Duration.ofMinutes(5);

    /** How many other questions an answer suggests. */
    private static final int TRY_COUNT = 2;

    private final AskProperties properties;
    private final AskSnapshotBuilder snapshotBuilder;
    private final AskEngine engine;
    private final RegionRepository regionRepository;
    private final AskReadyStore store;
    private final JobRunService jobRunService;
    private final JobRunRepository jobRunRepository;
    private final HotTopicSimulationService hotTopicSimulation;
    private final AuroraStateCache auroraStateCache;
    private final Clock clock;
    private final Duration deadline;

    /** Held for the whole of a precompute, so there is only ever one engine run at a time. */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Creates the service.
     *
     * @param properties         the Ask settings (the flag, the per-day cycle ceiling, the model)
     * @param snapshotBuilder    builds the snapshot answers are written and checked against
     * @param engine             the one active engine: the stub or Claude
     * @param regionRepository   the enabled regions, which are the scopes
     * @param store              the {@code ask_ready_answer} table
     * @param jobRunService      starts and completes the {@code ASK_READY} run
     * @param jobRunRepository   counts the day's scheduled runs against the ceiling
     * @param hotTopicSimulation the hot topic simulation switch
     * @param auroraStateCache   the aurora state, whose simulated data marks an aurora simulation
     * @param clock              the application clock: the UK day, the deadline
     */
    @Autowired
    public AskReadyService(AskProperties properties, AskSnapshotBuilder snapshotBuilder,
            AskEngine engine, RegionRepository regionRepository, AskReadyStore store,
            JobRunService jobRunService, JobRunRepository jobRunRepository,
            HotTopicSimulationService hotTopicSimulation, AuroraStateCache auroraStateCache,
            Clock clock) {
        this(properties, snapshotBuilder, engine, regionRepository, store, jobRunService,
                jobRunRepository, hotTopicSimulation, auroraStateCache, clock,
                PRECOMPUTE_DEADLINE);
    }

    /**
     * Creates the service with its own deadline, for tests.
     *
     * @param properties         the Ask settings
     * @param snapshotBuilder    builds the snapshot
     * @param engine             the one active engine
     * @param regionRepository   the enabled regions
     * @param store              the table
     * @param jobRunService      starts and completes the run
     * @param jobRunRepository   counts the day's runs
     * @param hotTopicSimulation the hot topic simulation switch
     * @param auroraStateCache   the aurora state
     * @param clock              the clock
     * @param deadline           how long a precompute may run
     */
    AskReadyService(AskProperties properties, AskSnapshotBuilder snapshotBuilder, AskEngine engine,
            RegionRepository regionRepository, AskReadyStore store, JobRunService jobRunService,
            JobRunRepository jobRunRepository, HotTopicSimulationService hotTopicSimulation,
            AuroraStateCache auroraStateCache, Clock clock, Duration deadline) {
        this.properties = properties;
        this.snapshotBuilder = snapshotBuilder;
        this.engine = engine;
        this.regionRepository = regionRepository;
        this.store = store;
        this.jobRunService = jobRunService;
        this.jobRunRepository = jobRunRepository;
        this.hotTopicSimulation = hotTopicSimulation;
        this.auroraStateCache = auroraStateCache;
        this.clock = clock;
        this.deadline = deadline;
    }

    /**
     * What a precompute did.
     *
     * @param written the answers stored
     * @param skipped the questions not run: not available for the scope, nothing to show, or left
     *                when the deadline or a stop came
     * @param failed  the questions tried that stored nothing because they failed
     * @param refusal why the whole precompute did not run, or null when it ran
     */
    public record Result(int written, int skipped, int failed, String refusal) {

        static Result refused(String reason) {
            return new Result(0, 0, 0, reason);
        }

        /**
         * Whether the whole precompute was refused.
         *
         * @return true when nothing ran
         */
        public boolean wasRefused() {
            return refusal != null;
        }
    }

    // -- precompute -------------------------------------------------------------------------

    /**
     * The scheduled precompute, dispatched after a pipeline cycle's run is finished. Counts against
     * the per-day ceiling.
     *
     * @param pipelineRunId the cycle that triggered it, stored on each answer
     * @return what it did; never throws for a refusal
     */
    public Result precompute(Long pipelineRunId) {
        return run(pipelineRunId, false);
    }

    /**
     * An on-demand precompute: the admin endpoint, the owner's lever after a manual briefing rebuild
     * and the only way to get Ready answers locally. Runs on the calling thread. Not counted against
     * the per-day ceiling.
     *
     * @return what it did
     */
    public Result precomputeOnDemand() {
        return run(null, true);
    }

    private Result run(Long pipelineRunId, boolean manual) {
        if (!properties.isEnabled()) {
            return Result.refused("Ask is switched off");
        }
        if (simulationActive()) {
            return Result.refused("a simulation is active");
        }
        if (!running.compareAndSet(false, true)) {
            return Result.refused("a precompute is already running");
        }
        try {
            return runExclusively(pipelineRunId, manual);
        } finally {
            running.set(false);
        }
    }

    private Result runExclusively(Long pipelineRunId, boolean manual) {
        LocalDate today = ForecastHorizon.today(clock);
        if (!manual) {
            long cycles = jobRunRepository.countByRunTypeAndTriggeredManuallyAndStartedAtGreaterThanEqual(
                    RunType.ASK_READY, false, AskJobRunService.ukDayStartUtc(today));
            if (cycles >= properties.getReady().getMaxCyclesPerDay()) {
                LOG.info("[ASK] Ready precompute skipped: {} scheduled runs already started today "
                        + "(ceiling {})", cycles, properties.getReady().getMaxCyclesPerDay());
                return Result.refused("the daily ceiling of "
                        + properties.getReady().getMaxCyclesPerDay() + " precomputes is reached");
            }
        }
        Optional<AskSnapshot> built = snapshotBuilder.build();
        if (built.isEmpty()) {
            return Result.refused("no briefing has been built");
        }
        AskSnapshot snapshot = built.get();
        if (snapshot.briefingStale()) {
            return Result.refused("the briefing is last-known-good, not fresh");
        }
        if (snapshot.generatedAt() == null) {
            return Result.refused("the briefing has no build time");
        }

        JobRunEntity job = jobRunService.startRun(RunType.ASK_READY, manual, properties.getModel());
        Counts counts = new Counts();
        try {
            work(snapshot, job.getId(), pipelineRunId, counts);
        } finally {
            complete(job, counts);
        }
        LOG.info("[ASK] Ready precompute finished: {} written, {} skipped, {} failed (job run {})",
                counts.written, counts.skipped, counts.failed, job.getId());
        return new Result(counts.written, counts.skipped, counts.failed, null);
    }

    /** The mutable tally of one precompute, so a stop keeps whatever was counted before it. */
    private static final class Counts {
        private int written;
        private int skipped;
        private int failed;
    }

    /** One (scope, question) to run. */
    private record Task(AskScope scope, ReadyQuestion question, ReadyQuestion.Offer offer) {
    }

    private void work(AskSnapshot snapshot, long jobRunId, Long pipelineRunId, Counts counts) {
        List<Task> tasks = new ArrayList<>();
        for (AskScope scope : scopes()) {
            for (ReadyQuestion question : ReadyQuestion.values()) {
                Optional<ReadyQuestion.Offer> offer = question.offer(snapshot, scope);
                if (offer.isPresent()) {
                    tasks.add(new Task(scope, question, offer.get()));
                } else {
                    counts.skipped++;
                }
            }
        }
        Instant stopAt = clock.instant().plus(deadline);
        for (int i = 0; i < tasks.size(); i++) {
            String stop = stopReason(stopAt);
            if (stop != null) {
                LOG.warn("[ASK] Ready precompute stopping before question {} of {}: {}", i + 1,
                        tasks.size(), stop);
                counts.skipped += tasks.size() - i;
                return;
            }
            if (!runOne(tasks.get(i), snapshot, jobRunId, pipelineRunId, counts)) {
                counts.skipped += tasks.size() - i - 1;
                return;
            }
        }
    }

    /** Why the precompute must not start another question, or null when it may. */
    private String stopReason(Instant stopAt) {
        if (!properties.isEnabled()) {
            return "Ask was switched off";
        }
        if (simulationActive()) {
            return "a simulation became active";
        }
        if (!clock.instant().isBefore(stopAt)) {
            return "the " + deadline.toMinutes() + "-minute deadline passed";
        }
        return null;
    }

    /**
     * Runs one question and stores its answer.
     *
     * @return false when the whole precompute must stop (accounting is unavailable), true otherwise
     */
    private boolean runOne(Task task, AskSnapshot snapshot, long jobRunId, Long pipelineRunId,
            Counts counts) {
        String label = task.scope().key() + "/" + task.question();
        AskQuestion question = AskQuestion.of(task.offer().text(), task.offer().contextWindowId(),
                task.scope(), "plan");
        AskRun run;
        try {
            run = engine.run(question, snapshot, AskUserContext.userLess(),
                    AskRunOptions.ready(jobRunId, task.question().anchor(task.offer())));
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Ready {} threw: {}", label, e.toString());
            counts.failed++;
            return true;
        }
        if (run.accountingUnavailable()) {
            LOG.error("[ASK] Ready precompute stopping: a model call's cost could not be recorded");
            counts.failed++;
            return false;
        }
        AskOutcome outcome = run.outcome();
        if (outcome.status() != AskOutcome.Status.OK || outcome.answer() == null || outcome.personal()) {
            LOG.warn("[ASK] Ready {} produced no answer: {} {}", label, outcome.status(),
                    run.reason() == null ? "" : run.reason());
            counts.failed++;
            return true;
        }
        if (task.question().dropsWarning(outcome.answer())) {
            LOG.warn("[ASK] Ready {} discarded: it carries a warned event the question would drop", label);
            counts.failed++;
            return true;
        }
        // The model is not trusted to keep to its question: events and picks the question does not
        // admit are removed before anything is judged or stored (the same predicate the serve re-applies).
        AskAnswer answer = task.question().relevantPart(outcome.answer());
        if (answer.events().size() < outcome.answer().events().size()
                || answer.picks().size() < outcome.answer().picks().size()) {
            LOG.info("[ASK] Ready {} had events or picks it may not carry; they were removed", label);
        }
        if (!task.question().picks() && answer.picks().isEmpty() && answer.events().isEmpty()) {
            // An events question may honestly find nothing, or nothing relevant once the irrelevant
            // events are removed: there is no card to show, and a "no snow" summary cannot be verified
            // against the prose, so no row (a SNOW_TOPS answer that only found an aurora lands here). A
            // pick question that returns nothing is not honest (the offer said a pick existed): it
            // falls through to the violation check and counts as a failure.
            LOG.info("[ASK] Ready {} found nothing to show; not stored", label);
            counts.skipped++;
            return true;
        }
        Optional<String> violation = task.question().violation(answer, task.offer(), snapshot,
                task.scope());
        if (violation.isPresent()) {
            LOG.warn("[ASK] Ready {} discarded: {}", label, violation.get());
            counts.failed++;
            return true;
        }
        if (simulationActive()) {
            LOG.warn("[ASK] Ready {} discarded: a simulation became active while it ran", label);
            counts.skipped++;
            return true;
        }
        try {
            store.upsert(task.scope().key(), task.question(), task.offer(), answer,
                    snapshot.generatedAt(), pipelineRunId);
            counts.written++;
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Ready {} could not be stored: {}", label, e.toString());
            counts.failed++;
        }
        return true;
    }

    /** Every region, then each enabled region alone: built from the entities already read. */
    private List<AskScope> scopes() {
        List<AskScope> scopes = new ArrayList<>();
        scopes.add(AskScope.ALL);
        for (RegionEntity region : regionRepository.findAllByEnabledTrueOrderByNameAsc()) {
            scopes.add(AskScope.of(List.of(region.getId()), List.of(region.getName())));
        }
        return scopes;
    }

    private void complete(JobRunEntity job, Counts counts) {
        try {
            job.setLocationsProcessed(counts.written + counts.failed);
            jobRunService.completeRun(job, counts.written, counts.failed);
        } catch (RuntimeException e) {
            LOG.error("[ASK] Could not complete the ASK_READY job run {}: {}", job.getId(),
                    e.getMessage());
        }
    }

    private boolean simulationActive() {
        return AskSimulation.active(hotTopicSimulation, auroraStateCache);
    }

    // -- serve ------------------------------------------------------------------------------

    /**
     * The Ready questions of a scope that are still true, in catalogue order.
     *
     * @param scope {@link AskScope#ALL} or one region
     * @return the fresh questions; empty when there is no briefing or none is fresh
     */
    public AskReadyResponse serve(AskScope scope) {
        String echo = scope.isEverywhere() ? "all" : scope.key();
        Optional<AskSnapshot> live = snapshotBuilder.current();
        if (live.isEmpty()) {
            return new AskReadyResponse(echo, List.of());
        }
        return new AskReadyResponse(echo, freshAnswers(scope, live.get()));
    }

    /**
     * The Ready questions of a scope that are still true against a snapshot the caller already holds,
     * each decorated exactly as {@link #serve} serves it. The typed question's intent match uses this,
     * so a Ready answer given to a typed question is the very object, with the very freshness test,
     * a tap on the same question would have got.
     *
     * @param scope {@link AskScope#ALL} or one region
     * @param live  the live snapshot the freshness check is made against
     * @return the fresh questions in catalogue order; empty when none is fresh
     */
    public List<AskReadyResponse.Question> freshAnswers(AskScope scope, AskSnapshot live) {
        List<Fresh> fresh = freshQuestions(scope, live);
        List<AskReadyResponse.Question> questions = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            questions.add(toQuestion(fresh, i));
        }
        return questions;
    }

    /**
     * Up to {@code limit} Ready questions of a scope that are fresh against the given snapshot, in
     * catalogue order: the {@code try} suggestions of a typed answer that could not answer (plan
     * §2.9). The same freshness test {@link #serve} applies, so a suggestion is always one the client
     * will find in its Ready list.
     *
     * @param scope {@link AskScope#ALL} or one region
     * @param live  the live snapshot the freshness check is made against
     * @param limit the most suggestions to return
     * @return the suggestions; empty when none is fresh
     */
    public List<AskReadyResponse.Suggestion> suggestions(AskScope scope, AskSnapshot live, int limit) {
        return freshQuestions(scope, live).stream().limit(Math.max(0, limit))
                .map(f -> new AskReadyResponse.Suggestion(f.question().name(), f.stored().questionText()))
                .toList();
    }

    /** The stored questions of a scope that are still true against {@code live}, in catalogue order. */
    private List<Fresh> freshQuestions(AskScope scope, AskSnapshot live) {
        Map<String, AskReadyStore.Stored> byId = new LinkedHashMap<>();
        store.findScope(scope.key()).forEach(s -> byId.put(s.questionId(), s));

        List<Fresh> fresh = new ArrayList<>();
        for (ReadyQuestion question : ReadyQuestion.values()) {
            AskReadyStore.Stored stored = byId.get(question.name());
            if (stored == null) {
                continue;
            }
            AskReadyFreshness.Verdict verdict =
                    AskReadyFreshness.check(question, stored, live, scope);
            if (verdict.fresh()) {
                fresh.add(new Fresh(question, stored, verdict.answer()));
            } else {
                LOG.debug("[ASK] Ready {}/{} withheld: {}", scope.key(), question, verdict.reason());
            }
        }
        return fresh;
    }

    private record Fresh(ReadyQuestion question, AskReadyStore.Stored stored, AskAnswer answer) {
    }

    /** Builds the wire question at {@code index}; its suggestions are the next fresh others, wrapping. */
    private static AskReadyResponse.Question toQuestion(List<Fresh> fresh, int index) {
        Fresh self = fresh.get(index);
        List<AskReadyResponse.Suggestion> suggestions = new ArrayList<>();
        for (int step = 1; step < fresh.size() && suggestions.size() < TRY_COUNT; step++) {
            Fresh other = fresh.get((index + step) % fresh.size());
            suggestions.add(new AskReadyResponse.Suggestion(other.question().name(),
                    other.stored().questionText()));
        }
        AskAnswer answer = self.answer();
        AskReadyResponse.Answer wire = new AskReadyResponse.Answer(true, "ready", answer.summary(),
                answer.picks().stream().map(AskReadyResponse.Pick::of).toList(), answer.events(),
                null, suggestions);
        // Each question carries its own build time and label: rows of one scope can come from
        // different precomputes (a question whose run failed keeps its earlier answer).
        return new AskReadyResponse.Question(self.question().name(), self.stored().questionText(),
                self.question().tabs(), self.stored().briefingGeneratedAt(),
                AskSnapshotBuilder.runLabel(self.stored().briefingGeneratedAt()), wire);
    }
}
