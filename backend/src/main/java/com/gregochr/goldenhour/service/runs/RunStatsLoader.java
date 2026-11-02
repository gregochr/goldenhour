package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelinePhase;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunPhaseEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunPhaseRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import com.gregochr.goldenhour.service.runs.RunStats.Sample;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads the finished-run samples behind the Day view's medians, 90th percentiles, "slow" flags and estimates.
 *
 * <p>One query per population present, never one per run: each reads the newest {@code 30 + k} finished runs of
 * its population before a moment ({@code k} being how many of that population are on the day), and the window slides
 * in memory ({@link RunStats}). The populations are {@link StatKey}: scheduler-started runs only (a manual run is not
 * scheduled behaviour), cycles that COMPLETED or DEGRADED, BRIEFING runs that belong to a cycle (linked by id, or
 * matched on the cycle's BRIEFING phase window), and "other" per run type and model.
 */
@Component
class RunStatsLoader {

    /** Cycle statuses that count as a finished cycle. */
    private static final Set<PipelineRunStatus> FINISHED_CYCLE =
            EnumSet.of(PipelineRunStatus.COMPLETED, PipelineRunStatus.DEGRADED);

    /** How many briefing runs are scanned per sample wanted, to find the cycle-linked ones among admin builds. */
    private static final int BRIEFING_SCAN_FACTOR = 4;

    /** How long after its start an unfinished BRIEFING phase is taken to still cover a briefing run. */
    private static final Duration OPEN_PHASE = Duration.ofHours(2);

    /** The recent cycles read to find the usual gap between a cycle's end and its ask-ready run. */
    private static final int GAP_CYCLES = RunStats.WINDOW;

    private final JobRunRepository jobRuns;
    private final PipelineRunRepository pipelineRuns;
    private final PipelineRunPhaseRepository phases;
    private final SkyRatingEvalRunRepository evalRuns;
    private final CycleFactsLoader cycleFacts;

    /**
     * Constructs the loader.
     *
     * @param jobRuns      job run store
     * @param pipelineRuns pipeline run store
     * @param phases       pipeline phase store
     * @param evalRuns     sky-rating eval run store
     * @param cycleFacts   loads the job runs behind cycles, for their cost
     */
    RunStatsLoader(JobRunRepository jobRuns, PipelineRunRepository pipelineRuns,
            PipelineRunPhaseRepository phases, SkyRatingEvalRunRepository evalRuns, CycleFactsLoader cycleFacts) {
        this.jobRuns = jobRuns;
        this.pipelineRuns = pipelineRuns;
        this.phases = phases;
        this.evalRuns = evalRuns;
        this.cycleFacts = cycleFacts;
    }

    /**
     * Loads the series for some populations.
     *
     * @param wanted how many of each population are on the day (0 for a population only wanted for estimates)
     * @param before the exclusive upper bound on a sample's start
     * @return the statistics over those populations
     */
    RunStats load(Map<StatKey, Integer> wanted, Instant before) {
        Map<StatKey, List<Sample>> series = new LinkedHashMap<>();
        wanted.forEach((key, onDay) -> {
            List<Sample> samples = key.hasDuration() ? loadSamples(key, before, RunStats.WINDOW + onDay) : List.of();
            List<Sample> ascending = new ArrayList<>(samples);
            ascending.sort(Comparator.comparing(Sample::start));
            series.put(key, ascending);
        });
        return new RunStats(series);
    }

    private List<Sample> loadSamples(StatKey key, Instant before, int need) {
        LocalDateTime beforeUtc = LocalDateTime.ofInstant(before, ZoneOffset.UTC);
        PageRequest page = PageRequest.of(0, need);
        if (key.cycle() != null) {
            return cycleSamples(pipelineRuns
                    .findByCycleTypeAndStatusInAndTriggerTimeLessThanOrderByTriggerTimeDescIdDesc(
                            key.cycle(), FINISHED_CYCLE, before, page));
        }
        if (key.type() == RunDeepType.SKY_EVAL) {
            return evalRuns.findByStatusAndRunTimestampLessThanOrderByRunTimestampDescIdDesc(
                    SkyRatingEvalStatus.COMPLETED, beforeUtc, page).stream().map(RunStatsLoader::evalSample).toList();
        }
        if (key.type() == RunDeepType.BRIEFING) {
            return briefingSamples(beforeUtc, need);
        }
        List<JobRunEntity> runs;
        if (key.type() == RunDeepType.OTHER && key.model() != null) {
            runs = jobRuns.findFinishedScheduledWithModel(key.runType(), key.model(), beforeUtc, page);
        } else if (key.type() == RunDeepType.OTHER) {
            runs = jobRuns.findFinishedScheduledWithoutModel(key.runType(), beforeUtc, page);
        } else {
            runs = jobRuns.findFinishedScheduled(key.runType(), beforeUtc, page);
        }
        return runs.stream().map(RunStatsLoader::jobSample).toList();
    }

    private List<Sample> cycleSamples(List<PipelineRunEntity> cycles) {
        CycleFacts facts = cycleFacts.load(cycles);
        List<Sample> samples = new ArrayList<>();
        for (PipelineRunEntity cycle : cycles) {
            if (cycle.getCompletedAt() == null) {
                continue;
            }
            List<JobRunEntity> members = facts.members(cycle.getId());
            boolean known = members.stream().noneMatch(m -> m.getTotalCostMicroDollars() == null);
            Long cost = known ? members.stream().mapToLong(JobRunEntity::getTotalCostMicroDollars).sum() : null;
            samples.add(new Sample(cycle.getTriggerTime(),
                    Duration.between(cycle.getTriggerTime(), cycle.getCompletedAt()).toSeconds(), cost));
        }
        return samples;
    }

    private List<Sample> briefingSamples(LocalDateTime before, int need) {
        List<JobRunEntity> recent = jobRuns.findFinishedScheduled(RunType.BRIEFING, before,
                PageRequest.of(0, need * BRIEFING_SCAN_FACTOR));
        List<JobRunEntity> unlinked = recent.stream().filter(r -> r.getPipelineRunId() == null).toList();
        List<PipelineRunPhaseEntity> windows = List.of();
        if (!unlinked.isEmpty()) {
            Instant oldest = unlinked.stream().map(r -> r.getStartedAt().toInstant(ZoneOffset.UTC))
                    .min(Comparator.naturalOrder()).orElseThrow();
            Instant newest = unlinked.stream().map(r -> r.getStartedAt().toInstant(ZoneOffset.UTC))
                    .max(Comparator.naturalOrder()).orElseThrow();
            windows = phases.findByPhaseAndStartedAtGreaterThanEqualAndStartedAtLessThan(PipelinePhase.BRIEFING,
                    oldest.minus(OPEN_PHASE), newest.plusSeconds(1));
        }
        List<PipelineRunPhaseEntity> cycleWindows = windows;
        return recent.stream()
                .filter(r -> r.getPipelineRunId() != null || cycleWindows.stream().anyMatch(p -> CycleChildLinker
                        .insidePhase(r.getStartedAt().toInstant(ZoneOffset.UTC), p,
                                p.getStartedAt().plus(OPEN_PHASE))))
                .limit(need)
                .map(RunStatsLoader::jobSample)
                .toList();
    }

    private static Sample jobSample(JobRunEntity run) {
        Instant start = run.getStartedAt().toInstant(ZoneOffset.UTC);
        long seconds = run.getDurationMs() != null ? run.getDurationMs() / 1000
                : Duration.between(run.getStartedAt(), run.getCompletedAt()).toSeconds();
        return new Sample(start, seconds, run.getTotalCostMicroDollars());
    }

    private static Sample evalSample(SkyRatingEvalRunEntity run) {
        long seconds = run.getDurationMs() != null ? run.getDurationMs() / 1000
                : run.getCompletedAt() == null ? 0 : Duration.between(run.getStartedAt(), run.getCompletedAt())
                        .toSeconds();
        return new Sample(run.getStartedAt().toInstant(ZoneOffset.UTC), seconds, run.getCostMicroDollars());
    }

    /**
     * The usual gap between a cycle finishing and its ask-ready run starting, over the recent cycles that have one.
     *
     * @param before the moment the history is read up to
     * @return the median gap in seconds, or null when no recent cycle has a linked ask-ready run
     */
    Long askReadyGapSeconds(Instant before) {
        List<PipelineRunEntity> recent = pipelineRuns.findTop50ByOrderByTriggerTimeDesc().stream()
                .filter(c -> FINISHED_CYCLE.contains(c.getStatus()) && c.getCompletedAt() != null
                        && c.getTriggerTime().isBefore(before))
                .limit(GAP_CYCLES).toList();
        if (recent.isEmpty()) {
            return null;
        }
        Instant from = recent.stream().map(PipelineRunEntity::getTriggerTime).min(Comparator.naturalOrder())
                .orElseThrow();
        List<Long> ids = recent.stream().map(PipelineRunEntity::getId).toList();
        Collection<JobRunEntity> asks = new ArrayList<>(jobRuns.findByPipelineRunIdIn(ids));
        Set<Long> seen = asks.stream().map(JobRunEntity::getId).collect(Collectors.toSet());
        jobRuns.findByRunTypeInAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAscIdAsc(
                        List.of(RunType.ASK_READY), LocalDateTime.ofInstant(from, ZoneOffset.UTC),
                        LocalDateTime.ofInstant(before, ZoneOffset.UTC))
                .stream().filter(r -> seen.add(r.getId())).forEach(asks::add);
        List<Long> gaps = new ArrayList<>();
        CycleChildLinker.link(recent, asks, List.of()).forEach((cycleId, children) -> {
            if (children.askReady() != null) {
                PipelineRunEntity cycle = recent.stream().filter(c -> c.getId().equals(cycleId)).findFirst()
                        .orElseThrow();
                gaps.add(Math.max(0, Duration.between(cycle.getCompletedAt(),
                        children.askReady().getStartedAt().toInstant(ZoneOffset.UTC)).toSeconds()));
            }
        });
        Collections.sort(gaps);
        return AdminRunsService.median(gaps);
    }
}
