package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.model.runs.NoteTone;
import com.gregochr.goldenhour.model.runs.RunDeepKind;
import com.gregochr.goldenhour.model.runs.RunDeepStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunPhaseRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one loader behind the Runs Day and Calendar views: every row that started in a window, with cycles folded
 * together with their batch runs and priced run by run.
 *
 * <p>It reads with a fixed number of set queries however many rows there are, applies the same top-level rule as the
 * Runs list ({@link TopLevelResolver}), links cycles to the briefing and ask-ready runs they started
 * ({@link CycleChildLinker}), and never truncates. A cost that is not known (a batch still open) is {@code null}
 * and marks the row's cost incomplete; it is never counted as zero.
 */
@Component
class RunsLoader {

    /** After the window ends, how long a cycle's chained briefing or ask-ready run may still start. */
    private static final Duration CHILD_MARGIN = Duration.ofHours(6);

    /** How long before an ask-ready run an astro-conditions run must have ended for it to have started that run. */
    private static final Duration ASTRO_LEAD = Duration.ofMinutes(CycleChildLinker.ASK_START_WINDOW_MINUTES);

    /** The longest error text shown as a sky eval row's note. */
    private static final int NOTE_MAX = 120;

    private static final Set<RunType> CHILD_TYPES = Set.of(RunType.BRIEFING, RunType.ASK_READY, RunType.WEATHER);

    /** The rows a window holds, and the rates they were priced with. */
    record LoadedRuns(List<DeepRun> rows, RunFx fx) {
    }

    private final JobRunRepository jobRuns;
    private final PipelineRunRepository pipelineRuns;
    private final PipelineRunPhaseRepository phases;
    private final SkyRatingEvalRunRepository evalRuns;
    private final CycleFactsLoader cycleFacts;
    private final RunFxLoader fxLoader;
    private final TopLevelResolver topLevel;
    private final Clock clock;

    /**
     * Constructs the loader.
     *
     * @param jobRuns      job run store
     * @param pipelineRuns pipeline run store
     * @param phases       pipeline phase store
     * @param evalRuns     sky-rating eval run store
     * @param batches      Anthropic batch store
     * @param cycleFacts   loads each cycle's batches and member job runs
     * @param fxLoader     reads stored exchange rates
     * @param clock        the application clock
     */
    RunsLoader(JobRunRepository jobRuns, PipelineRunRepository pipelineRuns, PipelineRunPhaseRepository phases,
            SkyRatingEvalRunRepository evalRuns, ForecastBatchRepository batches, CycleFactsLoader cycleFacts,
            RunFxLoader fxLoader, Clock clock) {
        this.jobRuns = jobRuns;
        this.pipelineRuns = pipelineRuns;
        this.phases = phases;
        this.evalRuns = evalRuns;
        this.cycleFacts = cycleFacts;
        this.fxLoader = fxLoader;
        this.topLevel = new TopLevelResolver(batches, pipelineRuns);
        this.clock = clock;
    }

    /**
     * Loads every row that started in a window.
     *
     * @param from the inclusive start
     * @param to   the exclusive end
     * @return the rows, ascending by start, and the rates they were priced with
     */
    LoadedRuns load(Instant from, Instant to) {
        LocalDateTime fromUtc = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        LocalDateTime toUtc = LocalDateTime.ofInstant(to, ZoneOffset.UTC);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        RunFx fx = fxLoader.load();

        List<JobRunEntity> jobs = jobRuns.findByStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                fromUtc, toUtc);
        List<PipelineRunEntity> cycles = pipelineRuns
                .findByTriggerTimeGreaterThanEqualAndTriggerTimeLessThanOrderByTriggerTimeAscIdAsc(from, to);
        List<SkyRatingEvalRunEntity> evals = evalRuns
                .findByRunTimestampGreaterThanEqualAndRunTimestampLessThanOrderByRunTimestampAscIdAsc(fromUtc, toUtc);
        TopLevelResolver.TopLevel top = topLevel.resolve(jobs);

        Map<Long, PipelineRunEntity> cyclesById = new LinkedHashMap<>();
        cycles.forEach(c -> cyclesById.put(c.getId(), c));
        Set<Long> parentIds = new LinkedHashSet<>();
        top.runs().stream().map(t -> t.run().getPipelineRunId())
                .filter(id -> id != null && !cyclesById.containsKey(id)).forEach(parentIds::add);
        if (!parentIds.isEmpty()) {
            pipelineRuns.findAllById(parentIds).forEach(c -> cyclesById.put(c.getId(), c));
        }
        Map<Long, CycleChildLinker.Children> kids = linkChildren(cyclesById, fromUtc, toUtc);
        Map<Long, Long> parentOfJob = new HashMap<>();
        kids.forEach((cycleId, c) -> {
            if (c.briefing() != null) {
                parentOfJob.put(c.briefing().getId(), cycleId);
            }
            if (c.askReady() != null) {
                parentOfJob.put(c.askReady().getId(), cycleId);
            }
        });
        List<JobRunEntity> astroRuns = jobRuns
                .findByRunTypeInAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                        List.of(RunType.WEATHER), fromUtc.minus(ASTRO_LEAD), toUtc).stream()
                .filter(r -> r.getEvaluationModel() == null).toList();

        CycleFacts facts = cycleFacts.load(cycles);
        List<DeepRun> rows = new ArrayList<>();
        for (PipelineRunEntity cycle : cycles) {
            rows.add(cycleRow(cycle, facts, kids.get(cycle.getId()), fx));
        }
        for (TopLevelResolver.TopLevelRun t : top.runs()) {
            rows.add(jobRow(t.run(), t.batchType(), nowUtc, fx, cyclesById, parentOfJob, astroRuns));
        }
        for (SkyRatingEvalRunEntity eval : evals) {
            rows.add(evalRow(eval, nowUtc, fx));
        }
        rows.sort(Comparator.comparing(DeepRun::startedAt).thenComparing(DeepRun::id));
        return new LoadedRuns(rows, fx);
    }

    private Map<Long, CycleChildLinker.Children> linkChildren(Map<Long, PipelineRunEntity> cyclesById,
            LocalDateTime fromUtc, LocalDateTime toUtc) {
        if (cyclesById.isEmpty()) {
            return Map.of();
        }
        Map<Long, JobRunEntity> candidates = new LinkedHashMap<>();
        jobRuns.findByPipelineRunIdIn(cyclesById.keySet()).forEach(r -> candidates.put(r.getId(), r));
        jobRuns.findByRunTypeInAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                List.of(RunType.BRIEFING, RunType.ASK_READY), fromUtc, toUtc.plus(CHILD_MARGIN))
                .forEach(r -> candidates.putIfAbsent(r.getId(), r));
        List<PipelineRunPhaseEntity> briefingPhases = phases.findByPipelineRunIdInAndPhase(cyclesById.keySet(),
                PipelinePhase.BRIEFING);
        return CycleChildLinker.link(cyclesById.values(), candidates.values(), briefingPhases);
    }

    // ── Cycles ────────────────────────────────────────────────────────────────

    private DeepRun cycleRow(PipelineRunEntity cycle, CycleFacts facts, CycleChildLinker.Children kids, RunFx fx) {
        RunClassifier.Outcome outcome = RunClassifier.classifyCycle(cycle);
        RunDeepStatus status = RunDeepStatus.from(outcome.status());
        List<ForecastBatchEntity> batches = facts.batches(cycle.getId());
        Instant ended = cycle.getCompletedAt();
        Long duration = ended == null ? null : Duration.between(cycle.getTriggerTime(), ended).toSeconds();

        long micro = 0;
        double gbp = 0;
        boolean known = true;
        for (JobRunEntity member : facts.members(cycle.getId())) {
            Long total = member.getTotalCostMicroDollars();
            if (total == null) {
                known = false;
                continue;
            }
            micro += total;
            gbp += RunFx.gbp(total, fx.rateFor(member.getExchangeRateGbpPerUsd(),
                    member.getStartedAt().toLocalDate()));
        }
        boolean complete = known && status != RunDeepStatus.RUNNING;
        int requests = batches.stream().mapToInt(ForecastBatchEntity::getRequestCount).sum();
        Instant answersReady = kids != null && kids.askReady() != null && kids.askReady().getCompletedAt() != null
                ? kids.askReady().getCompletedAt().toInstant(ZoneOffset.UTC) : null;
        String subline = batches.isEmpty() ? null : batches.size() + (batches.size() == 1 ? " batch · " : " batches · ")
                + requests + " requests";
        return new DeepRun("cycle-" + cycle.getId(), RunDeepKind.CYCLE, RunDeepType.ofCycle(cycle.getCycleType()),
                StatKey.ofCycle(cycle.getCycleType()), null, RunClassifier.cycleName(cycle.getCycleType()), subline,
                cycle.getTriggerTime(), ended, duration, micro, complete, gbp, status, outcome.note(),
                outcome.tone(), null, cycle.getId(), null, batches.size(), requests, answersReady);
    }

    // ── Job runs ──────────────────────────────────────────────────────────────

    private DeepRun jobRow(JobRunEntity run, BatchType batchType, LocalDateTime nowUtc, RunFx fx,
            Map<Long, PipelineRunEntity> cyclesById, Map<Long, Long> parentOfJob, List<JobRunEntity> astroRuns) {
        RunClassifier.Outcome outcome = RunClassifier.classifyJob(run, nowUtc);
        RunDeepStatus status = RunDeepStatus.from(outcome.status());
        Instant started = run.getStartedAt().toInstant(ZoneOffset.UTC);
        Instant ended = run.getCompletedAt() == null ? null : run.getCompletedAt().toInstant(ZoneOffset.UTC);
        Long duration = null;
        if (ended != null && run.getRunType() != RunType.ASK) {
            duration = run.getDurationMs() != null ? Duration.ofMillis(run.getDurationMs()).toSeconds()
                    : Duration.between(started, ended).toSeconds();
        }
        Long total = run.getTotalCostMicroDollars();
        double gbp = total == null ? 0 : RunFx.gbp(total, fx.rateFor(run.getExchangeRateGbpPerUsd(),
                run.getStartedAt().toLocalDate()));
        Long parent = parentOfJob.get(run.getId());
        if (parent == null && run.getPipelineRunId() != null) {
            parent = run.getPipelineRunId();
        }
        String subline = jobSubline(run, parent == null ? null : cyclesById.get(parent), astroRuns);
        return new DeepRun("job-" + run.getId(), RunDeepKind.JOB, RunDeepType.of(run.getRunType()),
                StatKey.ofJob(run), run.getRunType(), jobDisplayName(run, batchType), subline, started, ended,
                duration, total, total != null && status != RunDeepStatus.RUNNING, gbp, status, outcome.note(),
                outcome.tone(), parent, null, run.getId(), null, null, null);
    }

    /**
     * The display name of a job run. Wildlife comfort and Astro conditions share a run type and are told apart by
     * the evaluation model.
     *
     * @param run       the job run
     * @param batchType the type of its Anthropic batch, or null
     * @return the name
     */
    static String jobDisplayName(JobRunEntity run, BatchType batchType) {
        if (run.getRunType() == RunType.WEATHER) {
            return run.getEvaluationModel() == EvaluationModel.WILDLIFE ? "Wildlife comfort" : "Astro conditions";
        }
        return RunClassifier.jobName(run.getRunType(), batchType, run.getActiveStrategies());
    }

    private static String jobSubline(JobRunEntity run, PipelineRunEntity parent, List<JobRunEntity> astroRuns) {
        if (parent != null) {
            String cycle = RunClassifier.cycleName(parent.getCycleType());
            return run.getRunType() == RunType.BRIEFING ? "Runs at the end of " + cycle : "Runs after " + cycle;
        }
        if (Boolean.TRUE.equals(run.getTriggeredManually())) {
            return "Started by an admin";
        }
        if (run.getRunType() == RunType.ASK_READY && followsAstro(run, astroRuns)) {
            return "After Astro conditions";
        }
        return null;
    }

    private static boolean followsAstro(JobRunEntity ask, List<JobRunEntity> astroRuns) {
        LocalDateTime earliest = ask.getStartedAt().minus(ASTRO_LEAD);
        return astroRuns.stream().anyMatch(a -> a.getCompletedAt() != null
                && !a.getCompletedAt().isBefore(earliest) && !a.getCompletedAt().isAfter(ask.getStartedAt()));
    }

    // ── Sky eval ──────────────────────────────────────────────────────────────

    private DeepRun evalRow(SkyRatingEvalRunEntity eval, LocalDateTime nowUtc, RunFx fx) {
        Instant started = eval.getStartedAt().toInstant(ZoneOffset.UTC);
        Instant ended = eval.getCompletedAt() == null ? null : eval.getCompletedAt().toInstant(ZoneOffset.UTC);
        RunDeepStatus status;
        String note = null;
        NoteTone tone = null;
        if (eval.getStatus() == SkyRatingEvalStatus.COMPLETED) {
            status = RunDeepStatus.DONE;
        } else if (eval.getStatus() == SkyRatingEvalStatus.FAILED) {
            status = RunDeepStatus.FAILED;
            note = eval.getErrorMessage() == null || eval.getErrorMessage().isBlank() ? "Failed"
                    : truncate(eval.getErrorMessage());
            tone = NoteTone.ERROR;
        } else if (!nowUtc.isBefore(eval.getStartedAt().plus(RunClassifier.BATCH_STALE_CEILING))) {
            status = RunDeepStatus.FAILED;
            note = RunClassifier.DID_NOT_FINISH;
            tone = NoteTone.ERROR;
        } else {
            status = RunDeepStatus.RUNNING;
        }
        Long duration = null;
        if (ended != null) {
            duration = eval.getDurationMs() != null ? eval.getDurationMs() / 1000
                    : Duration.between(started, ended).toSeconds();
        }
        // The eval's cost is a primitive that reads zero until the run is finalised: unknown, not free, until then.
        boolean costKnown = status != RunDeepStatus.RUNNING;
        Long micro = costKnown ? eval.getCostMicroDollars() : null;
        double gbp = micro == null ? 0 : RunFx.gbp(micro, fx.rateOn(eval.getStartedAt().toLocalDate()));
        String trigger = eval.getTriggerSource() == SkyRatingEvalTrigger.SCHEDULED ? "Scheduled" : "Manual";
        return new DeepRun("eval-" + eval.getId(), RunDeepKind.SKY_EVAL, RunDeepType.SKY_EVAL, StatKey.SKY_EVAL,
                null, RunDeepType.SKY_EVAL.label(), trigger + " · " + eval.getModel().name(), started, ended,
                duration, micro, costKnown, gbp, status, note, tone, null, null, null, null, null, null);
    }

    private static String truncate(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= NOTE_MAX ? line : line.substring(0, NOTE_MAX - 1) + "…";
    }
}
